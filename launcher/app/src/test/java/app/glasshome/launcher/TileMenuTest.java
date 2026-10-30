package app.glasshome.launcher;

import static android.view.KeyEvent.KEYCODE_DPAD_CENTER;
import static android.view.KeyEvent.KEYCODE_DPAD_DOWN;
import static android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
import static android.view.KeyEvent.KEYCODE_DPAD_UP;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.provider.Settings;

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

/** Holding Select on a tile: Move, Hide from Home, App Info. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, qualifiers = "w960dp-h540dp-land-xhdpi")
public class TileMenuTest {
    private static UpdatePrompt prompt(TestHome h) {
        return TestHome.find(h.activity.getWindow().getDecorView(), UpdatePrompt.class);
    }

    private static AppAdapter apps(TestHome h) {
        return (AppAdapter) h.grid.getAdapter();
    }

    private static void holdSelect(TestHome h) throws InterruptedException {
        h.hold(KEYCODE_DPAD_CENTER, HomeActivity.TILE_HOLD_MS + 200, 50);
    }

    private static void choose(TestHome h, String button) throws InterruptedException {
        UpdatePrompt p = prompt(h);
        int i = Arrays.asList(p.buttons()).indexOf(button);
        assertTrue(button + " is offered", i >= 0);
        while (p.focusedIndex() < i) h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_CENTER);
    }

    @Test
    public void holdingSelectOpensTheMenuAndOpensNothing() throws Exception {
        TestHome h = new TestHome();
        h.loadSampleWeather();
        String key = h.focusedKey();
        holdSelect(h);
        assertEquals(HomeActivity.Zone.UPDATE, h.zone());
        assertEquals(apps(h).get(0).label, prompt(h).heading());
        assertArrayEquals(new String[] {"Move", "Hide from Home", "App Info", "Cancel"}, prompt(h).buttons());
        assertNull("the release opened nothing", shadowOf(h.activity).getNextStartedActivity());
        h.capture("tile-menu.png");
        h.back();
        assertEquals(HomeActivity.Zone.GRID, h.zone());
        assertEquals(key, h.focusedKey());
    }

    @Test
    public void aShortPressStillOpensTheApp() throws Exception {
        TestHome h = new TestHome();
        h.press(KEYCODE_DPAD_CENTER);
        assertNotEquals(HomeActivity.Zone.UPDATE, h.zone());
        assertNotNull(shadowOf(h.activity).getNextStartedActivity());
    }

    @Test
    public void moveCarriesTheTileAndKeepsTheOrder() throws Exception {
        TestHome h = new TestHome();
        h.loadSampleWeather();
        String key = h.focusedKey(), second = apps(h).get(1).key;
        holdSelect(h);
        choose(h, "Move");
        assertTrue(h.activity.isMovingTile());
        assertEquals(HomeActivity.Zone.GRID, h.zone());
        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_RIGHT);
        assertEquals("the tile went along", 2, apps(h).indexOf(key));
        assertEquals("the others made room", 0, apps(h).indexOf(second));
        assertEquals(key, h.focusedKey());
        h.press(KEYCODE_DPAD_DOWN);
        assertEquals("down a row", 2 + Ui.COLUMNS, apps(h).indexOf(key));
        h.press(KEYCODE_DPAD_UP);
        h.capture("tile-moving.png");
        h.press(KEYCODE_DPAD_CENTER);
        assertFalse(h.activity.isMovingTile());
        assertNull("placing it opened nothing", shadowOf(h.activity).getNextStartedActivity());
        List<String> saved = new HomeSettings(h.activity).appOrder();
        assertEquals(key, saved.get(2));

        // Kept when Home starts again
        h.controller.pause().stop().destroy();
        TestHome again = new TestHome();
        assertEquals(2, apps(again).indexOf(key));
    }

    @Test
    public void backPutsTheTileDownToo() throws Exception {
        TestHome h = new TestHome();
        String key = h.focusedKey();
        holdSelect(h);
        choose(h, "Move");
        h.press(KEYCODE_DPAD_RIGHT);
        h.back();
        assertFalse(h.activity.isMovingTile());
        assertEquals(1, apps(h).indexOf(key));
        assertEquals(HomeActivity.Zone.GRID, h.zone());
    }

    @Test
    public void hiddenAppsComeBackFromSettings() throws Exception {
        TestHome h = new TestHome();
        h.loadSampleWeather();
        String key = h.focusedKey();
        int before = apps(h).getItemCount();
        holdSelect(h);
        choose(h, "Hide from Home");
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
        holdSelect(h);
        choose(h, "App Info");
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
