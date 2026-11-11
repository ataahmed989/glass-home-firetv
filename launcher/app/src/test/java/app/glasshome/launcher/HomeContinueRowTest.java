package app.glasshome.launcher;

import static android.view.KeyEvent.KEYCODE_DPAD_CENTER;
import static android.view.KeyEvent.KEYCODE_DPAD_DOWN;
import static android.view.KeyEvent.KEYCODE_DPAD_LEFT;
import static android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
import static android.view.KeyEvent.KEYCODE_DPAD_UP;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.view.View;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import app.glasshome.launcher.ContinueWatchingTest.FakeFirePlayer;
import app.glasshome.launcher.HomeActivity.Zone;

/** Home's own Continue Watching row, beneath the apps: films and episodes together, as FirePlayer orders them. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, qualifiers = "w960dp-h540dp-land-xhdpi")
public class HomeContinueRowTest {
    private static final long MIN = 60_000L;

    @Before
    public void reset() {
        FakeFirePlayer.rows.clear();
        FakeFirePlayer.deleted.clear();
        FakeFirePlayer.queries = 0;
        FakeFirePlayer.failing = false;
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16));
    }

    private static Object[] movie(String id, String title, int year, long tmdb, long position, long duration, long watched) {
        return new Object[] {id, "movie", title, year, 0, 0, null, tmdb, position, duration, watched};
    }

    private static Object[] episode(String id, String show, int season, int number, String name, long tmdb, long position,
            long duration, long watched) {
        return new Object[] {id, "episode", show, 0, season, number, name, tmdb, position, duration, watched};
    }

    private static void withFirePlayer() {
        long t = Fixtures.RECORDED_AT;
        Collections.addAll(FakeFirePlayer.rows,
                episode("e1", "Law & Order", 24, 5, "Hard Cases", 549, 18 * MIN, 42 * MIN, t - 1000),
                movie("m1", "Sacrifice", 2026, 1284186, 28 * MIN, 100 * MIN, t - 2000),
                movie("m2", "Masterplan", 2026, 1561726, 75 * MIN, 110 * MIN, t - 3000),
                episode("e2", "The Simpsons", 37, 3, null, 456, 6 * MIN, 22 * MIN, t - 4000),
                movie("m3", "Animals", 2026, 1236045, 10 * MIN, 106 * MIN, t - 5000),
                episode("e3", "NCIS", 23, 12, "Ghosts", 4614, 30 * MIN, 44 * MIN, t - 7000),
                movie("m5", "The Breadwinner", 2026, 1440050, 50 * MIN, 100 * MIN, t - 8000));
        Robolectric.setupContentProvider(FakeFirePlayer.class, ContinueWatching.AUTHORITY);
    }

    private static TestHome home() throws Exception {
        TestHome h = TestHome.withTmdb();
        for (int i = 0; i < 3; i++) {
            TestHome.settle();
            TestHome.settleImages();
        }
        return h;
    }

    /** Down along the apps until the last row is in the dock. */
    private static void toLastRow(TestHome h) throws InterruptedException {
        int lastRow = (h.activity.appCount() - 1) / Ui.COLUMNS;
        for (int i = 0; i < 20 && h.activity.focusedPosition() / Ui.COLUMNS < lastRow; i++) h.press(KEYCODE_DPAD_DOWN);
        assertEquals(lastRow, h.activity.focusedPosition() / Ui.COLUMNS);
    }

    private static List<String> ids(ShelfPage row) {
        List<String> out = new ArrayList<>();
        for (PageModel.Card c : row.model().shelves.get(0).cards) out.add(c.id);
        return out;
    }

    @Test
    public void theRowPeeksBeneathTheLastAppsAndDownGoesIntoIt() throws Exception {
        withFirePlayer();
        TestHome h = home();
        ShelfPage row = h.activity.homeRow();
        assertEquals(View.VISIBLE, row.getVisibility());
        assertEquals("films and episodes together, most recently watched first",
                java.util.Arrays.asList("e1", "m1", "m2", "e2", "m3", "e3", "m5"), ids(row));

        // Further up the apps, nothing of it is on screen
        float screenH = h.activity.getWindow().getDecorView().getHeight();
        assertTrue(row.getTranslationY() + ShelfPage.embeddedTitleTop() >= screenH);

        toLastRow(h);
        assertEquals(Zone.GRID, h.zone());
        float titleTop = row.getTranslationY() + ShelfPage.embeddedTitleTop();
        assertTrue("its title peeks beneath the dock", titleTop > Ui.px(Ui.DOCK_BOTTOM) - Ui.px(8) && titleTop < screenH - Ui.px(40));
        h.capture("home-continue-1-peeking.png");

        h.press(KEYCODE_DPAD_DOWN);
        assertEquals(Zone.HOME_ROW, h.zone());
        assertEquals(1f, h.activity.homeLift(), 0f);
        assertEquals("the first card has focus", "e1", row.focusedCard().id);
        float lifted = row.getTranslationY() + ShelfPage.embeddedTitleTop();
        assertTrue("Home moved up, the whole row on screen", lifted + ShelfPage.embeddedHeight() <= screenH + Ui.px(60));
        assertEquals(-1, h.activity.focusedPosition());
        TestHome.settleImages();
        h.capture("home-continue-2-row.png");

        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_RIGHT);
        assertEquals("m2", row.focusedCard().id);
        TestHome.settleImages();
        h.capture("home-continue-3-along.png");
        h.press(KEYCODE_DPAD_LEFT);
        assertEquals("m1", row.focusedCard().id);

        // Up: back to the last row of apps, Home settles back down
        int lastApp = h.activity.focusedPosition();
        h.press(KEYCODE_DPAD_UP);
        assertEquals(Zone.GRID, h.zone());
        assertEquals(0f, h.activity.homeLift(), 0f);
        assertEquals((h.activity.appCount() - 1) / Ui.COLUMNS, h.activity.focusedPosition() / Ui.COLUMNS);

        // Back from the row: the top of Home
        h.press(KEYCODE_DPAD_DOWN);
        assertEquals(Zone.HOME_ROW, h.zone());
        h.back();
        assertEquals(Zone.GRID, h.zone());
        assertEquals(0f, h.activity.homeLift(), 0f);
        assertTrue(h.activity.focusedPosition() < Ui.COLUMNS);
    }

    /** Home after a title opened on the row: back on that row with Home up, however Fire OS made the trip back. */
    @Test
    public void backFromATitleOpenedOnTheRowIsOnTheRowWithHomeUp() throws Exception {
        withFirePlayer();
        TestHome h = home();
        toLastRow(h);
        h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_CENTER);   // Sacrifice, in FirePlayer
        assertNotNull(shadowOf(h.activity).getNextStartedActivity());
        h.controller.pause().stop();
        TestHome.advance(10 * MIN);
        // Played to the end: Fire OS passed through Amazon's home, so Home comes back as the Home button brings it
        h.controller.restart().start();
        h.controller.newIntent(new Intent(h.activity, HomeActivity.class).putExtra(HomeButtonService.EXTRA_HOME_BUTTON, true));
        h.controller.resume();
        for (int i = 0; i < 2; i++) TestHome.settle();
        assertEquals(Zone.HOME_ROW, h.zone());
        assertEquals("Home up, the whole row on screen", 1f, h.activity.homeLift(), 0f);
        assertEquals("m1", h.activity.homeRow().focusedCard().id);

        // The Home button pressed on Home itself still goes to the top of Home, focus on the apps
        h.controller.pause();
        h.controller.newIntent(new Intent(h.activity, HomeActivity.class).putExtra(HomeButtonService.EXTRA_HOME_BUTTON, true));
        h.controller.resume();
        for (int i = 0; i < 2; i++) TestHome.settle();
        assertEquals(Zone.GRID, h.zone());
        assertEquals(0f, h.activity.homeLift(), 0f);

        // However focus comes to the row, Home moves up to show it
        h.activity.homeRow().requestFocus();
        TestHome.settle();
        assertEquals(Zone.HOME_ROW, h.zone());
        assertEquals(1f, h.activity.homeLift(), 0f);
    }

    @Test
    public void selectResumesInFirePlayerAndHoldingOffersResumeAndRemove() throws Exception {
        withFirePlayer();
        TestHome h = home();
        toLastRow(h);
        h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_CENTER);
        Intent started = shadowOf(h.activity).getNextStartedActivity();
        assertNotNull(started);
        assertEquals(ContinueWatching.ACTION_RESUME, started.getAction());
        assertEquals("m1", started.getStringExtra(ContinueWatching.EXTRA_ID));

        h.hold(KEYCODE_DPAD_CENTER, HomeActivity.TILE_HOLD_MS + 200, 50);
        assertEquals(Zone.TILE_MENU, h.zone());
        assertArrayEquals(new String[] {"Resume", "Remove"}, h.activity.tileMenu().labels());
        h.press(KEYCODE_DPAD_DOWN);
        TestHome.settleImages();
        h.capture("home-continue-4-menu.png");
        h.press(KEYCODE_DPAD_CENTER);   // Remove
        TestHome.settle();
        assertTrue(FakeFirePlayer.deleted.contains("m1"));
        assertEquals(Zone.HOME_ROW, h.zone());
        assertEquals("m2", h.activity.homeRow().focusedCard().id);
    }

    @Test
    public void menuGoesToTheTabsAndDownComesBackToTheRow() throws Exception {
        withFirePlayer();
        TestHome h = home();
        toLastRow(h);
        h.press(KEYCODE_DPAD_DOWN);
        h.menu();
        assertEquals(Zone.TABS, h.zone());
        h.press(KEYCODE_DPAD_DOWN);
        assertEquals(Zone.HOME_ROW, h.zone());
        assertEquals(1f, h.activity.homeLift(), 0f);
    }

    @Test
    public void focusStaysOnItsCardWhenFirePlayerReordersItsList() throws Exception {
        withFirePlayer();
        TestHome h = home();
        toLastRow(h);
        h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_RIGHT);
        assertEquals("m2", h.activity.homeRow().focusedCard().id);
        // Masterplan was just watched: FirePlayer puts it first
        synchronized (FakeFirePlayer.rows) {
            Object[] m2 = FakeFirePlayer.rows.remove(2);
            m2[10] = Fixtures.RECORDED_AT;
            FakeFirePlayer.rows.add(0, m2);
        }
        h.activity.getContentResolver().notifyChange(ContinueWatching.ITEMS, null);
        TestHome.advance(400);
        for (int i = 0; i < 3; i++) TestHome.settle();
        assertEquals("m2", ids(h.activity.homeRow()).get(0));
        assertEquals("focus followed its card", "m2", h.activity.homeRow().focusedCard().id);
    }

    @Test
    public void downFromTheAppsAlwaysLandsOnTheFirstCard() throws Exception {
        withFirePlayer();
        TestHome h = home();
        toLastRow(h);
        h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_UP);
        h.press(KEYCODE_DPAD_DOWN);
        assertEquals("e1", h.activity.homeRow().focusedCard().id);
    }

    @Test
    public void backFromSourcesPutsHomeBackDown() throws Exception {
        withFirePlayer();
        TestHome h = home();
        toLastRow(h);
        h.press(KEYCODE_DPAD_DOWN);
        h.menu();
        h.press(KEYCODE_DPAD_LEFT);
        assertEquals(Zone.SOURCE, h.zone());
        h.back();
        assertEquals(Zone.GRID, h.zone());
        assertEquals(0f, h.activity.homeLift(), 0f);
    }

    @Test
    public void anotherTabPutsHomeBackDownAndDownFromTheTabsLiftsItAgain() throws Exception {
        withFirePlayer();
        TestHome h = home();
        toLastRow(h);
        h.press(KEYCODE_DPAD_DOWN);
        h.menu();
        h.press(KEYCODE_DPAD_RIGHT);
        TestHome.advance(1000);
        TestHome.settle();
        assertEquals(1, h.activity.page());
        assertEquals("settled down beneath the page", 0f, h.activity.homeLift(), 0f);
        assertEquals(View.GONE, h.activity.homeRow().getVisibility());
        h.press(KEYCODE_DPAD_LEFT);
        TestHome.advance(1000);
        TestHome.settle();
        assertEquals(0, h.activity.page());
        assertEquals(0f, h.activity.homeLift(), 0f);
        assertEquals(View.VISIBLE, h.activity.homeRow().getVisibility());
        h.press(KEYCODE_DPAD_DOWN);
        assertEquals("back to the row it was on", Zone.HOME_ROW, h.zone());
        assertEquals(1f, h.activity.homeLift(), 0f);
    }

    @Test
    public void withNothingToContinueThereIsNoRowAndNothingChanges() throws Exception {
        TestHome h = home();
        assertEquals(View.GONE, h.activity.homeRow().getVisibility());
        toLastRow(h);
        int at = h.activity.focusedPosition();
        h.press(KEYCODE_DPAD_DOWN);
        assertEquals(Zone.GRID, h.zone());
        assertEquals(at, h.activity.focusedPosition());
        assertEquals(0f, h.activity.homeLift(), 0f);
    }

    @Test
    public void switchedOffInSettingsThereIsNoRow() throws Exception {
        new HomeSettings(RuntimeEnvironment.getApplication()).setHomeContinue(false);
        withFirePlayer();
        TestHome h = home();
        assertEquals(View.GONE, h.activity.homeRow().getVisibility());
        toLastRow(h);
        h.press(KEYCODE_DPAD_DOWN);
        assertEquals(Zone.GRID, h.zone());
    }

    @Test
    public void aFirePlayerThatCannotAnswerKeepsTheRowAndIsAskedAgain() throws Exception {
        withFirePlayer();
        TestHome h = home();
        List<String> before = ids(h.activity.homeRow());
        assertEquals(7, before.size());
        // Its list changes as FirePlayer is being updated: no answer, and the row keeps what it had
        FakeFirePlayer.failing = true;
        h.activity.getContentResolver().notifyChange(ContinueWatching.ITEMS, null);
        TestHome.advance(400);
        TestHome.settle();
        assertEquals(View.VISIBLE, h.activity.homeRow().getVisibility());
        assertEquals(before, ids(h.activity.homeRow()));
        // Back a moment later: asked again on its own, and what changed meanwhile comes in
        synchronized (FakeFirePlayer.rows) {
            FakeFirePlayer.rows.remove(0);
        }
        FakeFirePlayer.failing = false;
        for (int i = 0; i < 4; i++) TestHome.settle();
        assertEquals(before.subList(1, before.size()), ids(h.activity.homeRow()));
    }

    @Test
    public void firePlayerUpdatedWhileHomeIsUpIsAskedAgain() throws Exception {
        withFirePlayer();
        // FirePlayer mid-update when Home asks, and still for as long as Home asks again on its own
        FakeFirePlayer.failing = true;
        TestHome h = home();
        for (int i = 0; i < 10; i++) TestHome.settle();
        assertEquals(View.GONE, h.activity.homeRow().getVisibility());
        int asked = FakeFirePlayer.queries;
        FakeFirePlayer.failing = false;
        TestHome.advance(20_000);
        assertEquals("not asked on its own forever", asked, FakeFirePlayer.queries);
        // The update finishes: Fire OS says so, and the list is asked for
        h.activity.sendBroadcast(new Intent(Intent.ACTION_PACKAGE_REPLACED, android.net.Uri.parse("package:" + FirePlayerTitles.PACKAGE)));
        for (int i = 0; i < 3; i++) TestHome.settle();
        assertEquals(View.VISIBLE, h.activity.homeRow().getVisibility());
        assertEquals(7, ids(h.activity.homeRow()).size());
    }

    @Test
    public void artworkMissedOfflineComesInWhenHomeIsBack() throws Exception {
        withFirePlayer();
        Object[] later;
        synchronized (FakeFirePlayer.rows) {
            later = FakeFirePlayer.rows.remove(1);
        }
        TestHome h = home();
        assertEquals(0, artless(h));
        // A film watched with TMDB out of reach: its card shows its title
        Net.Fetcher online = Net.fetcher;
        Net.fetcher = (url, bearer) -> {
            if (url.contains("themoviedb.org")) throw new java.net.UnknownHostException("api.themoviedb.org");
            return online.get(url, bearer);
        };
        FakeFirePlayer.rows.add(0, later);
        h.activity.getContentResolver().notifyChange(ContinueWatching.ITEMS, null);
        TestHome.advance(400);
        for (int i = 0; i < 2; i++) TestHome.settle();
        assertEquals(1, artless(h));
        // Another app opened before the network is back: its retry is dropped...
        h.controller.pause().stop();
        Net.fetcher = online;
        TestHome.advance(15 * MIN);
        // ...and asked again once Home is back
        h.controller.start().resume();
        for (int i = 0; i < 4; i++) {
            TestHome.settle();
            TestHome.settleImages();
        }
        assertEquals(0, artless(h));
    }

    private static int artless(TestHome h) {
        int n = 0;
        for (PageModel.Card c : h.activity.homeRow().model().shelves.get(0).cards) if (c.imageUrl == null) n++;
        return n;
    }

    @Test
    public void backToHomeFromAnotherTabSeesFirePlayersNewList() throws Exception {
        withFirePlayer();
        TestHome h = home();
        toLastRow(h);
        h.menu();
        for (int i = 0; i < 3; i++) {
            h.press(KEYCODE_DPAD_RIGHT);
            TestHome.advance(1000);
        }
        TestHome.settle();
        assertEquals(HomeActivity.SPORTS, h.activity.page());
        // Out to an app from Sports; FirePlayer plays something new meanwhile
        h.controller.pause().stop();
        synchronized (FakeFirePlayer.rows) {
            FakeFirePlayer.rows.remove(0);
        }
        h.activity.getContentResolver().notifyChange(ContinueWatching.ITEMS, null);
        TestHome.advance(400);
        h.controller.start().resume();
        TestHome.settle();
        // Back to Home's tab (Back to the tabs, Back again to Home): its row has the new list
        h.back();
        TestHome.advance(500);
        h.back();
        for (int i = 0; i < 3; i++) TestHome.settle();
        assertEquals(HomeActivity.HOME, h.activity.page());
        assertEquals(6, ids(h.activity.homeRow()).size());
    }
}
