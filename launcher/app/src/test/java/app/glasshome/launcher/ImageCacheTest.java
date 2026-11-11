package app.glasshome.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Covers come in on their own: one that fails (the TV's DNS through Tailscale drops out now and
 * then) is tried again a few seconds later, then less and less often, for the view still waiting
 * for it, without anything on screen having to ask again, and the others follow at once when one
 * comes in. Nothing can leave a cover loading for good, two sizes of one picture load side by side,
 * and covers being drawn go before those that failed before, then the ones fetched ahead of a scroll.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30)
public class ImageCacheTest {
    private static final String BASE = "https://image.tmdb.org/t/p/w780/";
    private static final String URL = BASE + "cover.jpg";
    private static final byte[] COVER = picture(400, 225);

    private Net.Fetcher saved;
    private ImageCache cache;
    private final AtomicInteger fetches = new AtomicInteger();
    private int arrivals;
    // Kept in a field, as the views keep theirs
    private final Runnable arrived = () -> arrivals++;

    @Before
    public void setUp() {
        saved = Net.fetcher;
        ImageCache.reset();
        cache = ImageCache.get(RuntimeEnvironment.getApplication());
    }

    @After
    public void tearDown() {
        Net.fetcher = saved;
        ImageCache.reset();
    }

    @Test
    public void aCoverThatFailedComesInOnItsOwn() {
        Net.fetcher = (url, bearer) -> {
            if (fetches.incrementAndGet() == 1) throw new UnknownHostException("image.tmdb.org");
            return COVER;
        };
        assertNull(cache.get(URL, 300, false, arrived));
        finish();
        assertEquals(1, fetches.get());
        assertEquals(0, arrivals);
        assertEquals("waiting to try again is not loading: screenshots don't wait for it", 0, cache.pending());

        after(5_000);   // nothing asks for it again
        assertEquals("the waiting view hears it came in", 1, arrivals);
        assertNotNull(cache.peek(URL, 300));
        assertEquals("tried once more, not over and over", 2, fetches.get());
    }

    @Test
    public void triesAgainLessOftenThenOnlyNowAndThen() {
        Net.fetcher = (url, bearer) -> {
            fetches.incrementAndGet();
            throw new IOException("offline");
        };
        cache.get(URL, 300, false, arrived);
        finish();
        after(1_000);
        assertEquals("not straight away", 1, fetches.get());
        after(60_000);
        int inAMinute = fetches.get();
        assertTrue("tries again a few times in the first minute: " + inAMinute, inAMinute >= 4);
        assertTrue("but slows down: " + inAMinute, inAMinute <= 7);

        after(60 * 60_000);
        int inAnHour = fetches.get();
        assertTrue("an hour offline is a bounded number of tries: " + inAnHour, inAnHour <= 25);
        after(60 * 60_000);
        int nextHour = fetches.get() - inAnHour;
        assertTrue("then only now and then: " + nextHour, nextHour >= 1 && nextHour <= 7);

        // Back online: Clear Cache tries again now for the view still showing it
        Net.fetcher = (url, bearer) -> COVER;
        cache.clearMemory();
        finish();
        assertEquals(1, arrivals);
    }

    @Test
    public void aCoverThatRestedIsTriedAgainWhenDrawnAgain() {
        Net.fetcher = (url, bearer) -> {
            fetches.incrementAndGet();
            throw new IOException("offline");
        };
        cache.get(URL, 300, false, arrived);
        finish();
        after(2 * 60 * 60_000);
        int tries = fetches.get();
        assertTrue("it slowed right down: " + tries, tries <= 30);
        Net.fetcher = (url, bearer) -> {
            fetches.incrementAndGet();
            return COVER;
        };
        assertNull(cache.get(URL, 300, false, arrived));   // the shelf is drawn again
        after(5_000);
        assertEquals(1, arrivals);
        assertEquals(tries + 1, fetches.get());
    }

