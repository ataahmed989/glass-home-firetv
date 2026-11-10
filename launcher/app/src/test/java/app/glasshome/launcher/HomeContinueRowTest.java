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
}
