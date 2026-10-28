package app.glasshome.launcher;

import static android.view.KeyEvent.KEYCODE_DPAD_CENTER;
import static android.view.KeyEvent.KEYCODE_DPAD_DOWN;
import static android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
import static android.view.KeyEvent.KEYCODE_DPAD_UP;
import static org.junit.Assert.*;

import android.app.Application;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

/** Glass Home's updates from its GitHub releases, and how they are offered. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, qualifiers = "w960dp-h540dp-land-xhdpi")
public class UpdaterTest {
    private static final String RELEASE = "{\"tag_name\":\"v1.0.%d\",\"assets\":["
            + "{\"name\":\"GlassHome.apk\",\"browser_download_url\":\"https://github.com/x/releases/download/v1.0.%d/GlassHome.apk\"}]}";

    private Application app;
    private Net.Fetcher fixtures;

    @Before
    public void reset() {
        app = RuntimeEnvironment.getApplication();
        HomeActivity.updateOffered = false;
        HomeActivity.updateCheckedAt = 0;
        Updater.latest = null;
    }

    @After
    public void restore() {
        if (fixtures != null) Net.fetcher = fixtures;
        HomeActivity.updateOffered = false;
        HomeActivity.updateCheckedAt = 0;
        Updater.latest = null;
    }

    /** GitHub answers with release {@code build}; everything else as recorded. */
    private void github(int build) {
        fixtures = Net.fetcher;
        Net.Fetcher recorded = fixtures;
        Net.fetcher = (url, bearer) -> url.equals(Updater.LATEST_URL)
                ? String.format(RELEASE, build, build).getBytes(StandardCharsets.UTF_8)
                : recorded.get(url, bearer);
    }

    private void waitForCheck() throws Exception {
        TestHome.advance(HomeActivity.UPDATE_CHECK_MS + 100);
        for (int i = 0; i < 20; i++) TestHome.settle();
    }

    @Test
    public void readsTheReleaseAndItsApk() throws Exception {
        Updater.Release r = Updater.parse(String.format(RELEASE, 7, 7));
        assertEquals(7, r.versionCode);
        assertEquals("1.0.7", r.name);
        assertTrue(r.apkUrl.endsWith("/v1.0.7/GlassHome.apk"));
        assertEquals("this build is 1.0.1", 1, Updater.installedCode(app));
        assertTrue(Updater.isNewer(app, r));
        assertFalse(Updater.isNewer(app, Updater.parse(String.format(RELEASE, 1, 1))));
        assertFalse(Updater.isNewer(app, null));
    }

    @Test(expected = JSONException.class)
    public void aReleaseWithoutAnApkIsNoUpdate() throws Exception {
        Updater.parse("{\"tag_name\":\"v1.0.9\",\"assets\":[]}");
    }

    @Test
    public void aNewerReleaseIsOfferedOnceHomeIsUp() throws Exception {
        TestHome h = new TestHome();
        github(7);
        h.controller.pause().resume();   // the check is made when Home comes up
        waitForCheck();
        assertEquals(HomeActivity.Zone.UPDATE, h.zone());
        UpdatePrompt prompt = TestHome.find(h.activity.getWindow().getDecorView(), UpdatePrompt.class);
        assertEquals("Update available", prompt.heading());
        assertArrayEquals(new String[] {"Update", "Later"}, prompt.buttons());
        assertEquals("Update first", 0, prompt.focusedIndex());
        h.capture("update-available.png");

        h.press(KEYCODE_DPAD_DOWN);
        assertEquals(1, prompt.focusedIndex());
        h.press(KEYCODE_DPAD_CENTER);
        assertEquals("Later closes it", HomeActivity.Zone.GRID, h.zone());

        // Not again this time round
        h.controller.pause().resume();
        waitForCheck();
        assertEquals(HomeActivity.Zone.GRID, h.zone());
    }

    @Test
    public void nothingWhenUpToDate() throws Exception {
        TestHome h = new TestHome();
        github(1);
        h.controller.pause().resume();
        waitForCheck();
        assertEquals(HomeActivity.Zone.GRID, h.zone());
    }

    /** The last row of Home Screen settings looks again, and says what it found. */
    @Test
    public void settingsChecksWhenAsked() throws Exception {
        TestHome h = new TestHome();
        github(1);
        h.press(KEYCODE_DPAD_UP);
        h.press(KEYCODE_DPAD_UP);
        h.press(KEYCODE_DPAD_RIGHT);   // from Control Center to the avatar
        h.press(KEYCODE_DPAD_CENTER);
        int last = 13;
        assertTrue(h.panel.option(last).action);
        assertEquals("Glass Home 1.0.1", h.panel.option(last).title);
        while (h.panel.focusedIndex() < last) h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_CENTER);
        for (int i = 0; i < 20; i++) TestHome.settle();
        UpdatePrompt prompt = TestHome.find(h.activity.getWindow().getDecorView(), UpdatePrompt.class);
        assertEquals(HomeActivity.Zone.UPDATE, h.zone());
        assertEquals("Glass Home is up to date", prompt.heading());
        h.capture("update-up-to-date.png");
        h.press(KEYCODE_DPAD_CENTER);
        assertEquals("OK goes back where it was", HomeActivity.Zone.CAPSULE, h.zone());
    }
}
