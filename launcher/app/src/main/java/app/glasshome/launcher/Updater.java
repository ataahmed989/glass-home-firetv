package app.glasshome.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Glass Home's updates, as FirePlayer has them: every push to main publishes a GitHub release
 * "v1.0.<build>" with GlassHome.apk (see .github/workflows/release.yml), <build> being the
 * release's versionCode. A newer one is offered when Glass Home starts and from Home Screen
 * settings; it is downloaded here and handed to Fire OS's installer, which asks before installing
 * it over this one (the same signing key, so settings and permissions stay).
 *
 * Network calls block: callers run them off the main thread.
 */
final class Updater {
    static final String LATEST_URL = "https://api.github.com/repos/ataahmed989/glass-home-firetv/releases/latest";

    static final class Release {
        final long versionCode;
        final String name, apkUrl;

        Release(long versionCode, String name, String apkUrl) {
            this.versionCode = versionCode;
            this.name = name;
            this.apkUrl = apkUrl;
        }
    }

    interface Progress {
        /** Percent downloaded, 0-100, or -1 while the size isn't known. */
        void at(int percent);
    }

    /** What the last check found, for Home Screen settings: null before any check. */
    static volatile Release latest;
    static volatile boolean checkFailed;

    private Updater() {}

    /** The newest release on GitHub. */
    static Release fetchLatest() throws IOException {
        try {
            Release r = parse(new String(Net.get(LATEST_URL, null), StandardCharsets.UTF_8));
            latest = r;
            checkFailed = false;
            return r;
        } catch (IOException | JSONException | RuntimeException e) {
            checkFailed = true;
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
    }

    /** GitHub's latest-release answer: the tag "v1.0.<build>" and the release's .apk. */
    static Release parse(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        String tag = o.getString("tag_name");
        long code = Long.parseLong(tag.substring(tag.lastIndexOf('.') + 1));
        JSONArray assets = o.getJSONArray("assets");
        String apk = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (a.getString("name").endsWith(".apk")) apk = a.getString("browser_download_url");
        }
        if (apk == null) throw new JSONException("no APK in " + tag);
        return new Release(code, tag.startsWith("v") ? tag.substring(1) : tag, apk);
    }

    static long installedCode(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return Long.MAX_VALUE;   // can't happen for itself; never offer anything then
        }
    }

    static String installedName(Context c) {
        try {
            PackageInfo info = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    static boolean isNewer(Context c, Release r) {
        return r != null && r.versionCode > installedCode(c);
    }

    /** Fire OS lets Glass Home hand it apps to install ("Install unknown apps", granted once). */
    static boolean canInstall(Context c) {
        return c.getPackageManager().canRequestPackageInstalls();
    }

    /** Where that permission is given: Glass Home's page under "Install unknown apps". */
    static Intent installPermissionSettings(Context c) {
        return new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.getPackageName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** Downloads the release's APK into the cache, reporting progress (on this thread). */
    static File download(Context c, Release r, Progress progress) throws IOException {
        File dir = new File(c.getCacheDir(), "updates");
        dir.mkdirs();
        File file = new File(dir, "GlassHome.apk");
        File part = new File(dir, "GlassHome.apk.part");
        HttpURLConnection conn = (HttpURLConnection) new URL(r.apkUrl).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "GlassHome/1.0 (Linux; Android 11; Fire TV)");
        try {
            int code = conn.getResponseCode();
            if (code / 100 != 2) throw new IOException("HTTP " + code);
            long total = conn.getContentLengthLong();
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[65536];
                long done = 0;
                int shown = -2;
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    done += n;
                    int pct = total > 0 ? (int) (done * 100 / total) : -1;
                    if (pct != shown) progress.at(shown = pct);
                }
                if (total > 0 && done != total) throw new IOException("download cut short");
            }
        } finally {
            conn.disconnect();
        }
        if (!part.renameTo(file)) throw new IOException("couldn't keep the download");
        return file;
    }

    /** Fire OS's installer, asked to install {@code apk} over this Glass Home. */
    static Intent installIntent(Context c, File apk) {
        Uri uri = FileProvider.getUriForFile(c, c.getPackageName() + ".updates", apk);
        return new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
