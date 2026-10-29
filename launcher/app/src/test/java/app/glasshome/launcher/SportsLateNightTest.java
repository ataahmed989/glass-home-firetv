package app.glasshome.launcher;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.ZonedDateTime;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** After midnight, last night's late games (still on, or just over) stay on the Sports page until morning. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SportsLateNightTest {

    private static boolean hasGameOn(List<Sports.Game> games, int day) {
        for (Sports.Game g : games) if (Clock.at(g.start).minusHours(6).getDayOfMonth() == day) return true;
        return false;
    }

    @Test
    public void lastNightsGamesStayUntilMorning() throws Exception {
        Fixtures.install();
        Sports.League nba = Sports.LEAGUES[0];
        ZonedDateTime recorded = Clock.at(Fixtures.RECORDED_AT);
        // 12:30 AM the next night: the games of the 20th are still there
        Clock.fixedMillis = recorded.plusDays(1).withHour(0).withMinute(30).toInstant().toEpochMilli();
        assertTrue(hasGameOn(Sports.fetch(RuntimeEnvironment.getApplication(), nba), 20));
        // By noon they've gone
        Clock.fixedMillis = recorded.plusDays(1).withHour(12).withMinute(0).toInstant().toEpochMilli();
        java.io.File cache = new java.io.File(RuntimeEnvironment.getApplication().getCacheDir(), "net");
        java.io.File[] files = cache.listFiles();
        if (files != null) for (java.io.File f : files) f.delete();
        assertFalse(hasGameOn(Sports.fetch(RuntimeEnvironment.getApplication(), nba), 20));
    }
}
