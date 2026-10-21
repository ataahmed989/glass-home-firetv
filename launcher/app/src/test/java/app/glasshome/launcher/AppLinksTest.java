package app.glasshome.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowPackageManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** "Open …" on a film or show: which link each app gets, from JustWatch's answer. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30)
public class AppLinksTest {
    private static final String MAX = "com.hbo.hbonow", PRIME = "com.amazon.firebat", NETFLIX = "com.netflix.ninja";
    private static final String MAX_LINK = "https://play.max.com/movie/0c3c7a6e";
    private static final String RENT_LINK = "https://app.primevideo.com/detail?gti=amzn1.dv.gti.rent";
    private static final String BUY_LINK = "https://app.primevideo.com/detail?gti=amzn1.dv.gti.buy";

    @After
    public void restore() {
        AppLinks.poster = AppLinks::http;
    }

    @Test
    public void trackingParametersAreDropped() {
        assertEquals("https://app.primevideo.com/detail?gti=amzn1.dv.gti.x",
                AppLinks.clean("https://app.primevideo.com/detail?gti=amzn1.dv.gti.x&utm_source=justwatch&at=1&ct=2&itscg=3"));
        assertEquals(MAX_LINK, AppLinks.clean(MAX_LINK));
        assertEquals("", AppLinks.clean("netflix://title/1"));
        assertEquals("", AppLinks.clean(""));
    }

    /**
     * Only the title with the same TMDB id counts (a remake of the same name does not), each app
     * gets a link only if it says it can open it, and a rental beats a purchase.
     */
    @Test
    public void eachAppGetsItsBestLinkForThisTitle() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        ShadowPackageManager pm = shadowOf(app.getPackageManager());
        opens(pm, MAX, MAX_LINK);
        opens(pm, PRIME, RENT_LINK);
        opens(pm, PRIME, BUY_LINK);
        Tmdb.Title t = movie(MAX, PRIME, NETFLIX);

