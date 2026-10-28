package app.glasshome.launcher;

import android.app.Application;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Looper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class FirePlayerTitlesTest {
    private Application app;
    private FirePlayerTitles titles;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        titles = new FirePlayerTitles(app);
    }

    @After public void tearDown() {
        titles.unwatch();
    }

    private void install(boolean stopped) {
        PackageInfo info = new PackageInfo();
        info.packageName = FirePlayerTitles.PACKAGE;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = FirePlayerTitles.PACKAGE;
        if (stopped) info.applicationInfo.flags |= ApplicationInfo.FLAG_STOPPED;
        shadowOf(app.getPackageManager()).installPackage(info);
    }

    private void forceStopped(String pkg) {
        app.sendBroadcast(new Intent(Intent.ACTION_PACKAGE_RESTARTED, Uri.fromParts("package", pkg, null)));
        shadowOf(Looper.getMainLooper()).idle();
    }

    private void after(long ms) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    private List<Intent> starts() {
        List<Intent> starts = new ArrayList<>();
        for (Intent i : shadowOf(app).getBroadcastIntents()) {
            if (FirePlayerTitles.STARTER.equals(i.getComponent())) starts.add(i);
        }
        return starts;
    }

    @Test public void startsTheServerAgainAMomentAfterFirePlayerIsForceStopped() {
        install(false);
        titles.watch();
        forceStopped(FirePlayerTitles.PACKAGE);
        assertTrue("not while Home is coming up", starts().isEmpty());
        after(FirePlayerTitles.AFTER_MS);
        List<Intent> starts = starts();
        assertEquals(1, starts.size());
        assertEquals(FirePlayerTitles.ACTION_START, starts.get(0).getAction());
        assertTrue("reaches a force-stopped app", (starts.get(0).getFlags() & Intent.FLAG_INCLUDE_STOPPED_PACKAGES) != 0);
    }

    @Test public void ignoresOtherAppsBeingForceStopped() {
        install(false);
        titles.watch();
        forceStopped("ar.tvplayer.tv");
        after(5 * FirePlayerTitles.AFTER_MS);
        assertTrue(starts().isEmpty());
    }

    @Test public void startsItWhenFirePlayerWasForceStoppedBeforeWatching() {
        install(true);
        titles.watch();
        after(FirePlayerTitles.AFTER_MS);
        assertEquals(1, starts().size());
    }

    @Test public void leavesARunningFirePlayerAlone() {
        install(false);
        titles.watch();
        after(5 * FirePlayerTitles.AFTER_MS);
        assertTrue(starts().isEmpty());
    }

    @Test public void nothingAfterUnwatching() {
        install(false);
        titles.watch();
        titles.unwatch();
        forceStopped(FirePlayerTitles.PACKAGE);
        after(5 * FirePlayerTitles.AFTER_MS);
        assertTrue(starts().isEmpty());
    }
}
