package app.glasshome.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.os.Looper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/** Fire OS's announcement of the Home button, which comes before Amazon's home does. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30)
public class HomeButtonServiceTest {
    /** Home pressed while the Appstore is still coming up: Amazon's home that follows is not left alone. */
    @Test
    public void theHomeButtonEndsAnOpening() {
        Application app = RuntimeEnvironment.getApplication();
        HomeButtonService service = Robolectric.setupService(HomeButtonService.class);
        service.onServiceConnected();
        HomeButtonService.opening(HomeButtonService.APPSTORE);
        assertTrue(HomeButtonService.opening());

        app.sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "recentapps"));
        shadowOf(Looper.getMainLooper()).idle();
        assertTrue("only the Home button counts", HomeButtonService.opening());

        app.sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey"));
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(HomeButtonService.opening());
        service.onUnbind(new Intent());
    }
}
