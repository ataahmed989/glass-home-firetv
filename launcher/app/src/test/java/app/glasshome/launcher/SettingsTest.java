package app.glasshome.launcher;

import static android.view.KeyEvent.KEYCODE_DPAD_CENTER;
import static android.view.KeyEvent.KEYCODE_DPAD_DOWN;
import static android.view.KeyEvent.KEYCODE_DPAD_LEFT;
import static android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
import static android.view.KeyEvent.KEYCODE_DPAD_UP;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.SharedPreferences;
import android.graphics.Bitmap;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import app.glasshome.launcher.HomeActivity.Zone;

/**
 * The profile capsule and the settings it opens: a "Featured titles" switch (needs a TMDB
 * token) and the constant background, chosen from the five bundled images.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, qualifiers = "w960dp-h540dp-land-xhdpi")
public class SettingsTest {

    @Test
    public void capsuleIsReachableAndUnavailableOptionsAreSkipped() throws Exception {
        TestHome h = new TestHome();
        h.press(KEYCODE_DPAD_UP);
        assertEquals(Zone.WEATHER, h.zone());
        h.press(KEYCODE_DPAD_UP);
        assertEquals(Zone.CAPSULE, h.zone());
        assertEquals("lands on Control Center first", ClockCapsule.CONTROL, h.capsule.item());
        h.press(KEYCODE_DPAD_RIGHT);
        assertEquals("then the avatar", Zone.CAPSULE, h.zone());
        assertEquals(ClockCapsule.PROFILE, h.capsule.item());
        h.press(KEYCODE_DPAD_RIGHT);
        assertEquals("right edge of the screen", ClockCapsule.PROFILE, h.capsule.item());

        h.press(KEYCODE_DPAD_CENTER);
        assertEquals(Zone.SETTINGS, h.zone());
        // Row 0 is the featured switch (no token here, so unavailable); rows 1-5 are the backgrounds
        assertFalse(h.panel.option(0).enabled);
        assertEquals("Night Dunes", h.panel.option(1).title);
        assertEquals("Lake at Dusk", h.panel.option(2).title);
        assertEquals("Blue Apple", h.panel.option(5).title);
        assertEquals("starts on the chosen background", 1, h.panel.focusedIndex());
        assertTrue(h.panel.option(1).checked);
        h.press(KEYCODE_DPAD_UP);
        assertEquals("the unavailable switch is skipped", 1, h.panel.focusedIndex());
        assertEquals("Smart Stack", h.panel.option(6).title);
        assertEquals("Glances", h.panel.option(7).title);
        assertTrue("Smart Stack is the default", h.panel.option(6).checked);
        assertFalse("Sleep's permission is not granted here", h.panel.option(8).enabled);
        for (int i = 0; i < 12; i++) h.press(KEYCODE_DPAD_DOWN);
        assertEquals("stops at the last option that can be used", 7, h.panel.focusedIndex());
        h.press(KEYCODE_DPAD_LEFT);
        assertEquals(Zone.SETTINGS, h.zone());

        h.back();
        assertEquals("Back closes Settings onto the capsule", Zone.CAPSULE, h.zone());
        h.back();
        assertEquals(Zone.GRID, h.zone());
        assertEquals(0, h.focused());
    }

    @Test
    public void theChosenBackgroundStaysBehindEveryApp() throws Exception {
        TestHome h = new TestHome();
        TestHome.settle();
        Bitmap dunes = h.stage.current().background;
        assertNotNull("Night Dunes by default", dunes);
        assertFalse(h.stage.current().isFeatured());

        h.press(KEYCODE_DPAD_UP);
        h.press(KEYCODE_DPAD_UP);
        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_CENTER);
        h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_DOWN);
        assertEquals("Glow", h.panel.option(3).title);
        h.press(KEYCODE_DPAD_CENTER);
        TestHome.settle();
        assertTrue(h.panel.option(3).checked);
        assertFalse(h.panel.option(1).checked);
        Bitmap glow = h.stage.current().background;
        assertNotNull(glow);
        assertNotSame(dunes, glow);
        h.capture("9-settings-panel.png");

        h.back();
        h.back();
        for (int i = 0; i < 3; i++) h.press(KEYCODE_DPAD_RIGHT);
        TestHome.advance(Ui.HERO_DWELL_MS + 50);
        TestHome.settle();
        assertEquals("com.apple.atve.amazon.appletv", h.stage.current().appKey);
        assertSame("the same background behind another app", glow, h.stage.current().background);
        SharedPreferences prefs = h.activity.getSharedPreferences("home", 0);
        assertEquals("3-glow.jpg", prefs.getString("background", ""));
        h.capture("10-glow-appletv.png");
    }

    @Test
    public void featuredTitlesSwitchIsAvailableWithAToken() throws Exception {
        // A placeholder token in the app's own config: enough to enable the switch (no network needed here)
        Application app = RuntimeEnvironment.getApplication();
        JSONObject cfg;
        try (InputStream in = app.getAssets().open("apps.json")) {
            cfg = new JSONObject(Weather.read(in));
        }
        cfg.put("tmdb_api_key", "placeholder-token");
        try (FileOutputStream out = new FileOutputStream(new File(app.getExternalFilesDir(null), "apps.json"))) {
            out.write(cfg.toString().getBytes(StandardCharsets.UTF_8));
        }
        TestHome h = new TestHome();
        h.press(KEYCODE_DPAD_UP);
        h.press(KEYCODE_DPAD_UP);
        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_CENTER);
        assertTrue(h.panel.option(0).enabled);
        assertTrue("on by default", h.panel.option(0).checked);
        h.press(KEYCODE_DPAD_UP);
        assertEquals(0, h.panel.focusedIndex());
        h.press(KEYCODE_DPAD_CENTER);
        assertFalse(h.panel.option(0).checked);
        assertFalse(h.activity.getSharedPreferences("home", 0).getBoolean("featured", true));
        assertTrue("choosing the switch leaves the background choice alone", h.panel.option(1).checked);
    }
}
