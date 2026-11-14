package app.glasshome.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.graphics.Bitmap;
import android.view.KeyEvent;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSystemClock;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What Glass Home does once another app has the screen: no network retries pile up, its snapshot
 * is copied after the pause rather than in it, and its GPU cache is trimmed once, a while later.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, qualifiers = "w960dp-h540dp-land-xhdpi")
public class HiddenHomeTest {
    private final List<List<Bitmap>> trims = new ArrayList<>();

    @After
    public void restore() {
        HomeActivity.gpuTrimmer = null;
        HomeActivity.playingProbe = null;
        HomeActivity.playbackTrimmer = null;
    }

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

    private TestHome trimmed() throws Exception {
        TestHome h = new TestHome();
        HomeActivity.gpuTrimmer = trims::add;
        // The background is decoded off the main thread, and the glass's backdrop made from it
        for (int i = 0; i < 400 && (h.stage.current() == null || h.activity.homeBackdrop() == null); i++) {
            Thread.sleep(25);
            TestHome.idle();
        }
        return h;
    }

    /** Some 41 s after Home goes out of sight, the GPU cache is trimmed once, and what Home draws first goes back. */
    @Test
    public void theGpuCacheIsTrimmedOnceAWhileAfterHiding() throws Exception {
        TestHome h = trimmed();
        h.controller.pause().stop();
        TestHome.advance(HomeActivity.GPU_TRIM_AFTER_MS - 1);
        assertEquals(0, trims.size());
        TestHome.advance(2000);
        assertEquals(1, trims.size());
        List<Bitmap> warm = trims.get(0);
        assertFalse(warm.isEmpty());
        for (Bitmap b : warm) {
            assertNotNull(b);
            assertFalse(b.isRecycled());
        }
        assertTrue("the background", warm.contains(h.stage.current().background));
        assertTrue("the glass's backdrop", warm.contains(h.activity.homeBackdrop()));
        TileView tile = ((AppAdapter.Holder) h.grid.findViewHolderForAdapterPosition(0)).tile;
        List<Bitmap> tilePictures = new ArrayList<>();
        tile.warm(tilePictures);
        assertFalse(tilePictures.isEmpty());
        assertTrue("the tiles' layers", warm.containsAll(tilePictures));
        TestHome.advance(5 * 60 * 1000L);
        assertEquals("once", 1, trims.size());
    }

    @Test
    public void backBeforeTheTrimNoneIsMade() throws Exception {
        TestHome h = trimmed();
        h.controller.pause().stop();
        TestHome.advance(20_000);
        h.controller.restart().start().resume();
        TestHome.advance(60_000);
        assertEquals(0, trims.size());
    }

    @Test
    public void leftFromABrowsePageNoneIsMade() throws Exception {
        TestHome h = TestHome.withTmdb();
        HomeActivity.gpuTrimmer = trims::add;
        h.menu();
        h.press(KeyEvent.KEYCODE_DPAD_RIGHT);
        h.waitForPage(HomeActivity.MOVIES);
        assertEquals(HomeActivity.MOVIES, h.activity.page());
        h.controller.pause().stop();
        TestHome.advance(HomeActivity.GPU_TRIM_AFTER_MS + 5000);
        assertEquals(0, trims.size());
    }

    /** The Home button's cover up for the trip back to Glass Home: nothing is taken off the GPU under it. */
    @Test
    public void duringATripBackNoneIsMade() throws Exception {
        HomeButtonService.forgetTrips();
        TestHome h = trimmed();
        TestHome.advance(3000);   // Home at rest: its snapshot, which the cover shows, is copied
        TestHome.settle();
        assertNotNull(HomeSnapshot.latest());
        ShadowSystemClock.advanceBy(Duration.ofMillis(HomeButtonService.BOOT_MS + 1000));
        HomeButtonService service = Robolectric.setupService(HomeButtonService.class);
        service.onServiceConnected();
        try {
            h.controller.pause().stop();
            TestHome.advance(HomeActivity.GPU_TRIM_AFTER_MS - 1000);
            HomeButtonService.losingTop(true);   // the cover goes up for a trip back
            assertTrue(HomeButtonService.tripInProgress());
            TestHome.advance(1500);
            assertEquals(0, trims.size());
        } finally {
            HomeButtonService.glassHomeResumed();
            HomeButtonService.forgetTrips();
            service.onUnbind(new Intent());
        }
    }

    /**
     * Behind a film, once it has played a minute, the GPU and the pictures in memory are let go of, once; when it
     * stops, the way back's pictures go back on the GPU.
     */
    @Test
    public void behindAFilmTheGpuIsLetGoAndPutBackWhenItStops() throws Exception {
        TestHome h = trimmed();
        boolean[] playing = {true};
        AtomicInteger letGo = new AtomicInteger();
        HomeActivity.playingProbe = () -> playing[0];
        HomeActivity.playbackTrimmer = letGo::incrementAndGet;
        h.controller.pause().stop();
        TestHome.advance(HomeActivity.GPU_TRIM_AFTER_MS + 1000);
        assertEquals("the usual trim", 1, trims.size());
        assertEquals("not before a minute of playing", 0, letGo.get());
        TestHome.advance(HomeActivity.PLAYING_TRIM_AFTER_MS + HomeActivity.PLAYING_CHECK_MS);
        assertEquals(1, letGo.get());
        TestHome.advance(10 * 60 * 1000L);
        assertEquals("once", 1, letGo.get());
        assertEquals(1, trims.size());
        playing[0] = false;
        TestHome.advance(HomeActivity.PLAYING_CHECK_MS + 1000);
        assertEquals("the way back's pictures go back", 2, trims.size());
        assertEquals(1, letGo.get());
    }

    @Test
    public void withNothingPlayingNothingMoreIsLetGo() throws Exception {
        TestHome h = trimmed();
        AtomicInteger letGo = new AtomicInteger();
        HomeActivity.playingProbe = () -> false;
        HomeActivity.playbackTrimmer = letGo::incrementAndGet;
        h.controller.pause().stop();
        TestHome.advance(10 * 60 * 1000L);
        assertEquals(0, letGo.get());
        assertEquals(1, trims.size());
    }

    @Test
    public void backBeforeAMinuteOfPlayingNothingMoreIsLetGo() throws Exception {
        TestHome h = trimmed();
        AtomicInteger letGo = new AtomicInteger();
        HomeActivity.playingProbe = () -> true;
        HomeActivity.playbackTrimmer = letGo::incrementAndGet;
        h.controller.pause().stop();
        TestHome.advance(HomeActivity.PLAYING_CHECK_MS + 5000);
        h.controller.restart().start().resume();
        TestHome.advance(10 * 60 * 1000L);
        assertEquals(0, letGo.get());
    }

    @Test
    public void destroyedBeforeTheTrimNothingBreaks() throws Exception {
        TestHome h = trimmed();
        h.controller.pause().stop().destroy();
        TestHome.advance(HomeActivity.GPU_TRIM_AFTER_MS + 5000);
        assertEquals(0, trims.size());
    }
}