    @Test
    public void clearCacheTriesWhatFailedAgainAtOnce() {
        Net.fetcher = (url, bearer) -> {
            if (fetches.incrementAndGet() == 1) throw new UnknownHostException("image.tmdb.org");
            return COVER;
        };
        cache.get(URL, 300, false, arrived);
        finish();
        assertEquals(0, arrivals);
        cache.clearMemory();   // Settings > Clear Cache, before its time to try again
        finish();
        assertEquals("the cover still showing came in, without being drawn again", 1, arrivals);
        after(10_000);
        assertEquals("and the try it was waiting for is not made as well", 2, fetches.get());
    }

    @Test
    public void aPictureTheServerSaysIsMissingIsNotAskedForOverAndOver() {
        Net.fetcher = (url, bearer) -> {
            fetches.incrementAndGet();
            throw new Net.HttpError(404, "image.tmdb.org");
        };
        cache.get(URL, 300, false, arrived);
        finish();
        after(10 * 60_000);
        assertEquals(1, fetches.get());
        assertEquals("nothing is left loading", 0, cache.pending());
    }

    @Test
    public void aPrefetchThatFailedIsTriedAgainOnceDrawn() {
        Net.fetcher = (url, bearer) -> {
            if (fetches.incrementAndGet() == 1) throw new IOException("offline");
            return COVER;
        };
        cache.get(URL, 300, false, null);   // fetched ahead of a scroll
        finish();
        assertNull("not hammered by every draw", cache.get(URL, 300, false, null));
        finish();
        assertEquals(1, fetches.get());
        assertNull(cache.get(URL, 300, false, arrived));   // scrolled in
        after(5_000);
        assertEquals(1, arrivals);
    }

    @Test
    public void anErrorWhileLoadingDoesNotLeaveItLoadingForGood() {
        Net.fetcher = (url, bearer) -> {
            if (fetches.incrementAndGet() == 1) throw new OutOfMemoryError("no room to decode");
            return COVER;
        };
        cache.get(URL, 300, false, arrived);
        finish();
        assertEquals("the load ended", 0, cache.pending());
        after(5_000);
        assertEquals(1, arrivals);
    }

    @Test
    public void twoSizesOfOnePictureLoadSideBySide() {
        // Large enough that the two downloads are still writing when they meet
        byte[] big = Arrays.copyOf(COVER, COVER.length + 8 * 1024 * 1024);
        CyclicBarrier together = new CyclicBarrier(2);
        Net.fetcher = (url, bearer) -> {
            fetches.incrementAndGet();
            try {
                together.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IOException(e);
            }
            return big;
        };
        AtomicInteger small = new AtomicInteger(), large = new AtomicInteger();
        Runnable smallIn = small::incrementAndGet, largeIn = large::incrementAndGet;
        cache.get(URL, 200, false, smallIn);
        cache.get(URL, 400, false, largeIn);
        finish();
        assertEquals(2, fetches.get());
        assertEquals("the small one came in first time", 1, small.get());
        assertEquals("the large one came in first time", 1, large.get());
        assertNotNull(cache.peek(URL, 200));
        assertNotNull(cache.peek(URL, 400));
    }

    @Test
    public void onePictureWrittenTwiceAtOnceStaysWhole() throws Exception {
        File file = new File(RuntimeEnvironment.getApplication().getCacheDir(), "twice");
        byte[] body = new byte[8 * 1024 * 1024];
        for (int i = 0; i < body.length; i++) body[i] = (byte) (i * 31);
        for (int round = 0; round < 5; round++) {
            CyclicBarrier together = new CyclicBarrier(2);
            List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
            Thread[] writers = new Thread[2];
            for (int w = 0; w < 2; w++) {
                writers[w] = new Thread(() -> {
                    try {
                        together.await(5, TimeUnit.SECONDS);
                        Net.write(file, body);
                    } catch (Throwable t) {
                        failures.add(t);
                    }
                });
                writers[w].start();
            }
            for (Thread t : writers) t.join(10_000);
            assertEquals("round " + round + ": " + failures, 0, failures.size());
            assertTrue(Arrays.equals(body, Files.readAllBytes(file.toPath())));
        }
    }

