package app.glasshome.launcher;

import static android.view.KeyEvent.KEYCODE_DPAD_CENTER;
import static android.view.KeyEvent.KEYCODE_DPAD_DOWN;
import static android.view.KeyEvent.KEYCODE_DPAD_LEFT;
import static android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
import static android.view.KeyEvent.KEYCODE_DPAD_UP;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import app.glasshome.launcher.ContinueWatchingTest.FakeFirePlayer;
import app.glasshome.launcher.HomeActivity.Zone;

/**
 * Details on a Continue Watching card's menu: the details window (a film, a show), and a show's Episodes window, where
 * any season and episode FirePlayer has can be picked and played. FirePlayer is stood in for as in
 * {@link ContinueWatchingTest}, as a newer one (with the details API: where each entry plays from, and its show's
 * episodes, made up here) or an older one (without); TMDB answers as recorded.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, qualifiers = "w960dp-h540dp-land-xhdpi")
public class DetailsWindowTest {
    private static final long MIN = 60_000L;
    /** Law & Order's backdrop, as the recordings have it, standing in for its episodes' stills. */
    private static final String STILL = "https://image.tmdb.org/t/p/w780/hrfP9BQ4mOMZOa8nHbFJLSDHBLz.jpg";
    private static final String[] NAMES = {"Cracked", "Freefall", "Play Dead", "Fallen Hero", "Hard Cases", "Stand Up", "",
            "Truth Be Told", "Old Wounds", "Second Chair", "Cold Open", "Glass Walls", "The Long Night"};
    private static final String[] STORIES = {
            "A body in a stairwell leads the detectives to a landlord with too many keys.",
            "The prosecutors weigh a plea deal when their only witness starts to waver.",
            "An alibi that holds up a little too well sends the case back to the precinct.",
            "A retired officer's past comes back as the squad looks into a cold shooting.",
            "Two cases collide when the same lawyer turns up on both sides of the courtroom.",
            "A juror's note mid-trial puts the whole verdict at risk.",
    };

    private Net.Fetcher recorded;

    @Before
    public void reset() {
        FakeFirePlayer.reset();
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16));
    }

    @After
    public void restore() {
        FakeFirePlayer.reset();
        if (recorded != null) Net.fetcher = recorded;
        recorded = null;
    }

    private static Object[] movie(String id, String title, int year, long tmdb, long position, long duration, long watched) {
        return new Object[] {id, "movie", title, year, 0, 0, null, tmdb, position, duration, watched};
    }

    private static Object[] episode(String id, String show, int season, int number, String name, long tmdb, long position,
            long duration, long watched) {
        return new Object[] {id, "episode", show, 0, season, number, name, tmdb, position, duration, watched};
    }

    /** The provider's id for an episode (made up). */
    private static String episodeId(int season, int number) {
        return String.valueOf(700000 + season * 100 + number);
    }

    /**
     * Law & Order's episodes as a newer FirePlayer lists them for its entry: 26 seasons and Specials, by season then
     * number (Specials, season 0, first, as FirePlayer orders them). Seasons 23 and 24 are filled in; the others have
     * only numbers, as a provider without TMDB's help has them.
     */
    private static List<Object[]> lawAndOrderEpisodes() {
        List<Object[]> out = new ArrayList<>();
        for (int n = 1; n <= 2; n++) out.add(new Object[] {episodeId(0, n), 0, n, "Special " + n, "", null, 0, "", 0L, 0});
        for (int s = 1; s <= 26; s++) {
            int count = s == 24 ? 22 : s == 23 ? 13 : 6;
            for (int n = 1; n <= count; n++) {
                boolean full = s == 23 || s == 24;
                String name = full ? NAMES[(n - 1) % NAMES.length] : "";
                String story = full && n != 8 ? STORIES[(n - 1) % STORIES.length] : "";
                String still = full && n != 7 ? STILL : null;   // E7 has none
                java.time.LocalDate first = s == 24 ? java.time.LocalDate.of(2024, 9, 5) : java.time.LocalDate.of(2000 + s, 1, 18);
                String aired = full ? first.plusWeeks(n - 1).toString() : "";
                long position = s == 24 && n == 4 ? 42 * MIN : s == 24 && n == 5 ? 18 * MIN : s == 23 && n == 3 ? 30 * MIN : 0;
                out.add(new Object[] {episodeId(s, n), s, n, name, story, still, full ? 42 : 0, aired, position,
                        s == 24 && n == 5 ? 1 : 0});
            }
        }
        return out;
    }

    /** FirePlayer with films and episodes on its list; {@code newer}: with the details API, Law & Order's episodes listable. */
    private static void withFirePlayer(boolean newer) {
        long t = Fixtures.RECORDED_AT;
        FakeFirePlayer.newer = newer;
        FakeFirePlayer.episodes.put("e1", lawAndOrderEpisodes());
        Collections.addAll(FakeFirePlayer.rows,
                episode("e1", "Law & Order", 24, 5, "Hard Cases", 549, 18 * MIN, 42 * MIN, t - 1000),
                movie("m1", "Sacrifice", 2026, 1284186, 28 * MIN, 100 * MIN, t - 2000),
                movie("m2", "Masterplan", 2026, 1561726, 75 * MIN, 110 * MIN, t - 3000),
                episode("e2", "The Simpsons", 37, 3, null, 456, 6 * MIN, 22 * MIN, t - 4000));
        Robolectric.setupContentProvider(FakeFirePlayer.class, ContinueWatching.AUTHORITY);
    }

    private static TestHome home(TestHome h) throws Exception {
        for (int i = 0; i < 3; i++) {
            TestHome.settle();
            TestHome.settleImages();
        }
        return h;
    }

    /** Home's Continue Watching row, focus on its card {@code index} along. */
    private static void toCard(TestHome h, int index) throws InterruptedException {
        int lastRow = (h.activity.appCount() - 1) / Ui.COLUMNS;
        for (int i = 0; i < 20 && h.activity.focusedPosition() / Ui.COLUMNS < lastRow; i++) h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_DOWN);
        assertEquals(Zone.HOME_ROW, h.zone());
        for (int i = 0; i < index; i++) h.press(KEYCODE_DPAD_RIGHT);
    }

    /** Holds Select on the focused card, then Details. */
    private static DetailsWindow details(TestHome h) throws InterruptedException {
        h.hold(KEYCODE_DPAD_CENTER, HomeActivity.TILE_HOLD_MS + 200, 50);
        assertEquals(Zone.TILE_MENU, h.zone());
        assertArrayEquals(new String[] {"Resume", "Details", "Remove"}, h.activity.tileMenu().labels());
        h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_CENTER);
        assertEquals(Zone.DETAILS, h.zone());
        assertFalse("the menu folded away", h.activity.tileMenu().isOpen());
        settle();
        return h.activity.infoWindow();
    }

    /** TMDB's and FirePlayer's answers, and the pictures, arrive. */
    private static void settle() throws InterruptedException {
        for (int i = 0; i < 2; i++) {
            TestHome.settle();
            TestHome.settleImages();
        }
    }

    private static List<String> facts(DetailsWindow w) {
        List<String> out = new ArrayList<>();
        for (String[] f : w.facts()) out.add(f[0] + " " + f[1]);
        return out;
    }

    @Test
    public void aFilmsDetails() throws Exception {
        withFirePlayer(true);
        TestHome h = home(TestHome.withTmdb());
        toCard(h, 2);
        assertEquals("m2", h.activity.homeRow().focusedCard().id);
        DetailsWindow w = details(h);
        assertTrue(w.isOpen());
        assertArrayEquals("a film: Resume alone", new String[] {"Resume"}, w.buttons());
        assertEquals(0, w.focusedButton());
        assertNull(w.status());
        assertEquals("Masterplan", w.item().title);
        assertTrue(w.meta().contains("Movie"));
        assertEquals("2026  ·  Movie  ·  1 hr 50 min", w.meta());
        assertEquals("Action, Thriller", w.genreLine());
        assertEquals(java.util.Arrays.asList("RATED R", "TMDB 7.1", "SOURCE tv.example.com:8080"), facts(w));
        assertNull("a film has no episode", w.episodeLine());
        assertTrue("its card keeps focus underneath, raised for the way back", h.activity.homeRow().isFocused());
        assertEquals("m2", h.activity.homeRow().focusedCard().id);
        assertEquals("Home stays up", 1f, h.activity.homeLift(), 0f);
        h.capture("details-1-film.png");

        // Left and Right go nowhere with one button; Up and Down stay in the window
        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_UP);
        assertEquals(Zone.DETAILS, h.zone());
        assertEquals(0, w.focusedButton());

        // Resume: the window goes, and FirePlayer plays on where it was left
        h.press(KEYCODE_DPAD_CENTER);
        Intent started = shadowOf(h.activity).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(ContinueWatching.ACTION_RESUME, started.getAction());
        assertEquals("m2", started.getStringExtra(ContinueWatching.EXTRA_ID));
        assertFalse(w.isOpen());
        assertFalse(h.activity.episodesWindow().isOpen());
        assertEquals(Zone.HOME_ROW, h.zone());
        assertEquals("m2", h.activity.homeRow().focusedCard().id);
    }

    @Test
    public void aShowsDetailsAndItsEpisodes() throws Exception {
        withFirePlayer(true);
        TestHome h = home(TestHome.withTmdb());
        toCard(h, 0);
        DetailsWindow w = details(h);
        assertArrayEquals(new String[] {"Resume", "Episodes"}, w.buttons());
        assertEquals("1990  ·  TV Show  ·  26 Seasons", w.meta());
        assertEquals("Truth matters.", w.tagline());
        assertEquals(java.util.Arrays.asList("RATED TV-14", "TMDB 7.3", "SOURCE tv.example.com:8080"), facts(w));
        assertEquals("S24 E5  ·  Hard Cases", w.episodeLine());
        assertEquals("FirePlayer's own episode, its date and length", "Oct 3, 2024  ·  42 min", w.episodeFacts());
        assertEquals("FirePlayer is asked for the episodes once, as the details open", 1, FakeFirePlayer.episodeQueries);
        h.capture("details-2-show.png");

        // Episodes: every season, the one it was left on shown, its episode focused
        h.press(KEYCODE_DPAD_RIGHT);
        assertEquals(1, w.focusedButton());
        h.press(KEYCODE_DPAD_CENTER);
        assertEquals(Zone.EPISODES, h.zone());
        settle();
        EpisodesWindow e = h.activity.episodesWindow();
        assertFalse("the details gave way", w.isOpen());
        assertEquals("the list the details asked for, not asked again", 1, FakeFirePlayer.episodeQueries);
        List<Integer> seasons = e.seasons();
        assertEquals(27, seasons.size());
        assertEquals(1, (int) seasons.get(0));
        assertEquals("Specials last", 0, (int) seasons.get(26));
        assertEquals(24, e.shownSeason());
        assertEquals(5, e.focusedEpisode().number);
        assertEquals("Hard Cases", e.focusedEpisode().name);
        assertFalse(e.inRail());
        assertEquals(22, e.shownEpisodes().size());
        h.capture("episodes-1-open.png");

        h.press(KEYCODE_DPAD_DOWN);
        assertEquals(6, e.focusedEpisode().number);
        h.press(KEYCODE_DPAD_RIGHT);
        assertEquals("Right goes nowhere", 6, e.focusedEpisode().number);
        h.press(KEYCODE_DPAD_LEFT);
        assertTrue(e.inRail());
        assertEquals(24, e.shownSeason());
        h.press(KEYCODE_DPAD_LEFT);
        assertTrue("Left goes nowhere", e.inRail());
        h.press(KEYCODE_DPAD_UP);
        assertEquals("the season shows as soon as it is moved to", 23, e.shownSeason());
        assertEquals(13, e.shownEpisodes().size());
        for (ContinueWatching.Episode ep : e.shownEpisodes()) assertEquals(23, ep.season);
        TestHome.settleImages();
        h.capture("episodes-2-season.png");

        // Back on the episodes, and one played: FirePlayer's play-episode for this entry and that episode
        h.press(KEYCODE_DPAD_RIGHT);
        assertFalse(e.inRail());
        assertEquals(1, e.focusedEpisode().number);
        h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_CENTER);
        Intent started = shadowOf(h.activity).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(ContinueWatching.ACTION_PLAY_EPISODE, started.getAction());
        assertEquals("app.fireplayer", started.getPackage());
        assertEquals("e1", started.getStringExtra(ContinueWatching.EXTRA_ID));
        assertEquals(episodeId(23, 2), started.getStringExtra(ContinueWatching.EXTRA_EPISODE_ID));
        assertTrue((started.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertTrue((started.getFlags() & Intent.FLAG_ACTIVITY_CLEAR_TASK) != 0);
        assertFalse(e.isOpen());
        assertFalse(w.isOpen());
        assertEquals(Zone.HOME_ROW, h.zone());
        assertEquals("e1", h.activity.homeRow().focusedCard().id);
        assertEquals(1f, h.activity.homeLift(), 0f);
    }

    @Test
    public void backGoesBackAWindowAtATimeAndItsOwnEpisodeResumes() throws Exception {
        withFirePlayer(true);
        TestHome h = home(TestHome.withTmdb());
        toCard(h, 0);
        DetailsWindow w = details(h);
        EpisodesWindow e = h.activity.episodesWindow();
        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_CENTER);
        settle();
        assertEquals(Zone.EPISODES, h.zone());
        // From the seasons too, Back is the details, Episodes focused
        h.press(KEYCODE_DPAD_LEFT);
        h.back();
        assertEquals(Zone.DETAILS, h.zone());
        assertEquals(1, w.focusedButton());
        assertFalse(e.isOpen());
        h.back();
        assertEquals(Zone.HOME_ROW, h.zone());
        assertFalse(w.isOpen());
        assertEquals("e1", h.activity.homeRow().focusedCard().id);
        assertEquals("Home still up on its row", 1f, h.activity.homeLift(), 0f);
        assertNull(shadowOf(h.activity).getNextStartedActivity());

        // The episode it was left on, picked from the list: it resumes where it was left
        details(h);
        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_CENTER);
        settle();
        assertEquals(5, e.focusedEpisode().number);
        h.press(KEYCODE_DPAD_CENTER);
        Intent started = shadowOf(h.activity).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(ContinueWatching.ACTION_RESUME, started.getAction());
        assertEquals("e1", started.getStringExtra(ContinueWatching.EXTRA_ID));
        assertEquals(Zone.HOME_ROW, h.zone());
    }

    @Test
    public void manySeasonsScrollTheRail() throws Exception {
        withFirePlayer(true);
        TestHome h = home(TestHome.withTmdb());
        toCard(h, 0);
        details(h);
        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_CENTER);
        settle();
        EpisodesWindow e = h.activity.episodesWindow();
        assertTrue("season 24 of 27 rows: scrolled to it", e.railScroll() > 0f);
        h.press(KEYCODE_DPAD_LEFT);
        // Held, the seasons go by at the remote's pace, to the first
        h.hold(KEYCODE_DPAD_UP, 4000, 50);
        assertEquals(1, e.shownSeason());
        assertEquals(0f, e.railScroll(), 0f);
        h.hold(KEYCODE_DPAD_DOWN, 4500, 50);
        assertEquals("to Specials, last", 0, e.shownSeason());
        assertTrue(e.railScroll() > 0f);
        assertEquals("Special 1", e.focusedEpisode().name);
        TestHome.settleImages();
        h.capture("episodes-3-rail-scrolled.png");
    }

    @Test
    public void anOlderFirePlayerHasNoSourceNorEpisodes() throws Exception {
        withFirePlayer(false);
        TestHome h = home(TestHome.withTmdb());
        toCard(h, 0);
        DetailsWindow w = details(h);
        assertArrayEquals("no Episodes: it couldn't play one", new String[] {"Resume"}, w.buttons());
        assertEquals("no Source", java.util.Arrays.asList("RATED TV-14", "TMDB 7.3"), facts(w));
        assertEquals("the episode, as the entry has it", "S24 E5  ·  Hard Cases", w.episodeLine());
        assertEquals("never asked for episodes", 0, FakeFirePlayer.episodeQueries);
        h.capture("details-3-older-fireplayer.png");
        h.press(KEYCODE_DPAD_RIGHT);
        assertEquals(0, w.focusedButton());
        h.press(KEYCODE_DPAD_CENTER);
        Intent started = shadowOf(h.activity).getNextStartedActivity();
        assertEquals(ContinueWatching.ACTION_RESUME, started.getAction());
        assertEquals("e1", started.getStringExtra(ContinueWatching.EXTRA_ID));
    }

    /** With an older FirePlayer, "This episode" comes from Glass Home's own TMDB: its date, length and story. */
    @Test
    public void anOlderFirePlayersEpisodeComesFromTmdb() throws Exception {
        withFirePlayer(false);
        TestHome h = home(TestHome.withTmdb());
        recorded = Net.fetcher;
        Net.Fetcher fixtures = recorded;
        // Made up here, unless recorded (-PnetRecord=true with -PtmdbTokenFile=…)
        String season = Tmdb.API + "tv/549/season/24?language=en-US";
        boolean madeUp = !new File(Fixtures.DIR, Net.sha1(season)).isFile() && !Fixtures.recording();
        if (madeUp) {
            Net.fetcher = (url, bearer) -> {
                if (!url.equals(season)) return fixtures.get(url, bearer);
                return ("{\"season_number\":24,\"episodes\":[{\"season_number\":24,\"episode_number\":4,\"name\":\"Fallen Hero\"},"
                        + "{\"season_number\":24,\"episode_number\":5,\"name\":\"Hard Cases\",\"air_date\":\"2024-10-03\","
                        + "\"runtime\":42,\"overview\":\"Two cases collide when the same lawyer turns up on both sides.\","
                        + "\"still_path\":null}]}").getBytes(StandardCharsets.UTF_8);
            };
        }
        toCard(h, 0);
        DetailsWindow w = details(h);
        assertEquals("S24 E5  ·  Hard Cases", w.episodeLine());
        if (madeUp) assertEquals("Oct 3, 2024  ·  42 min", w.episodeFacts());
        else assertFalse("its date and length, as TMDB has them", w.episodeFacts().isEmpty());
        assertFalse(w.hasListedEpisode());
        assertEquals(0, FakeFirePlayer.episodeQueries);
    }

    @Test
    public void tmdbOutOfReachLeavesWhatFirePlayerSays() throws Exception {
        // TMDB's title look-ups fail (the pages' own catalog still loads), nothing of them cached
        withFirePlayer(true);
        TestHome h = TestHome.withTmdb();
        TestHome.settle();
        recorded = Net.fetcher;
        Net.Fetcher fixtures = recorded;
        Net.fetcher = (url, bearer) -> {
            if (url.startsWith(Tmdb.API) && url.substring(Tmdb.API.length()).matches("(movie|tv)/\\d+.*")) {
                throw new java.net.UnknownHostException("api.themoviedb.org");
            }
            return fixtures.get(url, bearer);
        };
        File[] cached = new File(RuntimeEnvironment.getApplication().getCacheDir(), "net").listFiles();
        if (cached != null) for (File f : cached) f.delete();
        home(h);
        toCard(h, 0);
        DetailsWindow w = details(h);
        assertEquals("Couldn't reach TMDB just now.", w.status());
        assertEquals("the entry's own", "TV Show", w.meta());
        assertEquals("what FirePlayer said still shows", java.util.Arrays.asList("SOURCE tv.example.com:8080"), facts(w));
        assertEquals("S24 E5  ·  Hard Cases", w.episodeLine());
        assertArrayEquals(new String[] {"Resume", "Episodes"}, w.buttons());
        h.capture("details-4-offline.png");
        // And the episodes are FirePlayer's: they open all the same
        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_CENTER);
        settle();
        assertEquals(24, h.activity.episodesWindow().shownSeason());
    }

    @Test
    public void withoutATmdbTokenItSaysSo() throws Exception {
        withFirePlayer(true);
        TestHome h = home(new TestHome());
        toCard(h, 1);
        DetailsWindow w = details(h);
        assertEquals("More details need a TMDB token, which isn't set.", w.status());
        assertEquals("2026  ·  Movie", w.meta());
        assertEquals(java.util.Arrays.asList("SOURCE tv.example.com:8080"), facts(w));
        h.back();
        assertEquals(Zone.HOME_ROW, h.zone());
        assertEquals("m1", h.activity.homeRow().focusedCard().id);
    }

    @Test
    public void episodesFirePlayerCannotListCanBeAskedForAgain() throws Exception {
        withFirePlayer(true);
        FakeFirePlayer.failingEpisodes = true;
        TestHome h = home(TestHome.withTmdb());
        toCard(h, 0);
        DetailsWindow w = details(h);
        assertArrayEquals("the entry says they can be listed", new String[] {"Resume", "Episodes"}, w.buttons());
        assertEquals("S24 E5  ·  Hard Cases", w.episodeLine());
        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_CENTER);
        settle();
        EpisodesWindow e = h.activity.episodesWindow();
        assertEquals(Zone.EPISODES, h.zone());
        assertTrue(e.failed());
        assertEquals("Couldn't get this show's episodes just now.", e.status());
        assertEquals("asked again as the window opened", 2, FakeFirePlayer.episodeQueries);
        h.press(KEYCODE_DPAD_DOWN);
        assertTrue("nothing to move along", e.failed());
        h.capture("episodes-4-failed.png");

        // Select asks again; FirePlayer is slow to answer this time
        FakeFirePlayer.failingEpisodes = false;
        CountDownLatch gate = new CountDownLatch(1);
        FakeFirePlayer.episodesGate = gate;
        h.press(KEYCODE_DPAD_CENTER);
        assertTrue(e.loading());
        assertEquals("Loading episodes…", e.status());
        h.capture("episodes-0-loading.png");
        gate.countDown();
        FakeFirePlayer.episodesGate = null;
        settle();
        assertNull(e.status());
        assertEquals(24, e.shownSeason());
        assertEquals(5, e.focusedEpisode().number);
        // The details have FirePlayer's own episode now, for the way back
        h.back();
        assertEquals(Zone.DETAILS, h.zone());
        assertEquals("Oct 3, 2024  ·  42 min", w.episodeFacts());
        assertTrue(w.hasListedEpisode());
    }

    @Test
    public void fromAPageBackIsOnTheSameCard() throws Exception {
        withFirePlayer(true);
        TestHome h = TestHome.withTmdb();
        h.menu();
        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_RIGHT);
        assertEquals(HomeActivity.TV, h.activity.page());
        h.waitForPage(HomeActivity.TV);
        settle();
        h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_RIGHT);
        ShelfPage tv = h.page(HomeActivity.TV);
        assertEquals("e2", tv.focusedCard().id);
        DetailsWindow w = details(h);
        assertEquals("The Simpsons", w.item().title);
        assertEquals("no name for it: its number alone", "S37 E3", w.episodeLine());
        h.back();
        assertEquals(Zone.PAGE, h.zone());
        assertEquals("e2", tv.focusedCard().id);
        assertFalse(w.isOpen());
    }

    @Test
    public void menuGoesToTheTabsAndTheHomeButtonClosesBoth() throws Exception {
        withFirePlayer(true);
        TestHome h = home(TestHome.withTmdb());
        toCard(h, 0);
        DetailsWindow w = details(h);
        h.menu();
        assertEquals(Zone.TABS, h.zone());
        assertFalse(w.isOpen());
        h.press(KEYCODE_DPAD_DOWN);
        assertEquals("back down to the row", Zone.HOME_ROW, h.zone());

        details(h);
        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_CENTER);
        settle();
        assertEquals(Zone.EPISODES, h.zone());
        h.controller.newIntent(new Intent(h.activity, HomeActivity.class).putExtra(HomeButtonService.EXTRA_HOME_BUTTON, true));
        TestHome.settle();
        assertFalse(w.isOpen());
        assertFalse(h.activity.episodesWindow().isOpen());
        assertEquals(Zone.GRID, h.zone());
    }
}
