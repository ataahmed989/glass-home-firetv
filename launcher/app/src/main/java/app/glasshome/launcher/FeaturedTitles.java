package app.glasshome.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Titles currently available on a streaming service, from TMDB's watch-provider data (US).
 * Needs the user's own TMDB API key; without one, the focused-app area shows the branded
 * background instead. Results are cached on disk for six hours. These are catalogue titles,
 * never the user's own viewing activity.
 */
final class FeaturedTitles {
    private static final String API = "https://api.themoviedb.org/3/discover/";
    private static final String IMAGES = "https://image.tmdb.org/t/p/w1280";
    private static final long CACHE_MS = 6 * 60 * 60 * 1000L;
    static final int MAX_TITLES = 6;

    static final class Title {
        final String name, year, kind, overview, backdropPath;

        Title(String name, String year, String kind, String overview, String backdropPath) {
            this.name = name;
            this.year = year;
            this.kind = kind;
            this.overview = overview;
            this.backdropPath = backdropPath;
        }

        String meta() {
            return year.isEmpty() ? kind : year + "  ·  " + kind;
        }
    }

    private FeaturedTitles() {}

    static List<Title> load(Context context, String apiKey, String appKey, String providers) throws Exception {
        File cache = new File(context.getCacheDir(), "featured-" + appKey + ".json");
        if (cache.isFile() && System.currentTimeMillis() - cache.lastModified() < CACHE_MS) {
            return parseCombined(new String(Files.readAllBytes(cache.toPath()), StandardCharsets.UTF_8));
        }
        JSONArray tv = get(apiKey, "tv", providers);
        JSONArray movies = get(apiKey, "movie", providers);
        JSONObject combined = new JSONObject().put("tv", tv).put("movie", movies);
        try (OutputStream out = new FileOutputStream(cache)) {
            out.write(combined.toString().getBytes(StandardCharsets.UTF_8));
        }
        return parseCombined(combined.toString());
    }

    private static JSONArray get(String apiKey, String type, String providers) throws Exception {
        String query = API + type + "?watch_region=US&with_watch_monetization_types=flatrate&include_adult=false"
                + "&sort_by=popularity.desc&language=en-US&page=1&with_watch_providers=" + providers.replace("|", "%7C");
        // A v4 read-access token is a long JWT and goes in the header; a v3 key goes in the query
        boolean bearer = apiKey.length() > 40;
        if (!bearer) query += "&api_key=" + apiKey;
        HttpURLConnection c = (HttpURLConnection) new URL(query).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + apiKey);
        try (InputStream in = c.getInputStream()) {
            return new JSONObject(Weather.read(in)).getJSONArray("results");
        } finally {
            c.disconnect();
        }
    }

    /** Alternates series and films, keeping only titles with a backdrop image. */
    static List<Title> parseCombined(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        List<Title> tv = parse(o.getJSONArray("tv"), true), movies = parse(o.getJSONArray("movie"), false);
        List<Title> out = new ArrayList<>();
        for (int i = 0; out.size() < MAX_TITLES && (i < tv.size() || i < movies.size()); i++) {
            if (i < tv.size()) out.add(tv.get(i));
            if (i < movies.size() && out.size() < MAX_TITLES) out.add(movies.get(i));
        }
        return out;
    }

    private static List<Title> parse(JSONArray results, boolean series) {
        List<Title> list = new ArrayList<>();
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.optJSONObject(i);
            if (r == null) continue;
            String backdrop = r.optString("backdrop_path", "");
            String name = r.optString(series ? "name" : "title", "");
            if (backdrop.isEmpty() || backdrop.equals("null") || name.isEmpty()) continue;
            String date = r.optString(series ? "first_air_date" : "release_date", "");
            String year = date.length() >= 4 ? date.substring(0, 4) : "";
            list.add(new Title(name, year, series ? "Series" : "Film", r.optString("overview", ""), backdrop));
        }
        return list;
    }

    /** Downloads (once) and decodes a backdrop. RGB_565 halves memory for these opaque images. */
    static Bitmap backdrop(Context context, Title title) throws IOException {
        File dir = new File(context.getCacheDir(), "featured-img");
        dir.mkdirs();
        File file = new File(dir, title.backdropPath.replaceAll("[^A-Za-z0-9.]", ""));
        if (!file.isFile()) {
            HttpURLConnection c = (HttpURLConnection) new URL(IMAGES + title.backdropPath).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            File tmp = new File(dir, file.getName() + ".part");
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            } finally {
                c.disconnect();
            }
            if (!tmp.renameTo(file)) throw new IOException("cache write failed");
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        Bitmap b = BitmapFactory.decodeFile(file.getPath(), o);
        if (b == null) {
            file.delete();
            throw new IOException("undecodable backdrop");
        }
        return b;
    }
}
