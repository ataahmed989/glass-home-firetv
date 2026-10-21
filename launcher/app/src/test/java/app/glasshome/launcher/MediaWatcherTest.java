package app.glasshome.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Looper;
import android.os.SystemClock;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowMediaController;

import java.time.Duration;
import java.util.List;

/**
 * Apps that say they are playing, and where, but not what (Netflix): what plays just after Glass
 * Home opened a title there is that title; otherwise nothing is recorded.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30)
public class MediaWatcherTest {
    private static final String NETFLIX = "com.netflix.ninja", MAX = "com.hbo.hbonow";

    @After
    public void forgetOpenings() {
        WatchHistory.forgetOpened();
    }

    @Test
    public void anUnnamedSessionIsTheShowJustOpenedThere() throws Exception {
        Fixtures.install();
        Application app = RuntimeEnvironment.getApplication();
        WatchHistory.opened(NETFLIX, new Tmdb.Title(66732, true, "Stranger Things", "", "/st.jpg", "", "2016-07-15", new int[0]));
        playing(app, NETFLIX, 25);

        WatchHistory.Entry e = recorded(app);
        assertTrue(e.episode);
        assertEquals("Stranger Things", e.name());
        assertEquals(66732, e.tmdbId);
        assertEquals("/st.jpg", e.backdrop);
        assertTrue("known on TMDB already: no search", e.matched);
        assertEquals(1, WatchHistory.forPage(WatchHistory.load(app), true).size());
    }

    /** The title page's trailer plays first, then the episode: the episode is still the show opened. */
    @Test
    public void theTitleWatchedAfterItsTrailerIsRecorded() throws Exception {
        Fixtures.install();
        Application app = RuntimeEnvironment.getApplication();
        WatchHistory.opened(NETFLIX, new Tmdb.Title(66732, true, "Stranger Things", "", "/st.jpg", "", "2016-07-15", new int[0]));
        playing(app, NETFLIX, 2);    // the trailer
        assertTrue(WatchHistory.load(app).isEmpty());
        playing(app, NETFLIX, 25);   // the episode, resumed

        WatchHistory.Entry e = recorded(app);
        assertEquals("Stranger Things", e.name());
        assertEquals(66732, e.tmdbId);
    }

    /** A film's length comes from TMDB when the app gives none, so it shows how far along it is. */
    @Test
    public void anOpenedFilmShowsItsProgress() throws Exception {
        Fixtures.install();
        Application app = RuntimeEnvironment.getApplication();
        Tmdb.Title film = new Tmdb.Title(693134, false, "Dune: Part Two", "", "/dune.jpg", "", "2024-02-27", new int[0]);
        film.minutes = 120;
        WatchHistory.opened(NETFLIX, film);
        playing(app, NETFLIX, 30);

        WatchHistory.Entry e = recorded(app);
        assertFalse(e.episode);
        assertEquals("Dune: Part Two", e.name());
        assertEquals(0.25f, e.progress(), 0.01f);
    }

    /** Nothing opened from Glass Home, opened in another app, or too long ago: nothing is made up. */
    @Test
    public void otherwiseAnUnnamedSessionIsNotRecorded() {
        Fixtures.install();
        Application app = RuntimeEnvironment.getApplication();
        Tmdb.Title t = new Tmdb.Title(1399, true, "Game of Thrones", "", "/got.jpg", "", "2011-04-17", new int[0]);

        WatchHistory.opened(NETFLIX, t);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(WatchHistory.OPENED_MS + 60_000));
        playing(app, NETFLIX, 25);   // opened too long ago
        playing(app, NETFLIX, 25);   // nothing opened
        WatchHistory.opened(MAX, t);
        playing(app, NETFLIX, 25);   // opened in another app
        WatchHistory.opened(NETFLIX, t);
        playing(app, NETFLIX, 2);    // the title page's trailer

        assertTrue(WatchHistory.load(app).isEmpty());
    }

    /** A Netflix session playing {@code minutes} in, with no title, seen by a newly connected watcher. */
    private static void playing(Application app, String pkg, int minutes) {
        MediaController c = new MediaController(app, new MediaSession(app, "test").getSessionToken());
        ShadowMediaController shadow = shadowOf(c);
        shadow.setPackageName(pkg);
        shadow.setPlaybackState(new PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING, minutes * 60_000L, 1f, SystemClock.elapsedRealtime()).build());
        shadowOf(app.getSystemService(MediaSessionManager.class)).clearControllers();
        shadowOf(app.getSystemService(MediaSessionManager.class)).addController(c);
        Robolectric.setupService(MediaWatcher.class).onListenerConnected();
    }

    /** The newest entry, once the watcher has written it (it writes off the main thread). */
    private static WatchHistory.Entry recorded(Application app) throws InterruptedException {
        for (int i = 0; i < 250; i++) {
            List<WatchHistory.Entry> all = WatchHistory.load(app);
            if (!all.isEmpty()) return all.get(0);
            Thread.sleep(20);
        }
        throw new AssertionError("nothing was recorded");
    }
}
