package app.glasshome.launcher;

import static android.view.KeyEvent.KEYCODE_BACK;
import static android.view.KeyEvent.KEYCODE_DPAD_CENTER;
import static android.view.KeyEvent.KEYCODE_DPAD_DOWN;
import static android.view.KeyEvent.KEYCODE_DPAD_LEFT;
import static android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
import static android.view.KeyEvent.KEYCODE_DPAD_UP;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.graphics.RectF;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;

import androidx.recyclerview.widget.RecyclerView;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

/**
 * Holding Select on a tile: a glass card unfolds under it (Move, More Options); Move carries the
 * tile with the arrows while the others make room; More Options is the full menu.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, qualifiers = "w960dp-h540dp-land-xhdpi")
public class TileMenuTest {
    /** Frames as often as the TV draws them (Robolectric's default is one a millisecond, each drawn in full). */
    @org.junit.Before
    public void tvFrameRate() {
        org.robolectric.shadows.ShadowChoreographer.setFrameDelay(java.time.Duration.ofMillis(16));
    }

    private static UpdatePrompt prompt(TestHome h) {
        return TestHome.find(h.activity.getWindow().getDecorView(), UpdatePrompt.class);
    }

    private static AppAdapter apps(TestHome h) {
        return (AppAdapter) h.grid.getAdapter();
    }

    private static void holdSelect(TestHome h) throws InterruptedException {
        h.hold(KEYCODE_DPAD_CENTER, HomeActivity.TILE_HOLD_MS + 200, 50);
    }

    /** The focused tile's cell. */
    private static View cell(TestHome h) {
        return h.grid.getFocusedChild();
    }

    private static TileView tile(TestHome h) {
        return ((AppAdapter.Holder) h.grid.getChildViewHolder(cell(h))).tile;
    }

    /** Holds Select, then chooses a row of the card. */
    private static void menu(TestHome h, String row) throws InterruptedException {
        holdSelect(h);
        assertEquals(HomeActivity.Zone.TILE_MENU, h.zone());
        int i = Arrays.asList(TileMenu.LABELS).indexOf(row);
        while (h.activity.tileMenu().focusedIndex() < i) h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_CENTER);
    }

    /** Through More Options, a button of the full menu. */
    private static void fullMenu(TestHome h, String button) throws InterruptedException {
        menu(h, "More Options");
        UpdatePrompt p = prompt(h);
        assertTrue(p.isOpen());
        int i = Arrays.asList(p.buttons()).indexOf(button);
        assertTrue(button + " is offered", i >= 0);
        while (p.focusedIndex() < i) h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_CENTER);
    }

    /** A press, and then only {@code ms} of time: for frames in the middle of the motion. */
    private static void pressAndWait(TestHome h, int code, long ms) {
        long t = SystemClock.uptimeMillis();
        h.activity.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        h.activity.dispatchKeyEvent(new KeyEvent(t, t + 40, KeyEvent.ACTION_UP, code, 0));
        TestHome.advance(ms);
    }

    private static void assertAtRest(TestHome h) {
        for (int i = 0; i < h.grid.getChildCount(); i++) {
            View c = h.grid.getChildAt(i);
            assertEquals(0f, c.getTranslationX(), 0.01f);
            assertEquals(0f, c.getTranslationY(), 0.01f);
        }
    }

    @Test
    public void holdingSelectUnfoldsTheCardUnderTheTile() throws Exception {
        TestHome h = new TestHome();
        h.loadSampleWeather();
        String key = h.focusedKey();
        holdSelect(h);
        assertEquals(HomeActivity.Zone.TILE_MENU, h.zone());
        assertEquals(0, h.activity.tileMenu().focusedIndex());
        assertEquals(key, h.focusedKey());
        assertNull("the release opened nothing", shadowOf(h.activity).getNextStartedActivity());
        assertEquals("the tile rose to make room", -Ui.px(Ui.MENU_RISE), tile(h).getTranslationY(), 0.5f);
        RectF card = new RectF();
        h.activity.tileMenu().cardBounds(card);
        float tileBottom = cell(h).getTop() + h.grid.getY() + tile(h).getY() + tile(h).getHeight() / 2f
                + tile(h).getHeight() * tile(h).getScaleY() / 2f;
        assertTrue("under the tile", card.top >= tileBottom);
        assertTrue("on screen", card.bottom <= h.activity.getWindow().getDecorView().getHeight() - Ui.px(24));
        h.capture("tile-menu.png");
        h.press(KEYCODE_DPAD_DOWN);
        assertEquals(1, h.activity.tileMenu().focusedIndex());
        h.capture("tile-menu-more.png");
    }

    @Test
    public void backFoldsTheCardAway() throws Exception {
        TestHome h = new TestHome();
        String key = h.focusedKey();
        holdSelect(h);
        h.back();
        assertEquals(HomeActivity.Zone.GRID, h.zone());
        assertEquals(key, h.focusedKey());
        assertEquals(0f, tile(h).getTranslationY(), 0.01f);
        for (int i = 0; i < h.grid.getChildCount(); i++) {
            assertEquals("the others are back", 1f, ((AppAdapter.Holder) h.grid.getChildViewHolder(h.grid.getChildAt(i))).tile.getAlpha(), 0.01f);
        }
        assertEquals("its name is back", 1f, ((AppAdapter.Holder) h.grid.getChildViewHolder(cell(h))).name.getAlpha(), 0.01f);
        assertNull(shadowOf(h.activity).getNextStartedActivity());
    }

    @Test
    public void moreOptionsIsTheFullMenu() throws Exception {
        TestHome h = new TestHome();
        h.loadSampleWeather();
        String key = h.focusedKey();
        menu(h, "More Options");
        assertEquals(HomeActivity.Zone.UPDATE, h.zone());
        assertEquals(apps(h).get(0).label, prompt(h).heading());
        assertArrayEquals(new String[] {"Move", "Hide from Home", "App Info", "Cancel"}, prompt(h).buttons());
        h.capture("tile-more-options.png");
        h.back();
        assertEquals(HomeActivity.Zone.GRID, h.zone());
        assertEquals(key, h.focusedKey());
    }

    @Test
    public void aShortPressStillOpensTheApp() throws Exception {
        TestHome h = new TestHome();
        h.press(KEYCODE_DPAD_CENTER);
        assertNotEquals(HomeActivity.Zone.TILE_MENU, h.zone());
        assertNotNull(shadowOf(h.activity).getNextStartedActivity());
        assertEquals(Ui.FOCUS_SCALE, tile(h).getScaleX(), 0.001f);
    }

    @Test
    public void moveCarriesTheTileAndTheOthersMakeRoom() throws Exception {
        TestHome h = new TestHome();
        h.loadSampleWeather();
        String key = h.focusedKey(), second = apps(h).get(1).key;
        menu(h, "Move");
        assertTrue(h.activity.isMovingTile());
        assertEquals(HomeActivity.Zone.GRID, h.zone());
        assertEquals(Ui.LIFT_SCALE, tile(h).getScaleX(), 0.001f);
        assertTrue(h.activity.moveIndicator().isShowing());
        assertArrayEquals("nowhere left or up from the first tile", new boolean[] {false, false, true, true},
                h.activity.moveIndicator().directions());
        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_RIGHT);
        assertEquals("the tile went along", 2, apps(h).indexOf(key));
        assertEquals("the others made room", 0, apps(h).indexOf(second));
        assertEquals(key, h.focusedKey());
        assertEquals("still in the dock", Ui.px(Ui.DOCK_ROW_TOP), cell(h).getTop());
        assertAtRest(h);
        assertArrayEquals(new boolean[] {true, false, true, true}, h.activity.moveIndicator().directions());
        h.capture("tile-moving.png");

        // In the middle of a step: everything on its way
        pressAndWait(h, KEYCODE_DPAD_RIGHT, 120);
        h.capture("tile-reorder-mid.png");
        TestHome.settle();
        assertEquals(3, apps(h).indexOf(key));
        pressAndWait(h, KEYCODE_DPAD_DOWN, 120);
        h.capture("tile-reorder-row-mid.png");
        TestHome.settle();
        assertEquals("down a row", 3 + Ui.COLUMNS, apps(h).indexOf(key));
        assertEquals("the rows came up under it", Ui.px(Ui.DOCK_ROW_TOP), cell(h).getTop());
        assertAtRest(h);
        h.press(KEYCODE_DPAD_UP);
        assertEquals(3, apps(h).indexOf(key));
        assertEquals(Ui.px(Ui.DOCK_ROW_TOP), cell(h).getTop());

        h.press(KEYCODE_DPAD_CENTER);
        assertFalse(h.activity.isMovingTile());
        assertFalse(h.activity.moveIndicator().isShowing());
        assertEquals(Ui.FOCUS_SCALE, tile(h).getScaleX(), 0.001f);
        assertNull("putting it down opened nothing", shadowOf(h.activity).getNextStartedActivity());
        List<String> saved = new HomeSettings(h.activity).appOrder();
        assertEquals(key, saved.get(3));

        // Kept when Home starts again
        h.controller.pause().stop().destroy();
        TestHome again = new TestHome();
        assertEquals(3, apps(again).indexOf(key));
    }

    @Test
    public void backPutsTheTileDownToo() throws Exception {
        TestHome h = new TestHome();
        String key = h.focusedKey();
        menu(h, "Move");
        h.press(KEYCODE_DPAD_RIGHT);
        h.back();
        assertFalse(h.activity.isMovingTile());
        assertEquals(1, apps(h).indexOf(key));
        assertEquals(HomeActivity.Zone.GRID, h.zone());
    }

    @Test
    public void anEdgeOnlyBumpsTheTile() throws Exception {
        TestHome h = new TestHome();
        List<String> before = apps(h).keys();
        menu(h, "Move");
        h.press(KEYCODE_DPAD_LEFT);
        h.press(KEYCODE_DPAD_UP);
        assertEquals(before, apps(h).keys());
        assertTrue(h.activity.isMovingTile());
        assertAtRest(h);
    }

    @Test
    public void moveFromTheFullMenu() throws Exception {
        TestHome h = new TestHome();
        String key = h.focusedKey();
        fullMenu(h, "Move");
        assertTrue(h.activity.isMovingTile());
        h.press(KEYCODE_DPAD_RIGHT);
        assertEquals(Ui.LIFT_SCALE, tile(h).getScaleX(), 0.001f);
        h.press(KEYCODE_DPAD_CENTER);
        assertEquals(1, apps(h).indexOf(key));
    }

    @Test
    public void theLastColumnsCardStaysOnScreen() throws Exception {
        TestHome h = new TestHome();
        h.loadSampleWeather();
        for (int i = 0; i < Ui.COLUMNS - 1; i++) h.press(KEYCODE_DPAD_RIGHT);
        holdSelect(h);
        RectF card = new RectF();
        h.activity.tileMenu().cardBounds(card);
        assertTrue(card.right <= h.activity.getWindow().getDecorView().getWidth() - Ui.px(24));
        h.capture("tile-menu-last-column.png");
    }

    @Test
    public void theHomeButtonClosesEverything() throws Exception {
        TestHome h = new TestHome();
        holdSelect(h);
        h.controller.newIntent(new Intent(h.activity, HomeActivity.class).putExtra(HomeButtonService.EXTRA_HOME_BUTTON, true));
        TestHome.settle();
        assertFalse(h.activity.tileMenu().isOpen());
        menu(h, "Move");
        h.press(KEYCODE_DPAD_RIGHT);
        h.controller.newIntent(new Intent(h.activity, HomeActivity.class).putExtra(HomeButtonService.EXTRA_HOME_BUTTON, true));
        TestHome.settle();
        assertFalse(h.activity.isMovingTile());
        assertEquals("put down where it was", 1, new HomeSettings(h.activity).appOrder().indexOf(apps(h).get(1).key));
    }

    @Test
    public void hiddenAppsComeBackFromSettings() throws Exception {
        TestHome h = new TestHome();
        h.loadSampleWeather();
        String key = h.focusedKey();
        int before = apps(h).getItemCount();
        fullMenu(h, "Hide from Home");
        TestHome.settle();
        for (int i = 0; i < 40 && apps(h).indexOf(key) >= 0; i++) {
            Thread.sleep(25);
            TestHome.settle();
        }
        assertEquals(-1, apps(h).indexOf(key));
        assertEquals(before - 1, apps(h).getItemCount());

        // Home Screen settings, Hidden Apps
        h.press(KEYCODE_DPAD_UP);
        h.press(KEYCODE_DPAD_UP);
        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_CENTER);
        int row = -1;
        for (int i = 0; row < 0; i++) if ("Hidden Apps".equals(h.panel.option(i).title)) row = i;
        assertTrue(h.panel.option(row).enabled);
        while (h.panel.focusedIndex() < row) h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_CENTER);
        assertEquals("Hidden Apps", prompt(h).heading());
        String show = prompt(h).buttons()[0];
        assertTrue(show, show.startsWith("Show "));
        h.capture("hidden-apps.png");
        h.press(KEYCODE_DPAD_CENTER);
        for (int i = 0; i < 40 && apps(h).indexOf(key) < 0; i++) {
            Thread.sleep(25);
            TestHome.settle();
        }
        assertEquals("back where it was", 0, apps(h).indexOf(key));
        assertTrue(new HomeSettings(h.activity).hiddenApps().isEmpty());
    }

    @Test
    public void appInfoOpensFireTvsPageForTheApp() throws Exception {
        TestHome h = new TestHome();
        String key = h.focusedKey();
        fullMenu(h, "App Info");
        Intent opened = shadowOf(h.activity).getNextStartedActivity();
        assertNotNull(opened);
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, opened.getAction());
        assertEquals("package:" + key, opened.getDataString());
    }

    /** The order arranged on the TV holds, and an app installed since goes after the one it follows by default. */
    @Test
    public void anArrangementMakesRoomForNewApps() {
        List<AppEntry> all = Arrays.asList(entry("a"), entry("b"), entry("new"), entry("c"));
        List<AppEntry> out = AppRepository.arrange(all, Arrays.asList("c", "a", "b", "gone"), Collections.singleton("a"));
        StringBuilder keys = new StringBuilder();
        for (AppEntry e : out) keys.append(e.key).append(' ');
        assertEquals("c b new ", keys.toString());
    }

    private static AppEntry entry(String key) {
        return new AppEntry(key, key, new Intent());
    }

    /** The memory and storage strip at the foot of Control Center, and its switch. */
    @Test
    public void controlCenterShowsFreeMemoryAndStorage() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        android.app.ActivityManager.MemoryInfo info = new android.app.ActivityManager.MemoryInfo();
        info.availMem = 1_180L * 1024 * 1024;
        info.totalMem = 1_960L * 1024 * 1024;
        shadowOf(app.getSystemService(android.app.ActivityManager.class)).setMemoryInfo(info);
        org.robolectric.shadows.ShadowStatFs.registerStats(android.os.Environment.getDataDirectory(), 2_800_000, 1_350_000, 1_350_000);
        TestHome h = new TestHome();
        h.loadSampleWeather();
        h.press(KEYCODE_DPAD_UP);
        h.press(KEYCODE_DPAD_UP);
        h.press(KEYCODE_DPAD_CENTER);
        ControlCenter cc = TestHome.find(h.activity.getWindow().getDecorView(), ControlCenter.class);
        assertTrue(cc.isOpen());
        assertTrue(cc.showsStats());
        h.capture("control-center-stats.png");
        h.back();
        new HomeSettings(app).setShowSystemStats(false);
        h.press(KEYCODE_DPAD_CENTER);
        assertTrue(cc.isOpen());
        assertFalse("turned off in Home Screen settings", cc.showsStats());
    }

    @Test
    public void gigabytes() {
        assertEquals("1.2 GB", ControlCenter.gb(1_250L * 1024 * 1024));
        assertEquals("512 MB", ControlCenter.gb(512L * 1024 * 1024));
        assertEquals("11 GB", ControlCenter.gb(11L * 1024 * 1024 * 1024));
    }
}
