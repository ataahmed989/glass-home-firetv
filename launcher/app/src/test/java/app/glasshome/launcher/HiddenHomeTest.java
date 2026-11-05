package app.glasshome.launcher;

import static org.junit.Assert.assertEquals;

import android.view.KeyEvent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What Glass Home does once another app has the screen: no network retries pile up, and its
 * snapshot is copied after the pause rather than in it.
 */
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

    /** The pause asks for no copy itself: it is asked for right after, once something was drawn since the last. */
    @Test
    public void theSnapshotIsCopiedJustAfterThePause() throws Exception {
        TestHome h = new TestHome();
        TestHome.advance(3000);   // Home at rest: its snapshot is copied
        TestHome.settle();
        h.press(KeyEvent.KEYCODE_DPAD_RIGHT);
        drawn(h);   // the move drawn since the snapshot was copied
        int before = HomeSnapshot.requests;
        h.controller.pause();
        assertEquals("nothing copied in onPause itself", before, HomeSnapshot.requests);
        TestHome.idle();
        assertEquals("copied once the pause is over", before + 1, HomeSnapshot.requests);

        // Back, and settled: copied while resting; nothing drawn since, so the next pause copies nothing
        h.controller.resume();
        TestHome.advance(3000);
        TestHome.settle();
        int rested = HomeSnapshot.requests;
        h.controller.pause();
        TestHome.idle();
        assertEquals(rested, HomeSnapshot.requests);
    }

    /** The window's draw, as the TV makes it (Robolectric draws no frames of its own here). */
    private static void drawn(TestHome h) throws Exception {
        android.view.ViewTreeObserver observer = h.activity.getWindow().getDecorView().getViewTreeObserver();
        java.lang.reflect.Method dispatch = android.view.ViewTreeObserver.class.getDeclaredMethod("dispatchOnDraw");
        dispatch.setAccessible(true);
        dispatch.invoke(observer);
    }
}
