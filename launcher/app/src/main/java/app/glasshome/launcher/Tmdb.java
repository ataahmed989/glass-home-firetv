package app.glasshome.launcher;

import android.content.Context;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * TMDB catalogue data: lists of films and series, their artwork and US streaming availability
 * (TMDB's watch-provider data comes from JustWatch). All of it is public catalogue information;
 * none of it is the user's own activity.
 */
final class Tmdb {
    static final String API = "https://api.themoviedb.org/3/";
    static final String IMG = "https://image.tmdb.org/t/p/";
    static final String CREDIT = "Data and artwork: TMDB  ·  Streaming availability: JustWatch";
    private static final long LIST_AGE = 6 * 60 * 60 * 1000L;
    private static final long DETAIL_AGE = 24 * 60 * 60 * 1000L;

    /** A film or series. List fields are fixed; detail fields are filled by {@link #details}. */
    static final class Title {
        final long id;
        final boolean series;
        final String name, overview, backdrop, poster, date;
        final int[] genres;

        boolean detailed;
        String logo;
        int minutes, seasons;
        String certification = "";
        String cast = "", crew = "";
        String nextEpisode = "";
        /** Installed apps it streams on (subscription), and installed apps that rent or sell it. */
        final Set<String> streamingApps = new LinkedHashSet<>(), storeApps = new LinkedHashSet<>();
        /** Every US subscription service it streams on, installed or not, by name. */
        final Set<String> streamingNames = new LinkedHashSet<>();

        Title(long id, boolean series, String name, String overview, String backdrop, String poster, String date, int[] genres) {
            this.id = id;
            this.series = series;
            this.name = name;
            this.overview = overview;
            this.backdrop = backdrop;
            this.poster = poster;
            this.date = date;
            this.genres = genres;
        }

        String year() {
            return date.length() >= 4 ? date.substring(0, 4) : "";
        }

        /** "2026 · Action" for films, "Drama · 2024" for series, as on Apple's cards. */
        String cardLine() {
            String genre = genreNames(genres, series, 1);
            String y = year();
            if (genre.isEmpty()) return y;
            if (y.isEmpty()) return genre;
            return series ? genre + "  ·  " + y : y + "  ·  " + genre;
        }

        /** The hero and detail line: genres, year, length, rating. */
        String meta() {
            List<String> parts = new ArrayList<>();
            String g = genreNames(genres, series, 2);
            if (!g.isEmpty()) parts.add(g);
            if (!year().isEmpty()) parts.add(year());
            if (series && seasons > 0) parts.add(seasons == 1 ? "1 Season" : seasons + " Seasons");
            if (!series && minutes > 0) parts.add(duration(minutes));
            if (!certification.isEmpty()) parts.add(certification);
            return String.join("  ·  ", parts);
        }

        String backdropUrl(String size) {
            return backdrop.isEmpty() ? null : IMG + size + backdrop;
        }

        String posterUrl(String size) {
            return poster.isEmpty() ? null : IMG + size + poster;
        }
    }

    private final Context context;
    private final String key;

    Tmdb(Context context, String key) {
        this.context = context;
        this.key = key;
    }

    /** A v4 read-access token is a long JWT and goes in the header; a v3 key goes in the query. */
    private boolean bearer() {
        return key.length() > 40;
    }

    String url(String path, String query) {
        StringBuilder u = new StringBuilder(API).append(path).append("?language=en-US");
        if (query != null && !query.isEmpty()) u.append('&').append(query);
        if (!bearer()) u.append("&api_key=").append(key);
        return u.toString();
    }

    private JSONObject get(String path, String query, long maxAge) throws Exception {
        return new JSONObject(Net.cached(context, url(path, query), bearer() ? key : null, maxAge));
    }

    /** One page of a list endpoint (discover, trending, now_playing…), keeping titles with artwork. */
    List<Title> list(String path, String query, boolean series) throws Exception {
        JSONArray results = get(path, query, LIST_AGE).optJSONArray("results");
        List<Title> out = new ArrayList<>();
        for (int i = 0; results != null && i < results.length(); i++) {
            Title t = parse(results.optJSONObject(i), series);
            if (t != null) out.add(t);
        }
        return out;
    }

    static Title parse(JSONObject r, boolean series) {
        if (r == null) return null;
        String type = r.optString("media_type", "");
        if (!type.isEmpty()) {
            if (type.equals("tv")) series = true;
            else if (type.equals("movie")) series = false;
            else return null;
        }
        String name = r.optString(series ? "name" : "title", "");
        String backdrop = clean(r.optString("backdrop_path", "")), poster = clean(r.optString("poster_path", ""));
        if (name.isEmpty() || (backdrop.isEmpty() && poster.isEmpty())) return null;
        JSONArray g = r.optJSONArray("genre_ids");
        JSONArray named = g == null ? r.optJSONArray("genres") : null;   // a details response names them
        int[] genres = new int[g != null ? g.length() : named != null ? named.length() : 0];
        for (int i = 0; i < genres.length; i++) {
            JSONObject n = named == null ? null : named.optJSONObject(i);
            genres[i] = g != null ? g.optInt(i) : n == null ? 0 : n.optInt("id");
        }
        return new Title(r.optLong("id"), series, name, r.optString("overview", ""), backdrop, poster,
                r.optString(series ? "first_air_date" : "release_date", ""), genres);
    }

    private static String clean(String path) {
        return path == null || path.equals("null") ? "" : path;
    }

    /** Fills in the logo, length, rating, credits and where it streams. */
    Title details(Title t) throws Exception {
        if (t.detailed) return t;
        fill(t, detailsOf(t.id, t.series));
        return t;
    }

    /**
     * A film or series by its TMDB id, with its details: the one request {@link #details} makes,
     * so what one has fetched the other finds cached. Null if TMDB has no artwork for it.
     */
    Title byId(long id, boolean series) throws Exception {
        JSONObject d = detailsOf(id, series);
        Title t = parse(d, series);
        if (t != null) fill(t, d);
        return t;
    }

    /**
     * The film or series TMDB has by exactly this name, for a title FirePlayer sent without a TMDB
     * id (it had no key, TMDB wasn't sure, or an older FirePlayer): its name or original name is
     * {@code name} written plainly (see {@link #plain}), and when {@code year} is known (0 if not),
     * a film is from within a year of it and a series first aired no later than the year after (the
     * year can be a later season's); of those, the one from nearest the year. Without a year, only a
     * name no other title has: a remake by the same name would give the wrong picture, and a title
     * card is better than that. Null if there is none, or it has no artwork.
     */
    Title find(String name, int year, boolean series) throws Exception {
        String query = "query=" + Uri.encode(name) + "&include_adult=false";
        // With the year first, as it tells a title from others by its name; then without it, as a
        // show's year may be a later season's and a film's a year off
        for (int y : year > 0 ? new int[] {year, 0} : new int[] {0}) {
            String q = y > 0 ? query + (series ? "&first_air_date_year=" : "&year=") + y : query;
            JSONArray results = get(series ? "search/tv" : "search/movie", q + "&page=1", DETAIL_AGE).optJSONArray("results");
            JSONObject best = exact(results, name, year, series);
            if (best != null) return parse(best, series);
        }
        return null;
    }

    /**
     * Of TMDB's search results, the one by exactly {@code name} from nearest {@code year} (the more
     * popular when as near); with no year, the only one by that name, or null (see {@link #find}).
     */
    static JSONObject exact(JSONArray results, String name, int year, boolean series) {
        String wanted = plain(name);
        JSONObject best = null;
        int matches = 0;
        for (int i = 0; results != null && !wanted.isEmpty() && i < results.length(); i++) {
            JSONObject r = results.optJSONObject(i);
            if (r == null) continue;
            boolean named = wanted.equals(plain(r.optString(series ? "name" : "title", "")))
                    || wanted.equals(plain(r.optString(series ? "original_name" : "original_title", "")));
            if (!named || !fromAbout(dateOf(r, series), year, series)) continue;
            matches++;
            if (best == null) {
                best = r;
                continue;
            }
            int d = distance(dateOf(r, series), year), bestD = distance(dateOf(best, series), year);
            if (d < bestD || (d == bestD && r.optDouble("popularity", 0) > best.optDouble("popularity", 0))) best = r;
        }
        return year <= 0 && matches > 1 ? null : best;
    }

    private static String dateOf(JSONObject r, boolean series) {
        return r.optString(series ? "first_air_date" : "release_date", "");
    }

    /** How many years {@code date} is from {@code year}; 0 when the year isn't known. */
    private static int distance(String date, int year) {
        if (year <= 0) return 0;
        try {
            return Math.abs(Integer.parseInt(date.substring(0, 4)) - year);
        } catch (RuntimeException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static boolean fromAbout(String date, int year, boolean series) {
        if (year <= 0) return true;
        int y;
        try {
            y = Integer.parseInt(date.substring(0, 4));
        } catch (RuntimeException e) {
            return false;   // no date: nothing tells it from another title by that name
        }
        return series ? y <= year + 1 : Math.abs(y - year) <= 1;
    }

    /** A name written plainly, to compare: lower case, no accents, only letters and digits, "&" as "and". */
    static String plain(String name) {
        String s = Normalizer.normalize(name.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replace("&", "and");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) out.append(c);   // accents come apart from their letters, and go here
        }
        return out.toString();
    }

    private JSONObject detailsOf(long id, boolean series) throws Exception {
        String append = series ? "images,watch/providers,content_ratings,credits" : "images,watch/providers,release_dates,credits";
        return get((series ? "tv/" : "movie/") + id, "append_to_response=" + append + "&include_image_language=en,null", DETAIL_AGE);
    }

    private static void fill(Title t, JSONObject d) {
        t.logo = pickLogo(d.optJSONObject("images"));
        if (t.series) {
            t.seasons = d.optInt("number_of_seasons");
            JSONObject ratings = d.optJSONObject("content_ratings");
            JSONArray r = ratings == null ? null : ratings.optJSONArray("results");
            for (int i = 0; r != null && i < r.length(); i++) {
                JSONObject o = r.optJSONObject(i);
                if (o != null && "US".equals(o.optString("iso_3166_1"))) t.certification = o.optString("rating", "");
            }
            List<String> creators = new ArrayList<>();
            JSONArray by = d.optJSONArray("created_by");
            for (int i = 0; by != null && i < by.length() && creators.size() < 2; i++) creators.add(by.optJSONObject(i).optString("name"));
            t.crew = String.join(", ", creators);
            JSONObject next = d.optJSONObject("next_episode_to_air");
            if (next != null && !next.optString("air_date", "").isEmpty()) {
                t.nextEpisode = "Season " + next.optInt("season_number") + ", Episode " + next.optInt("episode_number")
                        + "  ·  " + next.optString("air_date");
            }
        } else {
            t.minutes = d.optInt("runtime");
            JSONObject rd = d.optJSONObject("release_dates");
            JSONArray r = rd == null ? null : rd.optJSONArray("results");
            for (int i = 0; r != null && i < r.length(); i++) {
                JSONObject o = r.optJSONObject(i);
                if (o == null || !"US".equals(o.optString("iso_3166_1"))) continue;
                JSONArray dates = o.optJSONArray("release_dates");
                for (int k = 0; dates != null && k < dates.length(); k++) {
                    String c = dates.optJSONObject(k).optString("certification", "");
                    if (!c.isEmpty()) t.certification = c;
                }
            }
            JSONObject credits = d.optJSONObject("credits");
            JSONArray crew = credits == null ? null : credits.optJSONArray("crew");
            List<String> directors = new ArrayList<>();
            for (int i = 0; crew != null && i < crew.length() && directors.size() < 2; i++) {
                JSONObject c = crew.optJSONObject(i);
                if ("Director".equals(c.optString("job"))) directors.add(c.optString("name"));
            }
            t.crew = String.join(", ", directors);
        }
        JSONObject credits = d.optJSONObject("credits");
        JSONArray cast = credits == null ? null : credits.optJSONArray("cast");
        List<String> names = new ArrayList<>();
        for (int i = 0; cast != null && i < cast.length() && names.size() < 3; i++) names.add(cast.optJSONObject(i).optString("name"));
        t.cast = String.join(", ", names);

        JSONObject wp = d.optJSONObject("watch/providers");
        JSONObject us = wp == null ? null : wp.optJSONObject("results");
        us = us == null ? null : us.optJSONObject("US");
        if (us != null) {
            for (String kind : new String[] {"flatrate", "ads", "free"}) {
                JSONArray a = us.optJSONArray(kind);
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject p = a.optJSONObject(i);
                    t.streamingNames.add(p.optString("provider_name"));
                    String app = appFor(p.optInt("provider_id"), p.optString("provider_name"));
                    if (app != null) t.streamingApps.add(app);
                }
            }
            for (String kind : new String[] {"rent", "buy"}) {
                JSONArray a = us.optJSONArray(kind);
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject p = a.optJSONObject(i);
                    String app = appFor(p.optInt("provider_id"), p.optString("provider_name"));
                    if (app != null && !t.streamingApps.contains(app)) t.storeApps.add(app);
                }
            }
        }
        t.detailed = true;
    }

    /** An English title logo, if TMDB has one: Apple's pages show the title as artwork. */
    private static String pickLogo(JSONObject images) {
        JSONArray logos = images == null ? null : images.optJSONArray("logos");
        String fallback = null;
        for (int i = 0; logos != null && i < logos.length(); i++) {
            JSONObject l = logos.optJSONObject(i);
            String path = clean(l.optString("file_path", ""));
            if (path.isEmpty() || path.endsWith(".svg")) continue;
            if ("en".equals(l.optString("iso_639_1"))) return path;
            if (fallback == null && l.isNull("iso_639_1")) fallback = path;
        }
        return fallback;
    }

    // ---- Streaming services and the apps on this TV ------------------------------------------

    /**
     * The app on this TV that plays a TMDB watch provider, or null. Amazon Channels play in Prime
     * Video and Apple TV Channels in the Apple TV app, whatever the channel.
     */
    static String appFor(int id, String name) {
        String n = name == null ? "" : name.toLowerCase();
        if (n.endsWith("amazon channel") || id == 9 || id == 10 || id == 2100 || n.equals("amazon prime video") || n.equals("amazon video")) {
            return "com.amazon.firebat";
        }
        if (n.endsWith("apple tv channel") || id == 350 || id == 2 || n.startsWith("apple tv")) return "com.apple.atve.amazon.appletv";
        if (id == 8 || id == 1796 || n.startsWith("netflix")) return "com.netflix.ninja";
        if (id == 1899 || id == 384 || n.equals("max") || n.startsWith("hbo max")) return "com.hbo.hbonow";
        if (id == 337 || n.startsWith("disney plus") || n.startsWith("disney+")) return "com.disney.disneyplus";
        return null;
    }

    static String duration(int minutes) {
        if (minutes < 60) return minutes + " min";
        int h = minutes / 60, m = minutes % 60;
        return m == 0 ? h + " hr" : h + " hr " + m + " min";
    }

    private static final Map<Integer, String> MOVIE_GENRES = new HashMap<>(), TV_GENRES = new HashMap<>();

    static {
        Object[][] movie = {{28, "Action"}, {12, "Adventure"}, {16, "Animation"}, {35, "Comedy"}, {80, "Crime"},
                {99, "Documentary"}, {18, "Drama"}, {10751, "Family"}, {14, "Fantasy"}, {36, "History"}, {27, "Horror"},
                {10402, "Music"}, {9648, "Mystery"}, {10749, "Romance"}, {878, "Sci-Fi"}, {10770, "TV Movie"},
                {53, "Thriller"}, {10752, "War"}, {37, "Western"}};
        Object[][] tv = {{10759, "Action & Adventure"}, {16, "Animation"}, {35, "Comedy"}, {80, "Crime"},
                {99, "Documentary"}, {18, "Drama"}, {10751, "Family"}, {10762, "Kids"}, {9648, "Mystery"}, {10763, "News"},
                {10764, "Reality"}, {10765, "Sci-Fi & Fantasy"}, {10766, "Soap"}, {10767, "Talk"},
                {10768, "War & Politics"}, {37, "Western"}};
        for (Object[] g : movie) MOVIE_GENRES.put((Integer) g[0], (String) g[1]);
        for (Object[] g : tv) TV_GENRES.put((Integer) g[0], (String) g[1]);
    }

    static String genreNames(int[] ids, boolean series, int max) {
        Map<Integer, String> names = series ? TV_GENRES : MOVIE_GENRES;
        List<String> out = new ArrayList<>();
        for (int id : ids) {
            String n = names.get(id);
            if (n != null && out.size() < max) out.add(n);
        }
        return String.join(", ", out);
    }

    static IOException noKey() {
        return new IOException("no TMDB key");
    }
}
