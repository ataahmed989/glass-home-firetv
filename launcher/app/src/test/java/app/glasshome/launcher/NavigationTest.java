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
import static org.junit.Assert.assertSame;
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
 * Remote navigation on the real HomeActivity. 13 apps: rows of 6, 6 and 1 (0 TiviMate, 1 Netflix,
 * 2 Prime Video, 3 Max, 4 Apple TV, 5 Disney+ | 6 FirePlayer, 7 YouTube, 8 Spotify, 9 Game Pass,
 * 10 Settings, 11 Downloader | 12 Tailscale).
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
    public void backUnwindsFromLowerRowsThenStopsAtRoot() throws Exception {
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
        assertFalse("at the root, Back stays: a home screen has nothing behind it", h.activity.isFinishing());
        assertEquals(2, h.focused());
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
        h.press(KEYCODE_DPAD_DOWN);             // FirePlayer, first in the second row
        // Held, but not long enough for the tile's menu: it opens once, on release
        h.hold(KEYCODE_DPAD_CENTER, HomeActivity.TILE_HOLD_MS - 150, 50);
        ShadowActivity sa = shadowOf(h.activity);
        Intent started = sa.getNextStartedActivity();
        assertNotNull(started);
        assertEquals("app.fireplayer", started.getComponent().getPackageName());
        assertNull("one launch per press", sa.getNextStartedActivity());
        // Held longer, the tile's menu opens instead, and nothing is launched
        h.hold(KEYCODE_DPAD_CENTER, 1500, 50);
        assertEquals(HomeActivity.Zone.TILE_MENU, h.zone());
        assertNull(sa.getNextStartedActivity());
        h.back();
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
    public void movingAcrossAppsLeavesTheBackgroundAlone() throws Exception {
        TestHome h = new TestHome();
        HeroSlide before = h.stage.current();
        assertNotNull(before);
        assertFalse("nothing about the focused app is shown", before.isFeatured());
        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_DOWN);
        TestHome.advance(Ui.HERO_DWELL_MS + Ui.CONTENT_FADE_MS + 100);
        assertSame("the same background, not redrawn", before, h.stage.current());
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
        assertEquals(11, h.focused());  // Tailscale, end of the second row
        uninstall(h, "com.esaba.downloader");
        assertEquals("still on Tailscale, which moved into Downloader's place", 10, h.focused());
        assertEquals(801, h.focusedRowTop());
    }

    @Test
    public void focusMovesToNearestWhenARowGetsShorter() throws Exception {
        TestHome h = new TestHome();
        for (int i = 0; i < 5; i++) h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_DOWN);
        assertEquals(11, h.focused());  // Downloader, last tile of row 2
        uninstall(h, "com.esaba.downloader");
        // Tailscale moves up into row 2; focus stays on the nearest valid tile at the end of that row
        assertEquals(11, h.focused());
        assertEquals(12, h.grid.getAdapter().getItemCount());
        h.press(KEYCODE_DPAD_DOWN);
        assertEquals("no third row any more", 11, h.focused());
    }

    /**
     * The Home key itself is never intercepted (Fire OS handles it, long press included); Glass Home
     * is a home screen that the system, and HomeButtonService, can bring up.
     */
    @Test
    public void homeKeyIsLeftToTheSystem() throws Exception {
        TestHome h = new TestHome();
        h.press(KEYCODE_DPAD_RIGHT);
        long t = SystemClock.uptimeMillis();
        assertFalse("Home key down not consumed", h.activity.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME, 0)));
        assertFalse("Home key up not consumed", h.activity.dispatchKeyEvent(new KeyEvent(t, t + 30, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME, 0)));
        TestHome.settle();
        assertEquals("nothing moved", 1, h.focused());
        assertFalse(h.activity.isFinishing());
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(h.activity.getPackageName());
        assertFalse("registered as a home screen", h.activity.getPackageManager().queryIntentActivities(home, 0).isEmpty());
    }

    /** What HomeButtonService does when the Home button showed Amazon's home: back to Home, everything closed. */
    @Test
    public void homeButtonReturnsToHomeWithEverythingClosed() throws Exception {
        TestHome h = new TestHome();
        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_UP);
        h.press(KEYCODE_DPAD_CENTER);
        assertEquals("forecast open", Zone.OVERLAY, h.zone());
        h.menu();
        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_RIGHT);
        assertEquals("on the Sports tab", HomeActivity.SPORTS, h.activity.page());

        h.controller.pause();
        h.controller.newIntent(new Intent(h.activity, HomeActivity.class).putExtra(HomeButtonService.EXTRA_HOME_BUTTON, true));
        h.controller.resume();
        TestHome.settle();
        assertEquals(HomeActivity.HOME, h.activity.page());
        assertFalse(h.detail.isOpen());
        assertEquals("back on the apps, where focus was", Zone.GRID, h.zone());
        assertEquals(2, h.focused());
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