        Set<String> apps = new LinkedHashSet<>(t.streamingApps);
        apps.addAll(t.storeApps);
        Map<String, String> links = AppLinks.pick(app.getPackageManager(), t, apps, results());
        assertEquals(MAX_LINK, links.get(MAX));
        assertEquals(RENT_LINK, links.get(PRIME));
        assertFalse("Netflix cannot open the Netflix link here, so it opens as an app", links.containsKey(NETFLIX));
    }

    /** Only a title's page, never playback; Apple TV's first-episode link becomes the series' page (as checked on the TV). */
    @Test
    public void linksGoToTheTitlesPageNotPlayback() {
        Tmdb.Title severance = new Tmdb.Title(95396, true, "Severance", "", "", "", "2022-02-18", new int[0]);
        assertEquals("https://tv.apple.com/us/show/severance/umc.cmc.1srk2goyh2q2zdxcx605w8vtx",
                AppLinks.page(severance, "https://tv.apple.com/us/episode/good-news-about-hell/umc.cmc.s80mx1ic96pu6ewupz8pfasf",
                        "intent://tv.apple.com/us/episode/good-news-about-hell/umc.cmc.s80mx1ic96pu6ewupz8pfasf?showId=umc.cmc.1srk2goyh2q2zdxcx605w8vtx&playableId=tvs.sbd.4000#Intent;scheme=https;end"));
        assertEquals("without a show id the episode's page is kept", "https://tv.apple.com/us/episode/x/umc.cmc.1",
                AppLinks.page(severance, "https://tv.apple.com/us/episode/x/umc.cmc.1", ""));
        assertEquals("", AppLinks.page(severance, "https://play.hbomax.com/video/watch/dae9e532-3714-4f2e-b758-fb9a13def902", ""));
        assertEquals("", AppLinks.page(severance, "https://www.disneyplus.com/play/91310366-d44b-44a4-bdc4-71f999472826", ""));
        String andor = "https://www.disneyplus.com/browse/entity-faba988a-a9f5-45f2-a074-0775a7d6f67a";
        assertEquals(andor, AppLinks.page(severance, andor, ""));
        assertEquals(RENT_LINK, AppLinks.page(severance, RENT_LINK, ""));
        assertEquals("https://www.netflix.com/title/70143836", AppLinks.page(severance, "https://www.netflix.com/title/70143836", ""));
    }

    /** The app starts afresh at the link (a running Netflix ignores one otherwise), and Netflix is told where it came from. */
    @Test
    public void theAppStartsAfreshAtTheLink() {
        Application app = RuntimeEnvironment.getApplication();
        String netflixLink = "https://www.netflix.com/title/70143836";
        opens(shadowOf(app.getPackageManager()), NETFLIX, netflixLink);
        opens(shadowOf(app.getPackageManager()), MAX, MAX_LINK);

        Intent netflix = AppLinks.intent(app, NETFLIX, netflixLink);
        assertEquals(Uri.parse(netflixLink), netflix.getData());
        assertEquals(NETFLIX + ".MainActivity", netflix.getComponent().getClassName());
        int fresh = Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK;
        assertEquals(fresh, netflix.getFlags() & fresh);
        assertEquals("30", netflix.getStringExtra("source"));

        Intent max = AppLinks.intent(app, MAX, MAX_LINK);
        assertEquals(fresh, max.getFlags() & fresh);
        assertNull(max.getStringExtra("source"));
        assertNull("an app that cannot open the link", AppLinks.intent(app, PRIME, MAX_LINK));
    }

    /** One look-up per title, sending only its name; "Open …" then finds the link without waiting. */
    @Test
    public void aTitleIsLookedUpOnce() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        opens(shadowOf(app.getPackageManager()), MAX, MAX_LINK);
        Tmdb.Title t = movie(MAX);
        byte[] answer = new JSONObject().put("data", new JSONObject().put("popularTitles", new JSONObject().put("edges", results())))
                .toString().getBytes(StandardCharsets.UTF_8);
        List<String> sent = new ArrayList<>();
        AppLinks.poster = (url, json) -> {
            sent.add(json);
            return answer;
        };

        assertFalse(AppLinks.lookedUp(t));
        assertEquals(MAX_LINK, AppLinks.lookUp(app, t).get(MAX));
        assertEquals(MAX_LINK, AppLinks.lookUp(app, t).get(MAX));
        assertEquals(1, sent.size());
        assertEquals("A Test Film", new JSONObject(sent.get(0)).getJSONObject("variables").getString("q"));
        assertTrue(AppLinks.lookedUp(t));
        assertEquals(MAX_LINK, AppLinks.known(t, MAX));
        assertNull(AppLinks.known(t, PRIME));
    }

    /** A failed look-up is not kept, so the next "Open …" tries again (and meanwhile the app opens). */
    @Test
    public void aFailedLookUpIsTriedAgain() {
        Application app = RuntimeEnvironment.getApplication();
        Tmdb.Title t = new Tmdb.Title(990001, true, "A Test Series", "", "", "", "2026-01-01", new int[0]);
        t.streamingApps.add("com.apple.atve.amazon.appletv");
        AppLinks.poster = (url, json) -> {
            throw new IOException("offline");
        };
        try {
            AppLinks.lookUp(app, t);
        } catch (Exception expected) {
            // the app itself opens
        }
        assertFalse(AppLinks.lookedUp(t));
    }

    private static Tmdb.Title movie(String streaming, String... stores) {
        Tmdb.Title t = new Tmdb.Title(990002, false, "A Test Film", "", "", "", "2026-01-01", new int[0]);
        t.streamingApps.add(streaming);
        for (String s : stores) t.storeApps.add(s);
        return t;
    }

    private static void opens(ShadowPackageManager pm, String app, String link) {
        pm.addResolveInfoForIntent(new Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(app), TestHome.resolve(app, app));
    }

    /** The shape of JustWatch's answer: a remake with the same name first, then the film itself. */
    private static JSONArray results() throws Exception {
        return new JSONArray()
                .put(node("MOVIE", "11111", offer("FLATRATE", "https://play.max.com/movie/remake")))
                .put(node("SHOW", "990002", offer("FLATRATE", "https://play.max.com/show/same-id-but-a-series")))
                .put(node("MOVIE", "990002",
                        offer("BUY", BUY_LINK + "&utm_source=justwatch"),
                        offer("RENT", RENT_LINK + "&utm_source=justwatch"),
                        offer("FLATRATE", MAX_LINK + "?utm_source=justwatch&utm_medium=partner"),
                        offer("FLATRATE", "https://www.netflix.com/title/81001234")));
    }

    private static JSONObject node(String type, String tmdbId, JSONObject... offers) throws Exception {
        JSONArray o = new JSONArray();
        for (JSONObject offer : offers) o.put(offer);
        JSONObject content = new JSONObject().put("externalIds", new JSONObject().put("tmdbId", tmdbId));
        return new JSONObject().put("node", new JSONObject().put("objectType", type).put("content", content).put("offers", o));
    }

    private static JSONObject offer(String monetization, String link) throws Exception {
        return new JSONObject().put("monetizationType", monetization).put("standardWebURL", link);
    }
}
