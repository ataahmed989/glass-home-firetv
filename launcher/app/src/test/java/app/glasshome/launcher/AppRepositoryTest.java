package app.glasshome.launcher;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowPackageManager;

import java.util.List;

/** The home screen's list of apps, for Amazon apps whose launcher entry does not open them. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30)
public class AppRepositoryTest {
    private static final String APPSTORE = "com.amazon.venezia", LIVE_TV = "com.amazon.tv.livetv";
    private static final Intent LAUNCHER = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);

    /** As on the TV: the Appstore's launcher entry hands over to Amazon's home, and Live TV has none. */
    @Test
    public void appstoreOpensItsStorefrontAndLiveTvItsGuide() {
        Application app = RuntimeEnvironment.getApplication();
        ShadowPackageManager pm = shadowOf(app.getPackageManager());
        pm.addResolveInfoForIntent(LAUNCHER, TestHome.resolve(APPSTORE, "Appstore"));
        pm.addResolveInfoForIntent(new Intent("com.amazon.venezia.ade.FEATURED_LAUNCH").setPackage(APPSTORE),
                TestHome.resolve(APPSTORE, "Appstore"));
        pm.addResolveInfoForIntent(new Intent(Intent.ACTION_VIEW, Uri.parse("livetv://showEpg")).setPackage(LIVE_TV),
                TestHome.resolve(LIVE_TV, "Live TV"));

        List<AppEntry> apps = AppRepository.load(app, HomeConfig.load(app));
        assertEquals("com.amazon.venezia.ade.FEATURED_LAUNCH", find(apps, APPSTORE).launchIntent.getAction());
        AppEntry live = find(apps, LIVE_TV);
        assertEquals(Uri.parse("livetv://showEpg"), live.launchIntent.getData());
        assertEquals("Live TV", live.label);
    }

    /** Where the storefront cannot be opened, the Appstore keeps its own launcher entry. */
    @Test
    public void appstoreFallsBackToItsLauncherEntry() {
        Application app = RuntimeEnvironment.getApplication();
        shadowOf(app.getPackageManager()).addResolveInfoForIntent(LAUNCHER, TestHome.resolve(APPSTORE, "Appstore"));

        List<AppEntry> apps = AppRepository.load(app, HomeConfig.load(app));
        assertEquals(APPSTORE + ".MainActivity", find(apps, APPSTORE).launchIntent.getComponent().getClassName());
    }

    private static AppEntry find(List<AppEntry> apps, String key) {
        for (AppEntry a : apps) if (a.key.equals(key)) return a;
        throw new AssertionError(key + " is not on the home screen");
    }
}
