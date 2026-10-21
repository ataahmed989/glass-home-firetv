package app.glasshome.launcher;

import android.content.Context;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Continue Watching, built only from what has actually played on this TV. {@link MediaWatcher}
 * reads the streaming apps' media sessions (title, episode, position, length) and records them
 * here. Fire TV does not share the apps' own watch history with other launchers, so this starts
 * empty and fills as you watch; nothing is ever made up. It stays on the TV: only the title is
 * sent to TMDB, to find its artwork.
 */
final class WatchHistory {
    private static final String FILE = "watch-history.json";
    private static final int MAX = 40;
    private static final long KEEP_MS = 60L * 24 * 60 * 60 * 1000;
    private static final long MIN_POSITION_MS = 60_000;         // skip autoplay previews and quick peeks
    private static final long MIN_OPENED_MS = 5 * 60_000;       // and a title page's trailer, where the app names nothing
    private static final long MIN_DURATION_MS = 10 * 60_000;    // skip trailers and clips
    /** How soon after Glass Home opens a title its app has to start playing for that to be the title. */
    static final long OPENED_MS = 3 * 60_000;
    static final float FINISHED = 0.95f;

    /**
     * A film or show Glass Home just opened in a streaming app. Some apps (Netflix among them) say
     * that they are playing but not what, so what plays next in that app is taken to be this title.
     */
    static final class Opened {
        final String pkg, name, backdrop;
        final long tmdbId, at;
        final boolean series;
        final int minutes;

        Opened(String pkg, Tmdb.Title t, long at) {
            this.pkg = pkg;
            name = t.name;
            backdrop = t.backdrop;
            tmdbId = t.id;
            series = t.series;
            minutes = t.minutes;
            this.at = at;
        }
    }

    private static Opened opened;

    // "S1:E3", "S01E03", "S1 · E3", "Season 1, Episode 3"
    private static final Pattern SEASON_EPISODE = Pattern.compile(
            "(?i)\\bS(?:eason)?\\s*(\\d{1,2})\\s*[:.,·\\-]?\\s*E(?:p(?:isode)?)?\\.?\\s*(\\d{1,3})\\b");
    // "Episode 3", "Ep. 3", "E3"
    private static final Pattern EPISODE_ONLY = Pattern.compile("(?i)\\b(?:Ep(?:isode)?\\.?|E)\\s*(\\d{1,3})\\b");

    /** What a media session reported at one moment. */
    static final class Snapshot {
        String pkg = "";
        String title = "", displayTitle = "", subtitle = "", artist = "", album = "", description = "";
        long positionMs, durationMs, at;
        long playedMs;      // how long it has been seen playing, for apps that report no position
        Opened opened;      // the title Glass Home opened, when the app does not name what it plays
    }

    static final class Entry {
        String pkg = "";
        boolean episode;
        String title = "";          // film title, or the episode's own title
        String series = "";         // series name, for episodes
        int season, number;         // 0 when unknown
        long positionMs, durationMs, updatedAt;
        // Matched on TMDB (for artwork); "matched" is set once a search has run, found or not
        boolean matched;
        long tmdbId;
        String backdrop = "", tmdbName = "";

        String key() {
            return (pkg + "|" + (episode ? "tv:" + series : "movie:" + title)).toLowerCase(Locale.ROOT);
        }

        /** The name to show: the series for an episode, otherwise the title. */
        String name() {
            if (!tmdbName.isEmpty()) return tmdbName;
            return episode && !series.isEmpty() ? series : title;
        }

        float progress() {
            return durationMs > 0 ? Math.min(1f, positionMs / (float) durationMs) : -1f;
        }

        boolean finished() {
            return progress() >= FINISHED;
        }

        /** "S2, E4  ·  23 min left", "1 hr 5 min left". */
        String detail() {
            List<String> parts = new ArrayList<>();
            if (episode && number > 0) parts.add(season > 0 ? "S" + season + ", E" + number : "Episode " + number);
            if (durationMs > 0) {
                int left = Math.round((durationMs - positionMs) / 60000f);
                parts.add(left <= 1 ? "Almost done" : Tmdb.duration(left) + " left");
            }
            return String.join("  ·  ", parts);
        }

        JSONObject toJson() throws Exception {
            return new JSONObject().put("pkg", pkg).put("episode", episode).put("title", title).put("series", series)
                    .put("season", season).put("number", number).put("position", positionMs).put("duration", durationMs)
                    .put("updated", updatedAt).put("matched", matched).put("tmdbId", tmdbId)
                    .put("backdrop", backdrop).put("tmdbName", tmdbName);
        }

        static Entry fromJson(JSONObject o) {
            Entry e = new Entry();
            e.pkg = o.optString("pkg");
            e.episode = o.optBoolean("episode");
            e.title = o.optString("title");
            e.series = o.optString("series");
            e.season = o.optInt("season");
            e.number = o.optInt("number");
            e.positionMs = o.optLong("position");
            e.durationMs = o.optLong("duration");
            e.updatedAt = o.optLong("updated");
            e.matched = o.optBoolean("matched");
            e.tmdbId = o.optLong("tmdbId");
            e.backdrop = o.optString("backdrop");
            e.tmdbName = o.optString("tmdbName");
            return e;
        }
    }

    private WatchHistory() {}

    /** The streaming apps whose playback is recorded: the subscription services and FirePlayer. */
    static boolean tracks(String pkg) {
        return Brand.of(pkg, pkg).kind == Brand.Kind.FEATURED || "app.fireplayer".equals(pkg);
    }

