package app.glasshome.launcher;

import static android.view.KeyEvent.KEYCODE_DPAD_CENTER;
import static android.view.KeyEvent.KEYCODE_DPAD_DOWN;
import static android.view.KeyEvent.KEYCODE_DPAD_LEFT;
import static android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
import static android.view.KeyEvent.KEYCODE_DPAD_UP;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.provider.Settings;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import app.glasshome.launcher.HomeActivity.Zone;

/**
 * The widgets (Smart Stack and Glances) and the Control Center, with the remote. The next game is
 * real ESPN data recorded on 20 October 2026; the Continue Watching entry is test data.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, qualifiers = "w960dp-h540dp-land-xhdpi")
public class WidgetsTest {

    /** Test data only: one show, as the media-session watcher would have recorded it. */
    private static void watched() {
        Fixtures.install();
        Application app = RuntimeEnvironment.getApplication();
        WatchHistory.record(app, PagesTest.snapshot("com.hbo.hbonow", "The Last of Us", "Season 2, Episode 3", 55, 12,
                Fixtures.RECORDED_AT - 3600_000L));
    }

    private static TestHome home() throws Exception {
        watched();
        TestHome h = TestHome.withTmdb();
        h.loadSampleWeather();
        for (int i = 0; i < 200 && (h.weather.game() == null || h.weather.watching() == null); i++) {
            Thread.sleep(25);
            TestHome.idle();
        }
        assertNotNull("your team's game, from the recorded scoreboards", h.weather.game());
        assertNotNull(h.weather.watching());
        return h;
    }

    @Test
    public void smartStackTurnsWithRightAndOpensEachWidget() throws Exception {
        TestHome h = home();
        assertEquals(WidgetPanel.STACK, h.weather.layout());
        h.press(KEYCODE_DPAD_UP);
        assertEquals(Zone.WEATHER, h.zone());
        assertEquals(WidgetPanel.Kind.WEATHER, h.weather.focusedKind());
        h.capture("widgets-stack-1-weather.png");

        h.press(KEYCODE_DPAD_RIGHT);
        assertEquals(WidgetPanel.Kind.GAME, h.weather.focusedKind());
        assertTrue(h.weather.game().favourite);
        h.capture("widgets-stack-2-game.png");
        h.press(KEYCODE_DPAD_CENTER);
        assertEquals("the game's page", Zone.PAGE_DETAIL, h.zone());
        h.back();
        assertEquals("back on the widget", Zone.WEATHER, h.zone());
        assertEquals(WidgetPanel.Kind.GAME, h.weather.focusedKind());

        h.press(KEYCODE_DPAD_RIGHT);
        assertEquals(WidgetPanel.Kind.CONTINUE, h.weather.focusedKind());
        h.capture("widgets-stack-3-continue.png");
        h.press(KEYCODE_DPAD_CENTER);
        Intent started = shadowOf(h.activity).getNextStartedActivity();
        assertNotNull(started);
        assertEquals("com.hbo.hbonow", started.getComponent().getPackageName());

        h.press(KEYCODE_DPAD_RIGHT);
        assertEquals(WidgetPanel.Kind.DATE, h.weather.focusedKind());
        h.capture("widgets-stack-4-date.png");
        h.press(KEYCODE_DPAD_RIGHT);
        assertEquals("round again", WidgetPanel.Kind.WEATHER, h.weather.focusedKind());
        h.press(KEYCODE_DPAD_CENTER);
        assertEquals("weather opens the forecast", Zone.OVERLAY, h.zone());
    }

    @Test
    public void glancesAreChosenInSettingsAndMovedBetween() throws Exception {
        TestHome h = home();
        h.press(KEYCODE_DPAD_UP);
        h.press(KEYCODE_DPAD_UP);
        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_CENTER);
        assertEquals(Zone.SETTINGS, h.zone());
        while (h.panel.focusedIndex() < 7) h.press(KEYCODE_DPAD_DOWN);
        assertEquals("Glances", h.panel.option(7).title);
        h.press(KEYCODE_DPAD_CENTER);
        assertTrue(h.panel.option(7).checked);
        assertFalse(h.panel.option(6).checked);
        assertTrue("the background choice is untouched", h.panel.option(1).checked);
        h.capture("settings-widgets.png");
        h.back();
        h.back();
        assertEquals(WidgetPanel.GLANCES, h.weather.layout());

        h.press(KEYCODE_DPAD_UP);
        assertEquals(Zone.WEATHER, h.zone());
        assertEquals("from the apps below: the lower-left square", 2, h.weather.focusedSquare());
        assertEquals(WidgetPanel.Kind.GAME, h.weather.focusedKind());
        h.capture("widgets-glances.png");
        h.press(KEYCODE_DPAD_RIGHT);
        assertEquals(WidgetPanel.Kind.CONTINUE, h.weather.focusedKind());
        h.press(KEYCODE_DPAD_UP);
        assertEquals(WidgetPanel.Kind.WEATHER, h.weather.focusedKind());
        h.press(KEYCODE_DPAD_LEFT);
        assertEquals(WidgetPanel.Kind.DATE, h.weather.focusedKind());
        h.press(KEYCODE_DPAD_LEFT);
        assertEquals("no hero action to the left", Zone.WEATHER, h.zone());
        h.press(KEYCODE_DPAD_UP);
        assertEquals(Zone.CAPSULE, h.zone());
        h.press(KEYCODE_DPAD_DOWN);
        assertEquals("from the capsule above: the upper-right square", 1, h.weather.focusedSquare());
        h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_DOWN);
        assertEquals(Zone.GRID, h.zone());
    }

    @Test
    public void controlCenterOpensFromTheCapsule() throws Exception {
        TestHome h = home();
        h.press(KEYCODE_DPAD_UP);
        h.press(KEYCODE_DPAD_UP);
        assertEquals(Zone.CAPSULE, h.zone());
        assertEquals(ClockCapsule.CONTROL, h.capsule.item());
        h.press(KEYCODE_DPAD_CENTER);
        assertEquals(Zone.CONTROL, h.zone());
        ControlCenter cc = TestHome.find(h.activity.getWindow().getDecorView(), ControlCenter.class);
        assertEquals(ControlCenter.Item.SLEEP, cc.focusedItem());
        h.capture("control-center-1.png");

        h.press(KEYCODE_DPAD_RIGHT);
        assertEquals(ControlCenter.Item.WIFI, cc.focusedItem());
        h.press(KEYCODE_DPAD_DOWN);
        assertEquals(ControlCenter.Item.BLUETOOTH, cc.focusedItem());
        h.press(KEYCODE_DPAD_DOWN);
        assertEquals(ControlCenter.Item.TIMER, cc.focusedItem());
        h.press(KEYCODE_DPAD_DOWN);
        assertTrue("a shortcut button", cc.focusedItem().ordinal() >= ControlCenter.Item.MIRRORING.ordinal());
        while (cc.focusedItem() != ControlCenter.Item.MIRRORING) h.press(KEYCODE_DPAD_LEFT);
        h.capture("control-center-2-shortcut.png");
        h.press(KEYCODE_DPAD_CENTER);
        Intent mirroring = shadowOf(h.activity).getNextStartedActivity();
        assertNotNull(mirroring);
        assertEquals("Fire TV's screen mirroring", SystemControls.MIRRORING_PACKAGE,
                mirroring.getComponent() != null ? mirroring.getComponent().getPackageName() : mirroring.getPackage());

        h.press(KEYCODE_DPAD_UP);
        assertEquals(ControlCenter.Item.TIMER, cc.focusedItem());
        h.press(KEYCODE_DPAD_UP);
        assertEquals("the tile right above the timer", ControlCenter.Item.BLUETOOTH, cc.focusedItem());
        h.press(KEYCODE_DPAD_UP);
        assertEquals(ControlCenter.Item.WIFI, cc.focusedItem());
        h.press(KEYCODE_DPAD_CENTER);
        Intent wifi = shadowOf(h.activity).getNextStartedActivity();
        assertNotNull(wifi);
        assertEquals(Settings.ACTION_WIFI_SETTINGS, wifi.getAction());

        // Sleep has not been granted in this test: it says so instead of doing nothing silently
        h.press(KEYCODE_DPAD_LEFT);
        assertEquals(ControlCenter.Item.SLEEP, cc.focusedItem());
        h.press(KEYCODE_DPAD_CENTER);
        assertEquals("still open", Zone.CONTROL, h.zone());

        h.press(KEYCODE_DPAD_UP);
        assertEquals("Up from the top row closes it", Zone.CAPSULE, h.zone());
        h.press(KEYCODE_DPAD_CENTER);
        h.back();
        assertEquals(Zone.CAPSULE, h.zone());
        assertEquals(ClockCapsule.CONTROL, h.capsule.item());
        h.press(KEYCODE_DPAD_CENTER);
        h.menu();
        assertEquals("☰ closes it on the way to the tabs", Zone.TABS, h.zone());
        assertFalse(cc.isOpen());
    }
}
