package app.glasshome.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

/**
 * Arrows pressed on the way back to Glass Home, before its window has focus (Android drops those), are kept by the
 * Home button service and played once focus comes. Nothing else is kept, and nothing once the window has had focus.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, qualifiers = "w960dp-h540dp-land-xhdpi")
public class TypeAheadTest {

    private static KeyEvent key(int action, int code, long down) {
        return new KeyEvent(down, SystemClock.uptimeMillis(), action, code, 0);
    }

    private static TestHome back() throws Exception {
        TestHome h = new TestHome();
        h.controller.pause().stop();
        TestHome.advance(2000);
        h.controller.restart().start().resume();
        TestHome.idle();
        return h;
    }

    @Test
    public void arrowsBeforeFocusArePlayedWhenItComes() throws Exception {
        TestHome h = back();
        int before = h.focused();
        long t = SystemClock.uptimeMillis();
        assertTrue(HomeActivity.holdUntilFocused(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, t)));
        assertTrue("its up goes with it", HomeActivity.holdUntilFocused(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, t)));
        TestHome.settle();
        assertEquals("nothing moves before focus", before, h.focused());
        h.activity.onWindowFocusChanged(true);
        TestHome.settle();
        assertEquals("played once focus came", before + 1, h.focused());
        long t2 = SystemClock.uptimeMillis();
        assertFalse("not once the window has had focus", HomeActivity.holdUntilFocused(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, t2)));
    }

    @Test
    public void anArrowStillHeldGoesOnRepeating() throws Exception {
        TestHome h = back();
        int before = h.focused();
        long t = SystemClock.uptimeMillis();
        assertTrue(HomeActivity.holdUntilFocused(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, t)));
        h.activity.onWindowFocusChanged(true);
        TestHome.advance(Ui.KEY_REPEAT_DELAY_MS + 2 * Ui.KEY_REPEAT_MS + 50);
        TestHome.settle();
        assertTrue("on past the first step while held", h.focused() > before + 1);
        int held = h.focused();
        long u = SystemClock.uptimeMillis();
        h.activity.dispatchKeyEvent(new KeyEvent(t, u, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, 0));
        TestHome.advance(1000);
        TestHome.settle();
        assertEquals("and stops when it comes up", held, h.focused());
    }

    @Test
    public void androidsLateCopyOfAPlayedKeyIsDropped() throws Exception {
        TestHome h = back();
        int before = h.focused();
        long t = SystemClock.uptimeMillis();
        KeyEvent down = key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, t);
        KeyEvent up = key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, t);
        assertTrue(HomeActivity.holdUntilFocused(down));
        assertTrue(HomeActivity.holdUntilFocused(up));
        h.activity.onWindowFocusChanged(true);
        TestHome.settle();
        // The same press, passed on by Android as well (its wait for the service ran out)
        h.activity.dispatchKeyEvent(new KeyEvent(down));
        h.activity.dispatchKeyEvent(new KeyEvent(up));
        TestHome.settle();
        assertEquals("one step, not two", before + 1, h.focused());
    }

    @Test
    public void selectAndOtherKeysAreLeftAlone() throws Exception {
        back();
        long t = SystemClock.uptimeMillis();
        assertFalse(HomeActivity.holdUntilFocused(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, t)));
        assertFalse(HomeActivity.holdUntilFocused(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, t)));
        assertFalse("an up whose down wasn't kept", HomeActivity.holdUntilFocused(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, t)));
    }

    @Test
    public void notLongAfterComingBack() throws Exception {
        back();
        TestHome.advance(HomeActivity.TYPE_AHEAD_MS + 500);
        long t = SystemClock.uptimeMillis();
        assertFalse(HomeActivity.holdUntilFocused(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, t)));
    }

    /** Home at rest, its snapshot copied (the cover needs it), and the Home button service connected. */
    private static TestHome restedWithService() throws Exception {
        TestHome h = new TestHome();
        for (int i = 0; i < 400 && HomeSnapshot.latest() == null; i++) {
            Thread.sleep(25);
            TestHome.idle();
        }
        TestHome.advance(3000);
        TestHome.settle();
        assertTrue("the snapshot is copied", HomeSnapshot.latest() != null);
        return h;
    }

    /**
     * Home pressed on Home: the cover goes up and the trip back starts. Until Home's window has focus again (Amazon's
     * home has it meanwhile), arrows are kept and played on Home; Select is kept from Amazon's home but not played.
     */
    @Test
    public void keysOnATripOnItsWayAreKeptForGlassHome() throws Exception {
        HomeButtonService.forgetTrips();
        TestHome h = restedWithService();
        HomeButtonService service = Robolectric.setupService(HomeButtonService.class);
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(HomeButtonService.BOOT_MS + 1000));
        service.onServiceConnected();
        try {
            h.controller.pause();
            assertTrue("the trip is on its way, under the cover", HomeButtonService.keysForGlassHome());
            long t = SystemClock.uptimeMillis();
            assertTrue(HomeActivity.holdUntilFocused(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, t)));
            assertTrue(HomeActivity.holdUntilFocused(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, t)));
            long s = SystemClock.uptimeMillis();
            assertTrue("Select kept from Amazon's home", HomeActivity.holdUntilFocused(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, s)));
            assertTrue(HomeActivity.holdUntilFocused(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, s)));
            assertFalse("not Back", HomeActivity.holdUntilFocused(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, s)));
            h.controller.resume();
            TestHome.idle();
            int before = h.focused();
            h.activity.onWindowFocusChanged(true);
            TestHome.settle();
            assertEquals("the arrow is played, the Select isn't", before + 1, h.focused());
            assertNull("nothing opened", shadowOf(RuntimeEnvironment.getApplication()).getNextStartedActivity());
            assertFalse("the trip is over once Home has focus", HomeButtonService.tripOnItsWay());
        } finally {
            HomeButtonService.tripLanded();
            service.onUnbind(new Intent());
        }
    }

    /** Another window comes up instead (an app opened by voice, the quick menu): its keys are its own. */
    @Test
    public void aTripOvertakenKeepsNothing() throws Exception {
        HomeButtonService.forgetTrips();
        TestHome h = restedWithService();
        HomeButtonService service = Robolectric.setupService(HomeButtonService.class);
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(HomeButtonService.BOOT_MS + 1000));
        service.onServiceConnected();
        try {
            h.controller.pause();
            long t = SystemClock.uptimeMillis();
            assertTrue(HomeActivity.holdUntilFocused(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, t)));
            AccessibilityEvent e = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
            e.setPackageName("com.netflix.ninja");
            e.setClassName("com.netflix.ninja.MainActivity");
            service.onAccessibilityEvent(e);
            assertFalse(HomeButtonService.keysForGlassHome());
            long u = SystemClock.uptimeMillis();
            assertFalse(HomeActivity.holdUntilFocused(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, u)));
        } finally {
            HomeButtonService.tripLanded();
            service.onUnbind(new Intent());
        }
    }

    /** Without a trip on its way, nothing is kept while Home is out of sight. */
    @Test
    public void nothingIsKeptBehindAnotherAppWithoutATrip() throws Exception {
        TestHome h = new TestHome();
        h.controller.pause().stop();
        HomeButtonService.tripLanded();
        long t = SystemClock.uptimeMillis();
        assertFalse(HomeActivity.holdUntilFocused(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, t)));
    }

    @Test
    public void notWhileAnotherWindowIsOverHome() throws Exception {
        TestHome h = back();
        h.activity.onTopResumedActivityChanged(false);   // the quick menu, Alexa
        long t = SystemClock.uptimeMillis();
        assertFalse(HomeActivity.holdUntilFocused(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, t)));
    }
}
