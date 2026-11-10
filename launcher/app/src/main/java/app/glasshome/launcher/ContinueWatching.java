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
 */
final class ContinueWatching {
    static final String AUTHORITY = "app.fireplayer.continue";
    static final Uri ITEMS = Uri.parse("content://" + AUTHORITY + "/items");
    static final String ACTION_RESUME = "app.fireplayer.action.RESUME";
    static final String EXTRA_ID = "app.fireplayer.extra.CONTINUE_ID";
    /** The most the row shows of each kind (FirePlayer keeps no more). */
    static final int MAX = 20;

    static final class Item {
        final String id, title, episodeTitle;
        final boolean episode;
        final int year, season, number;
        final long tmdbId, positionMs, durationMs, lastWatched;

        Item(String id, boolean episode, String title, int year, int season, int number, String episodeTitle,
                long tmdbId, long positionMs, long durationMs, long lastWatched) {
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
        }

        /** How far it has been watched, 0 to 1; -1 when its length isn't known. */
        float progress() {
            if (durationMs <= 0) return -1f;
            return Math.max(0f, Math.min(1f, positionMs / (float) durationMs));
        }

        /** "1 hr 12 min left", or for an episode "S2 E5  ·  23 min left". */
        String caption() {
            String left = "";
            if (durationMs > 0) {
                long minutes = Math.max(1, Math.round(Math.max(0, durationMs - Math.max(0, positionMs)) / 60000.0));
                left = Tmdb.duration((int) Math.min(minutes, 100 * 60)) + " left";   // nothing runs 100 hours
            }
            if (!episode) return left;
            String where = season > 0 && number > 0 ? "S" + season + " E" + number : number > 0 ? "E" + number : "";
            if (where.isEmpty()) return left;
            return left.isEmpty() ? where : where + "  ·  " + left;
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

    private ContinueWatching() {}

    /** Everything FirePlayer offers, newest first; empty without it. Off the main thread: it is another app. */
    static List<Item> query(Context context) {
        List<Item> out = new ArrayList<>();
        try (ContentProviderClient client = context.getContentResolver().acquireUnstableContentProviderClient(ITEMS)) {
            if (client == null) return out;   // no FirePlayer, or one without Continue Watching
            try (Cursor c = client.query(ITEMS, null, null, null, null)) {
                if (c == null) return out;
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
            // FirePlayer stopped or refused: as good as an empty list
            return new ArrayList<>();
        }
        return out;
    }

    private static Item read(Cursor c) {
        String id = text(c, "id"), kind = text(c, "kind"), title = text(c, "title");
        if (id == null || id.isEmpty() || title == null || title.isEmpty()) return null;
        boolean episode;
        if ("movie".equals(kind)) episode = false;
        else if ("episode".equals(kind)) episode = true;
        else return null;
        return new Item(id, episode, title, (int) number(c, "year"), (int) number(c, "season"), (int) number(c, "episode"),
                text(c, "episode_title"), number(c, "tmdb_id"), number(c, "position_ms"), number(c, "duration_ms"),
                number(c, "last_watched"));
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
