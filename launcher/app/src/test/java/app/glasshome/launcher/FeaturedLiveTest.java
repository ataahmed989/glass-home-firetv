package app.glasshome.launcher;

import static android.view.KeyEvent.KEYCODE_DPAD_DOWN;
import static android.view.KeyEvent.KEYCODE_DPAD_LEFT;
import static android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
import static android.view.KeyEvent.KEYCODE_DPAD_UP;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import org.json.JSONObject;
import org.junit.Assume;
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
import java.nio.file.Files;

import app.glasshome.launcher.HomeActivity.Zone;

/**
 * Live TMDB check, run only when a token file is passed with -PtmdbTokenFile=…: the real
 * featured-titles path, from the API call to the rendered slide, for each streaming service.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, qualifiers = "w960dp-h540dp-land-xhdpi")
public class FeaturedLiveTest {
    @Test
    public void featuredTitlesForEachStreamingService() throws Exception {
        String path = System.getProperty("tmdb.token.file", "");
        Assume.assumeTrue("no TMDB token file given", !path.isEmpty() && new File(path).isFile());
        String token = new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8).trim();

        // The token goes only into this test app's own (temporary) config override
        Application app = RuntimeEnvironment.getApplication();
        JSONObject cfg;
        try (InputStream in = app.getAssets().open("apps.json")) {
            cfg = new JSONObject(Weather.read(in));
        }
        cfg.put("tmdb_api_key", token);
        try (FileOutputStream out = new FileOutputStream(new File(app.getExternalFilesDir(null), "apps.json"))) {
            out.write(cfg.toString().getBytes(StandardCharsets.UTF_8));
        }

        TestHome h = new TestHome();
        h.loadSampleWeather();
        h.press(KEYCODE_DPAD_RIGHT);
        awaitFeatured(h, "com.netflix.ninja");
        h.capture("live-1-netflix.png");
        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_RIGHT);
        awaitFeatured(h, "com.apple.atve.amazon.appletv");
        h.capture("live-2-appletv.png");
        h.press(KEYCODE_DPAD_RIGHT);
        awaitFeatured(h, "com.disney.disneyplus");
        h.capture("live-3-disney.png");
        h.press(KEYCODE_DPAD_RIGHT);
        awaitFeatured(h, "com.hbo.hbonow");
        h.capture("live-4-max.png");
        h.press(KEYCODE_DPAD_DOWN);
        for (int i = 0; i < 4; i++) h.press(KEYCODE_DPAD_LEFT);
        assertEquals(7, h.focused());
        awaitFeatured(h, "com.amazon.firebat");
        h.capture("live-5-prime.png");

        // The hero now has an action. Up from the second row goes to Netflix in the first row,
        // then Up again reaches its hero; slideshow changes keep focus there.
        h.press(KEYCODE_DPAD_UP);
        assertEquals(1, h.focused());
        h.press(KEYCODE_DPAD_UP);
        assertEquals(Zone.HERO, h.zone());
        h.capture("live-6-netflix-hero-focused.png");
        TestHome.advance(Ui.SLIDE_INTERVAL_MS + 1000);
        TestHome.settle();
        assertEquals("slideshow did not move focus", Zone.HERO, h.zone());
    }

    private static void awaitFeatured(TestHome h, String appKey) throws InterruptedException {
        TestHome.advance(Ui.HERO_DWELL_MS + 50);
        for (int i = 0; i < 300; i++) {
            HeroSlide s = h.stage.current();
            if (s != null && appKey.equals(s.appKey) && s.isFeatured()) break;
            Thread.sleep(100);
            TestHome.idle();
        }
        TestHome.settle();
        HeroSlide s = h.stage.current();
        assertTrue("featured slide for " + appKey, s != null && appKey.equals(s.appKey) && s.isFeatured());
        System.out.println("FEATURED " + appKey + ": " + s.title + " (" + s.meta + ")");
    }
}
