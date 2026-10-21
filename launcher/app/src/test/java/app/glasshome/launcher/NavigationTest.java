package app.glasshome.launcher;

import static android.view.KeyEvent.KEYCODE_DPAD_CENTER;
import static android.view.KeyEvent.KEYCODE_DPAD_DOWN;
import static android.view.KeyEvent.KEYCODE_DPAD_LEFT;
import static android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
import static android.view.KeyEvent.KEYCODE_DPAD_UP;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.view.KeyEvent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowActivity;

import app.glasshome.launcher.HomeActivity.Zone;

/**
 * Remote navigation on the real HomeActivity. 13 apps: rows of 6, 6 and 1
 * (0 FirePlayer … 5 Max | 6 TiviMate, 7 Prime Video, 8 Spotify, 9 Game Pass, 10 Downloader, 11 Tailscale | 12 Settings).
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, qualifiers = "w960dp-h540dp-land-xhdpi")
public class NavigationTest {

    @Test
    public void gridMovesAdjacentAndSameColumnWithoutWrapping() throws Exception {
        TestHome h = new TestHome();
        assertEquals(13, h.grid.getAdapter().getItemCount());
        assertEquals(0, h.focused());
        assertEquals(Zone.GRID, h.zone());

        h.press(KEYCODE_DPAD_LEFT);
        assertEquals("left edge stops", 0, h.focused());
        for (int i = 1; i <= 5; i++) {
            h.press(KEYCODE_DPAD_RIGHT);
            assertEquals(i, h.focused());
        }
        h.press(KEYCODE_DPAD_RIGHT);
        assertEquals("right edge stops, no wrap to next row", 5, h.focused());

        h.press(KEYCODE_DPAD_DOWN);
        assertEquals("same column", 11, h.focused());
        assertEquals("focused row settles in the dock", 801, h.focusedRowTop());
        h.press(KEYCODE_DPAD_DOWN);
        assertEquals("shorter last row: nearest tile", 12, h.focused());
        h.press(KEYCODE_DPAD_DOWN);
        assertEquals("bottom edge stops", 12, h.focused());
        h.press(KEYCODE_DPAD_RIGHT);
        assertEquals("end of a short row stops", 12, h.focused());

        h.press(KEYCODE_DPAD_UP);
        assertEquals(6, h.focused());
        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_RIGHT);
        assertEquals(8, h.focused());
        h.press(KEYCODE_DPAD_UP);
        assertEquals("same column going up", 2, h.focused());
        assertEquals(801, h.focusedRowTop());
    }

    @Test
    public void upFromTopRowGoesToWeatherWhenHeroHasNoAction() throws Exception {
        TestHome h = new TestHome();
        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_RIGHT);
        assertFalse(h.stage.hasAction());
        h.press(KEYCODE_DPAD_UP);
        assertEquals(Zone.WEATHER, h.zone());
        assertTrue(h.weather.isFocused());
        h.press(KEYCODE_DPAD_LEFT);
        assertEquals("no hero action to go to", Zone.WEATHER, h.zone());
        h.press(KEYCODE_DPAD_RIGHT);
        assertEquals("right edge of the screen", Zone.WEATHER, h.zone());
        h.press(KEYCODE_DPAD_UP);
        assertEquals("up to the profile capsule", Zone.CAPSULE, h.zone());
        h.press(KEYCODE_DPAD_DOWN);
        assertEquals(Zone.WEATHER, h.zone());
        h.press(KEYCODE_DPAD_DOWN);
        assertEquals(Zone.GRID, h.zone());
        assertEquals("back to the app it came from", 2, h.focused());
    }

    @Test
    public void heroWithActionIsReachableAndLinksToWeather() throws Exception {
        TestHome h = new TestHome();
        h.press(KEYCODE_DPAD_RIGHT);            // Netflix
        TestHome.advance(Ui.HERO_DWELL_MS + 50);
        h.injectFeaturedSlide("com.netflix.ninja", "Netflix");
        TestHome.settle();
        assertTrue(h.stage.hasAction());

        h.press(KEYCODE_DPAD_UP);
        assertEquals(Zone.HERO, h.zone());
        h.press(KEYCODE_DPAD_RIGHT);
        assertEquals(Zone.WEATHER, h.zone());
        h.press(KEYCODE_DPAD_LEFT);
        assertEquals(Zone.HERO, h.zone());
        h.press(KEYCODE_DPAD_DOWN);
        assertEquals(Zone.GRID, h.zone());
        assertEquals(1, h.focused());

        // Select on the hero opens the presented app, once
        h.press(KEYCODE_DPAD_UP);
        h.press(KEYCODE_DPAD_CENTER);
        ShadowActivity sa = shadowOf(h.activity);
        Intent started = sa.getNextStartedActivity();
        assertNotNull(started);
        assertEquals("com.netflix.ninja", started.getComponent().getPackageName());
        assertNull(sa.getNextStartedActivity());
    }

    @Test
    public void weatherOpensDetailAndBackRestoresFocus() throws Exception {
        TestHome h = new TestHome();
        h.loadSampleWeather();
        h.press(KEYCODE_DPAD_UP);
        assertEquals(Zone.WEATHER, h.zone());
        h.press(KEYCODE_DPAD_CENTER);
        assertEquals(Zone.OVERLAY, h.zone());
        assertTrue(h.detail.isFocused());
        for (int code : new int[] {KEYCODE_DPAD_LEFT, KEYCODE_DPAD_RIGHT, KEYCODE_DPAD_UP, KEYCODE_DPAD_DOWN}) {
            h.press(code);
            assertEquals("arrows stay inside the overlay", Zone.OVERLAY, h.zone());
        }
        h.back();
        assertEquals("Back closes the overlay", Zone.WEATHER, h.zone());
        assertTrue(h.weather.isFocused());
        h.back();
        assertEquals("Back leaves the weather card", Zone.GRID, h.zone());
        assertEquals(0, h.focused());
    }

    @Test
    public void backUnwindsFromLowerRowsThenLeavesAtRoot() throws Exception {
        TestHome h = new TestHome();
        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_DOWN);
        assertEquals(12, h.focused());
        h.back();
        assertEquals("Back returns to the first row, same column as before", 2, h.focused());
        assertFalse(h.activity.isFinishing());
        h.back();
        assertTrue("at the root, Back behaves as for any app (this test build is not the default home)", h.activity.isFinishing());
    }

    @Test
    public void holdingADirectionStepsAtAControlledRate() throws Exception {
        TestHome h = new TestHome();
        // Repeats every 50 ms for 500 ms: the press, then steps at 300 and 450 ms only
        h.hold(KEYCODE_DPAD_RIGHT, 500, 50);
        assertEquals(3, h.focused());
        // A long hold glides to the end of the row and stops there
        h.hold(KEYCODE_DPAD_RIGHT, 3000, 33);
        assertEquals(5, h.focused());
        h.hold(KEYCODE_DPAD_DOWN, 3000, 33);
        assertEquals(12, h.focused());
    }

    @Test
    public void selectActivatesOncePerPressEvenWhenHeld() throws Exception {
        TestHome h = new TestHome();
        h.press(KEYCODE_DPAD_RIGHT);
        h.hold(KEYCODE_DPAD_CENTER, 1500, 50);
        ShadowActivity sa = shadowOf(h.activity);
        Intent started = sa.getNextStartedActivity();
        assertNotNull(started);
        assertEquals("com.netflix.ninja", started.getComponent().getPackageName());
        assertNull("one launch per press", sa.getNextStartedActivity());
        // A select whose press was cancelled does nothing
        long t = SystemClock.uptimeMillis();
        h.activity.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KEYCODE_DPAD_CENTER, 0));
        h.activity.dispatchKeyEvent(KeyEvent.changeFlags(new KeyEvent(t, t + 30, KeyEvent.ACTION_UP, KEYCODE_DPAD_CENTER, 0),
                KeyEvent.FLAG_CANCELED));
        assertNull(sa.getNextStartedActivity());
    }

    @Test
    public void focusAndScrollAreRestoredAfterReturningFromAnApp() throws Exception {
        TestHome h = new TestHome();
        h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_RIGHT);
        assertEquals(8, h.focused());
        h.press(KEYCODE_DPAD_CENTER);
        assertNotNull(shadowOf(h.activity).getNextStartedActivity());
        h.controller.pause().stop();
        h.grid.clearFocus();
        h.controller.restart().start().resume();
        TestHome.settle();
        assertEquals(8, h.focused());
        assertEquals("row still in the dock", 801, h.focusedRowTop());
    }

    @Test
    public void heroFollowsFocusOnlyAfterItRests() throws Exception {
        TestHome h = new TestHome();
        assertEquals("app.fireplayer", h.stage.current().appKey);
        long t = SystemClock.uptimeMillis();
        h.activity.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KEYCODE_DPAD_RIGHT, 0));
        h.activity.dispatchKeyEvent(new KeyEvent(t, t + 30, KeyEvent.ACTION_UP, KEYCODE_DPAD_RIGHT, 0));
        TestHome.advance(200);
        assertEquals("not yet: still the previous app", "app.fireplayer", h.stage.current().appKey);
        h.activity.dispatchKeyEvent(new KeyEvent(t + 250, t + 250, KeyEvent.ACTION_DOWN, KEYCODE_DPAD_RIGHT, 0));
        h.activity.dispatchKeyEvent(new KeyEvent(t + 250, t + 280, KeyEvent.ACTION_UP, KEYCODE_DPAD_RIGHT, 0));
        TestHome.advance(300);
        assertEquals("the Netflix update was cancelled", "app.fireplayer", h.stage.current().appKey);
        TestHome.advance(200);
        assertEquals("com.amazon.firetv.youtube", h.stage.current().appKey);
    }

    @Test
    public void focusMovesToNearestAppWhenAppsDisappear() throws Exception {
        TestHome h = new TestHome();
        h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_RIGHT);
        assertEquals(8, h.focused());   // Spotify
        uninstall(h, "com.spotify.tv.android");
        assertEquals(12, h.grid.getAdapter().getItemCount());
        assertEquals("nearest: Game Pass slides into Spotify's place", 8, h.focused());

        h.press(KEYCODE_DPAD_DOWN);
        assertEquals("12 apps fill two rows exactly: no row below", 8, h.focused());
        for (int i = 0; i < 3; i++) h.press(KEYCODE_DPAD_RIGHT);
        assertEquals(11, h.focused());  // Settings, end of the second row
        uninstall(h, "com.tailscale.ipn");
        assertEquals("still on Settings, which moved into Tailscale's place", 10, h.focused());
        assertEquals(801, h.focusedRowTop());
    }

    @Test
    public void focusMovesToNearestWhenARowGetsShorter() throws Exception {
        TestHome h = new TestHome();
        for (int i = 0; i < 5; i++) h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_DOWN);
        assertEquals(11, h.focused());  // Tailscale, last tile of row 2
        uninstall(h, "com.tailscale.ipn");
        // Settings moves up into row 2; focus stays on the nearest valid tile at the end of that row
        assertEquals(11, h.focused());
        assertEquals(12, h.grid.getAdapter().getItemCount());
        h.press(KEYCODE_DPAD_DOWN);
        assertEquals("no third row any more", 11, h.focused());
    }

    /** Home belongs to Fire OS: the launcher neither consumes the key nor registers as a home screen. */
    @Test
    public void homeButtonIsLeftToTheSystem() throws Exception {
        TestHome h = new TestHome();
        h.press(KEYCODE_DPAD_RIGHT);
        long t = SystemClock.uptimeMillis();
        assertFalse("Home key down not consumed", h.activity.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME, 0)));
        assertFalse("Home key up not consumed", h.activity.dispatchKeyEvent(new KeyEvent(t, t + 30, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME, 0)));
        TestHome.settle();
        assertEquals("nothing moved", 1, h.focused());
        assertFalse(h.activity.isFinishing());
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(h.activity.getPackageName());
        assertTrue("not registered as a home screen", h.activity.getPackageManager().queryIntentActivities(home, 0).isEmpty());
    }

    private static void uninstall(TestHome h, String pkg) throws Exception {
        h.pm.removeResolveInfosForIntent(TestHome.LEANBACK, pkg);
        h.activity.sendBroadcast(new Intent(Intent.ACTION_PACKAGE_REMOVED, Uri.parse("package:" + pkg)));
        for (int i = 0; i < 40; i++) {
            Thread.sleep(25);
            TestHome.idle();
        }
        TestHome.settle();
    }
}
