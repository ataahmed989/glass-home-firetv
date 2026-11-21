package app.glasshome.launcher;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.time.Duration;

/** Holding the remote's Menu button opens FirePlayer from anywhere; a press is left to the app in front. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30)
public class MenuHoldTest {
    private Application app;
    private HomeButtonService service;

    // The press being simulated: its down time, as the remote stamps it
    private long downAt;

    @Before
    public void start() {
        app = RuntimeEnvironment.getApplication();
        HomeActivity.inFront = false;
        HomeButtonService.forgetTrips();
        HomeButtonService.menuHoldTaken = -1;
        FirePlayer.forget();
        // FirePlayer installed, with a TV launch screen
        launchScreen(FirePlayer.V1);
        service = Robolectric.setupService(HomeButtonService.class);
    }

    @After
    public void stop() {
        HomeButtonService.forgetTrips();
        service.onUnbind(new Intent());
    }

    /** A key going down now, or coming up now (as part of the press that last went down). */
    private void key(int action, int code) {
        long now = SystemClock.uptimeMillis();
        if (action == KeyEvent.ACTION_DOWN) downAt = now;
        service.onKeyEvent(new KeyEvent(downAt, now, action, code, 0));
    }

    private void after(long ms) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    private Intent started() {
        return shadowOf(app).getNextStartedActivity();
    }

    private void launchScreen(String pkg) {
        Intent leanback = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        shadowOf(app.getPackageManager()).addResolveInfoForIntent(new Intent(leanback).setPackage(pkg),
                TestHome.resolve(pkg, "FirePlayer"));
    }

    /** With FirePlayer 2.0 installed beside FirePlayer, the hold opens FirePlayer 2.0. */
    @Test
    public void holdingMenuOpensFirePlayer2WhenInstalled() {
        android.content.pm.PackageInfo info = new android.content.pm.PackageInfo();
        info.packageName = FirePlayer.NEXT;
        info.applicationInfo = new android.content.pm.ApplicationInfo();
        info.applicationInfo.packageName = FirePlayer.NEXT;
        shadowOf(app.getPackageManager()).installPackage(info);
        launchScreen(FirePlayer.NEXT);
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU);
        after(HomeButtonService.MENU_HOLD_MS);
        Intent open = started();
        assertNotNull(open);
        assertEquals(FirePlayer.NEXT, open.getComponent().getPackageName());
    }

    @Test
    public void holdingMenuOpensFirePlayer() {
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU);
        after(HomeButtonService.MENU_HOLD_MS - 100);
        assertNull("not before the hold is long enough", started());
        after(100);
        Intent open = started();
        assertNotNull(open);
        assertEquals(FirePlayer.V1, open.getComponent().getPackageName());
        assertTrue((open.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertFalse("nothing marked as opening while Glass Home isn't in front", HomeButtonService.opening());
        assertEquals("Glass Home is told which press it was", downAt, HomeButtonService.menuHoldTaken);
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU);
        after(2000);
        assertNull("once per hold", started());
    }

    /** Over Glass Home, its going to the back for FirePlayer is no Home press. */
    @Test
    public void fromGlassHomeItIsMarkedAsOpening() {
        HomeActivity.inFront = true;
        try {
            key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU);
            after(HomeButtonService.MENU_HOLD_MS);
            assertNotNull(started());
            assertTrue(HomeButtonService.opening());
        } finally {
            HomeActivity.inFront = false;
        }
    }

    /** Let go past the hold before the timer's turn came (a busy main thread): a hold all the same. */
    @Test
    public void aLateTimerStillCountsTheHold() {
        long t = SystemClock.uptimeMillis();
        service.onKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU, 0));
        service.onKeyEvent(new KeyEvent(t, t + HomeButtonService.MENU_HOLD_MS + 10, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU, 0));
        Intent open = started();
        assertNotNull(open);
        assertEquals(t, HomeButtonService.menuHoldTaken);
    }

    /** A Back release Fire OS never shows doesn't block the shortcut for good. */
    @Test
    public void aMissedReleaseExpires() {
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK);
        after(HomeButtonService.COMBO_MS + 100);
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU);
        after(HomeButtonService.MENU_HOLD_MS);
        assertNotNull(started());
    }

    @Test
    public void aPressIsLeftAlone() {
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU);
        after(150);
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU);
        after(2000);
        assertNull(started());
    }

    @Test
    public void notWithBackOrLeftHeldToo() {
        // Back + Menu is Fire OS's VoiceView shortcut; Left + Menu + Back resets the remote
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK);
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU);
        after(2000);
        assertNull(started());
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MENU);
        key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK);

        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT);
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU);
        after(2000);
        assertNull(started());
    }

    @Test
    public void anotherKeyMeanwhileCallsItOff() {
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU);
        after(200);
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN);
        after(2000);
        assertNull(started());
    }

    @Test
    public void turnedOffInSettings() {
        new HomeSettings(app).setMenuHoldOpensFirePlayer(false);
        key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU);
        after(2000);
        assertNull(started());
    }

    /**
     * On Glass Home itself, Menu acts on release: a press goes to the tab bar; a hold the Home service
     * took for FirePlayer does nothing here; a hold it didn't take (service off) is a press after all.
     */
    @Test
    public void onGlassHomeMenuActsOnRelease() throws Exception {
        TestHome h = new TestHome();
        assertEquals(HomeActivity.Zone.GRID, h.zone());

        long t = SystemClock.uptimeMillis();
        h.activity.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU, 0));
        TestHome.settle();
        assertEquals("nothing moves while Menu is down", HomeActivity.Zone.GRID, h.zone());
        HomeButtonService.menuHoldTaken = t;
        h.activity.dispatchKeyEvent(new KeyEvent(t, t + HomeButtonService.MENU_HOLD_MS + 100, KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_MENU, 0));
        TestHome.settle();
        assertEquals("held for FirePlayer: not the tab bar too", HomeActivity.Zone.GRID, h.zone());

        long u = SystemClock.uptimeMillis();
        h.activity.dispatchKeyEvent(new KeyEvent(u, u, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU, 0));
        h.activity.dispatchKeyEvent(new KeyEvent(u, u + HomeButtonService.MENU_HOLD_MS + 100, KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_MENU, 0));
        TestHome.settle();
        assertEquals("held, but the service didn't take it: the tab bar", HomeActivity.Zone.TABS, h.zone());

        h.menu();
        h.menu();
        assertEquals("a press goes back and forth", HomeActivity.Zone.TABS, h.zone());
    }
}
