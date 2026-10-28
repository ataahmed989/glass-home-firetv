package app.glasshome.launcher;

import android.Manifest;
import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.provider.Settings;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class HomeServiceRecoveryTest {
    private Application app;
    private String own;
    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        own = new ComponentName(app, HomeButtonService.class).flattenToString();
        new HomeSettings(app).setHomeButtonOpensGlassHome(true);
        Settings.Secure.putString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, null);
        Settings.Secure.putInt(app.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
    }
    @Test public void repairsBootWithoutRemovingOtherServices() {
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS);
        Settings.Secure.putString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "other.pkg/.Reader");
        new HomeServiceRecovery().onReceive(app, new Intent(Intent.ACTION_BOOT_COMPLETED));
        assertEquals("other.pkg/.Reader:" + own, Settings.Secure.getString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));
        assertEquals(1, Settings.Secure.getInt(app.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0));
    }
    @Test public void doesNotRepairWithoutGrant() {
        shadowOf(app).denyPermissions(Manifest.permission.WRITE_SECURE_SETTINGS);
        HomeServiceRecovery.restore(app);
        assertNull(Settings.Secure.getString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));
    }
    /** The Home service holds more than the redirect (Menu for TiviMate, Sleep), so it comes back with the redirect off too. */
    @Test public void restoresTheServiceWithTheRedirectOff() {
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS);
        new HomeSettings(app).setHomeButtonOpensGlassHome(false);
        HomeServiceRecovery.restore(app);
        assertEquals(own, Settings.Secure.getString(app.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES));
        assertEquals(1, Settings.Secure.getInt(app.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0));
    }
    @Test public void recognizesShortComponentAndDoesNotDuplicateIt() {
        ComponentName component = new ComponentName(app, HomeButtonService.class);
        String shortName = component.flattenToShortString();
        assertEquals(shortName, HomeServiceRecovery.withService(shortName, component));
        assertEquals(own, HomeServiceRecovery.withService("null", component));
    }
    @Test public void ignoresUnrelatedBroadcasts() {
        shadowOf(app).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS);
        new HomeServiceRecovery().onReceive(app, new Intent("unrelated"));
        assertEquals(0, Settings.Secure.getInt(app.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0));
    }
}
