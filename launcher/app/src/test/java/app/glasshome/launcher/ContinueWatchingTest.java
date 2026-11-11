package app.glasshome.launcher;

import static android.view.KeyEvent.KEYCODE_DPAD_CENTER;
import static android.view.KeyEvent.KEYCODE_DPAD_DOWN;
import static android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.glasshome.launcher.HomeActivity.Zone;

/**
 * FirePlayer's Continue Watching atop Movies and TV Shows. FirePlayer is stood in for by a provider
 * under its authority, keeping rows as FirePlayer's contract gives them; the artwork comes from the
 * recorded TMDB responses for those titles (see {@link Fixtures}).
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, qualifiers = "w960dp-h540dp-land-xhdpi")
public class ContinueWatchingTest {
    private static final int MOVIES = HomeActivity.MOVIES, TV = HomeActivity.TV;
    private static final String[] COLUMNS = {"id", "kind", "title", "year", "season", "episode", "episode_title", "tmdb_id",
            "position_ms", "duration_ms", "last_watched"};
    private static final long MIN = 60_000L;

    /** FirePlayer's provider, as the contract has it: rows newest first, delete by id, a change notified. */
    public static final class FakeFirePlayer extends ContentProvider {
        static final List<Object[]> rows = Collections.synchronizedList(new ArrayList<>());
        static final List<String> deleted = Collections.synchronizedList(new ArrayList<>());
        static volatile int queries;
        /** FirePlayer in the middle of an update (or its process restarting): it can't answer. */
        static volatile boolean failing;

        @Override
        public boolean onCreate() {
            return true;
        }

        @Override
        public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
            queries++;
            if (failing) throw new IllegalStateException("FirePlayer is being updated");
            MatrixCursor c = new MatrixCursor(COLUMNS);
            synchronized (rows) {
                for (Object[] r : rows) c.addRow(r);
            }
            return c;
        }

        @Override
        public int delete(Uri uri, String selection, String[] args) {
            String id = uri.getLastPathSegment();
            deleted.add(id);
            boolean removed;
            synchronized (rows) {
                removed = rows.removeIf(r -> r[0].equals(id));
            }
            getContext().getContentResolver().notifyChange(ContinueWatching.ITEMS, null);
            return removed ? 1 : 0;
        }

        @Override
        public String getType(Uri uri) {
            return null;
        }

        @Override
        public Uri insert(Uri uri, ContentValues values) {
            return null;
        }

        @Override
        public int update(Uri uri, ContentValues values, String selection, String[] args) {
            return 0;
        }
    }

    private static Object[] movie(String id, String title, int year, long tmdb, long position, long duration, long watched) {
        return new Object[] {id, "movie", title, year, 0, 0, null, tmdb, position, duration, watched};
    }

    private static Object[] episode(String id, String show, int season, int number, String name, long tmdb, long position,
            long duration, long watched) {
        return new Object[] {id, "episode", show, 0, season, number, name, tmdb, position, duration, watched};
    }

    /** An episode of a show FirePlayer didn't match to TMDB, from a year it knows. */
    private static Object[] unmatched(String id, String show, int year, int season, int number, long position, long duration,
            long watched) {
        Object[] row = episode(id, show, season, number, null, 0, position, duration, watched);
        row[3] = year;
        return row;
    }

    /** The searches TMDB was asked, in order (see {@link #withSearchesByYear}). */
    private static final List<String> searches = Collections.synchronizedList(new ArrayList<>());
    private static final Pattern BY_YEAR = Pattern.compile("&(?:first_air_date_year|year)=(\\d{4})");
    private static Net.Fetcher recorded;

    @Before
    public void reset() {
        FakeFirePlayer.rows.clear();
        FakeFirePlayer.deleted.clear();
        FakeFirePlayer.queries = 0;
        FakeFirePlayer.failing = false;
        searches.clear();
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16));
        // These are the rows of Movies and TV Shows; Home's own row is tested in HomeContinueRowTest
        new HomeSettings(org.robolectric.RuntimeEnvironment.getApplication()).setHomeContinue(false);
    }

    @After
    public void restore() {
        if (recorded != null) Net.fetcher = recorded;
        recorded = null;
    }

    /**
     * TMDB as recorded, and its searches narrowed to a year too: those are answered from the
     * recorded search by the same name, keeping what came out in that year, as TMDB does. Each
     * search asked is noted in {@link #searches}. Once Home is up, which puts the recordings in place.
     */
    private static void withSearchesByYear() {
        recorded = Net.fetcher;
        Net.Fetcher fixtures = recorded;
        Net.fetcher = (url, bearer) -> {
            if (url.startsWith(Tmdb.API + "search/")) searches.add(url.substring(Tmdb.API.length()));
            Matcher m = BY_YEAR.matcher(url);
            if (!m.find()) return fixtures.get(url, bearer);
            String year = m.group(1), date = url.contains("/search/tv?") ? "first_air_date" : "release_date";
            try {
                JSONObject all = new JSONObject(new String(fixtures.get(m.replaceFirst(""), bearer), StandardCharsets.UTF_8));
                JSONArray results = all.getJSONArray("results"), kept = new JSONArray();
                for (int i = 0; i < results.length(); i++) {
                    if (results.getJSONObject(i).optString(date).startsWith(year)) kept.put(results.getJSONObject(i));
                }
                return all.put("results", kept).toString().getBytes(StandardCharsets.UTF_8);
            } catch (JSONException e) {
                throw new IOException(e);
            }
        };
    }

    /** FirePlayer with films and episodes on its list (TMDB ids the recorded responses have). */
    private static void withFirePlayer() {
        long t = Fixtures.RECORDED_AT;
        Collections.addAll(FakeFirePlayer.rows,
                episode("e1", "Law & Order", 24, 5, "Hard Cases", 549, 18 * MIN, 42 * MIN, t - 1000),
                movie("m1", "Sacrifice", 2026, 1284186, 28 * MIN, 100 * MIN, t - 2000),
                movie("m2", "Masterplan", 2026, 1561726, 75 * MIN, 110 * MIN, t - 3000),
                episode("e2", "The Simpsons", 37, 3, null, 456, 6 * MIN, 22 * MIN, t - 4000),
                movie("m3", "Animals", 2026, 1236045, 10 * MIN, 106 * MIN, t - 5000),
                movie("m4", "Summer Camp Tapes", 0, 0, 40 * MIN, 95 * MIN, t - 6000),
                episode("e3", "NCIS", 23, 12, "Ghosts", 4614, 30 * MIN, 44 * MIN, t - 7000),
                movie("m5", "The Breadwinner", 2026, 1440050, 50 * MIN, 100 * MIN, t - 8000));
        Robolectric.setupContentProvider(FakeFirePlayer.class, ContinueWatching.AUTHORITY);
    }

    /** Home, then the tab bar along to page {@code p}, its content loaded and the row asked for. */
    private static TestHome open(int p) throws Exception {
        return open(TestHome.withTmdb(), p);
    }

    private static TestHome open(TestHome h, int p) throws Exception {
        h.menu();
        for (int i = 0; i < p; i++) h.press(KEYCODE_DPAD_RIGHT);
        assertEquals(p, h.activity.page());
        h.waitForPage(p);
        settleRow();
        return h;
    }

    /** Lets FirePlayer's answer, and the artwork looked up for it, arrive. */
    private static void settleRow() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            TestHome.settle();
            TestHome.settleImages();
        }
    }

    private static PageModel.Shelf first(TestHome h, int p) {
        return h.page(p).model().shelves.get(0);
    }

    private static List<String> ids(PageModel.Shelf s) {
        List<String> out = new ArrayList<>();
        for (PageModel.Card c : s.cards) out.add(c.id);
        return out;
    }

    private static void holdSelect(TestHome h) throws InterruptedException {
        h.hold(KEYCODE_DPAD_CENTER, HomeActivity.TILE_HOLD_MS + 200, 50);
    }

    @Test
    public void moviesShowFilmsAndTvShowsShowEpisodes() throws Exception {
        withFirePlayer();
        TestHome h = open(MOVIES);
        PageModel.Shelf row = first(h, MOVIES);
        assertEquals("Continue Watching", row.title);
        assertEquals(PageModel.Style.CONTINUE, row.style);
        assertEquals("films only, newest first", java.util.Arrays.asList("m1", "m2", "m3", "m4", "m5"), ids(row));
        assertEquals("the page's own shelves follow", "Top 10 Movies This Week", h.page(MOVIES).model().shelves.get(1).title);
        PageModel.Card sacrifice = row.cards.get(0);
        assertEquals("Sacrifice", sacrifice.title);
        assertEquals("1 hr 12 min left", sacrifice.subtitle);
        assertEquals(0.28f, sacrifice.progress, 0.001f);
        assertEquals("TMDB's backdrop, by its id", "https://image.tmdb.org/t/p/w780/oAUf1pvGlafCZVlEuPt1JyONHR1.jpg", sacrifice.imageUrl);
        assertNull("no TMDB id, and none found by its name: its title stands in for artwork", row.cards.get(3).imageUrl);
        assertEquals("55 min left", row.cards.get(3).subtitle);

        h.press(KEYCODE_DPAD_DOWN);   // the hero
        h.press(KEYCODE_DPAD_DOWN);   // the row
        h.press(KEYCODE_DPAD_RIGHT);
        assertEquals(0, h.page(MOVIES).focusedRow());
        assertEquals(1, h.page(MOVIES).focusedColumn());
        TestHome.settleImages();
        h.capture("continue-movies.png");

        // TV Shows: one card per show, its episode in the caption
        h.menu();
        h.press(KEYCODE_DPAD_RIGHT);
        assertEquals(TV, h.activity.page());
        h.waitForPage(TV);
        settleRow();
        PageModel.Shelf shows = first(h, TV);
        assertEquals("Continue Watching", shows.title);
        assertEquals(java.util.Arrays.asList("e1", "e2", "e3"), ids(shows));
        assertEquals("Law & Order", shows.cards.get(0).title);
        assertEquals("S24 E5  ·  24 min left", shows.cards.get(0).subtitle);
        assertEquals("https://image.tmdb.org/t/p/w780/hrfP9BQ4mOMZOa8nHbFJLSDHBLz.jpg", shows.cards.get(0).imageUrl);
        assertEquals("Top 10 Shows This Week", h.page(TV).model().shelves.get(1).title);
        h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_DOWN);
        assertEquals(0, h.page(TV).focusedRow());
        TestHome.settleImages();
        h.capture("continue-tv.png");
    }

    @Test
    public void noRowWhereThereIsNothingOfItsKind() throws Exception {
        long t = Fixtures.RECORDED_AT;
        FakeFirePlayer.rows.add(episode("e1", "NCIS", 23, 12, null, 4614, 30 * MIN, 44 * MIN, t));
        Robolectric.setupContentProvider(FakeFirePlayer.class, ContinueWatching.AUTHORITY);
        TestHome h = open(MOVIES);
        assertTrue("FirePlayer was asked", FakeFirePlayer.queries > 0);
        assertEquals("no films: no row on Movies", "Top 10 Movies This Week", first(h, MOVIES).title);
        assertNull(h.page(MOVIES).topShelf());
        assertEquals("but one on TV Shows", "Continue Watching", h.page(TV).topShelf().title);
    }

    @Test
    public void noFirePlayerNoRowAndNothingElseChanges() throws Exception {
        TestHome h = open(MOVIES);
        assertEquals("Top 10 Movies This Week", first(h, MOVIES).title);
        assertNull(h.page(MOVIES).topShelf());
        h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_DOWN);
        assertEquals(0, h.page(MOVIES).focusedRow());
        // Holding Select on a page's card is a press, as before: its details on release
        holdSelect(h);
        assertFalse(h.activity.tileMenu().isOpen());
        assertEquals(Zone.PAGE_DETAIL, h.zone());
        h.back();

        h.menu();
        h.press(KEYCODE_DPAD_RIGHT);
        h.waitForPage(TV);
        settleRow();
        assertEquals("Top 10 Shows This Week", first(h, TV).title);
        assertNull(h.page(TV).topShelf());
    }

    @Test
    public void selectResumesInFirePlayer() throws Exception {
        withFirePlayer();
        TestHome h = open(MOVIES);
        h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_CENTER);
        Intent started = shadowOf(h.activity).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(ContinueWatching.ACTION_RESUME, started.getAction());
        assertEquals("app.fireplayer", started.getPackage());
        assertEquals("m2", started.getStringExtra(ContinueWatching.EXTRA_ID));
        assertTrue((started.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertTrue("a player left behind from another title is cleared away", (started.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TASK) != 0);
        assertEquals("no details page on the way", Zone.PAGE, h.zone());
    }

    @Test
    public void artworkLookedUpWithoutTheNetworkComesInOnceItIsBack() throws Exception {
        withFirePlayer();
        TestHome h = TestHome.withTmdb();
        recorded = Net.fetcher;
        Net.Fetcher fixtures = recorded;
        boolean[] offline = {true};
        Net.fetcher = (url, bearer) -> {
            // Only a title's own lookup fails (the page's catalog still loads)
            boolean title = url.startsWith(Tmdb.API) && url.substring(Tmdb.API.length()).matches("(movie|tv)/\\d+.*");
            if (offline[0] && title) throw new java.net.UnknownHostException("api.themoviedb.org");
            return fixtures.get(url, bearer);
        };
        open(h, MOVIES);
        assertNull("no network: a title card for now", first(h, MOVIES).cards.get(0).imageUrl);

        // The network comes back: the picture comes in on its own, nothing pressed or opened again
        offline[0] = false;
        TestHome.advance(5_100);
        settleRow();
        assertNotNull(first(h, MOVIES).cards.get(0).imageUrl);
    }

    @Test
    public void holdingSelectOffersResumeAndRemove() throws Exception {
        withFirePlayer();
        TestHome h = open(MOVIES);
        h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_DOWN);
        holdSelect(h);
        assertEquals(Zone.TILE_MENU, h.zone());
        TileMenu menu = h.activity.tileMenu();
        assertTrue(menu.isOpen());
        assertArrayEquals(new String[] {"Resume", "Remove"}, menu.labels());
        assertNull("the release after the hold is no press", shadowOf(h.activity).getNextStartedActivity());
        // Under the card
        android.graphics.RectF card = new android.graphics.RectF(), box = new android.graphics.RectF();
        h.page(MOVIES).focusedCardBounds(card);
        menu.cardBounds(box);
        assertTrue(box.top >= card.bottom);
        assertEquals(card.centerX(), box.centerX(), 2f);
        h.press(KEYCODE_DPAD_DOWN);
        assertEquals(1, menu.focusedIndex());
        TestHome.settleImages();
        h.capture("continue-menu.png");

        // Back folds it away, nothing done
        h.back();
        assertFalse(menu.isOpen());
        assertEquals(Zone.PAGE, h.zone());
        assertEquals(5, first(h, MOVIES).cards.size());
        assertTrue(FakeFirePlayer.deleted.isEmpty());

        // Resume from the menu
        holdSelect(h);
        h.press(KEYCODE_DPAD_CENTER);
        assertFalse(menu.isOpen());
        Intent started = shadowOf(h.activity).getNextStartedActivity();
        assertNotNull(started);
        assertEquals("m1", started.getStringExtra(ContinueWatching.EXTRA_ID));

        // A hold on the page's other cards is still a press
        h.press(KEYCODE_DPAD_DOWN);
        assertEquals("Top 10 Movies This Week", h.page(MOVIES).model().shelves.get(1).title);
        holdSelect(h);
        assertFalse(menu.isOpen());
        assertEquals(Zone.PAGE_DETAIL, h.zone());
    }

    @Test
    public void removeTakesTheCardAwayAndTellsFirePlayer() throws Exception {
        withFirePlayer();
        TestHome h = open(MOVIES);
        ShelfPage movies = h.page(MOVIES);
        h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_RIGHT);
        assertEquals("m2", movies.focusedCard().id);
        holdSelect(h);
        h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_CENTER);
        settleRow();
        assertEquals(java.util.Collections.singletonList("m2"), FakeFirePlayer.deleted);
        assertEquals(java.util.Arrays.asList("m1", "m3", "m4", "m5"), ids(first(h, MOVIES)));
        assertEquals("focus on the card that took its place", "m3", movies.focusedCard().id);
        assertEquals(Zone.PAGE, h.zone());

        // The last of the row: the row goes, focus on the shelf that moves up into its place
        for (String id : new String[] {"m3", "m4", "m5", "m1"}) {
            holdSelect(h);
            h.press(KEYCODE_DPAD_DOWN);
            h.press(KEYCODE_DPAD_CENTER);
            settleRow();
        }
        assertEquals(java.util.Arrays.asList("m2", "m3", "m4", "m5", "m1"), FakeFirePlayer.deleted);
        assertNull(movies.topShelf());
        assertEquals("Top 10 Movies This Week", first(h, MOVIES).title);
        assertEquals(0, movies.focusedRow());
        assertEquals(Zone.PAGE, h.zone());
        assertTrue("TV Shows keeps its row", h.page(TV).topShelf() != null);
    }

    @Test
    public void theRowFollowsFirePlayersChanges() throws Exception {
        withFirePlayer();
        TestHome h = open(MOVIES);
        h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_RIGHT);
        assertEquals("m3", h.page(MOVIES).focusedCard().id);
        // A film watched in FirePlayer meanwhile comes to the front; focus stays on its card
        FakeFirePlayer.rows.add(0, movie("m6", "Masterplan II", 2026, 0, 6 * MIN, 100 * MIN, Fixtures.RECORDED_AT));
        h.activity.getContentResolver().notifyChange(ContinueWatching.ITEMS, null);
        settleRow();
        assertEquals(java.util.Arrays.asList("m6", "m1", "m2", "m3", "m4", "m5"), ids(first(h, MOVIES)));
        assertEquals(0, h.page(MOVIES).focusedRow());
        assertEquals("m3", h.page(MOVIES).focusedCard().id);

        // All of it finished elsewhere: the row goes
        FakeFirePlayer.rows.clear();
        h.activity.getContentResolver().notifyChange(ContinueWatching.ITEMS, null);
        settleRow();
        assertNull(h.page(MOVIES).topShelf());
        assertEquals("Top 10 Movies This Week", first(h, MOVIES).title);
    }

    @Test
    public void oddRowsAreSkippedOrMadeSenseOf() throws Exception {
        long t = Fixtures.RECORDED_AT;
        Collections.addAll(FakeFirePlayer.rows,
                new Object[] {"x1", "movie", "No Length", 0, 0, 0, null, null, 5 * MIN, 0, t},
                new Object[] {"x2", "movie", "Back To Front", 0, 0, 0, null, 0, -4 * MIN, 30 * MIN, t},
                new Object[] {"x3", "movie", "Past The End", 0, 0, 0, null, 0, Long.MAX_VALUE, 30 * MIN, t},
                new Object[] {"x4", "movie", "Forever", 0, 0, 0, null, 0, 0, Long.MAX_VALUE, t},
                new Object[] {"x5", "movie", "Garbled", "a year", 0, 0, null, "not a number", 0, 30 * MIN, t},
                new Object[] {null, "movie", "No Id", 0, 0, 0, null, 0, 0, 30 * MIN, t},
                new Object[] {"x6", "live", "A Channel", 0, 0, 0, null, 0, 0, 30 * MIN, t},
                new Object[] {"x7", "movie", null, 0, 0, 0, null, 0, 0, 30 * MIN, t},
                new Object[] {"x1", "movie", "No Length Again", 0, 0, 0, null, 0, 0, 30 * MIN, t},
                new Object[] {"y1", "episode", "A Show", 0, 0, 0, null, 0, 0, 0, t});
        Robolectric.setupContentProvider(FakeFirePlayer.class, ContinueWatching.AUTHORITY);
        List<ContinueWatching.Item> all = ContinueWatching.query(org.robolectric.RuntimeEnvironment.getApplication());
        assertNotNull(all);
        List<ContinueWatching.Item> films = ContinueWatching.of(all, false);
        List<String> ids = new ArrayList<>();
        for (ContinueWatching.Item i : films) ids.add(i.id);
        assertEquals("no id, no title, not a film or episode, garbled or seen before: left out",
                java.util.Arrays.asList("x1", "x2", "x3", "x4"), ids);
        assertEquals("no length: no bar, no time left", -1f, films.get(0).progress(), 0f);
        assertEquals("", films.get(0).caption());
        assertEquals(0f, films.get(1).progress(), 0f);
        assertEquals("30 min left", films.get(1).caption());
        assertEquals(1f, films.get(2).progress(), 0f);
        assertEquals("1 min left", films.get(2).caption());
        assertEquals(0f, films.get(3).progress(), 0f);
        assertEquals("100 hr left", films.get(3).caption());
        ContinueWatching.Item show = ContinueWatching.of(all, true).get(0);
        assertEquals("no season or episode, no length: nothing to say", "", show.caption());
        assertEquals("not matched by FirePlayer: looked up by its name", "tv?A Show", show.artKey());

        // And the page shows them without trouble
        TestHome h = open(MOVIES);
        assertEquals(4, first(h, MOVIES).cards.size());
        h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_DOWN);
        for (int i = 0; i < 3; i++) h.press(KEYCODE_DPAD_RIGHT);
        assertEquals("x4", h.page(MOVIES).focusedCard().id);
        h.press(KEYCODE_DPAD_RIGHT);
        assertEquals("Right again at the end goes back to the first", "x1", h.page(MOVIES).focusedCard().id);
    }

    @Test
    public void theMenuIsAsWideAsItsCardAndGoesWhenTheCardMoves() throws Exception {
        withFirePlayer();
        TestHome h = open(MOVIES);
        h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_RIGHT);
        holdSelect(h);
        TileMenu menu = h.activity.tileMenu();
        assertTrue(menu.isOpen());
        android.graphics.RectF card = new android.graphics.RectF(), box = new android.graphics.RectF();
        h.page(MOVIES).focusedCardBounds(card);
        menu.cardBounds(box);
        assertEquals("as wide as its card, as a tile's is", card.width(), box.width(), 1f);
        assertEquals(Math.round(card.width()), menu.getWidth());

        // Something played meanwhile comes in ahead of it: its card moves on, and the menu goes
        FakeFirePlayer.rows.add(0, movie("m6", "Masterplan II", 2026, 0, 6 * MIN, 100 * MIN, Fixtures.RECORDED_AT));
        h.activity.getContentResolver().notifyChange(ContinueWatching.ITEMS, null);
        settleRow();
        assertFalse(menu.isOpen());
        assertEquals(Zone.PAGE, h.zone());
        assertEquals("m2", h.page(MOVIES).focusedCard().id);
        assertTrue(FakeFirePlayer.deleted.isEmpty());

        // Home's tiles keep their own size of menu
        h.menu();
        for (int i = 0; i < MOVIES; i++) h.press(android.view.KeyEvent.KEYCODE_DPAD_LEFT);
        assertEquals(HomeActivity.HOME, h.activity.page());
        h.press(KEYCODE_DPAD_DOWN);
        holdSelect(h);
        assertTrue(menu.isOpen());
        assertArrayEquals(TileMenu.LABELS, menu.labels());
        assertEquals(TileMenu.width(), menu.getWidth());
    }

    @Test
    public void aRefreshOfTheSameCardsLetsTheGapFinishClosing() throws Exception {
        withFirePlayer();
        TestHome h = open(MOVIES);
        ShelfPage movies = h.page(MOVIES);
        PageModel.Shelf row = movies.topShelf();
        PageModel.Shelf fewer = new PageModel.Shelf(row.title, row.style), again = new PageModel.Shelf(row.title, row.style);
        for (PageModel.Card c : row.cards) {
            if (c.id.equals("m2")) continue;
            fewer.cards.add(c);
            again.cards.add(c);
        }
        movies.setTopShelf(fewer);
        assertTrue(movies.closingGap());
        // FirePlayer's answer to the Remove, or artwork arriving, before the gap has closed
        movies.setTopShelf(again);
        assertTrue("still closing", movies.closingGap());
        TestHome.settle();
        assertFalse(movies.closingGap());
        assertEquals(java.util.Arrays.asList("m1", "m3", "m4", "m5"), ids(first(h, MOVIES)));
    }

    /**
     * An entry FirePlayer sent without a TMDB id (no key there, TMDB not sure, an older FirePlayer) has
     * its cover all the same: the title TMDB has by exactly its name, searched for once.
     */
    @Test
    public void entriesWithoutATmdbIdAreFoundByTheirNames() throws Exception {
        long t = Fixtures.RECORDED_AT;
        Collections.addAll(FakeFirePlayer.rows,
                episode("e1", "Stranger Things", 4, 1, "The Hellfire Club", 0, 20 * MIN, 78 * MIN, t - 1000),
                // Its second season's year: the show first aired two years before
                unmatched("e2", "The Last of Us", 2025, 2, 3, 10 * MIN, 50 * MIN, t - 2000),
                movie("m1", "Inside Out 2", 0, 0, 30 * MIN, 97 * MIN, t - 3000));
        Robolectric.setupContentProvider(FakeFirePlayer.class, ContinueWatching.AUTHORITY);
        TestHome h = TestHome.withTmdb();
        withSearchesByYear();
        open(h, TV);
        PageModel.Shelf shows = first(h, TV);
        assertEquals(java.util.Arrays.asList("e1", "e2"), ids(shows));
        assertEquals("TMDB's Stranger Things, by its name", "https://image.tmdb.org/t/p/w780/9P4IIMYY3HifqeruZq0ZZ9g7YUi.jpg",
                shows.cards.get(0).imageUrl);
        assertEquals("first aired before the year FirePlayer has", "https://image.tmdb.org/t/p/w780/lY2DhbA7Hy44fAKddr06UrXWWaQ.jpg",
                shows.cards.get(1).imageUrl);
        assertEquals("S2 E3  ·  40 min left", shows.cards.get(1).subtitle);
        assertNull("TMDB has two films by that name (1992's and 2024's) and FirePlayer gave no year to tell them apart:"
                + " its title card, not perhaps the wrong cover", h.page(MOVIES).topShelf().cards.get(0).imageUrl);
        List<String> asked = java.util.Arrays.asList(
                "search/tv?language=en-US&query=Stranger%20Things&include_adult=false&page=1",
                "search/tv?language=en-US&query=The%20Last%20of%20Us&include_adult=false&first_air_date_year=2025&page=1",
                "search/tv?language=en-US&query=The%20Last%20of%20Us&include_adult=false&page=1",
                "search/movie?language=en-US&query=Inside%20Out%202&include_adult=false&page=1");
        assertEquals("with the year when there is one, then without it", asked, new ArrayList<>(searches));

        // Asked again (FirePlayer's list changed): the covers stay, and nothing is searched again
        h.activity.getContentResolver().notifyChange(ContinueWatching.ITEMS, null);
        settleRow();
        assertTrue(FakeFirePlayer.queries > 1);
        assertEquals(asked, new ArrayList<>(searches));
        assertEquals("https://image.tmdb.org/t/p/w780/9P4IIMYY3HifqeruZq0ZZ9g7YUi.jpg", first(h, TV).cards.get(0).imageUrl);
        h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_DOWN);
        assertEquals(0, h.page(TV).focusedRow());
        TestHome.settleImages();
        h.capture("continue-by-name.png");
    }

    /**
     * Only a title by exactly its name, from about its year, is taken: anything else could put the
     * wrong cover on it. Without one its title stands in, as before, and it isn't searched again.
     */
    @Test
    public void nothingByExactlyItsNameKeepsItsTitleCard() throws Exception {
        long t = Fixtures.RECORDED_AT;
        Collections.addAll(FakeFirePlayer.rows,
                // TMDB's The Last of Us first aired in 2023; from 2013 it has only "The Last of Us Development Series"
                unmatched("e1", "The Last of Us", 2013, 1, 2, 10 * MIN, 50 * MIN, t - 1000),
                // TMDB's films by that name are from 1992 and 2024
                movie("m1", "Inside Out 2", 2010, 0, 30 * MIN, 97 * MIN, t - 2000));
        Robolectric.setupContentProvider(FakeFirePlayer.class, ContinueWatching.AUTHORITY);
        TestHome h = TestHome.withTmdb();
        withSearchesByYear();
        open(h, MOVIES);
        PageModel.Card film = first(h, MOVIES).cards.get(0), show = h.page(TV).topShelf().cards.get(0);
        assertNull(film.imageUrl);
        assertEquals("Inside Out 2", film.title);
        assertEquals("1 hr 7 min left", film.subtitle);
        assertNull(show.imageUrl);
        assertEquals("The Last of Us", show.title);
        assertEquals("S1 E2  ·  40 min left", show.subtitle);
        assertEquals("each with its year, then without it", 4, searches.size());

        h.activity.getContentResolver().notifyChange(ContinueWatching.ITEMS, null);
        settleRow();
        assertTrue(FakeFirePlayer.queries > 1);
        assertEquals("not searched again", 4, searches.size());
        assertNull(first(h, MOVIES).cards.get(0).imageUrl);
    }

    @Test
    public void aNameSharedByARemakeNeedsTheYearAndTheNearestYearWins() throws Exception {
        org.json.JSONArray shows = new org.json.JSONArray()
                .put(new org.json.JSONObject().put("id", 1).put("name", "Shōgun").put("first_air_date", "1980-09-15").put("popularity", 90.0))
                .put(new org.json.JSONObject().put("id", 2).put("name", "Shogun").put("first_air_date", "2024-02-27").put("popularity", 40.0))
                .put(new org.json.JSONObject().put("id", 3).put("name", "Shogun Assassin").put("first_air_date", "2024-01-01").put("popularity", 99.0));
        assertNull("no year: two shows by that name, so neither", Tmdb.exact(shows, "Shogun", 0, true));
        assertEquals("watched in 2024: the 2024 show, though the other is more popular",
                2, Tmdb.exact(shows, "Shogun", 2024, true).optInt("id"));
        assertEquals("a season from 1981: the 1980 show", 1, Tmdb.exact(shows, "Shogun", 1981, true).optInt("id"));
        org.json.JSONArray one = new org.json.JSONArray()
                .put(new org.json.JSONObject().put("id", 33880).put("name", "The Legend of Korra").put("first_air_date", "2012-04-14"))
                .put(new org.json.JSONObject().put("id", 7).put("name", "Avatar: The Legend of Korra Recap").put("first_air_date", "2013-01-01"));
        assertEquals("no year, but the only show by exactly that name", 33880,
                Tmdb.exact(one, "The Legend of Korra", 0, true).optInt("id"));
    }

    @Test
    public void namesAreComparedWrittenPlainly() {
        assertEquals(Tmdb.plain("Law and Order"), Tmdb.plain("Law & Order"));
        assertEquals(Tmdb.plain("Pokemon: Indigo League"), Tmdb.plain("Pokémon – Indigo League"));
        assertEquals("thelegendofkorra", Tmdb.plain("The Legend of Korra"));
        assertFalse("no word is left out", Tmdb.plain("Legend of Korra").equals(Tmdb.plain("The Legend of Korra")));
    }

    @Test
    public void aFirePlayerThatCannotAnswerIsNoAnswerNotAnEmptyList() {
        long t = Fixtures.RECORDED_AT;
        FakeFirePlayer.rows.add(new Object[] {"m1", "movie", "Sacrifice", 2026, 0, 0, null, 1284186, 28 * MIN, 100 * MIN, t});
        Robolectric.setupContentProvider(FakeFirePlayer.class, ContinueWatching.AUTHORITY);
        android.content.Context app = org.robolectric.RuntimeEnvironment.getApplication();
        FakeFirePlayer.failing = true;
        assertNull(ContinueWatching.query(app));
        FakeFirePlayer.failing = false;
        assertEquals(1, ContinueWatching.query(app).size());
    }

    @Test
    public void withoutFirePlayerTheListIsEmpty() {
        List<ContinueWatching.Item> none = ContinueWatching.query(org.robolectric.RuntimeEnvironment.getApplication());
        assertNotNull(none);
        assertTrue(none.isEmpty());
    }
}
