package app.glasshome.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.AppOpsManager;
import android.app.Application;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Intent;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSystemClock;
import org.robolectric.shadows.ShadowUsageStatsManager;

import java.time.Duration;

/**
 * Amazon's home is never left on screen: what Fire OS lets the service see of the Home button
 * (checked on the TV), and what it does about each case.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30)
public class HomeButtonServiceTest {
    private static final String AMAZON_HOME = "com.amazon.tv.launcher.ui.HomeActivity_vNext";
    private Application app;
    private HomeButtonService service;

    @Before
    public void start() {
        app = RuntimeEnvironment.getApplication();
        HomeActivity.inFront = false;
        // The service's timing is static, and the clock starts again for each case: none may carry over
        HomeButtonService.forgetTrips();
        service = Robolectric.setupService(HomeButtonService.class);
    }

    @After
    public void stop() {
        HomeActivity.inFront = false;
        HomeButtonService.glassHomeResumed();
        HomeButtonService.forgetTrips();
        service.onUnbind(new Intent());
    }

    /** Fire OS starts on Amazon's home, which may be up before the service is: Glass Home comes forward. */
    @Test
    public void theTvStartingBringsGlassHome() {
        service.onServiceConnected();
        assertGlassHomeStarted();
    }

    /** Started long after the TV was (Glass Home updated, say), the service leaves the screen alone. */
    @Test
    public void laterTheServiceStartingChangesNothing() {
        ShadowSystemClock.advanceBy(Duration.ofMillis(HomeButtonService.BOOT_MS + 1000));
        service.onServiceConnected();
        assertNull(shadowOf(app).getNextStartedActivity());
    }

    /**
     * Home pressed twice: the second press comes once Glass Home is back from the first, and Fire OS
     * reports only a redraw of Amazon's home for it. That is followed like the first.
     */
    @Test
    public void aSecondPressIsFollowedToo() {
        connectedAfterStartUp();
        window("com.amazon.tv.launcher", AMAZON_HOME);
        assertGlassHomeStarted();

        HomeActivity.inFront = true;
        HomeButtonService.glassHomeResumed();
        HomeActivity.inFront = false;   // the second press
        HomeButtonService.leftUnexpectedly();   // as Glass Home's onPause says
        redraw("com.amazon.tv.launcher");
        assertGlassHomeStarted();
    }

    /** Glass Home not in front a moment after a trip, with Amazon's home the last thing up: the trip is made again. */
    @Test
    public void aTripThatDidNotLandIsMadeAgain() {
        connectedAfterStartUp();
        window("com.amazon.tv.launcher", AMAZON_HOME);
        assertGlassHomeStarted();

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(HomeButtonService.CHECK_MS));
        assertGlassHomeStarted();
    }

    /** Should Amazon's home stay up anyway, its next redraw or the next key brings Glass Home back. */
    @Test
    public void strandedOnAmazonsHomeTheNextKeyOrRedrawLeavesIt() {
        connectedAfterStartUp();
        window("com.amazon.tv.launcher", AMAZON_HOME);
        assertGlassHomeStarted();
        ShadowSystemClock.advanceBy(Duration.ofMillis(HomeButtonService.REDIRECT_GAP_MS + 10));

        service.onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN));
        assertGlassHomeStarted();

        ShadowSystemClock.advanceBy(Duration.ofMillis(HomeButtonService.REDIRECT_GAP_MS + 10));
        redraw("com.amazon.tv.launcher");
        assertGlassHomeStarted();
    }

    /** Amazon's other screens (Settings, the quick menu) are wanted: keys and redraws there change nothing. */
    @Test
    public void amazonsOtherScreensAreLeftAlone() {
        connectedAfterStartUp();
        window("com.amazon.tv.launcher", "com.amazon.tv.launcher.ui.MainSettingsActivity");
        service.onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN));
        redraw("com.amazon.tv.launcher");
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(HomeButtonService.CHECK_MS));
        assertNull(shadowOf(app).getNextStartedActivity());
    }

    /** Another app came up after the trip (opened by voice, say): no second trip over it. */
    @Test
    public void noSecondTripOverAnotherApp() {
        connectedAfterStartUp();
        window("com.amazon.tv.launcher", AMAZON_HOME);
        assertGlassHomeStarted();
        window("com.netflix.ninja", "com.netflix.ninja.MainActivity");
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(HomeButtonService.CHECK_MS));
        service.onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN));
        assertNull(shadowOf(app).getNextStartedActivity());
    }

    /**
     * Home pressed twice, quickly (seen on the TV): Amazon's home comes back over Glass Home before
     * it has quite left, and says nothing. Fire OS's usage record shows it in front: Glass Home returns.
     */
    @Test
    public void amazonsHomeComingBackSilentlyIsCaught() {
        usageAccess();
        connectedAfterStartUp();
        window("com.amazon.tv.launcher", AMAZON_HOME);
        assertGlassHomeStarted();
        HomeActivity.inFront = true;
        HomeButtonService.glassHomeResumed();

        HomeActivity.inFront = false;
        resumed("com.amazon.tv.launcher", AMAZON_HOME);
        HomeButtonService.leftUnexpectedly();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(HomeButtonService.LEFT_CHECK_MS));
        assertGlassHomeStarted();
    }

    /** Glass Home left for an app opened by voice, still on its way up: it is left alone. */
    @Test
    public void anAppOpenedByVoiceIsLeftAlone() {
        usageAccess();
        connectedAfterStartUp();
        window("com.amazon.tv.launcher", AMAZON_HOME);
        assertGlassHomeStarted();
        HomeActivity.inFront = true;
        HomeButtonService.glassHomeResumed();

        HomeActivity.inFront = false;
        resumed("com.netflix.ninja", "com.netflix.ninja.MainActivity");
        HomeButtonService.leftUnexpectedly();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(HomeButtonService.CHECK_MS));
        assertNull(shadowOf(app).getNextStartedActivity());
    }

    /**
     * Home pressed while an app opened from Glass Home (the Appstore, slow to start) is still on its
     * way: Amazon's home comes up instead, and once the app's opening time is over, Glass Home returns.
     */
    @Test
    public void homePressedWhileAnAppStartsIsFollowedOnceItsOpeningTimeIsOver() {
        usageAccess();
        connectedAfterStartUp();
        HomeButtonService.opening(HomeButtonService.APPSTORE);
        resumed("com.amazon.tv.launcher", AMAZON_HOME);
        window("com.amazon.tv.launcher", AMAZON_HOME);
        assertNull("not while the Appstore may still be on its way", shadowOf(app).getNextStartedActivity());

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(HomeButtonService.OPENING_MS));
        assertGlassHomeStarted();
    }

    /** The Appstore's library, reached through its launcher entry, is Amazon's home window itself: it is left alone. */
    @Test
    public void theAppstoresLibraryIsLeftAlone() {
        usageAccess();
        connectedAfterStartUp();
        HomeButtonService.opening(HomeButtonService.APPSTORE);
        window(HomeButtonService.APPSTORE, "com.amazon.venezia.grid.AppsGridLauncherActivity");
        resumed("com.amazon.tv.launcher", AMAZON_HOME);
        window("com.amazon.tv.launcher", AMAZON_HOME);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(HomeButtonService.OPENING_MS + HomeButtonService.CHECK_MS));

        service.onKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN));
        redraw("com.amazon.tv.launcher");
        assertNull(shadowOf(app).getNextStartedActivity());
    }

    /** Without usage access, Glass Home leaving unexpectedly is not taken for Amazon's home: nothing is guessed. */
    @Test
    public void withoutUsageAccessNothingIsGuessed() {
        connectedAfterStartUp();
        HomeButtonService.leftUnexpectedly();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(HomeButtonService.CHECK_MS));
        assertNull(shadowOf(app).getNextStartedActivity());
    }

    private void usageAccess() {
        shadowOf(app.getSystemService(AppOpsManager.class)).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(), app.getPackageName(), AppOpsManager.MODE_ALLOWED);
    }

    private void resumed(String pkg, String cls) {
        shadowOf(app.getSystemService(UsageStatsManager.class)).addEvent(ShadowUsageStatsManager.EventBuilder.buildEvent()
                .setPackage(pkg).setClass(cls).setTimeStamp(System.currentTimeMillis())
                .setEventType(UsageEvents.Event.ACTIVITY_RESUMED).build());
    }

    private void connectedAfterStartUp() {
        ShadowSystemClock.advanceBy(Duration.ofMillis(HomeButtonService.BOOT_MS + 1000));
        service.onServiceConnected();
    }

    private void window(String pkg, String cls) {
        AccessibilityEvent e = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        e.setPackageName(pkg);
        e.setClassName(cls);
        service.onAccessibilityEvent(e);
    }

    private void redraw(String pkg) {
        AccessibilityEvent e = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED);
        e.setPackageName(pkg);
        e.setClassName("android.widget.FrameLayout");
        service.onAccessibilityEvent(e);
    }

    private void assertGlassHomeStarted() {
        Intent started = shadowOf(app).getNextStartedActivity();
        assertTrue("Glass Home was not brought forward", started != null);
        assertEquals(HomeActivity.class.getName(), started.getComponent().getClassName());
        assertTrue(started.getBooleanExtra(HomeButtonService.EXTRA_HOME_BUTTON, false));
    }
}
