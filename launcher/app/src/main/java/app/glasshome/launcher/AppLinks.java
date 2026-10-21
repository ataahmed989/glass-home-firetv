package app.glasshome.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Links into the streaming apps for one film or show, so "Open Netflix" lands on the title itself
 * rather than on the app's home. TMDB says where a title streams but not where it is inside each
 * app; JustWatch, which supplies TMDB's streaming data, has each service's own link. Its API is
 * public but unofficial, so when it fails or changes, the app simply opens as before. A link is
 * kept only for an app on this TV that says it can open it, and only the title's name is sent:
 * for the banner's titles and a title's details page as they are shown, and for a Continue
 * Watching title when its card is opened.
 */
final class AppLinks {
    interface Poster {
        byte[] post(String url, String json) throws IOException;
    }

    /** Tests replace this, so they never reach the network. */
    static volatile Poster poster = AppLinks::http;

    private static final String API = "https://apis.justwatch.com/graphql";
    private static final String NETFLIX = "com.netflix.ninja";
    private static final String QUERY = "query($q:String!){popularTitles(country:US,first:10,filter:{searchQuery:$q})"
            + "{edges{node{objectType content(country:US,language:\"en\"){externalIds{tmdbId}}"
            + "offers(country:US,platform:ANDROID_TV){monetizationType standardWebURL deeplinkURL(platform:ANDROID_TV)}}}}}";

    /** Per title and app ("movie:603|com.netflix.ninja"): the link, or "" when that app has none. */
    private static final Map<String, String> found = new ConcurrentHashMap<>();
    /**
     * A title whose look-up failed is not looked up again for a while, and after several failures
     * in a row (JustWatch down or changed) none is: the banner turns every few seconds.
     */
    private static final long RETRY_MS = 10 * 60_000L, PAUSE_MS = 30 * 60_000L;
    private static final int FAILURES_TO_PAUSE = 3;
    private static final Map<String, Long> failedAt = new ConcurrentHashMap<>();
    private static int failuresInARow;
    private static long pausedUntil;

    private AppLinks() {}

    private static String key(Tmdb.Title t) {
        return (t.series ? "tv:" : "movie:") + t.id;
    }

    private static String key(Tmdb.Title t, String app) {
        return key(t) + "|" + app;
    }

    /** Tests start each case with nothing looked up and nothing failed. */
    static synchronized void reset() {
        found.clear();
        failedAt.clear();
        failuresInARow = 0;
        pausedUntil = 0;
    }

    /** The apps the title streams on, rents or sells on: the ones "Open …" can take it to. */
    private static Set<String> apps(Tmdb.Title t) {
        Set<String> apps = new LinkedHashSet<>(t.streamingApps);
        apps.addAll(t.storeApps);
        return apps;
    }

    /** Whether every one of the title's apps has been looked up. */
    static boolean lookedUp(Tmdb.Title t) {
        for (String app : apps(t)) {
            if (!found.containsKey(key(t, app))) return false;
        }
        return true;
    }

    /** The link into {@code app} for {@code t}, if it has been looked up and found. */
    static String known(Tmdb.Title t, String app) {
        String link = found.get(key(t, app));
        return link == null || link.isEmpty() ? null : link;
    }

    /**
     * Looks the title up (on the network: never on the main thread) and keeps, for each of its apps
     * that can open one of the links, the best one: a subscription or free link before a rental,
     * a rental before a purchase. A failed look-up is not kept, so a later one tries again.
     */
    static synchronized Map<String, String> lookUp(Context context, Tmdb.Title t) throws Exception {
        Set<String> apps = apps(t);
        if (!lookedUp(t)) {
            long now = SystemClock.elapsedRealtime();
            Long failed = failedAt.get(key(t));
            if (now < pausedUntil || (failed != null && now - failed < RETRY_MS)) throw new IOException("not looked up for now");
            try {
                JSONObject body = new JSONObject().put("query", QUERY).put("variables", new JSONObject().put("q", t.name));
                JSONObject response = new JSONObject(new String(poster.post(API, body.toString()), StandardCharsets.UTF_8));
                JSONArray edges = response.getJSONObject("data").getJSONObject("popularTitles").getJSONArray("edges");
                Map<String, String> links = pick(context.getPackageManager(), t, apps, edges);
                for (String app : apps) found.put(key(t, app), links.containsKey(app) ? links.get(app) : "");
                failuresInARow = 0;
            } catch (Exception e) {
                failedAt.put(key(t), now);
                if (++failuresInARow >= FAILURES_TO_PAUSE) pausedUntil = now + PAUSE_MS;
                throw e;
            }
        }
        Map<String, String> links = new HashMap<>();
        for (String app : apps) {
            String link = known(t, app);
            if (link != null) links.put(app, link);
        }
        return links;
    }

