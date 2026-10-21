package app.glasshome.launcher;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * App order and options from apps.json. A copy of it in the app's private files (written with
 * {@code adb shell run-as}; Fire OS lets ADB into nothing else) or in its external files folder
 * overrides the bundled one.
 */
final class HomeConfig {
    final List<String> order = new ArrayList<>();
    final Set<String> hidden = new HashSet<>();
    final boolean blurGlass;
    final boolean reduceMotion;
    final String profileInitial;
    final String tmdbApiKey;
    /** Sports teams to follow, as "league:ABBR" (e.g. "nfl:CIN"); Cincinnati and Ohio teams by default. */
    final List<String> favoriteTeams = new ArrayList<>();

    private HomeConfig(JSONObject json) {
        JSONArray teams = json.optJSONArray("favorite_teams");
        if (teams == null) favoriteTeams.addAll(Sports.DEFAULT_TEAMS);
        for (int i = 0; teams != null && i < teams.length(); i++) favoriteTeams.add(teams.optString(i));
        JSONArray o = json.optJSONArray("order");
        for (int i = 0; o != null && i < o.length(); i++) order.add(o.optString(i));
        JSONArray h = json.optJSONArray("hidden");
        for (int i = 0; h != null && i < h.length(); i++) hidden.add(h.optString(i));
        blurGlass = !"tint".equals(json.optString("glass", "blur"));
        reduceMotion = json.optBoolean("reduce_motion", false);
        profileInitial = json.optString("profile_initial", "");
        tmdbApiKey = json.optString("tmdb_api_key", "").trim();
    }

    static HomeConfig load(Context context) {
        File external = context.getExternalFilesDir(null);
        File[] custom = {new File(context.getFilesDir(), "apps.json"), external == null ? null : new File(external, "apps.json")};
        try {
            for (File f : custom) {
                if (f != null && f.isFile()) return new HomeConfig(new JSONObject(read(new FileInputStream(f))));
            }
            return new HomeConfig(new JSONObject(read(context.getAssets().open("apps.json"))));
        } catch (Exception e) {
            try {
                return new HomeConfig(new JSONObject(read(context.getAssets().open("apps.json"))));
            } catch (Exception fatal) {
                return new HomeConfig(new JSONObject());
            }
        }
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream s = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            for (int n; (n = s.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
