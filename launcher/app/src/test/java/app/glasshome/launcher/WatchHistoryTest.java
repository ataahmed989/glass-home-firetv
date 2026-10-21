package app.glasshome.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;

/** How what a media session reports becomes a Continue Watching entry, and what is left out. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class WatchHistoryTest {
    private static WatchHistory.Snapshot s(String pkg, String displayTitle, String title, String subtitle, int minutes, int at) {
        WatchHistory.Snapshot s = PagesTest.snapshot(pkg, title, subtitle, minutes, at, Fixtures.RECORDED_AT);
        s.displayTitle = displayTitle;
        return s;
    }

    @Test
    public void episodesAreRecognisedWhereverTheMarkerIs() {
        WatchHistory.Entry a = WatchHistory.parse(s("com.netflix.ninja", "", "Stranger Things", "S4:E7 Chapter Seven", 98, 41));
        assertTrue(a.episode);
        assertEquals("Stranger Things", a.series);
        assertEquals(4, a.season);
        assertEquals(7, a.number);
        assertEquals("Chapter Seven", a.title);

        WatchHistory.Entry b = WatchHistory.parse(s("com.hbo.hbonow", "The Bear: S03E02 Next", "", "", 30, 12));
        assertTrue(b.episode);
        assertEquals("The Bear", b.series);
        assertEquals(3, b.season);
        assertEquals(2, b.number);
        assertEquals("Next", b.title);

        WatchHistory.Entry c = WatchHistory.parse(s("com.amazon.firebat", "", "Reacher", "Season 2, Episode 5", 50, 20));
        assertTrue(c.episode);
        assertEquals("Reacher", c.series);
        assertEquals("S2, E5  ·  30 min left", c.detail());

        WatchHistory.Entry d = WatchHistory.parse(s("com.disney.disneyplus", "", "Andor", "Episode 3", 45, 10));
        assertTrue(d.episode);
        assertEquals(0, d.season);
        assertEquals("Episode 3  ·  35 min left", d.detail());
    }

    @Test
    public void anythingWithoutAMarkerIsAFilm() {
        WatchHistory.Entry f = WatchHistory.parse(s("com.disney.disneyplus", "Inside Out 2", "Inside Out 2", "", 96, 52));
        assertFalse(f.episode);
        assertEquals("Inside Out 2", f.title);
        assertEquals("44 min left", f.detail());
    }

    @Test
    public void previewsTrailersAndOtherAppsAreNotRecorded() {
        Fixtures.install();
        Application app = RuntimeEnvironment.getApplication();
        assertFalse("an autoplay preview in the app's menus", WatchHistory.record(app, s("com.netflix.ninja", "", "Wednesday", "S2:E1", 50, 0)));
        assertFalse("a trailer", WatchHistory.record(app, s("com.netflix.ninja", "", "Trailer", "", 3, 2)));
        assertFalse("YouTube is not a TV or movie service", WatchHistory.record(app, s("com.amazon.firetv.youtube", "", "Video", "", 30, 10)));
        assertTrue(WatchHistory.load(app).isEmpty());
    }

    @Test
    public void laterSnapshotsReplaceEarlierOnesAndFinishedTitlesDrop() {
        Fixtures.install();
        Application app = RuntimeEnvironment.getApplication();
        WatchHistory.record(app, s("com.netflix.ninja", "", "Stranger Things", "S4:E6", 75, 30));
        WatchHistory.record(app, s("com.netflix.ninja", "", "Stranger Things", "S4:E7", 98, 5));
        List<WatchHistory.Entry> all = WatchHistory.load(app);
        assertEquals("one card per series", 1, all.size());
        assertEquals(7, all.get(0).number);

        WatchHistory.record(app, s("com.disney.disneyplus", "", "Inside Out 2", "", 96, 95));
        assertEquals(2, WatchHistory.load(app).size());
        assertTrue("watched to the end: not on the Movies row", WatchHistory.forPage(WatchHistory.load(app), false).isEmpty());
        assertEquals(1, WatchHistory.forPage(WatchHistory.load(app), true).size());
    }
}