    /** Glass Home opened {@code t} in {@code pkg} (at its own page, not just the app). */
    static synchronized void opened(String pkg, Tmdb.Title t) {
        opened = new Opened(pkg, t, SystemClock.elapsedRealtime());
    }

    /** What Glass Home just opened in {@code pkg}, if it was recent enough; each opening is taken once. */
    static synchronized Opened takeOpened(String pkg) {
        Opened o = opened;
        if (o == null || !o.pkg.equals(pkg) || SystemClock.elapsedRealtime() - o.at > OPENED_MS) return null;
        opened = null;
        return o;
    }

    /**
     * Turns what a session reported into an entry: an episode when a season/episode marker appears
     * anywhere ("S1:E3", "Season 1, Episode 3"), otherwise a film.
     */
    static Entry parse(Snapshot s) {
        String[] fields = {s.displayTitle, s.title, s.subtitle, s.album, s.artist, s.description};
        Entry e = new Entry();
        e.pkg = s.pkg;
        e.positionMs = s.positionMs;
        e.durationMs = s.durationMs;
        e.updatedAt = s.at;
        for (int i = 0; i < fields.length; i++) {
            String f = fields[i] == null ? "" : fields[i].trim();
            Matcher m = SEASON_EPISODE.matcher(f);
            boolean full = m.find();
            if (!full) {
                m = EPISODE_ONLY.matcher(f);
                if (!m.find()) continue;
            }
            e.episode = true;
            e.season = full ? Integer.parseInt(m.group(1)) : 0;
            e.number = Integer.parseInt(full ? m.group(2) : m.group(1));
            String before = trimSeparators(f.substring(0, m.start())), after = trimSeparators(f.substring(m.end()));
            e.title = after;
            e.series = before;
            if (e.series.isEmpty()) {
                // The series name is in another field: the first one that is neither the marker nor the episode title
                for (int k = 0; k < fields.length; k++) {
                    String other = fields[k] == null ? "" : fields[k].trim();
                    if (k == i || other.isEmpty() || other.equalsIgnoreCase(after)) continue;
                    if (SEASON_EPISODE.matcher(other).find() || EPISODE_ONLY.matcher(other).find()) continue;
                    e.series = other;
                    break;
                }
            }
            if (e.series.isEmpty()) e.series = e.title;
            return e.series.isEmpty() ? null : e;
        }
        String name = !s.displayTitle.trim().isEmpty() ? s.displayTitle.trim() : s.title == null ? "" : s.title.trim();
        if (name.isEmpty()) return null;
        e.title = name;
        return e;
    }

    private static String trimSeparators(String s) {
        return s.replaceAll("^[\\s:·\\-–—|,\"]+|[\\s:·\\-–—|,\"]+$", "");
    }

    /** An entry for a title Glass Home opened: already known on TMDB, so nothing is searched for. */
    static Entry fromOpened(Snapshot s) {
        Opened o = s.opened;
        Entry e = new Entry();
        e.pkg = s.pkg;
        e.episode = o.series;
        if (o.series) e.series = o.name;
        else e.title = o.name;
        e.positionMs = s.positionMs;
        // A film's length from TMDB when the app gives none, so it shows progress and drops off once watched
        e.durationMs = s.durationMs > 0 || o.series ? s.durationMs : o.minutes * 60_000L;
        e.updatedAt = s.at;
        e.matched = true;
        e.tmdbId = o.tmdbId;
        e.backdrop = o.backdrop;
        e.tmdbName = o.name;
        return e;
    }

    /** Records one snapshot. Returns false when it is not worth keeping (a preview, a trailer, nothing named). */
    static synchronized boolean record(Context context, Snapshot s) {
        if (!tracks(s.pkg)) return false;
        if (Math.max(s.positionMs, s.playedMs) < (s.opened != null ? MIN_OPENED_MS : MIN_POSITION_MS)) return false;
        if (s.durationMs > 0 && s.durationMs < MIN_DURATION_MS) return false;
        Entry e = s.opened != null ? fromOpened(s) : parse(s);
        if (e == null) return false;
        List<Entry> all = load(context);
        for (Iterator<Entry> it = all.iterator(); it.hasNext(); ) {
            Entry old = it.next();
            if (!old.key().equals(e.key())) continue;
            // Same film or series: keep what was learnt about it on TMDB
            if (!e.matched) {
                e.matched = old.matched;
                e.tmdbId = old.tmdbId;
                e.backdrop = old.backdrop;
                e.tmdbName = old.tmdbName;
            }
            it.remove();
        }
        all.add(0, e);
        save(context, all);
        return true;
    }

    static synchronized List<Entry> load(Context context) {
        List<Entry> list = new ArrayList<>();
        File f = new File(context.getFilesDir(), FILE);
        if (!f.isFile()) return list;
        try {
            JSONArray a = new JSONArray(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) list.add(Entry.fromJson(a.getJSONObject(i)));
        } catch (Exception ignored) {
            // An unreadable file is treated as empty; the next recording replaces it
        }
        return list;
    }

    static synchronized void save(Context context, List<Entry> list) {
        try {
            JSONArray a = new JSONArray();
            long cutoff = Clock.now() - KEEP_MS;
            for (Entry e : list) {
                if (a.length() >= MAX) break;
                if (e.updatedAt >= cutoff) a.put(e.toJson());
            }
            Net.write(new File(context.getFilesDir(), FILE), a.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // Keeping history is best effort
        }
    }

    /** Unfinished entries for one page, newest first: films for Movies, episodes for TV Shows. */
    static List<Entry> forPage(List<Entry> all, boolean series) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : all) {
            if (e.episode == series && !e.finished()) out.add(e);
        }
        out.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        return out;
    }
}
