package app.glasshome.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.RectF;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import java.io.InputStream;

/** Home's Lock Screen clock: the date and time, behind the Night Dunes' dune, and away over a featured title. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, qualifiers = "w960dp-h540dp-land-xhdpi")
public class ClockTest {

    @Test
    public void showsTheDateAndTimeBehindTheDune() throws Exception {
        TestHome h = new TestHome();
        h.loadSampleWeather();
        TestHome.settle();
        HomeClock clock = TestHome.find(h.activity.getWindow().getDecorView(), HomeClock.class);
        // The recorded moment: Tuesday 20 October 2026, 7:30 PM in Ohio
        assertEquals("Tue Oct 20", clock.date());
        assertEquals("7:30", clock.time());
        assertTrue(clock.showing());
        assertTrue("Night Dunes stands in front of the clock", clock.hasDepth());
        h.capture("clock-1-night-dunes.png");

        // A minute on, the clock turns with it
        Clock.fixedMillis = Fixtures.RECORDED_AT + 60_000L;
        TestHome.advance(61_000);
        assertEquals("7:31", clock.time());
    }

    @Test
    public void goesAwayOverAFeaturedTitle() throws Exception {
        TestHome h = new TestHome();
        TestHome.settle();
        HomeClock clock = TestHome.find(h.activity.getWindow().getDecorView(), HomeClock.class);
        assertTrue(clock.showing());
        h.injectFeaturedSlide("netflix", "Netflix");
        TestHome.settle();
        assertFalse("a featured title has its own text there", clock.showing());
    }

    @Test
    public void onAnotherPageItIsGoneWithHome() throws Exception {
        TestHome h = TestHome.withTmdb();
        TestHome.settle();
        HomeClock clock = TestHome.find(h.activity.getWindow().getDecorView(), HomeClock.class);
        h.menu();
        h.press(android.view.KeyEvent.KEYCODE_DPAD_RIGHT);
        h.waitForPage(HomeActivity.MOVIES);
        TestHome.advance(Ui.PAGE_FADE_MS + 100);
        assertEquals(View.GONE, clock.getVisibility());
    }

    /** Every numeral, behind the dune: three times that use 0 to 9 between them. */
    @Test
    public void everyNumeral() throws Exception {
        TestHome h = new TestHome();
        h.loadSampleWeather();
        TestHome.settle();
        HomeClock clock = TestHome.find(h.activity.getWindow().getDecorView(), HomeClock.class);
        java.time.ZonedDateTime day = Clock.at(Fixtures.RECORDED_AT);
        String[] times = {"10:28", "4:57", "6:39"};
        for (String t : times) {
            String[] hm = t.split(":");
            Clock.fixedMillis = day.withHour(Integer.parseInt(hm[0])).withMinute(Integer.parseInt(hm[1])).toInstant().toEpochMilli();
            clock.timeChanged();
            TestHome.settle();
            assertEquals(t, clock.time());
            h.capture("clock-numerals-" + t.replace(':', '-') + ".png");
        }
    }

    /**
     * The Night Dunes' mask is kept only where the clock is, as ALPHA_8 (its grey the alpha): 764 x 422
     * bytes rather than 8 MB, and the clock it cuts is the same as the whole mask's.
     */
    @Test
    public void theDepthMaskIsKeptSmallAndCutsTheSame() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        Ui.init(1920);
        Backgrounds.Entry dunes = Backgrounds.find(Backgrounds.list(app), "1-night-dunes.jpg");
        Backgrounds.DepthMask small = Backgrounds.depth(app, dunes, HomeClock.frame(), 1920, 1080);
        assertNotNull(small);
        assertEquals(Bitmap.Config.ALPHA_8, small.bitmap.getConfig());
        assertEquals(764 * 422, small.bitmap.getAllocationByteCount());

        Bitmap full;
        try (InputStream in = app.getAssets().open("depth/1-night-dunes.png")) {
            full = BitmapFactory.decodeStream(in);
        }
        assertEquals(Bitmap.Config.ARGB_8888, full.getConfig());
        HeroSlide slide = HeroSlide.backdrop(Backgrounds.decode(app, dunes, 1920));
        Bitmap whole = clock(slide, new Backgrounds.DepthMask(full, 0, 0, full.getWidth(), full.getHeight()), 1920, 1080);
        Bitmap cut = clock(slide, small, 1920, 1080);
        assertTrue("the same clock, the same dune in front", whole.sameAs(cut));

        // On a smaller screen the picture is scaled: what is kept still covers the whole clock
        try {
            Ui.init(1280);
            RectF frame = HomeClock.frame();
            Backgrounds.DepthMask m = Backgrounds.depth(app, dunes, frame, 1280, 720);
            assertNotNull(m);
            float scale = Math.max(1280f / m.fullW, 720f / m.fullH);
            float offX = (1280 - m.fullW * scale) / 2f, offY = (720 - m.fullH * scale) / 2f;
            RectF kept = new RectF(m.originX * scale + offX, m.originY * scale + offY,
                    (m.originX + m.bitmap.getWidth()) * scale + offX, (m.originY + m.bitmap.getHeight()) * scale + offY);
            assertTrue(kept + " covers " + frame, kept.contains(frame));
            assertTrue(m.bitmap.getWidth() < m.fullW && m.bitmap.getHeight() < m.fullH);
        } finally {
            Ui.init(1920);
        }
    }

    private static Bitmap clock(HeroSlide slide, Backgrounds.DepthMask mask, int w, int h) {
        HomeClock clock = new HomeClock(RuntimeEnvironment.getApplication());
        clock.setScreen(w, h);
        clock.setSlide(slide, mask);
        return clock.drawnBitmap();
    }
}
