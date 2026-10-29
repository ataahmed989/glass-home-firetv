package app.glasshome.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

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
}