    /** From the search results, the title's own entry, then each app's best link. */
    static Map<String, String> pick(PackageManager pm, Tmdb.Title t, Set<String> apps, JSONArray edges) {
        Map<String, String> links = new HashMap<>();
        Map<String, Integer> ranks = new HashMap<>();
        for (int i = 0; i < edges.length(); i++) {
            JSONObject node = edges.optJSONObject(i) == null ? null : edges.optJSONObject(i).optJSONObject("node");
            if (node == null || "SHOW".equals(node.optString("objectType")) != t.series) continue;
            JSONObject content = node.optJSONObject("content");
            JSONObject ids = content == null ? null : content.optJSONObject("externalIds");
            if (ids == null || !String.valueOf(t.id).equals(ids.optString("tmdbId"))) continue;
            JSONArray offers = node.optJSONArray("offers");
            for (int k = 0; offers != null && k < offers.length(); k++) {
                JSONObject o = offers.optJSONObject(k);
                String link = o == null ? "" : page(t, clean(o.optString("standardWebURL")), o.optString("deeplinkURL"));
                if (link.isEmpty()) continue;
                int rank = rank(o.optString("monetizationType"));
                for (String app : apps) {
                    Integer best = ranks.get(app);
                    if (best != null && best <= rank) continue;
                    if (pm.resolveActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(app), 0) == null) continue;
                    ranks.put(app, rank);
                    links.put(app, link);
                }
            }
            break;
        }
        return links;
    }

    private static int rank(String monetization) {
        switch (monetization) {
            case "RENT":
                return 1;
            case "BUY":
                return 2;
            default:
                return 0;   // FLATRATE, FREE, ADS…: included with the service
        }
    }

    /**
     * The title's page in the app, never playback: "Open …" should land where its own Play or
     * Resume is. Links that start playing (HBO Max has only those) are not used, and Apple TV's
     * link to a series' first episode becomes the series' page, from the show id in its app link.
     */
    static String page(Tmdb.Title t, String link, String appLink) {
        if (link.isEmpty()) return "";
        Uri u = Uri.parse(link);
        String path = u.getPath() == null ? "" : u.getPath();
        if (path.contains("/watch") || path.contains("/play/")) return "";
        if ("tv.apple.com".equals(u.getHost()) && path.contains("/episode/")) {
            int at = appLink == null ? -1 : appLink.indexOf("showId=");
            if (at < 0) return link;
            String id = appLink.substring(at + "showId=".length()).split("[&#]")[0];
            String country = path.split("/").length > 1 ? path.split("/")[1] : "us";
            String slug = t.name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
            return id.isEmpty() ? link : "https://tv.apple.com/" + country + "/show/" + (slug.isEmpty() ? "show" : slug) + "/" + id;
        }
        return link;
    }

    /** Without the tracking and affiliate parameters JustWatch adds; everything else is kept. */
    static String clean(String link) {
        if (link == null || link.isEmpty()) return "";
        Uri u = Uri.parse(link);
        if (u.getScheme() == null || !u.getScheme().startsWith("http")) return "";
        Uri.Builder b = u.buildUpon().clearQuery();
        for (String name : u.getQueryParameterNames()) {
            if (name.startsWith("utm_") || name.equals("at") || name.equals("ct") || name.startsWith("its")) continue;
            for (String value : u.getQueryParameters(name)) b.appendQueryParameter(name, value);
        }
        return b.build().toString();
    }

    /**
     * An explicit intent opening {@code link} in {@code app}, or null when the app cannot open it.
     * The app starts afresh at the link: one already running (Netflix, checked on the TV) shows
     * whatever it was on and ignores a link handed to it then.
     */
    static Intent intent(Context context, String app, String link) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(app);
        ResolveInfo r = context.getPackageManager().resolveActivity(i, 0);
        if (r == null) return null;
        i.setComponent(new ComponentName(r.activityInfo.packageName, r.activityInfo.name))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        // Netflix follows a link from another app only with a "source" (30, as other TV launchers send; checked on the TV)
        if (NETFLIX.equals(app)) i.putExtra("source", "30");
        return i;
    }

    static byte[] http(String url, String json) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept-Encoding", "gzip");
        c.setRequestProperty("User-Agent", "GlassHome/1.0 (Linux; Android 11; Fire TV)");
        try {
            try (OutputStream out = c.getOutputStream()) {
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new IOException("HTTP " + code + " from " + c.getURL().getHost());
            try (InputStream in = c.getInputStream()) {
                return Net.gunzip(Net.readAll(in));
            }
        } finally {
            c.disconnect();
        }
    }
}
