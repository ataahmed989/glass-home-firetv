package app.glasshome.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Men's tennis: ESPN's matches read as games between two players; "atp:*" is any match on now. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class SportsTennisTest {
    private static final String BOARD = "{\"events\":[{\"shortName\":\"Paris\",\"groupings\":["
            + "{\"grouping\":{\"displayName\":\"Men's Singles\"},\"competitions\":["
            + match("1", "in", "3rd Set", "Round of 16", 6, 4, 2, 6, 3, 1)
            + "," + match("2", "pre", "11:00 AM", "Qualifying 2nd Round", 0, 0, 0, 0, 0, 0)
            + "]},{\"grouping\":{\"displayName\":\"Men's Doubles\"},\"competitions\":["
            + match("3", "in", "1st Set", "Round of 32", 1, 0, 0, 0, 0, 0) + "]}]}]}";

    private static String match(String id, String state, String detail, String round, int a1, int b1, int a2, int b2, int a3, int b3) {
        return "{\"id\":\"" + id + "\",\"date\":\"2026-10-29T10:00Z\",\"status\":{\"type\":{\"state\":\"" + state
                + "\",\"shortDetail\":\"" + detail + "\"}},\"round\":{\"displayName\":\"" + round + "\"},\"competitors\":["
                + player("Carlos Alcaraz", "C. Alcaraz", "es", a1, a2, a3) + "," + player("Jannik Sinner", "J. Sinner", "it", b1, b2, b3) + "]}";
    }

    private static String player(String name, String shortName, String flag, int s1, int s2, int s3) {
        return "{\"athlete\":{\"id\":\"" + shortName + "\",\"displayName\":\"" + name + "\",\"shortName\":\"" + shortName
                + "\",\"flag\":{\"href\":\"https://a.espncdn.com/i/teamlogos/countries/500/" + flag + ".png\"}},\"linescores\":["
                + "{\"value\":" + s1 + "},{\"value\":" + s2 + "},{\"value\":" + s3 + "}]}";
    }

    @Test
    public void readsMensSinglesMatches() throws Exception {
        Clock.fixedMillis = java.time.Instant.parse("2026-10-29T11:00:00Z").toEpochMilli();
        Sports.League atp = null;
        for (Sports.League l : Sports.LEAGUES) if (l.tennis()) atp = l;
        List<Sports.Game> games = Sports.parseTennis(new JSONObject(BOARD), atp);
        assertEquals("singles only, qualifying left out", 1, games.size());
        Sports.Game g = games.get(0);
        assertEquals("C. Alcaraz vs J. Sinner", g.matchup());
        assertEquals("sets won; the third is still on", "1", g.away.score);
        assertEquals("1", g.home.score);
        assertEquals("6 2 3", g.away.record);
        assertEquals("Paris  ·  Round of 16", g.note);
        assertTrue(g.away.logo.endsWith("/es.png"));

        assertTrue("men's tennis: a match on now is yours", Sports.isFavourite(g, Collections.singletonList("atp:*")));
        g.state = "pre";
        assertFalse("but not every match scheduled", Sports.isFavourite(g, Collections.singletonList("atp:*")));
    }
}
