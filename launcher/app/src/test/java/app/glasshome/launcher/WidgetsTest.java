package app.glasshome.launcher;

import static android.view.KeyEvent.KEYCODE_DPAD_CENTER;
import static android.view.KeyEvent.KEYCODE_DPAD_DOWN;
import static android.view.KeyEvent.KEYCODE_DPAD_LEFT;
import static android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
import static android.view.KeyEvent.KEYCODE_DPAD_UP;
import static android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
import static android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
import static android.view.KeyEvent.KEYCODE_MEDIA_REWIND;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.provider.Settings;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import app.glasshome.launcher.HomeActivity.Zone;

/**
 * The widgets (Smart Stack and Glances) and the Control Center, with the remote. The next game is
 * real ESPN data recorded on 20 October 2026.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, qualifiers = "w960dp-h540dp-land-xhdpi")
public class WidgetsTest {

    private static TestHome home() throws Exception {
        Fixtures.install();
        TestHome h = TestHome.withTmdb();
        h.loadSampleWeather();
        for (int i = 0; i < 200 && h.weather.game() == null; i++) {
            Thread.sleep(25);
            TestHome.idle();
        }
        assertNotNull("your team's game, from the recorded scoreboards", h.weather.game());
        return h;
    }

    @Test
    public void smartStackTurnsOnlyWhenAskedAndOpensEachWidget() throws Exception {
        TestHome h = home();
        assertEquals(WidgetPanel.STACK, h.weather.layout());
        TestHome.advance(60_000);
        assertEquals("it never turns by itself", WidgetPanel.Kind.WEATHER, h.weather.focusedKind());
        h.press(KEYCODE_DPAD_UP);
        assertEquals(Zone.WEATHER, h.zone());
        assertEquals(WidgetPanel.Kind.WEATHER, h.weather.focusedKind());
        h.capture("widgets-stack-1-weather.png");

        // Mid-scroll: the weather leaving upwards, the game coming up from below
        h.tap(KEYCODE_MEDIA_PLAY_PAUSE);
        TestHome.advance(170);
        h.capture("widgets-stack-turning.png");
        TestHome.settle();
        assertEquals("Play/Pause scrolls to the next widget", WidgetPanel.Kind.GAME, h.weather.focusedKind());
        assertTrue(h.weather.game().favourite);
        h.capture("widgets-stack-2-game.png");
        h.press(KEYCODE_DPAD_CENTER);
        assertEquals("the game's page", Zone.PAGE_DETAIL, h.zone());
        h.back();
        assertEquals("back on the widget", Zone.WEATHER, h.zone());
        assertEquals(WidgetPanel.Kind.GAME, h.weather.focusedKind());

        h.press(KEYCODE_DPAD_RIGHT);
        assertEquals("Right turns it too", WidgetPanel.Kind.DATE, h.weather.focusedKind());
        h.capture("widgets-stack-3-date.png");
        h.press(KEYCODE_MEDIA_REWIND);
        assertEquals("rewind goes back one", WidgetPanel.Kind.GAME, h.weather.focusedKind());
        h.press(KEYCODE_MEDIA_FAST_FORWARD);
        assertEquals(WidgetPanel.Kind.DATE, h.weather.focusedKind());
        h.press(KEYCODE_MEDIA_PLAY_PAUSE);
        assertEquals("round again", WidgetPanel.Kind.WEATHER, h.weather.focusedKind());
        h.press(KEYCODE_MEDIA_PLAY_PAUSE);
        assertEquals(WidgetPanel.Kind.GAME, h.weather.focusedKind());

        // The stack stays where it was left, after a restart too
        TestHome again = new TestHome(false, true);
        again.loadSampleWeather();
        for (int i = 0; i < 200 && again.weather.game() == null; i++) {
            Thread.sleep(25);
            TestHome.idle();
        }
        assertEquals(WidgetPanel.Kind.GAME, again.weather.focusedKind());

        h.press(KEYCODE_MEDIA_PLAY_PAUSE);
        h.press(KEYCODE_MEDIA_PLAY_PAUSE);
        assertEquals(WidgetPanel.Kind.WEATHER, h.weather.focusedKind());
        h.press(KEYCODE_DPAD_CENTER);
        assertEquals("weather opens the forecast", Zone.OVERLAY, h.zone());
    }

    @Test
    public void playPauseOnTheAppsLeavesTheStackAlone() throws Exception {
        TestHome h = home();
        assertEquals(Zone.GRID, h.zone());
        h.press(KEYCODE_MEDIA_PLAY_PAUSE);   // the remote's usual media job, not the widgets'
        assertEquals(Zone.GRID, h.zone());
        assertEquals(WidgetPanel.Kind.WEATHER, h.weather.focusedKind());
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
        assertEquals("sunrise and sunset", WidgetPanel.Kind.SUN, h.weather.focusedKind());
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

        // The kit's grid: Wi-Fi and Bluetooth pills on top, the Sleep and Sleep Timer tiles, then four circles
        h.press(KEYCODE_DPAD_RIGHT);
        assertEquals(ControlCenter.Item.TIMER, cc.focusedItem());
        h.press(KEYCODE_DPAD_UP);
        assertEquals(ControlCenter.Item.BLUETOOTH, cc.focusedItem());
        h.press(KEYCODE_DPAD_LEFT);
        assertEquals(ControlCenter.Item.WIFI, cc.focusedItem());
        h.press(KEYCODE_DPAD_DOWN);
        assertEquals(ControlCenter.Item.SLEEP, cc.focusedItem());
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
        assertEquals("the tile right above", ControlCenter.Item.SLEEP, cc.focusedItem());
        h.press(KEYCODE_DPAD_UP);
        assertEquals(ControlCenter.Item.WIFI, cc.focusedItem());
        h.press(KEYCODE_DPAD_CENTER);
        Intent wifi = shadowOf(h.activity).getNextStartedActivity();
        assertNotNull(wifi);
        assertEquals(Settings.ACTION_WIFI_SETTINGS, wifi.getAction());

        // Sleep has not been granted in this test: it says so instead of doing nothing silently
        h.press(KEYCODE_DPAD_DOWN);
        assertEquals(ControlCenter.Item.SLEEP, cc.focusedItem());
        h.press(KEYCODE_DPAD_CENTER);
        assertEquals("still open", Zone.CONTROL, h.zone());

        h.press(KEYCODE_DPAD_UP);
        assertEquals(ControlCenter.Item.WIFI, cc.focusedItem());
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
