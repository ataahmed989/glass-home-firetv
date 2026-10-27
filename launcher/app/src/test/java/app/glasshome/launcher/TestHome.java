package app.glasshome.launcher;

import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONObject;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowPackageManager;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;

/** Shared harness: fakes the user's installed apps and drives HomeActivity with remote key events. */
final class TestHome {
    static final String[][] APPS = {
            {"app.fireplayer", "FirePlayer"}, {"com.netflix.ninja", "Netflix"}, {"com.amazon.firetv.youtube", "YouTube"},
            {"com.apple.atve.amazon.appletv", "Apple TV"}, {"com.disney.disneyplus", "Disney+"}, {"com.hbo.hbonow", "Max"},
            {"ar.tvplayer.tv", "TiviMate"}, {"com.amazon.firebat", "Prime Video"}, {"com.spotify.tv.android", "Spotify"},
            {"gamepass.fire.tv", "Game Pass"}, {"com.esaba.downloader", "Downloader"}, {"com.tailscale.ipn", "Tailscale"},
    };
    static final Intent LEANBACK = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);

    final ActivityController<HomeActivity> controller;
    final HomeActivity activity;
    final RecyclerView grid;
    final HeroStage stage;
    final WidgetPanel weather;
    final WeatherDetail detail;
    final ClockCapsule capsule;
    final SettingsPanel panel;
    final TabBar tabs;
    final DetailView titleDetail;
    final ShadowPackageManager pm;

    TestHome() throws Exception {
        this(false, false);
    }

    TestHome(boolean withBackground) throws Exception {
        this(withBackground, false);
    }

    /** With a TMDB token configured, so Movies and TV Shows load (from recorded responses). */
    static TestHome withTmdb() throws Exception {
        return new TestHome(false, true);
    }

    /**
     * {@code withBackground}: puts a test image in the app's backgrounds folder before launch.
     * {@code withTmdb}: writes an apps.json override carrying a TMDB token.
     */
    TestHome(boolean withBackground, boolean withTmdb) throws Exception {
        TileView.swayEnabled = false;   // every render shows the focused tile at rest
        ImageCache.reset();
        Fixtures.install();
        Application app = RuntimeEnvironment.getApplication();
        if (withTmdb) {
            JSONObject cfg;
            try (java.io.InputStream in = app.getAssets().open("apps.json")) {
                cfg = new JSONObject(Weather.read(in));
            }
            cfg.put("tmdb_api_key", Fixtures.tmdbToken());
            try (FileOutputStream out = new FileOutputStream(new File(app.getExternalFilesDir(null), "apps.json"))) {
                out.write(cfg.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        if (withBackground) {
            Bitmap b = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888);
            Paint paint = new Paint();
            paint.setShader(new LinearGradient(0, 0, 0, 1080, 0xFF2C6E9E, 0xFFE0A35C, Shader.TileMode.CLAMP));
            new Canvas(b).drawRect(0, 0, 1920, 1080, paint);
            try (FileOutputStream out = new FileOutputStream(new File(app.getExternalFilesDir("backgrounds"), "test-sky.jpg"))) {
                b.compress(Bitmap.CompressFormat.JPEG, 90, out);
            }
        }
        pm = shadowOf(app.getPackageManager());
        for (String[] a : APPS) pm.addResolveInfoForIntent(LEANBACK, resolve(a[0], a[1]));
        pm.addResolveInfoForIntent(new Intent(Settings.ACTION_SETTINGS), resolve("com.amazon.tv.settings.v2", "Settings"));

        controller = Robolectric.buildActivity(HomeActivity.class).setup();
        activity = controller.get();
        View root = activity.getWindow().getDecorView();
        grid = find(root, RecyclerView.class);
        stage = find(root, HeroStage.class);
        weather = find(root, WidgetPanel.class);
        detail = find(root, WeatherDetail.class);
        capsule = find(root, ClockCapsule.class);
        panel = find(root, SettingsPanel.class);
        tabs = find(root, TabBar.class);
        titleDetail = find(root, DetailView.class);
        for (int i = 0; i < 200 && (grid.getAdapter().getItemCount() == 0 || grid.getChildCount() == 0); i++) {
            Thread.sleep(25);
            idle();
        }
        // The JVM window starts in touch mode, where focus requests are ignored; a remote press leaves it
        ((AppAdapter.Holder) grid.findViewHolderForAdapterPosition(0)).tile.requestFocusFromTouch();
        settle();
    }

    static ResolveInfo resolve(String pkg, String label) {
        ApplicationInfo appInfo = new ApplicationInfo();
        appInfo.packageName = pkg;
        appInfo.nonLocalizedLabel = label;
        ActivityInfo activityInfo = new ActivityInfo();
        activityInfo.packageName = pkg;
        activityInfo.name = pkg + ".MainActivity";
        activityInfo.applicationInfo = appInfo;
        ResolveInfo info = new ResolveInfo();
        info.activityInfo = activityInfo;
        return info;
    }

    static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    /** Lets background work, animations and smooth scrolls finish. */
    static void settle() throws InterruptedException {
        for (int i = 0; i < 12; i++) {
            Thread.sleep(15);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
        }
    }

    static void advance(long ms) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
    }

    /** One short press: down then up. */
    void press(int code) throws InterruptedException {
        long t = SystemClock.uptimeMillis();
        activity.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        activity.dispatchKeyEvent(new KeyEvent(t, t + 40, KeyEvent.ACTION_UP, code, 0));
        settle();
    }

    /** Holds a key for {@code holdMs}, with the remote auto-repeating every {@code everyMs}. */
    void hold(int code, long holdMs, long everyMs) throws InterruptedException {
        long down = SystemClock.uptimeMillis();
        activity.dispatchKeyEvent(new KeyEvent(down, down, KeyEvent.ACTION_DOWN, code, 0));
        int repeat = 1;
        for (long t = everyMs; t <= holdMs; t += everyMs) {
            activity.dispatchKeyEvent(new KeyEvent(down, down + t, KeyEvent.ACTION_DOWN, code, repeat++));
        }
        activity.dispatchKeyEvent(new KeyEvent(down, down + holdMs + 10, KeyEvent.ACTION_UP, code, 0));
        settle();
    }

    void back() throws InterruptedException {
        press(KeyEvent.KEYCODE_BACK);
    }

    void menu() throws InterruptedException {
        press(KeyEvent.KEYCODE_MENU);
    }

    /** A press without letting time pass afterwards, for checking what happens within a dwell. */
    void tap(int code) {
        long t = SystemClock.uptimeMillis();
        activity.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        activity.dispatchKeyEvent(new KeyEvent(t, t + 40, KeyEvent.ACTION_UP, code, 0));
        idle();
    }

    ShelfPage page(int p) {
        return activity.pageView(p);
    }

    /** Waits for a browse page's content (recorded responses) to load. */
    void waitForPage(int p) throws InterruptedException {
        for (int i = 0; i < 2400 && !page(p).hasModel(); i++) {
            Thread.sleep(25);
            idle();
        }
        if (!page(p).hasModel()) throw new AssertionError("page " + p + " did not load");
        settleImages();
    }

    /** Lets artwork downloads (replayed from disk) decode and arrive. */
    static void settleImages() throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            Thread.sleep(40);
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
        }
        waitForImages();
    }

    /** True if there were loads in flight (now finished); false if nothing was loading. */
    private static boolean waitForImages() throws InterruptedException {
        ImageCache cache = ImageCache.get(RuntimeEnvironment.getApplication());
        if (cache.pending() == 0) return false;
        for (int i = 0; i < 800 && cache.pending() > 0; i++) {
            Thread.sleep(25);
            idle();
        }
        idle();
        return true;
    }

    int focused() {
        return activity.focusedPosition();
    }

    HomeActivity.Zone zone() {
        return activity.zone();
    }

    /** Top of the focused tile's cell on screen; the dock row sits at 801 on a 1920x1080 screen. */
    int focusedRowTop() {
        View cell = grid.getFocusedChild();
        return cell == null ? -1 : Math.round(cell.getTop() + cell.getTranslationY());
    }

    /** A clearly fake featured slide for layout and navigation tests (the real ones come from TMDB). */
    void injectFeaturedSlide(String appKey, String brandName) {
        Bitmap backdrop = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888);
        Paint p = new Paint();
        p.setShader(new LinearGradient(0, 0, 1280, 720, 0xFF3A5A78, 0xFF8A5A3A, Shader.TileMode.CLAMP));
        new Canvas(backdrop).drawRect(0, 0, 1280, 720, p);
        FeaturedTitles.Title title = new FeaturedTitles.Title("Test Title", "2026", "Series",
                "Placeholder overview used only to check the featured layout in an automated test.", "/test.jpg");
        HeroSlide slide = HeroSlide.featured(appKey, Brand.of(appKey, brandName), backdrop, title);
        activity.addFeaturedSlide(slide, activity.featuredGeneration());
    }

    /** Real Open-Meteo response saved from this session, so renders show genuine data offline. */
    void loadSampleWeather() throws Exception {
        String json = new String(Files.readAllBytes(new File("src/test/weather-sample.json").toPath()), StandardCharsets.UTF_8);
        Weather.Data data = Weather.parse(new JSONObject(json), Fixtures.RECORDED_AT);
        weather.setData(data);
        detail.setData(data, false);
    }

    void capture(String name) throws Exception {
        View root = activity.getWindow().getDecorView();
        Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        // Drawing is what asks for artwork: draw, wait for it to arrive, and draw again
        for (int round = 0; round < 4; round++) {
            root.draw(new Canvas(bitmap));
            if (!waitForImages()) break;
        }
        root.draw(new Canvas(bitmap));
        File dir = new File(System.getProperty("screenshot.dir", "screenshots"));
        dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        System.out.println("SCREENSHOT " + new File(dir, name));
    }

    static <T extends View> T find(View v, Class<T> type) {
        if (type.isInstance(v)) return type.cast(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                T r = find(g.getChildAt(i), type);
                if (r != null) return r;
            }
        }
        return null;
    }
}
