package app.glasshome.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;

/**
 * The Home button's cover shows the time as it is now: each minute, Home's snapshot gets the clock
 * and the capsule drawn again where they are, and nothing else of it changes.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, qualifiers = "w960dp-h540dp-land-xhdpi")
public class CoverTimeTest {
    private static final long MINUTE = 60_000L;

    @Before
    public void start() {
        HomeSnapshot.forget();
        HomeButtonService.forgetTrips();
    }

    @After
    public void stop() {
        HomeSnapshot.forget();
    }

    /** Home at rest, with its background up and its snapshot copied. */
    private static TestHome rested() throws Exception {
        TestHome h = new TestHome();
        for (int i = 0; i < 400 && (h.stage.current() == null || h.activity.homeBackdrop() == null); i++) {
            Thread.sleep(25);
            TestHome.idle();
        }
        TestHome.advance(3000);
        TestHome.settle();
        assertNotNull("the snapshot is copied", HomeSnapshot.latest());
        return h;
    }

    private static void tick(TestHome h, long at) {
        Clock.fixedMillis = at;
        h.activity.sendBroadcast(new Intent(Intent.ACTION_TIME_TICK));
        TestHome.idle();
    }

    /** What Home's window shows now, drawn afresh. */
    private static Bitmap render(TestHome h) {
        View root = h.activity.getWindow().getDecorView();
        Bitmap b = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        return b;
    }

    private static Rect box(View v) {
        int[] at = new int[2];
        v.getLocationInWindow(at);
        return new Rect(at[0], at[1], at[0] + v.getWidth(), at[1] + v.getHeight());
    }

    private static HomeClock clock(TestHome h) {
        return TestHome.find(h.activity.getWindow().getDecorView(), HomeClock.class);
    }

    /** Pixels of {@code a} and {@code b} that differ inside {@code boxes} or, {@code inside} false, outside all of them. */
    private static int differing(Bitmap a, Bitmap b, boolean inside, Rect... boxes) {
        int n = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                boolean in = false;
                for (Rect r : boxes) in |= r.contains(x, y);
                if (in == inside && a.getPixel(x, y) != b.getPixel(x, y)) n++;
            }
        }
        return n;
    }

    private static void save(Bitmap b, String name) throws Exception {
        File dir = new File(System.getProperty("screenshot.dir", "screenshots"));
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        System.out.println("SCREENSHOT " + new File(dir, name));
    }

    /**
     * Each copy of Home for the cover is made without the time: the clock's place shows the stage,
     * the capsule its glass without the time, and nothing else changes. A minute turning later draws
     * nothing into it, so an hour behind another app the cover still shows no time, never an old one.
     */
    @Test
    public void theSnapshotHasNoTime() throws Exception {
        TestHome h = rested();
        Rect clockBox = box(clock(h)), capsuleBox = box(h.capsule);
        assertEquals("7:30", clock(h).time());
        // Behind another app: the time comes out of the snapshot, the rest stays as copied
        Bitmap live = render(h);
        h.controller.pause().stop();
        TestHome.idle();
        Bitmap shot = HomeSnapshot.latest();
        save(shot, "cover-time-blank.png");
        assertTrue("the cover has no clock digits where Home shows them", differing(shot, live, true, clockBox) > 1000);
        assertTrue("nor the capsule's time", differing(shot, live, true, capsuleBox) > 100);

        // An hour on: nothing is drawn into it, so it still shows no time, never an old one
        Bitmap blank = shot.copy(Bitmap.Config.ARGB_8888, false);
        int then = HomeSnapshot.retimes;
        tick(h, Fixtures.RECORDED_AT + 61 * MINUTE);
        assertEquals("8:31", clock(h).time());
        assertEquals(then, HomeSnapshot.retimes);
        assertEquals(0, differing(blank, HomeSnapshot.latest(), true, clockBox, capsuleBox));
    }

    /** In front the cover (for Home pressed on Home) shows the time, kept to the minute: no blink. */
    @Test
    public void inFrontTheSnapshotKeepsTheTime() throws Exception {
        TestHome h = rested();
        Rect clockBox = box(clock(h)), capsuleBefore = box(h.capsule);
        int retimes = HomeSnapshot.retimes;
        tick(h, Fixtures.RECORDED_AT + 31 * MINUTE);
        assertEquals("8:01", clock(h).time());
        assertEquals(retimes + 1, HomeSnapshot.retimes);
        Rect capsuleBox = new Rect(capsuleBefore);
        capsuleBox.union(box(h.capsule));
        Bitmap after = HomeSnapshot.latest(), fresh = render(h);
        assertEquals(0, differing(after, fresh, true, clockBox));
        assertEquals(0, differing(after, fresh, true, capsuleBox));
    }

    /** The cover up for a trip back: the snapshot under it is left alone. */
    @Test
    public void duringATripNothingIsDrawn() throws Exception {
        TestHome h = rested();
        ShadowSystemClock.advanceBy(Duration.ofMillis(HomeButtonService.BOOT_MS + 1000));
        HomeButtonService service = Robolectric.setupService(HomeButtonService.class);
        service.onServiceConnected();
        try {
            h.controller.pause().stop();
            TestHome.idle();
            HomeButtonService.losingTop(true);
            assertTrue(HomeButtonService.tripInProgress());
            Bitmap before = HomeSnapshot.latest().copy(Bitmap.Config.ARGB_8888, false);
            int retimes = HomeSnapshot.retimes;
            tick(h, Fixtures.RECORDED_AT + 61 * MINUTE);
            assertEquals(retimes, HomeSnapshot.retimes);
            assertTrue(before.sameAs(HomeSnapshot.latest()));
        } finally {
            HomeButtonService.glassHomeResumed();
            HomeButtonService.forgetTrips();
            service.onUnbind(new Intent());
        }
    }

    /** No snapshot yet: the minute turns and nothing breaks. */
    @Test
    public void withNoSnapshotNothingBreaks() throws Exception {
        TestHome h = new TestHome();
        HomeSnapshot.forget();
        h.controller.pause().stop();
        TestHome.idle();
        int retimes = HomeSnapshot.retimes;
        tick(h, Fixtures.RECORDED_AT + 61 * MINUTE);
        assertNull(HomeSnapshot.latest());
        assertEquals(retimes, HomeSnapshot.retimes);
        assertEquals("8:31", clock(h).time());
    }

    /** Left from a browse page, Home is not as its snapshot shows it: the snapshot is left alone. */
    @Test
    public void onABrowsePageTheOldTimeComesOutAtTheNextMinute() throws Exception {
        TestHome h = rested();
        Rect clockBox = box(clock(h)), capsuleBox = box(h.capsule);
        Bitmap withTime = HomeSnapshot.latest().copy(Bitmap.Config.ARGB_8888, false);
        h.menu();
        h.press(android.view.KeyEvent.KEYCODE_DPAD_RIGHT);
        assertNotEquals(HomeActivity.HOME, h.activity.page());
        tick(h, Fixtures.RECORDED_AT + 1 * MINUTE);
        Bitmap after = HomeSnapshot.latest();
        assertTrue("the old time came out of the clock's box", differing(withTime, after, true, clockBox) > 1000);
        assertTrue("and the capsule's", differing(withTime, after, true, capsuleBox) > 100);
        assertEquals("the rest as copied", 0, differing(withTime, after, false, clockBox, capsuleBox));
    }

    @Test
    public void leftFromABrowsePageTheTimeComesOutToo() throws Exception {
        TestHome h = rested();
        Rect clockBox = box(clock(h)), capsuleBox = box(h.capsule);
        Bitmap withTime = HomeSnapshot.latest().copy(Bitmap.Config.ARGB_8888, false);
        // Off to Movies (a Continue Watching card, say), and from there to another app
        h.menu();
        h.press(android.view.KeyEvent.KEYCODE_DPAD_RIGHT);
        assertNotEquals(HomeActivity.HOME, h.activity.page());
        h.controller.pause().stop();
        TestHome.idle();
        Bitmap left = HomeSnapshot.latest().copy(Bitmap.Config.ARGB_8888, false);
        assertTrue("the time came out of the clock's box", differing(withTime, left, true, clockBox) > 1000);
        assertTrue("and the capsule's", differing(withTime, left, true, capsuleBox) > 100);
        assertEquals("the rest of the snapshot as copied", 0, differing(withTime, left, false, clockBox, capsuleBox));
        // An hour on: nothing is drawn into it
        int retimes = HomeSnapshot.retimes;
        tick(h, Fixtures.RECORDED_AT + 61 * MINUTE);
        assertEquals(retimes, HomeSnapshot.retimes);
        assertTrue(left.sameAs(HomeSnapshot.latest()));
    }
}
