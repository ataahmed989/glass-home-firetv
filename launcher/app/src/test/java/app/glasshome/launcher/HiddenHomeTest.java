package app.glasshome.launcher;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/** What Glass Home does once another app has the screen: no network retries pile up. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, qualifiers = "w960dp-h540dp-land-xhdpi")
public class HiddenHomeTest {
    /** Lets {@code ms} pass, then lets the network thread answer, and its answer land. */
    private static void pass(long ms) throws InterruptedException {
        TestHome.advance(ms);
        for (int i = 0; i < 8; i++) {
            Thread.sleep(25);
            TestHome.idle();
        }
    }

    /** Offline, the weather is tried again once per interval while Home is in front, and not at all behind another app. */
    @Test
    public void weatherRetriesStopWhileHidden() throws Exception {
        TestHome h = new TestHome();
        AtomicInteger fetches = new AtomicInteger();
        Net.Fetcher recorded = Net.fetcher;
        Net.fetcher = (url, bearer) -> {
            if (url.startsWith("https://api.open-meteo.com/")) {
                fetches.incrementAndGet();
                throw new IOException("offline");
            }
            return recorded.get(url, bearer);
        };
        pass(5 * 60 * 1000L);
        assertEquals("one retry in the interval", 1, fetches.get());
        pass(5 * 60 * 1000L);
        assertEquals("and one more in the next", 2, fetches.get());

        h.controller.pause().stop();
        for (int i = 0; i < 6; i++) pass(5 * 60 * 1000L);
        assertEquals("none while another app is in front", 2, fetches.get());
    }
}
