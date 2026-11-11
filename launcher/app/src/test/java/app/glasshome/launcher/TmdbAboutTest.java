package app.glasshome.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.List;

/**
 * What the Continue Watching details window shows of a film or show, from the same recorded TMDB answers the cards'
 * artwork comes from (one request serves both), and the episode facts' formats.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class TmdbAboutTest {
    private Tmdb tmdb;

    @Before
    public void setUp() throws Exception {
        Fixtures.install();
        Context app = RuntimeEnvironment.getApplication();
        tmdb = new Tmdb(app, Fixtures.tmdbToken());
    }

    @Test
    public void aShow() throws Exception {
        Tmdb.About lawAndOrder = tmdb.about(549, true);
        assertEquals("Law & Order", lawAndOrder.name);
        assertEquals("1990", lawAndOrder.year);
        assertEquals(26, lawAndOrder.seasons);
        assertEquals(Arrays.asList("Crime", "Drama"), lawAndOrder.genres);
        assertEquals("Truth matters.", lawAndOrder.tagline);
        assertEquals(7.289, lawAndOrder.rating, 0.0001);
        assertEquals("7.3", lawAndOrder.ratingText());
        assertEquals("TV-14", lawAndOrder.ageRating);
        assertEquals("https://image.tmdb.org/t/p/w342/hmd2tEtjolLWpNLCTvXS2PO5VCi.jpg", lawAndOrder.poster);
        assertTrue(lawAndOrder.overview.startsWith("In cases ripped from the headlines"));
        assertEquals("1990  ·  TV Show  ·  26 Seasons", lawAndOrder.facts());
    }

    @Test
    public void aFilm() throws Exception {
        Tmdb.About sacrifice = tmdb.about(1284186, false);
        assertEquals("Sacrifice", sacrifice.name);
        assertEquals("2026", sacrifice.year);
        assertEquals("", sacrifice.tagline);
        assertEquals(100, sacrifice.minutes);
        assertEquals("R", sacrifice.ageRating);
        assertEquals(Arrays.asList("Adventure", "Comedy", "Action"), sacrifice.genres);
        assertEquals("2026  ·  Movie  ·  1 hr 40 min", sacrifice.facts());
        Tmdb.About masterplan = tmdb.about(1561726, false);
        assertEquals("7.1", masterplan.ratingText());
        assertEquals("https://image.tmdb.org/t/p/w342/5xmXVd7nCK3tAo34XkO58SxUCip.jpg", masterplan.poster);
    }

    /** As FirePlayer picks it: a film's US theatrical (type 3) rating first, else the first US release that has one. */
    @Test
    public void theTheatricalRatingComesFirst() throws Exception {
        JSONObject film = new JSONObject("{\"id\":1,\"title\":\"A Film\",\"release_date\":\"2023-05-01\",\"runtime\":125,"
                + "\"vote_average\":0,\"release_dates\":{\"results\":["
                + "{\"iso_3166_1\":\"GB\",\"release_dates\":[{\"certification\":\"15\",\"type\":3}]},"
                + "{\"iso_3166_1\":\"US\",\"release_dates\":[{\"certification\":\"\",\"type\":1},{\"certification\":\"R\",\"type\":4},"
                + "{\"certification\":\"PG-13\",\"type\":3}]}]}}");
        Tmdb.About a = Tmdb.aboutOf(film, false);
        assertEquals("PG-13", a.ageRating);
        assertEquals("no rating, no TMDB fact", "", a.ratingText());
        assertNull("no poster", a.poster);
        assertEquals("2023  ·  Movie  ·  2 hr 5 min", a.facts());
        film.getJSONObject("release_dates").getJSONArray("results").getJSONObject(1).getJSONArray("release_dates").remove(2);
        assertEquals("R", Tmdb.aboutOf(film, false).ageRating);
        assertNull("no name, nothing to show", Tmdb.aboutOf(new JSONObject("{\"id\":2}"), false));
        JSONObject untitled = new JSONObject("{\"id\":3,\"original_title\":\"Le Film\",\"runtime\":45}");
        assertEquals("its original title stands in", "Le Film", Tmdb.aboutOf(untitled, false).name);
        assertEquals("Movie  ·  45 min", Tmdb.aboutOf(untitled, false).facts());
    }

    @Test
    public void episodeFacts() {
        assertEquals("Oct 3, 2024", DetailsWindow.airDate("2024-10-03"));
        assertEquals("", DetailsWindow.airDate(""));
        assertEquals("a date in another form, as it is", "Autumn 2024", DetailsWindow.airDate("Autumn 2024"));
        ContinueWatching.Episode e = new ContinueWatching.Episode("9", 24, 5, "Hard Cases", "", null, 42, "2024-10-03", 0, false);
        assertEquals("Oct 3, 2024  ·  42 min", DetailsWindow.episodeFacts(e));
        assertEquals(-1f, e.progress(), 0f);
        ContinueWatching.Episode none = new ContinueWatching.Episode("9", 24, 6, null, null, null, 0, null, 21 * 60_000L, false);
        assertEquals("", DetailsWindow.episodeFacts(none));
        assertEquals("no length, no bar", -1f, none.progress(), 0f);
        ContinueWatching.Episode half = new ContinueWatching.Episode("9", 24, 6, null, null, null, 42, "", 21 * 60_000L, false);
        assertEquals(0.5f, half.progress(), 0.001f);
        assertEquals("42 min", DetailsWindow.episodeFacts(half));
    }

    /** Where FirePlayer plays from is shown as a host and port, and only that, whatever else should come with it. */
    @Test
    public void onlyTheProvidersAddressIsShown() {
        assertEquals("tv.example.com:8080", ContinueWatching.address("tv.example.com:8080"));
        assertEquals("tv.example.com", ContinueWatching.address(" tv.example.com "));
        assertEquals("tv.example.com:8080", ContinueWatching.address("http://login@tv.example.com:8080/a/b.ts?x=1"));
        assertNull(ContinueWatching.address(""));
        assertNull(ContinueWatching.address(null));
    }

    @Test
    public void aSeasonFromTmdb() throws Exception {
        Net.Fetcher recorded = Net.fetcher;
        try {
            Net.fetcher = (url, bearer) -> {
                if (url.equals(Tmdb.API + "tv/549/season/24?language=en-US")) {
                    return ("{\"episodes\":[{\"season_number\":24,\"episode_number\":5,\"name\":\"Hard Cases\","
                            + "\"overview\":\"A story.\",\"air_date\":\"2024-10-03\",\"runtime\":42,\"still_path\":\"/x.jpg\"},"
                            + "{\"season_number\":24,\"episode_number\":6,\"name\":\"\",\"still_path\":null}]}")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                }
                if (url.contains("/season/99")) throw new Net.HttpError(404, "api.themoviedb.org");
                return recorded.get(url, bearer);
            };
            List<Tmdb.Episode> season = tmdb.season(549, 24);
            assertEquals(2, season.size());
            Tmdb.Episode e5 = season.get(0);
            assertEquals(5, e5.number);
            assertEquals("Hard Cases", e5.name);
            assertEquals("https://image.tmdb.org/t/p/w300/x.jpg", e5.still);
            assertEquals(42, e5.runtime);
            assertNull(season.get(1).still);
            assertTrue("no such season", tmdb.season(549, 99).isEmpty());
        } finally {
            Net.fetcher = recorded;
        }
    }
}
