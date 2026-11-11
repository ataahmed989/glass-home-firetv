package app.glasshome.launcher;

import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * FirePlayer's Continue Watching: the films and episodes played there lately and not finished
 * (TiviMate plays its VODs in FirePlayer), which Glass Home shows at the top of Movies and TV
 * Shows. FirePlayer keeps the list and offers it through a content provider; an entry here is only
 * what the row shows and an opaque id that FirePlayer resumes by. No FirePlayer, or one without the
 * provider, is an empty list.
 *
 * The provider is reached through an unstable client: should FirePlayer be stopped while it is
 * being asked (Fire OS stops apps freely on this TV), the question fails rather than Glass Home.
 *
 * Newer FirePlayers also say where each entry plays from (the provider's address, for the details window), whether
 * they can list a show's episodes, list them ({@link #episodes}) and play any one of them ({@link #playEpisodeIntent}).
 * What an older FirePlayer lacks (a column, a path) is simply absent, and means "not offered": nothing else is checked.
 */
final class ContinueWatching {
    static final String AUTHORITY = "app.fireplayer.continue";
    static final Uri ITEMS = Uri.parse("content://" + AUTHORITY + "/items");
    static final String ACTION_RESUME = "app.fireplayer.action.RESUME";
    static final String EXTRA_ID = "app.fireplayer.extra.CONTINUE_ID";
    /** Plays another episode of an entry's show: {@link #EXTRA_ID} and {@link #EXTRA_EPISODE_ID} (newer FirePlayers only). */
    static final String ACTION_PLAY_EPISODE = "app.fireplayer.action.PLAY_EPISODE";
    static final String EXTRA_EPISODE_ID = "app.fireplayer.extra.EPISODE_ID";
    /** The most the row shows of each kind (FirePlayer keeps no more). */
    static final int MAX = 20;

    static final class Item {
        final String id, title, episodeTitle;
        final boolean episode;
        final int year, season, number;
        final long tmdbId, positionMs, durationMs, lastWatched;
        /**
         * Where FirePlayer plays it from, as its own details show it ("tv.example.com:8080": the provider's address, never
         * a login or path); null from an older FirePlayer, or a link it can't name. Shown on screen, never logged.
         */
        final String source;
        /** An episode whose show FirePlayer can list ({@link #episodes}) and play any episode of; never a film. */
        final boolean listsEpisodes;

        Item(String id, boolean episode, String title, int year, int season, int number, String episodeTitle,
                long tmdbId, long positionMs, long durationMs, long lastWatched) {
            this(id, episode, title, year, season, number, episodeTitle, tmdbId, positionMs, durationMs, lastWatched, null, false);
        }

        Item(String id, boolean episode, String title, int year, int season, int number, String episodeTitle,
                long tmdbId, long positionMs, long durationMs, long lastWatched, String source, boolean listsEpisodes) {
            this.id = id;
            this.episode = episode;
            this.title = title;
            this.year = year;
            this.season = season;
            this.number = number;
            this.episodeTitle = episodeTitle;
            this.tmdbId = tmdbId;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.lastWatched = lastWatched;
            this.source = address(source);
            this.listsEpisodes = episode && listsEpisodes;
        }

        /** How far it has been watched, 0 to 1; -1 when its length isn't known. */
        float progress() {
            if (durationMs <= 0) return -1f;
            return Math.max(0f, Math.min(1f, positionMs / (float) durationMs));
        }

        /** "1 hr 12 min left", or for an episode "S2 E5  ·  23 min left". */
        String caption() {
            String left = timeLeft();
            if (!episode) return left;
            String where = where(season, number);
            if (where.isEmpty()) return left;
            return left.isEmpty() ? where : where + "  ·  " + left;
        }

        /** "1 hr 12 min left"; "" when its length isn't known. */
        String timeLeft() {
            if (durationMs <= 0) return "";
            long minutes = Math.max(1, Math.round(Math.max(0, durationMs - Math.max(0, positionMs)) / 60000.0));
            return Tmdb.duration((int) Math.min(minutes, 100 * 60)) + " left";   // nothing runs 100 hours
        }

        /**
         * Its artwork's key in TMDB: the film's or the show's id ("movie/1284186"), or, when
         * FirePlayer didn't match it, the name and year it is searched for by ("tv?The Last of Us (2025)").
         */
        String artKey() {
            String kind = episode ? "tv" : "movie";
            if (tmdbId > 0) return kind + "/" + tmdbId;
            return kind + "?" + title + (year > 0 ? " (" + year + ")" : "");
        }
    }

    /**
     * A provider's address as FirePlayer sends it ("tv.example.com:8080"), kept to that alone should anything else
     * come with it (a scheme, a login, a path): only the host and port are ever shown. Null for none.
     */
    static String address(String source) {
        if (source == null) return null;
        String s = source.trim();
        int scheme = s.indexOf("://");
        if (scheme >= 0) s = s.substring(scheme + 3);
        for (char end : new char[] {'/', '?', '#', ' '}) {
            int i = s.indexOf(end);
            if (i >= 0) s = s.substring(0, i);
        }
        int login = s.lastIndexOf('@');
        if (login >= 0) s = s.substring(login + 1);
        return s.isEmpty() ? null : s;
    }

    /** "S2 E5", "E5" without a season, or "". */
    static String where(int season, int number) {
        return season > 0 && number > 0 ? "S" + season + " E" + number : number > 0 ? "E" + number : "";
    }

    /**
     * An episode of an entry's show, as FirePlayer lists it ({@link #episodes}): what its Episodes sheet shows, and the
     * provider's id for it, handed back to play it. One from TMDB ({@link #fromTmdb}) has no id and can't be played.
     */
    static final class Episode {
        /** The provider's id for it (opaque); "" for one that came from TMDB. */
        final String id;
        /** As FirePlayer shows them (TMDB's numbering when it paired the show); season 0 is Specials. */
        final int season, number, runtimeMin;
        /** "" when there is none; {@code still} is a picture's address, or null. */
        final String name, overview, still, airDate;
        /** Where it was left in FirePlayer, 0 for never. */
        final long positionMs;
        /** The entry's own episode (FirePlayer marks at most one). */
        final boolean current;

        Episode(String id, int season, int number, String name, String overview, String still, int runtimeMin,
                String airDate, long positionMs, boolean current) {
            this.id = id == null ? "" : id;
            this.season = season;
            this.number = number;
            this.name = name == null ? "" : name.trim();
            this.overview = overview == null ? "" : overview.trim();
            this.still = still == null || still.isEmpty() ? null : still;
            this.runtimeMin = Math.max(0, runtimeMin);
            this.airDate = airDate == null ? "" : airDate.trim();
            this.positionMs = Math.max(0, positionMs);
            this.current = current;
        }

        /** How far it has been watched, 0 to 1; -1 when it hasn't been, or its length isn't known. */
        float progress() {
            if (positionMs <= 0 || runtimeMin <= 0) return -1f;
            return Math.min(1f, positionMs / (runtimeMin * 60000f));
        }
    }

    /** An episode as Glass Home's own TMDB has it, for the details window's "This episode" when FirePlayer can't list them. */
    static Episode fromTmdb(Tmdb.Episode e) {
        return new Episode("", e.season, e.number, e.name, e.overview, e.still, e.runtime, e.airDate, 0, true);
    }

    /** Whether {@code e} is the episode {@code item} was left on: FirePlayer's mark, or the same season and number. */
    static boolean isItsEpisode(Episode e, Item item) {
        return e.current || (item.number > 0 && e.season == item.season && e.number == item.number);
    }

    /** The episode {@code item} was left on, of its show's {@code episodes}; null if they don't have it. */
    static Episode itsEpisode(List<Episode> episodes, Item item) {
        if (episodes == null) return null;
        for (Episode e : episodes) if (e.current) return e;
        for (Episode e : episodes) if (isItsEpisode(e, item)) return e;
        return null;
    }

    private ContinueWatching() {}

    /**
     * Everything FirePlayer offers, newest first; empty without it. Null when FirePlayer is there but couldn't answer
     * (being updated, or its process restarting): that isn't an empty list, and is asked again. Off the main thread: it
     * is another app.
     */
    static List<Item> query(Context context) {
        List<Item> out = new ArrayList<>();
        try (ContentProviderClient client = context.getContentResolver().acquireUnstableContentProviderClient(ITEMS)) {
            // No FirePlayer, or one without Continue Watching; but one that's installed may be in the middle of an update
            if (client == null) return installed(context) ? null : out;
            try (Cursor c = client.query(ITEMS, null, null, null, null)) {
                if (c == null) return null;
                while (c.moveToNext()) {
                    Item item;
                    try {
                        item = read(c);
                    } catch (RuntimeException e) {
                        continue;   // a row FirePlayer filled oddly (text where a number goes): the others still show
                    }
                    if (item != null) out.add(item);
                }
            }
        } catch (Exception e) {
            // FirePlayer stopped while answering (an update, Fire OS closing it): not an answer
            return null;
        }
        return out;
    }

    /** FirePlayer is installed (with or without Continue Watching). */
    private static boolean installed(Context context) {
        try {
            context.getPackageManager().getPackageInfo(FirePlayerTitles.PACKAGE, 0);
            return true;
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static Item read(Cursor c) {
        String id = text(c, "id"), kind = text(c, "kind"), title = text(c, "title");
        if (id == null || id.isEmpty() || title == null || title.isEmpty()) return null;
        boolean episode;
        if ("movie".equals(kind)) episode = false;
        else if ("episode".equals(kind)) episode = true;
        else return null;
        // "source" and "has_episodes" come from newer FirePlayers; an older one's rows have neither (null, false)
        return new Item(id, episode, title, (int) number(c, "year"), (int) number(c, "season"), (int) number(c, "episode"),
                text(c, "episode_title"), number(c, "tmdb_id"), number(c, "position_ms"), number(c, "duration_ms"),
                number(c, "last_watched"), optionalText(c, "source"), optionalNumber(c, "has_episodes") == 1);
    }

    /** A column only newer FirePlayers have: null when it's missing or odd, never a reason to drop the row. */
    private static String optionalText(Cursor c, String column) {
        try {
            return text(c, column);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long optionalNumber(Cursor c, String column) {
        try {
            return number(c, column);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static String text(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? null : c.getString(i);
    }

    private static long number(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i < 0 || c.isNull(i) ? 0 : c.getLong(i);
    }

    /** The films (or, with {@code episodes}, the shows) among {@code items}, in their order; an id once. */
    static List<Item> of(List<Item> items, boolean episodes) {
        List<Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Item i : items) {
            if (i.episode == episodes && out.size() < MAX && seen.add(i.id)) out.add(i);
        }
        return Collections.unmodifiableList(out);
    }

    /** Films and shows together for Home's row, in FirePlayer's order (most recently watched first); an id once. */
    static List<Item> forHome(List<Item> items) {
        List<Item> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Item i : items) {
            if (out.size() < MAX && seen.add((i.episode ? "s:" : "f:") + i.id)) out.add(i);
        }
        return Collections.unmodifiableList(out);
    }

    /** Takes an entry off the list (for an episode, its show); false if FirePlayer couldn't be asked. Off the main thread. */
    static boolean delete(Context context, String id) {
        try (ContentProviderClient client = context.getContentResolver().acquireUnstableContentProviderClient(ITEMS)) {
            if (client == null) return false;
            client.delete(Uri.withAppendedPath(ITEMS, id), null, null);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Every episode FirePlayer has for the show of entry {@code id} (an episode it said {@link Item#listsEpisodes}
     * of), by season and then number: the list its own Episodes sheet shows. Null when FirePlayer couldn't list them
     * (no answer, the entry gone, the provider out of reach with nothing saved): an empty answer means the same. Off
     * the main thread, and it may take a while: FirePlayer may ask the provider, and TMDB the first time.
     */
    static List<Episode> episodes(Context context, String id) {
        Uri uri = ITEMS.buildUpon().appendPath(id).appendPath("episodes").build();
        List<Episode> out = new ArrayList<>();
        try (ContentProviderClient client = context.getContentResolver().acquireUnstableContentProviderClient(ITEMS)) {
            if (client == null) return null;
            try (Cursor c = client.query(uri, null, null, null, null)) {
                if (c == null) return null;
                while (c.moveToNext()) {
                    try {
                        Episode e = readEpisode(c);
                        if (e != null) out.add(e);
                    } catch (RuntimeException e) {
                        // a row filled oddly: the others still show
                    }
                }
            }
        } catch (Exception e) {
            return null;   // FirePlayer stopped while answering (an update, Fire OS closing it)
        }
        return out.isEmpty() ? null : out;
    }

    private static Episode readEpisode(Cursor c) {
        String id = text(c, "episode_id");
        if (id == null || id.isEmpty()) return null;   // nothing to play it by
        return new Episode(id, (int) number(c, "season"), (int) number(c, "episode"), text(c, "name"), text(c, "overview"),
                text(c, "image"), (int) number(c, "runtime_min"), text(c, "air_date"), number(c, "position_ms"),
                number(c, "current") == 1);
    }

    /**
     * Opens FirePlayer's player on another episode of entry {@code id}'s show (its episode {@code episodeId}, from
     * {@link #episodes}), from where FirePlayer left that one, else its start. An older FirePlayer has nothing that
     * answers it: starting it fails, rather than playing anything else.
     */
    static Intent playEpisodeIntent(String id, String episodeId) {
        return new Intent(ACTION_PLAY_EPISODE).setPackage(FirePlayerTitles.PACKAGE).putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_EPISODE_ID, episodeId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    }

    /** Opens FirePlayer's player on the entry, where it was left. */
    static Intent resumeIntent(String id) {
        return new Intent(ACTION_RESUME).setPackage(FirePlayerTitles.PACKAGE).putExtra(EXTRA_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);   // a player left behind from another title never stands in for this one
    }

    /** Calls {@code onChange} (on {@code handler}'s thread) whenever FirePlayer's list changes. */
    static final class Watcher {
        private final ContentResolver resolver;
        private final ContentObserver observer;
        private boolean registered;

        Watcher(Context context, Handler handler, Runnable onChange) {
            resolver = context.getContentResolver();
            observer = new ContentObserver(handler) {
                @Override
                public void onChange(boolean selfChange) {
                    onChange.run();
                }
            };
        }

        /**
         * Starts watching, if it isn't already. Android refuses to watch a provider that isn't there
         * (FirePlayer not installed yet, or an older one), so this is tried again whenever the list is.
         */
        void watch() {
            if (registered) return;
            try {
                resolver.registerContentObserver(ITEMS, true, observer);
                registered = true;
            } catch (SecurityException e) {
                // no provider yet
            }
        }

        void stop() {
            if (!registered) return;
            registered = false;
            resolver.unregisterContentObserver(observer);
        }
    }
}
