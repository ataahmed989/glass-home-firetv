package app.glasshome.launcher;

import static android.view.KeyEvent.KEYCODE_DPAD_CENTER;
import static android.view.KeyEvent.KEYCODE_DPAD_DOWN;
import static android.view.KeyEvent.KEYCODE_DPAD_LEFT;
import static android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
import static android.view.KeyEvent.KEYCODE_DPAD_UP;
import static org.junit.Assert.*;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

/** The Sources button and its menu, in both looks, reached with the remote from the apps. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, qualifiers = "w960dp-h540dp-land-xhdpi")
public class SourcesScreenshotTest {
    /** Up to the tab bar, then Left past Home onto the button. */
    private static void toButton(TestHome h) throws Exception {
        while (h.activity.zone() != HomeActivity.Zone.TABS) h.press(KEYCODE_DPAD_UP);
        h.press(KEYCODE_DPAD_LEFT);
        assertEquals(HomeActivity.Zone.SOURCE, h.activity.zone());
    }

    @Test
    public void captureTiles() throws Exception {
        TestHome h = new TestHome();
        h.loadSampleWeather();
        TestHome.settle();
        h.capture("sources-1-home.png");

        toButton(h);
        h.capture("sources-2-button-focused.png");

        h.press(KEYCODE_DPAD_CENTER);
        assertEquals(HomeActivity.Zone.SOURCES, h.activity.zone());
        assertEquals("the tiles by default", SourcesPanel.TILES, h.sources.look());
        h.capture("sources-3-tiles-fire-tv.png");

        h.press(KEYCODE_DPAD_RIGHT);
        assertEquals(1, h.sources.focusedIndex());
        h.capture("sources-4-tiles-hdmi2.png");

        h.press(KEYCODE_DPAD_DOWN);
        assertEquals(3, h.sources.focusedIndex());
        h.capture("sources-5-tiles-av.png");

        // Back closes the menu onto the button; Right goes on to the tabs
        h.back();
        assertEquals(HomeActivity.Zone.SOURCE, h.activity.zone());
        h.press(KEYCODE_DPAD_RIGHT);
        assertEquals(HomeActivity.Zone.TABS, h.activity.zone());
    }

    @Test
    public void captureList() throws Exception {
        TestHome h = new TestHome();
        h.loadSampleWeather();
        TestHome.settle();
        // Chosen in Home Screen settings, under the widgets
        h.press(KEYCODE_DPAD_UP);
        h.press(KEYCODE_DPAD_UP);
        h.press(KEYCODE_DPAD_RIGHT);   // from Control Center to the avatar
        h.press(KEYCODE_DPAD_CENTER);
        assertEquals("Tiles", h.panel.option(8).title);
        assertTrue("the tiles by default", h.panel.option(8).checked);
        assertEquals("List", h.panel.option(9).title);
        while (h.panel.focusedIndex() < 9) h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_CENTER);
        assertTrue(h.panel.option(9).checked);
        assertFalse(h.panel.option(8).checked);
        TestHome.settle();
        h.capture("sources-settings.png");
        h.back();
        assertEquals("home", "list", h.activity.getSharedPreferences("home", 0).getString("sources", ""));

        toButton(h);
        h.press(KEYCODE_DPAD_CENTER);
        assertEquals(SourcesPanel.LIST, h.sources.look());
        h.capture("sources-6-list-fire-tv.png");

        h.press(KEYCODE_DPAD_DOWN);
        assertEquals(1, h.sources.focusedIndex());
        h.capture("sources-7-list-hdmi2.png");

        h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_DOWN);
        h.capture("sources-8-list-av.png");

        // Up from the top closes it
        for (int i = 0; i < 4; i++) h.press(KEYCODE_DPAD_UP);
        assertEquals(HomeActivity.Zone.SOURCE, h.activity.zone());
    }
}
