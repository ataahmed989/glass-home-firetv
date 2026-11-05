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
import static org.robolectric.Shadows.shadowOf;

import android.Manifest;
import android.app.Application;
import android.content.ComponentName;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.provider.Settings;

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
        assertEquals("Glow", h.panel.option(2).title);
        assertEquals("Blue Apple", h.panel.option(4).title);
        assertEquals("starts on the chosen background", 1, h.panel.focusedIndex());
        assertTrue(h.panel.option(1).checked);
        h.press(KEYCODE_DPAD_UP);
        assertEquals("the unavailable switch is skipped", 1, h.panel.focusedIndex());
        assertEquals("Smart Stack", h.panel.option(5).title);
        assertEquals("Glances", h.panel.option(6).title);
        assertTrue("Smart Stack is the default", h.panel.option(5).checked);
        assertEquals("Tiles", h.panel.option(7).title);
        assertEquals("List", h.panel.option(8).title);
        assertFalse("Sleep's permission is not granted here", h.panel.option(9).enabled);
        assertTrue("Updates, last", h.panel.option(17).action);
        for (int i = 0; i < 20; i++) h.press(KEYCODE_DPAD_DOWN);
        assertEquals("stops at the last option, Updates, past those that can't be used", 17, h.panel.focusedIndex());
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
        assertEquals("Glow", h.panel.option(2).title);
        h.press(KEYCODE_DPAD_CENTER);
        TestHome.settle();
        assertTrue(h.panel.option(2).checked);
        assertFalse(h.panel.option(1).checked);
        Bitmap glow = h.stage.current().background;
        assertNotNull(glow);
        assertNotSame(dunes, glow);
        h.capture("9-settings-panel.png");

        h.back();
        h.back();
        for (int i = 0; i < 4; i++) h.press(KEYCODE_DPAD_RIGHT);   // Apple TV, after TiviMate, Netflix, Prime Video and Max
        TestHome.advance(Ui.HERO_DWELL_MS + 50);
        TestHome.settle();
        assertEquals(4, h.focused());
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

    /**
     * Home Button switched off, then the TV restarted and Fire OS turned the Home service off: with
     * the ADB grant that lets Glass Home turn it back on, the switch still works, and does so.
     */
    @Test
    public void theHomeButtonCanBeTurnedBackOnAfterARestart() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS);
        new HomeSettings(app).setHomeButtonOpensGlassHome(false);
        Settings.Secure.putString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, null);
        TestHome h = new TestHome();
        h.press(KEYCODE_DPAD_UP);
        h.press(KEYCODE_DPAD_UP);
        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_CENTER);
        assertEquals("Opens Glass Home", h.panel.option(11).title);
        assertTrue(h.panel.option(11).enabled);
        assertFalse(h.panel.option(11).checked);
        while (h.panel.focusedIndex() < 11) h.press(KEYCODE_DPAD_DOWN);
        assertEquals(11, h.panel.focusedIndex());
        h.press(KEYCODE_DPAD_CENTER);

        assertTrue(new HomeSettings(app).homeButtonOpensGlassHome());
        assertEquals(new ComponentName(app, HomeButtonService.class).flattenToString(),
                Settings.Secure.getString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));
    }

    /** Clear Cache empties Glass Home's own cache and says how much it freed. */
    @Test
    public void clearCacheEmptiesTheCache() throws Exception {
        TestHome h = new TestHome();
        java.io.File junk = new java.io.File(new java.io.File(h.activity.getCacheDir(), "img"), "old-artwork");
        junk.getParentFile().mkdirs();
        java.nio.file.Files.write(junk.toPath(), new byte[300 * 1024]);
        h.press(KEYCODE_DPAD_UP);
        h.press(KEYCODE_DPAD_UP);
        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_CENTER);
        assertEquals("Clear Cache", h.panel.option(15).title);
        while (h.panel.focusedIndex() < 15) h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_CENTER);
        for (int i = 0; i < 5; i++) TestHome.settle();
        assertFalse(junk.exists());
        assertTrue("the folder stays", junk.getParentFile().isDirectory());
        assertEquals("settings stay open", Zone.SETTINGS, h.zone());
        String said = org.robolectric.shadows.ShadowToast.getTextOfLatestToast();
        assertTrue(said, said.startsWith("Cache cleared: "));
    }
}
