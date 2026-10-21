package app.glasshome.launcher;

import android.content.Context;
import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Scores and schedules from ESPN's public scoreboard feed: the one espn.com's own pages use. It
 * needs no key but is unofficial, so it can change without notice. Times show in the TV's zone.
 */
final class Sports {
    static final String CREDIT = "Scores and schedules: ESPN";
    private static final String API = "https://site.api.espn.com/apis/site/v2/sports/";
    private static final long LIVE_AGE = 60_000, LATER_AGE = 30 * 60_000L;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US);

    static final class League {
        final String key, path, name;
        final boolean weekly;   // football: one request covers the week

        League(String key, String path, String name, boolean weekly) {
            this.key = key;
            this.path = path;
            this.name = name;
            this.weekly = weekly;
        }
    }

    static final League[] LEAGUES = {
            new League("nfl", "football/nfl", "NFL", true),
            new League("college-football", "football/college-football", "College Football", true),
            new League("nba", "basketball/nba", "NBA", false),
            new League("nhl", "hockey/nhl", "NHL", false),
            new League("mlb", "baseball/mlb", "MLB", false),
            new League("mls", "soccer/usa.1", "MLS", false),
            new League("epl", "soccer/eng.1", "Premier League", false),
    };

    /** Cincinnati and Ohio teams, for a TV in Centerville; "favorite_teams" in apps.json replaces them. */
    static final List<String> DEFAULT_TEAMS = Collections.unmodifiableList(java.util.Arrays.asList(
            "nfl:CIN", "college-football:OSU", "mlb:CIN", "nba:CLE", "nhl:CBJ", "mls:CIN"));

    static final class Team {
        String abbr = "", name = "", shortName = "", logo = "", record = "", score = "";
        int color = 0xFF2A2F3A, rank;
        boolean winner;

        /** "#4 Ohio State" in college, otherwise the short name. */
        String label() {
            return rank > 0 && rank <= 25 ? "#" + rank + " " + shortName : shortName;
        }
    }

    static final class Game {
        String id = "";
        League league;
        long start;
        String state = "pre";       // pre, in, post
        String status = "";         // ESPN's short detail: "Q3 4:12", "Final/OT", "10/25 - 1:00 PM EDT"
        String note = "", venue = "", week = "";
        Team away = new Team(), home = new Team();
        final List<String> networks = new ArrayList<>();
        boolean favourite;

        boolean live() {
            return "in".equals(state);
        }

        boolean over() {
            return "post".equals(state);
        }

        String matchup() {
            return away.shortName + " at " + home.shortName;
        }

        String network() {
            return networks.isEmpty() ? "" : networks.get(0);
        }

        /** What the card and hero say about time: the clock while live, "Final", or when it starts. */
        String when() {
            if (live()) return status;
            if (over()) return status.isEmpty() ? "Final" : status;
            return startText(start);
        }

        String leagueLine() {
            String line = league.name;
            if (!note.isEmpty()) line += "  ·  " + note;
            else if (!week.isEmpty()) line += "  ·  " + week;
            return line;
        }
    }

    private Sports() {}

    // ---- Fetching ---------------------------------------------------------------------------

    static List<Game> fetch(Context context, League league) throws Exception {
        List<Game> games = new ArrayList<>();
        if (league.weekly) {
            JSONObject board = board(context, league, null, LIVE_AGE);
            games.addAll(parse(board, league));
            boolean allOver = !games.isEmpty();
            for (Game g : games) allOver &= g.over();
            JSONObject week = board.optJSONObject("week");
            JSONObject season = board.optJSONObject("season");
            if (allOver && week != null) {
                // The current week is done: the next one holds the upcoming games
                int type = season == null ? 2 : season.optInt("type", 2);
                games.addAll(parse(board(context, league, "seasontype=" + type + "&week=" + (week.optInt("number") + 1), LATER_AGE), league));
            }
        } else {
            LocalDate today = Clock.today();
            for (int d = 0; d < 3; d++) {
                try {
                    games.addAll(parse(board(context, league, "dates=" + today.plusDays(d).format(DAY), d == 0 ? LIVE_AGE : LATER_AGE), league));
                } catch (Exception e) {
                    if (d == 0) throw e;
                }
            }
        }
        return games;
    }

    private static JSONObject board(Context context, League league, String query, long maxAge) throws Exception {
        String url = API + league.path + "/scoreboard" + (query == null ? "" : "?" + query);
        return new JSONObject(Net.cached(context, url, null, maxAge));
    }

    static List<Game> parse(JSONObject board, League league) {
        List<Game> out = new ArrayList<>();
        JSONArray events = board.optJSONArray("events");
        JSONObject weekObj = board.optJSONObject("week");
        String week = weekObj != null && weekObj.optInt("number") > 0 ? "Week " + weekObj.optInt("number") : "";
        for (int i = 0; events != null && i < events.length(); i++) {
            try {
                JSONObject e = events.getJSONObject(i);
                JSONObject c = e.getJSONArray("competitions").getJSONObject(0);
                Game g = new Game();
                g.id = e.optString("id");
                g.league = league;
                // ESPN writes times without seconds ("2026-10-20T23:00Z"), which Instant.parse rejects
                g.start = OffsetDateTime.parse(e.optString("date")).toInstant().toEpochMilli();
                JSONObject type = e.getJSONObject("status").getJSONObject("type");
                g.state = type.optString("state", "pre");
                g.status = type.optString("shortDetail", "");
                g.week = league.weekly ? week : "";
                JSONArray notes = c.optJSONArray("notes");
                if (notes != null && notes.length() > 0) g.note = notes.getJSONObject(0).optString("headline", "");
                JSONObject venue = c.optJSONObject("venue");
                if (venue != null) g.venue = venue.optString("fullName", "");
                JSONArray competitors = c.getJSONArray("competitors");
                for (int k = 0; k < competitors.length(); k++) {
                    JSONObject t = competitors.getJSONObject(k);
                    Team team = team(t);
                    if ("home".equals(t.optString("homeAway"))) g.home = team;
                    else g.away = team;
                }
                JSONArray broadcasts = c.optJSONArray("broadcasts");
                for (int k = 0; broadcasts != null && k < broadcasts.length(); k++) {
                    JSONArray names = broadcasts.getJSONObject(k).optJSONArray("names");
                    for (int n = 0; names != null && n < names.length(); n++) {
                        String name = names.optString(n);
                        if (!name.isEmpty() && !g.networks.contains(name)) g.networks.add(name);
                    }
                }
                out.add(g);
            } catch (Exception ignored) {
                // A malformed event is skipped, not the whole league
            }
        }
        return out;
    }

    private static Team team(JSONObject t) {
        JSONObject j = t.optJSONObject("team");
        Team team = new Team();
        if (j == null) return team;
        team.abbr = j.optString("abbreviation", "");
        team.name = j.optString("displayName", "");
        team.shortName = j.optString("shortDisplayName", team.name);
        team.logo = j.optString("logo", "");
        team.color = colour(j.optString("color", ""), j.optString("alternateColor", ""));
        team.score = t.optString("score", "");
        team.winner = t.optBoolean("winner");
        JSONArray records = t.optJSONArray("records");
        if (records != null && records.length() > 0) team.record = records.optJSONObject(0).optString("summary", "");
        JSONObject rank = t.optJSONObject("curatedRank");
        if (rank != null) team.rank = rank.optInt("current", 0);
        if (team.rank == 99) team.rank = 0;
        return team;
    }

    /** The team colour, or its alternate when the main one is near black or near white. */
    private static int colour(String main, String alternate) {
        int c = hex(main), a = hex(alternate);
        if (c == 0) return a != 0 ? a : 0xFF2A2F3A;
        float l = luminance(c);
        if ((l < 0.04f || l > 0.85f) && a != 0) {
            float la = luminance(a);
            if (la >= 0.04f && la <= 0.85f) return a;
        }
        return c;
    }

    private static int hex(String s) {
        try {
            return s == null || s.length() != 6 ? 0 : 0xFF000000 | Integer.parseInt(s, 16);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static float luminance(int c) {
        return (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f;
    }

    /** ESPN logos come at 500 px; its image service returns them at the size drawn. */
    static String logo(String url, int size) {
        String host = "https://a.espncdn.com";
        if (url == null || url.isEmpty()) return null;
        if (!url.startsWith(host + "/i/")) return url;
        return host + "/combiner/i?img=" + url.substring(host.length()) + "&w=" + size + "&h=" + size;
    }

    // ---- Where to watch ---------------------------------------------------------------------

    private static final Map<String, String> STREAMING = new LinkedHashMap<>();

    static {
        STREAMING.put("prime video", "com.amazon.firebat");
        STREAMING.put("netflix", "com.netflix.ninja");
        STREAMING.put("apple tv", "com.apple.atve.amazon.appletv");
        STREAMING.put("apple tv+", "com.apple.atve.amazon.appletv");
        STREAMING.put("mls season pass", "com.apple.atve.amazon.appletv");
        STREAMING.put("hbo max", "com.hbo.hbonow");
        STREAMING.put("max", "com.hbo.hbonow");
        STREAMING.put("youtube", "com.amazon.firetv.youtube");
    }

    /** Streaming services that air games but have no app on this TV list; everything else is a TV channel. */
    private static final Set<String> OTHER_STREAMING = new HashSet<>(java.util.Arrays.asList(
            "espn+", "peacock", "paramount+", "nfl+", "dazn", "fubo", "fubotv", "nba league pass", "mlb.tv", "espn unlmtd"));

    /**
     * Buttons for a game: the installed streaming app that carries it, and TiviMate (the user's live
     * channels) when it is on a TV channel. Never more than the data supports.
     */
    static List<PageModel.Action> watchActions(Game g, Set<String> installed, Map<String, String> labels) {
        List<PageModel.Action> actions = new ArrayList<>();
        Set<String> added = new HashSet<>();
        boolean channel = false;
        for (String network : g.networks) {
            String n = network.toLowerCase(Locale.US).trim();
            String app = STREAMING.get(n);
            if (app != null) {
                if (installed.contains(app) && added.add(app)) actions.add(PageModel.Action.open("Watch on " + labels.get(app), app));
            } else if (!OTHER_STREAMING.contains(n)) {
                channel = true;
            }
        }
        String tivimate = "ar.tvplayer.tv";
        if (channel && installed.contains(tivimate) && !g.over()) actions.add(PageModel.Action.open("Open " + labels.get(tivimate), tivimate));
        return actions;
    }

    // ---- Page -------------------------------------------------------------------------------

    /** Favourites are "league:ABBR", e.g. "nfl:CIN". */
    static boolean isFavourite(Game g, List<String> favourites) {
        for (String f : favourites) {
            int colon = f.indexOf(':');
            if (colon <= 0) continue;
            String league = f.substring(0, colon), abbr = f.substring(colon + 1);
            if (league.equalsIgnoreCase(g.league.key) && (abbr.equalsIgnoreCase(g.away.abbr) || abbr.equalsIgnoreCase(g.home.abbr))) return true;
        }
        return false;
    }

    /**
     * The game for the "next game" widget: one of your teams live now, else the soonest one
     * coming up, else the latest result from the past day. Null when there is none of these.
     */
    static Game nextFor(List<Game> games, List<String> favourites) {
        Game live = null, next = null, recent = null;
        long now = Clock.now();
        for (Game g : games) {
            if (!isFavourite(g, favourites)) continue;
            g.favourite = true;
            if (g.live()) {
                if (live == null || g.start < live.start) live = g;
            } else if (!g.over()) {
                if (next == null || g.start < next.start) next = g;
            } else if (now - g.start < 24 * 3600_000L) {
                if (recent == null || g.start > recent.start) recent = g;
            }
        }
        return live != null ? live : next != null ? next : recent;
    }

    /** The leagues your teams play in, so Home fetches only those. */
    static List<League> leaguesFor(List<String> favourites) {
        List<League> out = new ArrayList<>();
        for (League l : LEAGUES) {
            for (String f : favourites) {
                if (f.toLowerCase(Locale.US).startsWith(l.key + ":")) {
                    out.add(l);
                    break;
                }
            }
        }
        return out;
    }

    /** Live first, then by start time; finished games last, the most recent first. */
    private static int order(Game a, Game b) {
        int ra = a.live() ? 0 : a.over() ? 2 : 1, rb = b.live() ? 0 : b.over() ? 2 : 1;
        if (ra != rb) return Integer.compare(ra, rb);
        return ra == 2 ? Long.compare(b.start, a.start) : Long.compare(a.start, b.start);
    }

    static PageModel page(Map<League, List<Game>> byLeague, List<String> favourites, Set<String> installed, Map<String, String> labels) {
        PageModel page = new PageModel();
        page.credit = CREDIT;
        List<Game> all = new ArrayList<>();
        for (List<Game> games : byLeague.values()) all.addAll(games);
        for (Game g : all) g.favourite = isFavourite(g, favourites);

        // Hero: your teams' next or live games (one each), then live games on national TV
        List<Game> hero = new ArrayList<>();
        Set<String> heroTeams = new HashSet<>();
        List<Game> mine = new ArrayList<>();
        for (Game g : all) if (g.favourite) mine.add(g);
        mine.sort(Sports::order);
        for (Game g : mine) {
            if (g.over() && Clock.now() - g.start > 36 * 3600_000L) continue;
            String team = g.league.key + (isFavourite(g, Collections.singletonList(g.league.key + ":" + g.away.abbr)) ? g.away.abbr : g.home.abbr);
            if (heroTeams.add(team) && hero.size() < 4) hero.add(g);
        }
        List<Game> live = new ArrayList<>();
        for (Game g : all) if (g.live()) live.add(g);
        live.sort(Sports::order);
        for (Game g : live) if (hero.size() < 5 && !hero.contains(g) && !g.networks.isEmpty()) hero.add(g);
        if (hero.isEmpty()) {
            List<Game> next = new ArrayList<>(all);
            next.sort(Sports::order);
            for (Game g : next) if (hero.size() < 5 && !g.over() && !g.networks.isEmpty()) hero.add(g);
        }
        for (Game g : hero) page.hero.add(feature(g, installed, labels, false));

        if (!live.isEmpty()) page.shelves.add(shelf("Live Now", live));
        if (!mine.isEmpty()) page.shelves.add(shelf("My Teams", mine));
        for (Map.Entry<League, List<Game>> e : byLeague.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            List<Game> games = new ArrayList<>(e.getValue());
            games.sort(Sports::order);
            String title = e.getKey().name;
            String week = games.get(0).week;
            if (!week.isEmpty()) title += "  ·  " + week;
            page.shelves.add(shelf(title, games));
        }
        return page;
    }

    private static PageModel.Shelf shelf(String title, List<Game> games) {
        PageModel.Shelf s = new PageModel.Shelf(title, PageModel.Style.SCORE);
        for (Game g : games) {
            if (s.cards.size() >= 20) break;
            PageModel.Card c = new PageModel.Card();
            c.game = g;
            c.ref = g;
            c.title = g.matchup();
            String network = g.network();
            c.subtitle = g.over() ? dayText(g.start) : (network.isEmpty() ? g.when() : g.when() + "  ·  " + network);
            s.cards.add(c);
        }
        return s;
    }

    /** A hero slide or the detail page for one game. */
    static PageModel.Feature feature(Game g, Set<String> installed, Map<String, String> labels, boolean detail) {
        PageModel.Feature f = new PageModel.Feature();
        f.game = g;
        f.ref = g;
        f.label = (g.live() ? "Live  ·  " : g.favourite ? "Your team  ·  " : "") + g.leagueLine();
        f.title = g.matchup();
        List<String> meta = new ArrayList<>();
        meta.add(g.live() || g.over() ? g.when() : fullStartText(g.start));
        if (!g.networks.isEmpty()) meta.add(String.join(", ", g.networks));
        f.meta = String.join("  ·  ", meta);
        List<String> body = new ArrayList<>();
        if (!g.away.record.isEmpty() && !g.home.record.isEmpty()) {
            body.add(g.away.shortName + " " + g.away.record + ",  " + g.home.shortName + " " + g.home.record);
        }
        if (!g.venue.isEmpty()) body.add(g.venue);
        f.body = String.join("  ·  ", body);
        f.actions.addAll(watchActions(g, installed, labels));
        if (!detail) f.actions.add(PageModel.Action.details());
        if (detail) {
            if (!g.networks.isEmpty()) f.facts.add(new String[] {"On", String.join(", ", g.networks)});
            if (!g.venue.isEmpty()) f.facts.add(new String[] {"Venue", g.venue});
            f.facts.add(new String[] {g.over() ? "Played" : g.live() ? "Started" : "Starts", fullStartText(g.start)});
            if (f.actions.isEmpty() && !g.over()) f.note = "Not on an app on this TV";
        }
        return f;
    }

    // ---- Time text --------------------------------------------------------------------------

    /** "Today 7:30 PM", "Tomorrow 8:00 PM", "Sun 1:00 PM", "Oct 25". */
    static String startText(long millis) {
        ZonedDateTime t = Clock.at(millis);
        long days = ChronoUnit.DAYS.between(Clock.today(), t.toLocalDate());
        String time = t.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US));
        if (days == 0) return "Today " + time;
        if (days == 1) return "Tomorrow " + time;
        if (days > 1 && days < 7) return t.format(DateTimeFormatter.ofPattern("EEE", Locale.US)) + " " + time;
        return t.format(DateTimeFormatter.ofPattern("MMM d", Locale.US)) + "  ·  " + time;
    }

    static String fullStartText(long millis) {
        ZonedDateTime t = Clock.at(millis);
        return t.format(DateTimeFormatter.ofPattern("EEEE, MMM d  ·  h:mm a", Locale.US));
    }

    static String dayText(long millis) {
        ZonedDateTime t = Clock.at(millis);
        long days = ChronoUnit.DAYS.between(t.toLocalDate(), Clock.today());
        if (days == 0) return "Final  ·  Today";
        if (days == 1) return "Final  ·  Yesterday";
        return "Final  ·  " + t.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US));
    }
}