    @Test
    public void theFolderEmptiedUnderItStillLoads() {
        // Fire OS can empty an app's cache, folders and all, while it runs (storage running low)
        Net.fetcher = (url, bearer) -> COVER;
        File dir = new File(RuntimeEnvironment.getApplication().getCacheDir(), "img");
        finish();
        deleteAll(dir);
        cache.get(URL, 300, false, arrived);
        finish();
        assertEquals(1, arrivals);
    }

    @Test
    public void coversBeingDrawnGoBeforeTheOnesFetchedAhead() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        Net.fetcher = (url, bearer) -> {
            order.add(url);
            if (url.contains("busy")) {
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
            }
            return COVER;
        };
        String base = "https://image.tmdb.org/t/p/w780/";
        for (int i = 0; i < 3; i++) cache.get(base + "busy" + i, 300, false, arrived);
        for (int i = 0; i < 200 && order.size() < 3; i++) Thread.sleep(10);
        assertEquals("every loader is busy", 3, order.size());
        for (int i = 0; i < 6; i++) cache.get(base + "ahead" + i, 300, false, null);
        cache.get(base + "shown", 300, false, arrived);
        cache.get(base + "ahead5", 300, false, arrived);   // scrolled in while still waiting
        release.countDown();
        finish();
        assertEquals(order.toString(), 10, order.size());
        int third = order.indexOf(base + "ahead3");
        assertTrue("the cover on screen goes first: " + order, order.indexOf(base + "shown") < third);
        assertTrue("so does one drawn since it was asked for ahead: " + order, order.indexOf(base + "ahead5") < third);
        assertEquals(5, arrivals);
    }

    @Test
    public void aViewLongGoneIsNotKeptForever() throws Exception {
        Net.fetcher = (url, bearer) -> {
            fetches.incrementAndGet();
            throw new IOException("offline");
        };
        Runnable gone = () -> arrivals++;
        WeakReference<Runnable> ref = new WeakReference<>(gone);
        cache.get(URL, 300, false, gone);
        gone = null;
        finish();
        after(2 * 60 * 60_000);
        for (int i = 0; i < 20 && ref.get() != null; i++) {
            System.gc();
            Thread.sleep(20);
        }
        assertNull("once it slows down, it lets go of the view", ref.get());
        int tries = fetches.get();
        after(2 * 60 * 60_000);
        assertEquals("and stops trying for it", tries, fetches.get());
    }

    @Test
    public void coversThatFailedComeInAsSoonAsAnotherDoes() {
        AtomicBoolean online = new AtomicBoolean();
        Net.fetcher = (url, bearer) -> {
            fetches.incrementAndGet();
            if (!online.get()) throw new UnknownHostException("image.tmdb.org");
            return COVER;
        };
        // A shelf of covers drawn while DNS is out
        for (int i = 0; i < 12; i++) cache.get(BASE + "shelf" + i + ".jpg", 300, false, arrived);
        finish();
        after(3 * 60_000);   // tried a few times: the next tries are a minute or so away
        assertEquals(0, arrivals);
        online.set(true);   // DNS is back
        cache.get(BASE + "new.jpg", 300, false, arrived);   // a cover scrolled into view comes straight in...
        finish();   // (the clock doesn't move)
        assertEquals("...and so do the ones that failed, without waiting out their time", 13, arrivals);
    }

    @Test
    public void aCoverFromDiskIsNoSignTheNetworkIsBack() throws IOException {
        Net.fetcher = (url, bearer) -> {
            fetches.incrementAndGet();
            throw new UnknownHostException("image.tmdb.org");
        };
        cache.get(URL, 300, false, arrived);
        finish();
        after(3 * 60_000);
        int tries = fetches.get();
        // Another cover comes in from disk, without the network
        Net.write(new File(new File(RuntimeEnvironment.getApplication().getCacheDir(), "img"), Net.sha1(BASE + "kept.jpg")), COVER);
        cache.get(BASE + "kept.jpg", 300, false, arrived);
        finish();
        assertEquals(1, arrivals);
        assertEquals("the one that failed still waits its time", tries, fetches.get());
    }

    @Test
    public void aCoverComesInAfterALongOutageWithoutBeingDrawnAgain() {
        AtomicBoolean online = new AtomicBoolean();
        Net.fetcher = (url, bearer) -> {
            fetches.incrementAndGet();
            if (!online.get()) throw new UnknownHostException("image.tmdb.org");
            return COVER;
        };
        cache.get(URL, 300, false, arrived);
        finish();
        after(40 * 60_000);   // the router restarting, the internet out a while
        assertEquals(0, arrivals);
        online.set(true);
        after(20 * 60_000);   // nobody touches the remote, so nothing is drawn again
        assertEquals("the cover still came in", 1, arrivals);
    }

    @Test
    public void aCoverTriedAgainGoesAfterOnesAskedForTheFirstTime() throws Exception {
        CountDownLatch first = new CountDownLatch(1), rest = new CountDownLatch(1);
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        Net.fetcher = (url, bearer) -> {
            order.add(url);
            try {
                if (url.endsWith("busy0")) first.await(5, TimeUnit.SECONDS);
                else if (url.contains("busy")) rest.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new IOException(e);
            }
            if (url.contains("failed") && fetches.incrementAndGet() == 1) throw new UnknownHostException("image.tmdb.org");
            return COVER;
        };
        cache.get(BASE + "failed", 300, false, arrived);
        finish();   // tried again 2 s later
        for (int i = 0; i < 3; i++) cache.get(BASE + "busy" + i, 300, false, arrived);
        for (int i = 0; i < 200 && order.size() < 4; i++) Thread.sleep(10);
        assertEquals("every loader is busy", 4, order.size());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2_500));   // its time to try again comes
        cache.get(BASE + "fresh", 300, false, arrived);   // then a cover scrolled into view for the first time
        first.countDown();   // one loader comes free
        for (int i = 0; i < 200 && order.size() < 6; i++) Thread.sleep(10);
        assertEquals("the new cover goes first: " + order, Arrays.asList(BASE + "fresh", BASE + "failed"), order.subList(4, 6));
        rest.countDown();
        finish();
        assertEquals(5, arrivals);
    }

    // ---- Helpers ------------------------------------------------------------------------------

    private static byte[] picture(int w, int h) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        new Canvas(b).drawColor(0xFF3A5A78);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 90, out);
        return out.toByteArray();
    }

    /** Lets the loads under way finish and report back, without moving the clock on. */
    private void finish() {
        for (int i = 0; i < 500; i++) {
            shadowOf(Looper.getMainLooper()).idle();
            if (cache.pending() == 0) break;
            sleep(10);
        }
        shadowOf(Looper.getMainLooper()).idle();
    }

    /** Moves the clock on by {@code ms}, a little at a time, letting each try that comes due finish. */
    private void after(long ms) {
        long step = Math.min(ms, ms > 10 * 60_000 ? 30_000 : 250);
        for (long t = 0; t < ms; t += step) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(step));
            finish();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void deleteAll(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteAll(c);
        f.delete();
    }

    @Test
    public void nothingIsTriedAgainWhileGlassHomeIsOutOfSight() {
        Net.fetcher = (url, bearer) -> {
            fetches.incrementAndGet();
            throw new IOException("offline");
        };
        cache.get(URL, 300, false, arrived);
        finish();
        assertEquals(1, fetches.get());
        // Another app on screen for an hour: no downloads behind it
        cache.pause();
        after(60 * 60_000);
        assertEquals("nothing tried behind another app", 1, fetches.get());
        // Back to Glass Home, the network back too: tried at once
        Net.fetcher = (url, bearer) -> {
            fetches.incrementAndGet();
            return COVER;
        };
        cache.resume();
        finish();
        assertEquals(2, fetches.get());
        assertEquals("the waiting view gets it", 1, arrivals);
    }
}
