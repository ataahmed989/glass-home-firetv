package app.glasshome.launcher;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.accessibilityservice.AccessibilityService;
import android.app.Application;
import android.content.Intent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** Sleep without the device-admin grant: the Home service asks Fire OS to lock the screen. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30)
public class SleepTest {
    private Application app;
    private HomeButtonService service;

    @Before
    public void start() {
        app = RuntimeEnvironment.getApplication();
        HomeButtonService.forgetTrips();
        service = Robolectric.setupService(HomeButtonService.class);
    }

    @After
    public void stop() {
        HomeButtonService.forgetTrips();
        service.onUnbind(new Intent());
    }

    @Test
    public void notWithoutTheGrantOrTheService() {
        assertFalse(SystemControls.sleepAvailable(app));
        assertFalse(SystemControls.canSleep(app));
        assertFalse(SystemControls.sleepNow(app));
    }

    @Test
    public void theHomeServiceLocksTheScreen() {
        service.onServiceConnected();
        assertTrue(SystemControls.canSleep(app));
        assertTrue(SystemControls.sleepNow(app));
        assertTrue(shadowOf(service).getGlobalActionsPerformed().contains(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN));
    }

    @Test
    public void turnedOffAndOnAgainInSettings() {
        service.onServiceConnected();
        SystemControls.releaseSleep(app);
        assertTrue("still possible here", SystemControls.sleepAvailable(app));
        assertFalse(SystemControls.canSleep(app));
        assertFalse(SystemControls.sleepNow(app));
        assertTrue(shadowOf(service).getGlobalActionsPerformed().isEmpty());

        SystemControls.allowSleep(app);
        assertTrue(SystemControls.canSleep(app));
        assertTrue(SystemControls.sleepNow(app));
    }
}
