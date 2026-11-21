package app.glasshome.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.net.Uri;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** FirePlayer 2.0 when it is installed, FirePlayer otherwise; and everything Glass Home asks of it follows. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class FirePlayerTest {
    private Application app;

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        FirePlayer.forget();
    }

    @After public void tearDown() {
        FirePlayer.forget();
    }

    private void install(String pkg) {
        PackageInfo info = new PackageInfo();
        info.packageName = pkg;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = pkg;
        shadowOf(app.getPackageManager()).installPackage(info);
    }

    @Test public void choosesFirePlayer2WhenInstalled() {
        assertEquals("app.fireplayer.next", FirePlayer.choose(true));
        assertEquals("app.fireplayer", FirePlayer.choose(false));

        assertEquals("nothing installed: FirePlayer", FirePlayer.V1, FirePlayer.get(app));
        FirePlayer.forget();
        install(FirePlayer.V1);
        assertEquals("FirePlayer alone", FirePlayer.V1, FirePlayer.get(app));
        install(FirePlayer.NEXT);
        assertEquals("kept until chosen again", FirePlayer.V1, FirePlayer.get(app));
        assertEquals("both: FirePlayer 2.0", FirePlayer.NEXT, FirePlayer.choose(app));
        assertEquals(FirePlayer.NEXT, FirePlayer.current());
        shadowOf(app.getPackageManager()).deletePackage(FirePlayer.V1);
        assertEquals("FirePlayer 2.0 alone", FirePlayer.NEXT, FirePlayer.choose(app));
        shadowOf(app.getPackageManager()).deletePackage(FirePlayer.NEXT);
        assertEquals("2.0 removed: back to FirePlayer", FirePlayer.V1, FirePlayer.choose(app));
    }

    @Test public void currentIsFirePlayerUntilChosen() {
        install(FirePlayer.NEXT);
        assertEquals(FirePlayer.V1, FirePlayer.current());
        FirePlayer.get(app);
        assertEquals(FirePlayer.NEXT, FirePlayer.current());
    }

    @Test public void eitherIsFirePlayer() {
        assertTrue(FirePlayer.is("app.fireplayer"));
        assertTrue(FirePlayer.is("app.fireplayer.next"));
        assertFalse(FirePlayer.is("app.fireplayer.nextx"));
        assertFalse(FirePlayer.is(null));
    }

    @Test public void derivedFromThePackage() {
        assertEquals("app.fireplayer.continue", FirePlayer.authority(FirePlayer.V1));
        assertEquals("app.fireplayer.next.continue", FirePlayer.authority(FirePlayer.NEXT));
        assertEquals(Uri.parse("content://app.fireplayer.next.continue/items"), FirePlayer.items(FirePlayer.NEXT));
        assertEquals("app.fireplayer.next", FirePlayerTitles.starter(FirePlayer.NEXT).getPackageName());
        assertEquals("app.fireplayer.titles.TitleService$Starter", FirePlayerTitles.starter(FirePlayer.NEXT).getClassName());
    }

    @Test public void intentsGoToFirePlayer2() {
        install(FirePlayer.NEXT);
        FirePlayer.choose(app);
        Intent resume = ContinueWatching.resumeIntent("m1");
        assertEquals("app.fireplayer.action.RESUME", resume.getAction());
        assertEquals("app.fireplayer.next", resume.getPackage());
        assertEquals("m1", resume.getStringExtra(ContinueWatching.EXTRA_ID));
        Intent episode = ContinueWatching.playEpisodeIntent("s1", "e2");
        assertEquals("app.fireplayer.action.PLAY_EPISODE", episode.getAction());
        assertEquals("app.fireplayer.next", episode.getPackage());
        assertEquals("e2", episode.getStringExtra(ContinueWatching.EXTRA_EPISODE_ID));
    }

    @Test public void intentsGoToFirePlayerWithout2() {
        install(FirePlayer.V1);
        FirePlayer.choose(app);
        assertEquals("app.fireplayer", ContinueWatching.resumeIntent("m1").getPackage());
        assertEquals("app.fireplayer", ContinueWatching.playEpisodeIntent("s1", "e2").getPackage());
    }

    @Test public void queryAsksFirePlayer2sProvider() {
        install(FirePlayer.NEXT);
        // Nothing answers at 2.0's address, yet it is installed: no answer (null), not 1.0's list nor an empty one
        assertEquals(null, ContinueWatching.query(app));
        assertEquals(FirePlayer.NEXT, FirePlayer.current());
    }
}
