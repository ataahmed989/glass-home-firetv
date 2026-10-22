package app.glasshome.launcher;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Glass Home: the focused app's content fills the screen, a fixed glass dock holds the focused
 * row of tiles with the next row peeking below, a weather card sits to the right of the content,
 * and a time/profile capsule sits in the corner. A glass tab bar at the top switches between
 * Home and the Movies, TV Shows and Sports pages.
 *
 * Remote navigation is handled in one place ({@link #dispatchKeyEvent}) across these zones: the
 * app grid, the hero's "Open …" action, the weather card, the profile capsule, the tab bar, a
 * browse page, and the overlays (detailed forecast, home-screen settings, a title's details).
 * Arrows and Select are consumed there, so the framework's own focus search never moves focus a
 * second time; the ☰ (Menu) key goes to the tab bar; Back goes through {@link #onBackPressed};
 * Home is left to the system.
 */
public final class HomeActivity extends Activity implements AppAdapter.Listener, ShelfPage.Listener {
    private static final String STATE_FOCUS = "focus";
    private static final String STATE_FOCUS_KEY = "focus_key";
    private static final long WEATHER_EVERY_MS = 15 * 60 * 1000L;
    private static final long WEATHER_RETRY_MS = 5 * 60 * 1000L;
    private static final long CATALOG_REFRESH_MS = 2 * 60 * 1000L;
    private static final long SPORTS_LIVE_MS = 60 * 1000L, SPORTS_IDLE_MS = 10 * 60 * 1000L;
    private static final long FEATURED_WAIT_MS = 1200;
    /** How long "Open …" waits for a title's link before opening the app itself. */
    private static final long LINK_WAIT_MS = 2000;
    /** "Open …" waiting for a title's link (main thread only); null when nothing is waiting. */
    private Runnable linkWait;
    /** How long after another app takes the screen Glass Home's given-up pictures are collected. */
    private static final long COLLECT_AFTER_MS = 10_000;
    /** The cover over the trip back goes after this regardless (it also goes on any key). */
    private static final long SHOWN_FALLBACK_MS = 1500;
    /** Once the screen has been still this long (focus and the tile's sway settled), its frame is kept for the Home button. */
    private static final long SNAPSHOT_IDLE_MS = 2800;
    /** Back from an app, what comes over the network is fetched again this long after Glass Home resumed. */
    private static final long RESUME_REFRESH_MS = 600;

    enum Zone { GRID, HERO, WEATHER, CAPSULE, OVERLAY, SETTINGS, TABS, PAGE, PAGE_DETAIL, CONTROL }

    static final int HOME = 0, MOVIES = 1, TV = 2, SPORTS = 3;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final ExecutorService pagesNet = Executors.newFixedThreadPool(2);
    /** Look-ups of titles' links (one at a time, apart from the pages' own loading). */
    private final ExecutorService linksNet = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private HomeConfig config;
    private HomeSettings settings;
    private List<Backgrounds.Entry> backgrounds = new ArrayList<>();
    private Bitmap background;
    private String backgroundName;
    private final java.util.Map<String, Bitmap> thumbnails = new java.util.HashMap<>();
    private SettingsPanel panel;
    private int screenW, screenH;
    private HeroStage stage;
    private WidgetPanel weather;
    private WeatherDetail detail;
    private RecyclerView grid;
    private AppAdapter adapter;
    private GlassDock dock;
    private Glass dockGlass, capsuleGlass, weatherGlass, tabGlass;
    private ClockCapsule capsule;
    private NavSounds navSounds;
    private TabBar tabs;
    private final ShelfPage[] pages = new ShelfPage[4];   // index by tab; [HOME] stays null
    private final int[] pageGeneration = new int[4];
    private DetailView titleDetail;
    private ControlCenter control;
    private Glass controlGlass, panelGlass;
    private int page = HOME;
    private boolean barHidden;

    // Focus memory
    private int lastFocused;
    private String lastFocusedKey;
    private int lastDockColumn;
    private Zone lastZone = Zone.GRID;
    private Zone homeZone = Zone.GRID;   // where focus was on Home, for coming back down from the tabs

    private final Runnable commitTab = () -> showPage(tabs.selected(), false);
    private final Runnable sportsTick = new Runnable() {
        @Override
        public void run() {
            if (page == SPORTS && resumed) loadPage(SPORTS);
        }
    };

    // Remote input
    private boolean selectArmed;
    private long lastStepTime;

    private int appsGeneration;
    private int backdropGeneration;
    private int featuredGeneration;
    private boolean resumed;
    /**
     * Glass Home has been out of sight (onStop) since it was last in front: back from another app,
     * rather than from a trip through Amazon's home (the Home button pressed on Home), which only pauses it.
     */
    private boolean stopped = true;
    private boolean teamsFetching;
    /** Whether Glass Home is the screen in front (for HomeButtonService); set on the main thread. */
    static volatile boolean inFront;

    // What fills the screen behind Home: the background, or the focused streaming app's featured titles
    private String presentedKey;
    private AppEntry presentedApp;
    private AppEntry pendingApp;
    private List<HeroSlide> slides;
    private int slideIndex;

    private final Runnable presentPending = () -> {
        if (pendingApp != null) present(pendingApp);
    };

    private final Runnable takeSnapshot = () -> {
        if (!resumed || !showingHomeAsTheHomeButtonLeavesIt()) return;
        HomeSnapshot.capture(this);
        snapshotDue = false;
    };
    /** Home has been drawn since its snapshot was last copied (see {@link #snapshotLater}). */
    private boolean snapshotDue;

    /** In case the window's "shown" report does not come: the cover goes regardless, a while after Glass Home resumed. */
    private final Runnable shownFallback = HomeButtonService::glassHomeShown;

    /** A streaming app's featured titles are slow to come: the plain background takes over meanwhile. */
    private final Runnable featuredLate = () -> {
        if (slides == null) showBackground();
    };

    private final Runnable advanceSlide = new Runnable() {
        @Override
        public void run() {
            if (slides == null || slides.size() < 2 || Ui.reducedMotion) return;
            slideIndex = (slideIndex + 1) % slides.size();
            showSlide(slides.get(slideIndex), Ui.SLIDE_FADE_MS);
            main.postDelayed(this, Ui.SLIDE_INTERVAL_MS);
        }
    };

    private final Runnable weatherTick = new Runnable() {
        @Override
        public void run() {
            refreshWeather();
            main.postDelayed(this, WEATHER_EVERY_MS);
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            loadApps();
        }
    };

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            capsule.updateTime();
            weather.invalidate();   // re-checks staleness
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Rect window = getWindowManager().getCurrentWindowMetrics().getBounds();
        screenW = window.width();
        screenH = window.height();
        Ui.init(screenW);
        config = HomeConfig.load(this);
        settings = new HomeSettings(this);
        backgrounds = Backgrounds.list(this);
        Ui.reducedMotion = config.reduceMotion || !ValueAnimator.areAnimatorsEnabled();
        if (savedInstanceState != null) {
            lastFocused = savedInstanceState.getInt(STATE_FOCUS, 0);
            lastFocusedKey = savedInstanceState.getString(STATE_FOCUS_KEY);
        }
        lastDockColumn = lastFocused % Ui.COLUMNS;

        setContentView(buildViews());
        navSounds = new NavSounds(this);
        // Anything drawn (a key, the clock turning, the weather…) leaves Home's snapshot behind
        getWindow().getDecorView().getViewTreeObserver().addOnDrawListener(() -> snapshotDue = true);
        loadProfile();
        loadApps();

        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packages.addDataScheme("package");
        registerReceiver(packageReceiver, packages);
        IntentFilter time = new IntentFilter(Intent.ACTION_TIME_TICK);
        time.addAction(Intent.ACTION_TIME_CHANGED);
        time.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, time);
    }

    private View buildViews() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        root.setClipChildren(false);

        stage = new HeroStage(this);
        root.addView(stage, match());

        weatherGlass = new Glass(config.blurGlass, 0xDB1E2636);
        weather = new WidgetPanel(this, weatherGlass);
        weather.setLayout(settings.widgetLayout());
        // The widgets' view reaches PAD past them on every side (see WidgetPanel)
        int pad = Ui.px(WidgetPanel.PAD);
        FrameLayout.LayoutParams wl = new FrameLayout.LayoutParams(Ui.px(Ui.WEATHER_W) + 2 * pad, Ui.px(Ui.WEATHER_H) + 2 * pad);
        wl.leftMargin = Ui.px(Ui.WEATHER_LEFT) - pad;
        wl.topMargin = Ui.px(Ui.WEATHER_TOP) - pad;
        root.addView(weather, wl);

        dockGlass = new Glass(config.blurGlass, 0xA61C1E25);
        dock = new GlassDock(dockGlass);
        adapter = new AppAdapter(this);
        grid = new RecyclerView(this);
        grid.setLayoutManager(new HomeLayoutManager(this));
        grid.setAdapter(adapter);
        grid.setItemAnimator(null);
        grid.setHasFixedSize(true);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        grid.setVerticalScrollBarEnabled(false);
        grid.setItemViewCacheSize(24);
        grid.addItemDecoration(dock);
        // Row 0 starts in the dock at y = 801; the bottom padding lets the last row reach the dock too
        grid.setPadding(Ui.px(Ui.GRID_SIDE), Ui.px(Ui.DOCK_ROW_TOP), Ui.px(Ui.GRID_SIDE),
                Math.max(0, screenH - Ui.px(Ui.DOCK_ROW_TOP) - Ui.px(Ui.ROW_PITCH)));
        grid.getViewTreeObserver().addOnPreDrawListener(() -> {
            fadeRowsAboveDock();
            return true;
        });
        root.addView(grid, match());

        // The browse pages cover Home completely while they are shown
        String[] names = {null, "Movies", "TV Shows", "Sports"};
        for (int p = MOVIES; p <= SPORTS; p++) {
            pages[p] = new ShelfPage(this, names[p]);
            pages[p].setListener(this);
            pages[p].setVisibility(View.GONE);
            root.addView(pages[p], match());
        }

        // Darker glass than the dock: the capsule's white text must hold up over bright skies
        capsuleGlass = new Glass(config.blurGlass, 0xB31C1E23);
        capsule = new ClockCapsule(this, capsuleGlass);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.px(Ui.CAPSULE_H), Gravity.TOP | Gravity.END);
        cl.topMargin = Ui.px(Ui.CAPSULE_TOP);
        cl.rightMargin = Ui.px(Ui.CAPSULE_RIGHT);
        root.addView(capsule, cl);
        capsule.updateTime();

        tabGlass = new Glass(config.blurGlass, 0xB31C1E23);
        tabs = new TabBar(this, tabGlass);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.px(Ui.CAPSULE_H), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tl.topMargin = Ui.px(Ui.CAPSULE_TOP);
        root.addView(tabs, tl);

        // Overlays, above everything else
        detail = new WeatherDetail(this, weatherGlass);
        root.addView(detail, match());
        titleDetail = new DetailView(this);
        root.addView(titleDetail, match());
        controlGlass = new Glass(config.blurGlass, 0xE62A2E36);
        control = new ControlCenter(this, controlGlass);
        control.setListener(this::onControl);
        root.addView(control, match());
        panelGlass = new Glass(config.blurGlass, 0xE6181A20);
        panel = new SettingsPanel(this, panelGlass);
        panel.setListener(this::onSettingChosen);
        root.addView(panel, match());
        return root;
    }

    private static FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    /** Rows that have moved up past the dock fade out, so they never cover the app content. */
    private void fadeRowsAboveDock() {
        float dockTop = Ui.px(Ui.DOCK_ROW_TOP), fade = Ui.px(Ui.ROW_PITCH) * 0.6f;
        for (int i = 0; i < grid.getChildCount(); i++) {
            View cell = grid.getChildAt(i);
            float top = cell.getTop() + cell.getTranslationY();
            float alpha = top >= dockTop - 1 ? 1f : Math.max(0f, 1f - (dockTop - top) / fade);
            if (cell.getAlpha() != alpha) cell.setAlpha(alpha);
        }
    }

    private void loadProfile() {
        File file = new File(getExternalFilesDir(null), "avatar.png");
        Bitmap avatar = file.isFile() ? BitmapFactory.decodeFile(file.getPath()) : null;
        capsule.setProfile(avatar, config.profileInitial);
    }

    // ---- Focused-app content ----------------------------------------------------------------

    private boolean featuredTitles() {
        return settings.featuredTitles(!config.tmdbApiKey.isEmpty());
    }

    /**
     * Home shows the user's constant background and nothing about the focused app itself, so moving
     * between apps changes nothing behind them. With "Featured titles" on, a streaming app swaps in a
     * TMDB slideshow once its artwork has loaded (what was showing stays until then, for a moment at
     * most); if that fails, the background stays.
     */
    private void present(AppEntry app) {
        main.removeCallbacks(presentPending);
        pendingApp = null;
        if (app.key.equals(presentedKey)) return;
        presentedKey = app.key;
        presentedApp = app;
        main.removeCallbacks(advanceSlide);
        main.removeCallbacks(featuredLate);
        slides = null;
        final int generation = ++featuredGeneration;

        Brand brand = Brand.of(app.key, app.label);
        Backgrounds.Entry chosen = Backgrounds.find(backgrounds, settings.backgroundName());
        boolean ready = background != null && chosen != null && chosen.name.equals(backgroundName);
        if (chosen == null) {
            showSlide(HeroSlide.backdrop(null), Ui.CONTENT_FADE_MS);   // no backgrounds at all: plain graphite
        } else if (!ready) {
            loadBackground(chosen);
        }
        if (featuredTitles() && brand.kind == Brand.Kind.FEATURED) {
            loadFeatured(app, brand, generation);
            main.postDelayed(featuredLate, FEATURED_WAIT_MS);
        } else {
            showBackground();
        }
    }

    /** The constant background; when it is already showing (moving between apps), nothing redraws. */
    private void showBackground() {
        if (background == null) return;   // still decoding: it shows once ready
        HeroSlide current = stage.current();
        if (current != null && !current.isFeatured() && current.background == background) return;
        showSlide(HeroSlide.backdrop(background), Ui.CONTENT_FADE_MS);
    }

    /** Decodes the chosen background off the main thread, then re-presents the current app over it. */
    private void loadBackground(Backgrounds.Entry entry) {
        if (entry == null) return;
        worker.execute(() -> {
            Bitmap b = Backgrounds.decode(this, entry, screenW);
            main.post(() -> {
                Backgrounds.Entry chosen = Backgrounds.find(backgrounds, settings.backgroundName());
                if (b == null || chosen == null || !chosen.name.equals(entry.name)) return;
                background = b;
                backgroundName = entry.name;
                // The browse pages settle on the same picture once their banner scrolls away
                for (int p = MOVIES; p <= SPORTS; p++) pages[p].setHomeBackground(b);
                // A featured slide already showing stays; otherwise the background shows now
                HeroSlide current = stage.current();
                if (current == null || !current.isFeatured()) showBackground();
            });
        });
    }

    private void representCurrentApp() {
        if (presentedApp == null) return;
        presentedKey = null;
        present(presentedApp);
    }

    /** Applies a hero update that is still waiting out its dwell time (used when focus leaves the grid). */
    private void flushPendingPresent() {
        if (pendingApp != null) present(pendingApp);
    }

    private void loadFeatured(AppEntry app, Brand brand, int generation) {
        network.execute(() -> {
            List<FeaturedTitles.Title> titles;
            try {
                titles = FeaturedTitles.load(this, config.tmdbApiKey, app.key, brand.tmdbProviders);
            } catch (Exception e) {
                return;   // branded presentation stays
            }
            for (FeaturedTitles.Title t : titles) {
                if (generation != featuredGeneration) return;
                HeroSlide slide;
                try {
                    slide = HeroSlide.featured(app.key, brand, FeaturedTitles.backdrop(this, t), t);
                } catch (Exception e) {
                    continue;
                }
                main.post(() -> addFeaturedSlide(slide, generation));
            }
        });
    }

    /** Package-private so tests can supply a featured slide without network access. */
    void addFeaturedSlide(HeroSlide slide, int generation) {
        if (generation != featuredGeneration) return;
        if (slides == null) {
            slides = new ArrayList<>();
            slideIndex = 0;
            slides.add(slide);
            showSlide(slide, Ui.CONTENT_FADE_MS);
            scheduleSlides();
        } else {
            slides.add(slide);
        }
    }

    int featuredGeneration() {
        return featuredGeneration;
    }

    private void scheduleSlides() {
        main.removeCallbacks(advanceSlide);
        if (resumed && page == HOME && !Ui.reducedMotion) main.postDelayed(advanceSlide, Ui.SLIDE_INTERVAL_MS);
    }

    /** Crossfades only the content; the glass picks up the new background once its blur is ready. */
    private void showSlide(HeroSlide slide, long fadeMs) {
        stage.show(slide, Ui.reducedMotion ? 0 : fadeMs);
        refreshHomeBackdrop();
        // Home's snapshot follows the slideshow once the new slide has settled
        snapshotLater(fadeMs + SNAPSHOT_IDLE_MS);
    }

    /**
     * Home's snapshot is copied again once Home has rested for {@code ms}, or as Glass Home goes to
     * the back before then; one copied while nothing has been drawn since is used as it is.
     */
    private void snapshotLater(long ms) {
        main.removeCallbacks(takeSnapshot);
        main.postDelayed(takeSnapshot, ms);
    }

    private void refreshHomeBackdrop() {
        HeroSlide slide = stage.current();
        if (slide == null) return;
        final int generation = ++backdropGeneration;
        worker.execute(() -> {
            Bitmap small = Backdrop.render(slide);
            main.post(() -> {
                if (generation != backdropGeneration) return;
                dockGlass.setBackdrop(small, screenW, screenH);
                weatherGlass.setBackdrop(small, screenW, screenH);
                if (page == HOME) setBarBackdrop(small);
                grid.invalidate();
                weather.invalidate();
                detail.invalidate();
            });
        });
    }

    /** The tab bar and the capsule sample whatever page is behind them. */
    private void setBarBackdrop(Bitmap small) {
        capsuleGlass.setBackdrop(small, screenW, screenH);
        tabGlass.setBackdrop(small, screenW, screenH);
        controlGlass.setBackdrop(small, screenW, screenH);
        panelGlass.setBackdrop(small, screenW, screenH);
        capsule.invalidate();
        tabs.invalidate();
    }

    // ---- Weather ----------------------------------------------------------------------------

    private void refreshWeather() {
        network.execute(() -> {
            try {
                Weather.Data data = Weather.fetch();
                main.post(() -> {
                    weather.setData(data);
                    detail.setData(data, false);
                });
            } catch (Exception e) {
                main.post(() -> {
                    weather.setFailed();
                    detail.setData(weather.data(), weather.hasFailed());
                    main.postDelayed(this::refreshWeather, WEATHER_RETRY_MS);
                });
            }
        });
    }

    /** Select on a widget: the forecast for weather and the date, the game's page, or back into the show. */
    private void openWidget(WidgetPanel.Kind kind) {
        switch (kind) {
            case GAME:
                Sports.Game g = weather.game();
                if (g != null) {
                    Set<String> installed = installedKeys();
                    showDetail(Sports.feature(g, installed, appNames(installed), true), Sports.CREDIT);
                }
                return;
            case CONTINUE:
                if (weather.watching() != null) resume(weather.watching());
                return;
            default:
                openDetail();
        }
    }

    /**
     * Your teams' next game for the widgets: only their leagues are fetched, every ten minutes,
     * or every minute while one of them is playing.
     */
    private void refreshMyTeams() {
        main.removeCallbacks(teamsTick);
        teamsFetching = true;
        final List<String> teams = config.favoriteTeams;
        pagesNet.execute(() -> {
            List<Sports.Game> games = new ArrayList<>();
            for (Sports.League league : Sports.leaguesFor(teams)) {
                try {
                    games.addAll(Sports.fetch(this, league));
                } catch (Exception ignored) {
                    // that league is left out this time
                }
            }
            Sports.Game next = Sports.nextFor(games, teams);
            main.post(() -> {
                weather.setGame(next);
                teamsFetching = false;
                if (!stopped) main.postDelayed(teamsTick, next != null && next.live() ? SPORTS_LIVE_MS : SPORTS_IDLE_MS);
            });
        });
    }

    /** The latest unfinished title watched here, for the Continue Watching widget (artwork found on TMDB once). */
    private void refreshWatching() {
        final String key = config.tmdbApiKey;
        pagesNet.execute(() -> {
            List<WatchHistory.Entry> all = WatchHistory.load(this);
            WatchHistory.Entry latest = null;
            for (WatchHistory.Entry e : all) {
                if (!e.finished() && (latest == null || e.updatedAt > latest.updatedAt)) latest = e;
            }
            if (latest != null && !latest.matched && !key.isEmpty()) {
                try {
                    Tmdb.Title t = new Tmdb(this, key).search(latest.episode ? latest.series : latest.title, latest.episode, 0);
                    if (t != null) {
                        latest.tmdbId = t.id;
                        latest.backdrop = t.backdrop;
                        latest.tmdbName = t.name;
                    }
                    latest.matched = true;
                    WatchHistory.save(this, all);
                } catch (Exception ignored) {
                    // artwork is looked up again next time
                }
            }
            final WatchHistory.Entry show = latest;
            main.post(() -> weather.setWatching(show));
        });
    }

    private final Runnable teamsTick = this::refreshMyTeams;

    private void openDetail() {
        detail.setData(weather.data(), weather.hasFailed());
        detail.setVisibility(View.VISIBLE);
        detail.setAlpha(Ui.reducedMotion ? 1f : 0f);
        detail.animate().alpha(1f).setDuration(Ui.reducedMotion ? 0 : 200).setInterpolator(Ui.EASE).start();
        detail.requestFocus();
    }

    private void closeDetail() {
        detail.animate().cancel();
        detail.setVisibility(View.GONE);
        weather.requestFocus();
    }

    // ---- Apps -------------------------------------------------------------------------------

    private void loadApps() {
        final int generation = ++appsGeneration;
        final int artWidth = Math.round(Ui.tileW() * Ui.FOCUS_SCALE);
        worker.execute(() -> {
            List<AppEntry> apps = AppRepository.load(this, config);
            for (AppEntry app : apps) {
                app.art = ArtLoader.load(this, app, artWidth);
                app.layers = ArtLoader.loadLayers(this, app, artWidth);
            }
            main.post(() -> {
                if (generation != appsGeneration) return;
                boolean wasInGrid = zone() == Zone.GRID;
                adapter.setApps(apps);
                java.util.Map<String, Bitmap> art = new java.util.HashMap<>();
                for (AppEntry a : apps) if (a.art != null) art.put(a.key, a.art);
                for (int p = MOVIES; p <= SPORTS; p++) pages[p].setAppArt(art);
                // If the remembered app is gone, or its row got shorter, land on the nearest one
                int index = lastFocusedKey == null ? -1 : adapter.indexOf(lastFocusedKey);
                lastFocused = index >= 0 ? index : Math.max(0, Math.min(lastFocused, apps.size() - 1));
                lastDockColumn = Math.max(0, Math.min(lastDockColumn, Math.min(Ui.COLUMNS, apps.size()) - 1));
                presentedKey = null;
                if (wasInGrid || lastZone == Zone.GRID) {
                    focusGrid(lastFocused);
                } else if (!apps.isEmpty()) {
                    present(adapter.get(lastFocused));
                }
            });
        });
    }

    private void launch(AppEntry app) {
        // Some of Amazon's screens pass through its launcher on their way up; that is not the Home button
        HomeButtonService.opening(app.key);
        try {
            startActivity(app.launchIntent, openingAnimation());
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, app.label + " couldn't be opened", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * An app opens with a short fade over Home, which holds still beneath it: no copy of the tile
     * is drawn first, and nothing is scaled while the app starts (it stuttered on the TV).
     */
    private Bundle openingAnimation() {
        if (Ui.reducedMotion) return ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle();
        return ActivityOptions.makeCustomAnimation(this, R.anim.app_enter, R.anim.app_exit).toBundle();
    }

    /** Opens an app by its key, if it is installed. */
    private void launch(String key) {
        int i = adapter.indexOf(key);
        if (i >= 0) launch(adapter.get(i));
    }

    private Set<String> installedKeys() {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < adapter.getItemCount(); i++) keys.add(adapter.get(i).key);
        return keys;
    }

    private static Map<String, String> appNames(Set<String> keys) {
        Map<String, String> names = new HashMap<>();
        for (String k : keys) names.put(k, Brand.of(k, k).name);
        return names;
    }

    // ---- Tabs and browse pages ----------------------------------------------------------------

    private ShelfPage currentPage() {
        return page == HOME ? null : pages[page];
    }

    /** Moves the tab selection; the page follows once the selection rests (or at once). */
    private void selectTab(int index, boolean dwell) {
        tabs.select(index);
        main.removeCallbacks(commitTab);
        if (dwell && !Ui.reducedMotion) main.postDelayed(commitTab, Ui.TAB_DWELL_MS);
        else commitTab.run();
    }

    /** Applies a tab change that is still waiting out its dwell. */
    private void flushTab() {
        main.removeCallbacks(commitTab);
        if (tabs.selected() != page) showPage(tabs.selected(), false);
    }

    /** The Home button from another tab: straight to Home, the tab and the bar already in place, nothing fading. */
    private void showHomeAtOnce() {
        main.removeCallbacks(commitTab);
        tabs.select(HOME, false);
        showPage(HOME, true);
        setBarHidden(false, true);
    }

    private void showPage(int p, boolean atOnce) {
        main.removeCallbacks(commitTab);
        if (p == page) return;
        int old = page;
        page = p;
        long fade = Ui.reducedMotion || atOnce ? 0 : Ui.PAGE_FADE_MS;
        // Each page covers the whole screen, the browse pages over Home in tab order. Only what lies
        // over the other fades (the new page in over the old, or the old out over the new) while the
        // one beneath stays as it is, so the screen never dims towards black on the way and only one
        // page at a time is blended.
        for (int q = HOME; q <= SPORTS; q++) {
            if (q == p) continue;
            for (View v : pageViews(q)) {
                if (v.getVisibility() != View.VISIBLE) continue;
                if (q > p) fadeOut(v, fade);
                else hideAfter(v, fade);
            }
        }
        for (View v : pageViews(p)) fadeIn(v, p > old ? fade : 0);
        setBarHidden(false, atOnce);
        main.removeCallbacks(sportsTick);
        if (p == HOME) {
            main.removeCallbacks(advanceSlide);
            scheduleSlides();
            refreshHomeBackdrop();
        } else {
            main.removeCallbacks(advanceSlide);   // Home's slideshow rests while it is covered
            ShelfPage sp = pages[p];
            if (sp.hasModel()) setBarBackdrop(sp.backdrop());
            if (!sp.hasModel() || sp.age() > (p == SPORTS ? SPORTS_LIVE_MS : CATALOG_REFRESH_MS)) loadPage(p);
            else if (p == SPORTS) scheduleSports();
        }
    }

    /**
     * Page fades run through GPU layers: each view is drawn once and then only blended, where a
     * translucent view would otherwise be drawn into a buffer of its own on every frame.
     */
    private static void fadeIn(View v, long ms) {
        v.animate().cancel();
        if (v.getVisibility() != View.VISIBLE) {
            v.setAlpha(ms > 0 ? 0f : 1f);
            v.setVisibility(View.VISIBLE);
        }
        if (ms > 0) v.animate().alpha(1f).setDuration(ms).setInterpolator(Ui.EASE).withLayer().withEndAction(null).start();
        else v.setAlpha(1f);
    }

    private static void fadeOut(View v, long ms) {
        v.animate().cancel();
        if (ms <= 0) {
            v.setVisibility(View.GONE);
            return;
        }
        v.animate().alpha(0f).setDuration(ms).setInterpolator(Ui.EASE).withLayer().withEndAction(() -> v.setVisibility(View.GONE)).start();
    }

    /**
     * Leaves a page as it is, beneath the one fading in over it, and takes it away once that one
     * covers it: an animation of nothing and without a layer, for its end (a cancelled one has none).
     */
    private static void hideAfter(View v, long ms) {
        v.animate().cancel();
        if (ms <= 0) {
            v.setVisibility(View.GONE);
            return;
        }
        v.animate().alpha(v.getAlpha()).setDuration(ms).withEndAction(() -> v.setVisibility(View.GONE)).start();
    }

    /** What page {@code p} is on screen: Home's hero, widgets and apps, or a browse page. */
    private View[] pageViews(int p) {
        return p == HOME ? new View[] {stage, weather, grid} : new View[] {pages[p]};
    }

    /** Loads (or refreshes) a browse page off the main thread; a page keeps its old content if this fails. */
    private void loadPage(int p) {
        ShelfPage sp = pages[p];
        final int generation = ++pageGeneration[p];
        final Set<String> installed = installedKeys();
        if (p != SPORTS && config.tmdbApiKey.isEmpty()) {
            sp.setMessage(sp.name + " needs a TMDB token in apps.json");
            return;
        }
        if (!sp.hasModel()) sp.setMessage("Loading " + sp.name + "…");
        pagesNet.execute(() -> {
            PageModel model;
            try {
                if (p == SPORTS) {
                    Map<Sports.League, List<Sports.Game>> games = new java.util.LinkedHashMap<>();
                    for (Sports.League league : Sports.LEAGUES) {
                        try {
                            games.put(league, Sports.fetch(this, league));
                        } catch (Exception e) {
                            games.put(league, new ArrayList<>());
                        }
                    }
                    model = Sports.page(games, config.favoriteTeams, installed, appNames(installed));
                    if (model.hero.isEmpty() && model.shelves.isEmpty()) throw new java.io.IOException("no games");
                    List<Sports.Game> all = new ArrayList<>();
                    for (List<Sports.Game> l : games.values()) all.addAll(l);
                    Sports.Game next = Sports.nextFor(all, config.favoriteTeams);
                    main.post(() -> weather.setGame(next));
                } else {
                    model = Catalog.page(this, new Tmdb(this, config.tmdbApiKey), p == TV, installed, WatchHistory.load(this));
                }
            } catch (Exception e) {
                main.post(() -> {
                    if (generation == pageGeneration[p] && !sp.hasModel()) {
                        sp.setMessage(sp.name + " couldn't load. Check the internet connection.");
                    }
                    if (p == SPORTS) scheduleSports();
                });
                return;
            }
            final PageModel ready = model;
            main.post(() -> {
                if (generation != pageGeneration[p]) return;
                sp.setModel(ready);
                if (p == SPORTS) scheduleSports();
            });
        });
    }

    /** Live games refresh every minute while Sports is on screen; otherwise every ten. */
    private void scheduleSports() {
        main.removeCallbacks(sportsTick);
        if (page != SPORTS || !resumed) return;
        boolean live = false;
        PageModel m = pages[SPORTS].model();
        if (m != null) {
            for (PageModel.Shelf s : m.shelves) for (PageModel.Card c : s.cards) live |= c.game != null && c.game.live();
        }
        main.postDelayed(sportsTick, live ? SPORTS_LIVE_MS : SPORTS_IDLE_MS);
    }

    /** While browsing the shelves, the tab bar and the capsule slide up out of the way. */
    private void setBarHidden(boolean hidden) {
        setBarHidden(hidden, false);
    }

    private void setBarHidden(boolean hidden, boolean atOnce) {
        if (hidden == barHidden && !atOnce) return;
        barHidden = hidden;
        float y = hidden ? -Ui.px(Ui.BAR_HIDE_Y) : 0f;
        long ms = Ui.reducedMotion || atOnce ? 0 : Ui.SCROLL_MS;
        for (View v : new View[] {tabs, capsule}) {
            v.animate().cancel();
            if (ms == 0) {
                v.setTranslationY(y);
                v.setAlpha(hidden ? 0f : 1f);
            } else {
                v.animate().translationY(y).alpha(hidden ? 0f : 1f).setDuration(ms).setInterpolator(Ui.EASE).start();
            }
        }
    }

    @Override
    public void onAction(ShelfPage source, PageModel.Feature feature, PageModel.Action action) {
        if (action.details) openDetails(source, feature.ref);
        else if (action.appKey != null) open(feature.ref, action.appKey);
    }

    /** "Open …" on a film or show: into the app at that title when its link is known, otherwise the app. */
    private void open(Object ref, String app) {
        if (!(ref instanceof Tmdb.Title)) {
            launch(app);
            return;
        }
        Tmdb.Title t = (Tmdb.Title) ref;
        if (AppLinks.lookedUp(t)) {
            openLink(t, app, AppLinks.known(t, app));
            return;
        }
        // Not looked up yet (it usually is, from when the title came on screen): wait a moment for it
        if (linkWait != null) return;   // pressed again while waiting
        final Runnable[] wait = new Runnable[1];
        wait[0] = () -> {
            if (linkWait != wait[0]) return;
            linkWait = null;
            if (resumed) launch(app);
        };
        if (!lookUpLinks(() -> {
            String link = null;
            try {
                link = AppLinks.lookUp(this, t).get(app);
            } catch (Exception ignored) {
                // the app opens instead
            }
            final String found = link;
            main.post(() -> {
                if (linkWait != wait[0]) return;
                linkWait = null;
                main.removeCallbacks(wait[0]);
                if (resumed) openLink(t, app, found);   // not if Glass Home was left meanwhile
            });
        })) {
            launch(app);
            return;
        }
        linkWait = wait[0];
        main.postDelayed(wait[0], LINK_WAIT_MS);
    }

    /** "Open …" stops waiting for its link: Back, Home, or Glass Home left. */
    private void cancelLinkWait() {
        if (linkWait == null) return;
        main.removeCallbacks(linkWait);
        linkWait = null;
    }

    /** Runs a look-up of links, unless Glass Home is closing; false if it was not run. */
    private boolean lookUpLinks(Runnable lookUp) {
        if (isDestroyed() || linksNet.isShutdown()) return false;
        try {
            linksNet.execute(lookUp);
            return true;
        } catch (java.util.concurrent.RejectedExecutionException e) {
            return false;
        }
    }

    private void openLink(Tmdb.Title t, String app, String link) {
        Intent intent = link == null ? null : AppLinks.intent(this, app, link);
        if (intent == null) {
            launch(app);
            return;
        }
        HomeButtonService.opening(app);
        try {
            startActivity(intent);
            WatchHistory.opened(app, t);   // for Continue Watching, if the app does not say what it plays
        } catch (ActivityNotFoundException | SecurityException e) {
            launch(app);
        }
    }

    /** Looks up where a film or show is in each of its apps while it is on screen, so "Open …" is instant. */
    private void prefetchLinks(Object ref) {
        if (!(ref instanceof Tmdb.Title) || AppLinks.lookedUp((Tmdb.Title) ref)) return;
        Tmdb.Title t = (Tmdb.Title) ref;
        if (!t.detailed || (t.streamingApps.isEmpty() && t.storeApps.isEmpty())) return;
        lookUpLinks(() -> {
            try {
                AppLinks.lookUp(this, t);
            } catch (Exception ignored) {
                // tried again when "Open …" is pressed
            }
        });
    }

    @Override
    public void onCard(ShelfPage source, PageModel.Shelf shelf, PageModel.Card card) {
        if (card.ref instanceof WatchHistory.Entry) {
            resume((WatchHistory.Entry) card.ref);
        } else {
            openDetails(source, card.ref);
        }
    }

    @Override
    public void onHeroShown(ShelfPage source) {
        if (source != currentPage()) return;
        setBarBackdrop(source.backdrop());
        PageModel.Feature hero = source.currentHero();
        if (hero != null) prefetchLinks(hero.ref);
    }

    @Override
    public void onScrolled(ShelfPage source, boolean intoShelves) {
        if (source == currentPage()) setBarHidden(intoShelves);
    }

    /**
     * Continue Watching: back into the app. The app's own "return to what was playing" link is
     * used while its session is alive and this is the latest thing it played; otherwise the app
     * opens at the title's page (its own Resume is there) when that is known, or else at its home.
     */
    private void resume(WatchHistory.Entry e) {
        android.app.PendingIntent intent = MediaWatcher.RESUME.get(e.pkg);
        boolean latest = true;
        for (WatchHistory.Entry other : WatchHistory.load(this)) {
            if (other.pkg.equals(e.pkg)) {
                latest = other.key().equals(e.key());
                break;
            }
        }
        if (intent != null && latest) {
            try {
                HomeButtonService.opening(e.pkg);
                intent.send();
                // What plays next there is this title again, for apps that do not name it
                if (e.tmdbId > 0) WatchHistory.opened(e.pkg, titleOf(e));
                return;
            } catch (android.app.PendingIntent.CanceledException ignored) {
                MediaWatcher.RESUME.remove(e.pkg);
            }
        }
        if (e.tmdbId > 0) {
            open(titleOf(e), e.pkg);
            return;
        }
        launch(e.pkg);
    }

    /** A Continue Watching entry known on TMDB, as the title "Open …" takes into its app. */
    private static Tmdb.Title titleOf(WatchHistory.Entry e) {
        Tmdb.Title t = new Tmdb.Title(e.tmdbId, e.episode, e.name(), "", e.backdrop, "", "", new int[0]);
        t.streamingApps.add(e.pkg);
        if (!e.episode) t.minutes = (int) (e.durationMs / 60_000);
        return t;
    }

    /** Opens the detail page at once with what is known, then fills in where to watch. */
    private void openDetails(ShelfPage source, Object ref) {
        final Set<String> installed = installedKeys();
        if (ref instanceof Sports.Game) {
            showDetail(Sports.feature((Sports.Game) ref, installed, appNames(installed), true), Sports.CREDIT);
            return;
        }
        if (!(ref instanceof Tmdb.Title)) return;
        Tmdb.Title t = (Tmdb.Title) ref;
        if (t.detailed) {
            showDetail(Catalog.feature(t, true), Tmdb.CREDIT);
            prefetchLinks(t);
            return;
        }
        PageModel.Feature preview = new PageModel.Feature();
        preview.ref = t;
        preview.label = t.series ? "Series" : "Movie";
        preview.title = t.name;
        preview.meta = t.meta();
        preview.body = t.overview;
        preview.imageUrl = t.backdropUrl("w1280");
        preview.note = "Checking where to watch…";
        showDetail(preview, Tmdb.CREDIT);
        pagesNet.execute(() -> {
            try {
                new Tmdb(this, config.tmdbApiKey).details(t);
                main.post(() -> {
                    if (titleDetail.isOpen() && titleDetail.feature() != null && titleDetail.feature().ref == t) {
                        titleDetail.show(Catalog.feature(t, true), Tmdb.CREDIT);
                    }
                    prefetchLinks(t);   // now that it is known where it streams
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (titleDetail.isOpen() && titleDetail.feature() == preview) {
                        preview.note = "Couldn't check where to watch.";
                        titleDetail.invalidate();
                    }
                });
            }
        });
    }

    private void showDetail(PageModel.Feature f, String credit) {
        titleDetail.show(f, credit);
        if (!titleDetail.isOpen()) {
            titleDetail.setVisibility(View.VISIBLE);
            titleDetail.setAlpha(Ui.reducedMotion ? 1f : 0f);
            titleDetail.animate().alpha(1f).setDuration(Ui.reducedMotion ? 0 : 200).setInterpolator(Ui.EASE).start();
        }
        setBarHidden(true);
        titleDetail.requestFocus();
    }

    private void closeDetailPage() {
        cancelLinkWait();
        titleDetail.animate().cancel();
        titleDetail.setVisibility(View.GONE);
        focusPage();
    }

    private void focusTabs() {
        flushPendingPresent();
        setBarHidden(false);
        tabs.requestFocus();
        lastZone = Zone.TABS;
    }

    private void focusPage() {
        ShelfPage sp = currentPage();
        if (sp == null) {
            focusHomeContent();
            return;
        }
        sp.requestFocus();
        setBarHidden(sp.scrolledIntoShelves());
        lastZone = Zone.PAGE;
    }

    /** Back down from the tabs or the capsule to wherever focus last was on Home. */
    private void focusHomeContent() {
        setBarHidden(false);
        if (homeZone == Zone.HERO && stage.hasAction()) focusHero();
        else if (homeZone == Zone.WEATHER) focusWeather();
        else focusGrid(lastFocused);
    }

    /** ☰ goes to the tab bar from anywhere (closing whatever is open); pressed there, it goes back down. */
    private void onMenu() {
        switch (zone()) {
            case TABS:
                flushTab();
                focusPage();
                return;
            case OVERLAY:
                detail.animate().cancel();
                detail.setVisibility(View.GONE);
                break;
            case SETTINGS:
                panel.animate().cancel();
                panel.setVisibility(View.GONE);
                break;
            case CONTROL:
                hideControlCenter();
                break;
            case PAGE_DETAIL:
                cancelLinkWait();
                titleDetail.animate().cancel();
                titleDetail.setVisibility(View.GONE);
                break;
            default:
                break;
        }
        focusTabs();
    }

    @Override
    public void onLaunch(AppEntry app, int position) {
        lastFocused = position;
        launch(app);
    }

    @Override
    public void onFocused(AppEntry app, int position) {
        lastFocused = position;
        lastFocusedKey = app.key;
        lastZone = Zone.GRID;
        homeZone = Zone.GRID;
        if (position < Ui.COLUMNS) lastDockColumn = position;
        // The hero follows only once focus has rested; moving on cancels the pending update
        main.removeCallbacks(presentPending);
        pendingApp = app;
        if (presentedKey == null) present(app);
        else main.postDelayed(presentPending, Ui.HERO_DWELL_MS);
    }

    // ---- Focus zones ------------------------------------------------------------------------

    Zone zone() {
        if (panel.isOpen()) return Zone.SETTINGS;
        if (control.isOpen()) return Zone.CONTROL;
        if (detail.isOpen()) return Zone.OVERLAY;
        if (titleDetail.isOpen()) return Zone.PAGE_DETAIL;
        if (tabs.isFocused()) return Zone.TABS;
        if (capsule.isFocused()) return Zone.CAPSULE;
        if (page != HOME) return Zone.PAGE;
        if (stage.isFocused()) return Zone.HERO;
        if (weather.isFocused()) return Zone.WEATHER;
        return Zone.GRID;
    }

    int page() {
        return page;
    }

    ShelfPage pageView(int p) {
        return pages[p];
    }

    int focusedPosition() {
        View cell = grid.getFocusedChild();
        return cell == null ? RecyclerView.NO_POSITION : grid.getChildAdapterPosition(cell);
    }

    private void focusGrid(int position) {
        int count = adapter.getItemCount();
        if (count == 0) return;
        int target = Math.max(0, Math.min(position, count - 1));
        RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(target);
        if (holder != null) {
            ((AppAdapter.Holder) holder).tile.requestFocus();
            return;
        }
        // Not laid out yet (e.g. right after loading): scroll it in, then focus it
        grid.scrollToPosition(target);
        grid.post(() -> {
            RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(target);
            if (h != null) ((AppAdapter.Holder) h).tile.requestFocus();
        });
    }

    private void focusHero() {
        stage.requestFocus();
        lastZone = Zone.HERO;
        homeZone = Zone.HERO;
    }

    private void focusWeather() {
        weather.requestFocus();
        lastZone = Zone.WEATHER;
        homeZone = Zone.WEATHER;
    }

    /** Into the widgets from {@code direction} (Glances picks the square nearest to where focus came from). */
    private void focusWeather(int direction) {
        weather.enter(direction);
        focusWeather();
    }

    private void focusCapsule() {
        capsule.requestFocus();
        lastZone = Zone.CAPSULE;
    }

    // ---- Settings ---------------------------------------------------------------------------

    private void openSettings() {
        backgrounds = Backgrounds.list(this);
        boolean tmdb = !config.tmdbApiKey.isEmpty();
        Backgrounds.Entry chosen = Backgrounds.find(backgrounds, settings.backgroundName());
        List<SettingsPanel.Option> options = new ArrayList<>();
        options.add(SettingsPanel.Option.toggle("FEATURED TITLES", "Featured on streaming apps",
                tmdb ? "Popular titles behind each streaming app" : "Needs a TMDB token, which isn't set",
                tmdb, featuredTitles()));
        int start = 0;
        for (Backgrounds.Entry e : backgrounds) {
            boolean isChosen = chosen != null && e.name.equals(chosen.name);
            if (isChosen) start = options.size();
            options.add(SettingsPanel.Option.choice("BACKGROUND", e.title, "Behind every app", e.name,
                    thumbnails.computeIfAbsent(e.name, n -> Backgrounds.thumbnail(this, e)), isChosen));
        }
        String layout = settings.widgetLayout();
        options.add(SettingsPanel.Option.choice("WIDGETS", "Smart Stack",
                "One card that turns between your widgets", WidgetPanel.STACK, null,
                WidgetPanel.STACK.equals(layout)));
        options.add(SettingsPanel.Option.choice("WIDGETS", "Glances",
                "Four small squares, all at once", WidgetPanel.GLANCES, null,
                WidgetPanel.GLANCES.equals(layout)));
        // Sleep's permission can be given back here (it is granted once over ADB), so the launcher can always be uninstalled
        boolean sleep = SystemControls.canSleep(this);
        options.add(SettingsPanel.Option.toggle("CONTROL CENTER", "Allow Sleep",
                sleep ? "Lets Control Center put the TV to sleep" : "Turned on during setup over ADB", sleep, sleep));
        // Glass Home can turn its service back on itself when allowed to (see HomeServiceRecovery)
        boolean redirect = homeButtonServiceOn() || HomeServiceRecovery.allowed(this);
        options.add(SettingsPanel.Option.toggle("HOME BUTTON", "Opens Glass Home",
                redirect ? "Off: the Home button shows Amazon's home" : "Turned on during setup over ADB",
                redirect, redirect && settings.homeButtonOpensGlassHome()));
        panel.setOptions(options, start);
        panel.setVisibility(View.VISIBLE);
        panel.setAlpha(Ui.reducedMotion ? 1f : 0f);
        panel.animate().alpha(1f).setDuration(Ui.reducedMotion ? 0 : 200).setInterpolator(Ui.EASE).start();
        panel.requestFocus();
    }

    private void closeSettings() {
        panel.animate().cancel();
        panel.setVisibility(View.GONE);
        focusCapsule();
    }

    // ---- Control Center ---------------------------------------------------------------------

    private void openControlCenter() {
        control.setStatus(SystemControls.canSleep(this), SystemControls.networkStatus(this), SystemControls.wifiBars(this),
                "", SystemControls.sleepMinutesLeft(this));
        SystemControls.bluetoothStatus(this, status -> main.post(() -> control.setBluetooth(status)));
        control.reset();
        control.setVisibility(View.VISIBLE);
        control.setAlpha(Ui.reducedMotion ? 1f : 0f);
        control.animate().alpha(1f).setDuration(Ui.reducedMotion ? 0 : 200).setInterpolator(Ui.EASE).start();
        capsule.setControlOpen(true);
        // The panel takes the widgets' place while it is open
        weather.animate().alpha(0f).setDuration(Ui.reducedMotion ? 0 : 200).start();
        control.requestFocus();
    }

    private void hideControlCenter() {
        hideControlCenter(false);
    }

    private void hideControlCenter(boolean atOnce) {
        control.animate().cancel();
        control.setVisibility(View.GONE);
        capsule.setControlOpen(false);
        weather.animate().cancel();
        if (atOnce || Ui.reducedMotion) weather.setAlpha(1f);
        else weather.animate().alpha(1f).setDuration(200).start();
    }

    private void closeControlCenter() {
        hideControlCenter();
        capsule.setItem(ClockCapsule.CONTROL);
        focusCapsule();
    }

    private static final int[] TIMER_STEPS = {15, 30, 60, 90};

    private void onControl(ControlCenter.Item item) {
        switch (item) {
            case SLEEP:
                if (SystemControls.sleepNow(this)) closeControlCenter();
                else Toast.makeText(this, "Sleep needs a one-time permission, set up over ADB", Toast.LENGTH_LONG).show();
                return;
            case TIMER:
                if (!SystemControls.canSleep(this)) {
                    Toast.makeText(this, "The sleep timer needs the same one-time permission as Sleep", Toast.LENGTH_LONG).show();
                    return;
                }
                // Off → 15 → 30 → 60 → 90 minutes → off
                int left = SystemControls.sleepMinutesLeft(this), next = 0;
                for (int step : TIMER_STEPS) {
                    if (step > left + 1) {
                        next = step;
                        break;
                    }
                }
                SystemControls.setSleepTimer(this, next);
                control.setTimerMinutes(next);
                return;
            case WIFI:
                SystemControls.open(this, SystemControls.wifiSettings());
                return;
            case BLUETOOTH:
                SystemControls.open(this, SystemControls.bluetoothSettings());
                return;
            case MIRRORING:
                // Fire TV's mirroring screen (open to other apps); failing that, Display & Sounds, where it can be enabled
                SystemControls.open(this, SystemControls.mirroring(this), new Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS));
                return;
            case ACCESSIBILITY:
                SystemControls.open(this, SystemControls.accessibilitySettings());
                return;
            case DISPLAY:
                SystemControls.open(this, SystemControls.displaySettings());
                return;
            default:
                hideControlCenter();
                capsule.setItem(ClockCapsule.PROFILE);
                openSettings();
        }
    }

    /** From the capsule, Down returns to the page underneath: the widgets on Home. */
    private void leaveCapsuleDown() {
        if (page == HOME) focusWeather(View.FOCUS_DOWN);
        else focusPage();
    }

    /** Whether HomeButtonService has been turned on (over ADB; Fire TV has no screen for it). */
    private boolean homeButtonServiceOn() {
        String enabled = android.provider.Settings.Secure.getString(getContentResolver(),
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.contains(getPackageName() + "/" + HomeButtonService.class.getName());
    }

    private void onSettingChosen(SettingsPanel.Option option) {
        if (option.toggle && option.section.equals("HOME BUTTON")) {
            settings.setHomeButtonOpensGlassHome(option.checked);
            if (option.checked) HomeServiceRecovery.restore(this);   // Fire OS may have turned the service off meanwhile
            return;
        }
        if (option.toggle && option.section.equals("CONTROL CENTER")) {
            if (!option.checked) {
                SystemControls.releaseSleep(this);
                SystemControls.setSleepTimer(this, 0);
            }
            return;
        } else if (option.toggle) {
            settings.setFeaturedTitles(option.checked);
        } else if (option.section.equals("WIDGETS")) {
            settings.setWidgetLayout(option.value);
            weather.setLayout(option.value);
            return;
        } else {
            settings.setBackground(option.value);
            if (!option.value.equals(backgroundName)) background = null;
        }
        representCurrentApp();
    }

    /** Puts focus back where it was: the remembered app (and with it the scroll position), the hero or the weather card. */
    private void restoreFocus() {
        grid.post(() -> {
            switch (lastZone) {
                case OVERLAY:
                    if (detail.isOpen()) detail.requestFocus();
                    else focusWeather();
                    break;
                case SETTINGS:
                    if (panel.isOpen()) panel.requestFocus();
                    else focusCapsule();
                    break;
                case CAPSULE:
                    focusCapsule();
                    break;
                case TABS:
                    focusTabs();
                    break;
                case CONTROL:
                    if (control.isOpen()) control.requestFocus();
                    else focusCapsule();
                    break;
                case PAGE:
                    focusPage();
                    break;
                case PAGE_DETAIL:
                    if (titleDetail.isOpen()) titleDetail.requestFocus();
                    else focusPage();
                    break;
                case HERO:
                    if (stage.hasAction()) focusHero();
                    else focusGrid(lastFocused);
                    break;
                case WEATHER:
                    focusWeather();
                    break;
                default:
                    if (focusedPosition() != lastFocused) focusGrid(lastFocused);
                    break;
            }
        });
    }

    // ---- Remote input -----------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        snapshotLater(SNAPSHOT_IDLE_MS);
        int code = event.getKeyCode();
        int direction = direction(code);
        if (direction != 0) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && acceptStep(event)) {
                String before = focusSignature();
                move(direction);
                // Fire TV's faint tick when focus moved on the Home page (not the browse pages)
                if (page == HOME && tabs.selected() == HOME && !before.equals(focusSignature())) navSounds.play(direction);
            }
            return true;
        }
        if (isSelect(code)) {
            // Activate on release, once per press; holding Select does not repeat it
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) selectArmed = true;
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                boolean fire = selectArmed && !event.isCanceled();
                selectArmed = false;
                if (fire) activate();
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU) {
            // The remote's ☰ button: once per press, to the tab bar and back
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) onMenu();
            return true;
        }
        int step = stackStep(code);
        if (step != 0 && zone() == Zone.WEATHER && WidgetPanel.STACK.equals(weather.layout())) {
            // Play/Pause on the Smart Stack scrolls it, once per press; elsewhere the media keys keep their usual job
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) weather.turn(step);
            return true;
        }
        return super.dispatchKeyEvent(event);   // Back reaches onBackPressed; Home is never handled here
    }

    /** Play/Pause and fast-forward turn the Smart Stack to the next widget, rewind to the previous. */
    private static int stackStep(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                return 1;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                return -1;
            default:
                return 0;
        }
    }

    /** Navigation ticks asked for so far (tests). */
    int navTicks() {
        return navSounds.requests;
    }

    /** Where focus is, as far as the arrows can move it: unchanged after a move, nothing moved. */
    private String focusSignature() {
        return zone() + "/" + focusedPosition() + "/" + capsule.item() + "/" + tabs.selected() + "/" + weather.focusedKind()
                + "/" + panel.focusedIndex() + "/" + control.focusedItem();
    }

    private static int direction(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: return View.FOCUS_LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return View.FOCUS_RIGHT;
            case KeyEvent.KEYCODE_DPAD_UP: return View.FOCUS_UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return View.FOCUS_DOWN;
            default: return 0;
        }
    }

    private static boolean isSelect(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A;
    }

    /**
     * A fresh press always moves. While a direction is held, the remote's auto-repeat is paced:
     * nothing for the first 300 ms, then one step per 110 ms however fast repeats arrive, so a
     * hold glides tile by tile without skipping.
     */
    private boolean acceptStep(KeyEvent event) {
        long t = event.getEventTime();
        if (event.getRepeatCount() == 0) {
            lastStepTime = t;
            return true;
        }
        if (t - event.getDownTime() < Ui.KEY_REPEAT_DELAY_MS || t - lastStepTime < Ui.KEY_REPEAT_MS) return false;
        lastStepTime = t;
        return true;
    }

    private void move(int direction) {
        switch (zone()) {
            case OVERLAY:
                return;   // arrows stay inside the overlay; Back closes it
            case SETTINGS:
                if (direction == View.FOCUS_UP) panel.moveFocus(-1);
                else if (direction == View.FOCUS_DOWN) panel.moveFocus(1);
                return;
            case PAGE_DETAIL:
                if (direction == View.FOCUS_LEFT) titleDetail.left();
                else if (direction == View.FOCUS_RIGHT) titleDetail.right();
                return;
            case TABS:
                // Moving along the tabs selects them; the page follows once the selection rests
                if (direction == View.FOCUS_LEFT && tabs.selected() > 0) {
                    selectTab(tabs.selected() - 1, true);
                } else if (direction == View.FOCUS_RIGHT) {
                    if (tabs.selected() < tabs.count() - 1) {
                        selectTab(tabs.selected() + 1, true);
                    } else {
                        capsule.setItem(ClockCapsule.CONTROL);
                        focusCapsule();
                    }
                } else if (direction == View.FOCUS_DOWN) {
                    flushTab();
                    focusPage();
                }
                return;
            case PAGE:
                ShelfPage sp = currentPage();
                if (direction == View.FOCUS_UP) {
                    if (!sp.up()) focusTabs();
                } else if (direction == View.FOCUS_DOWN) {
                    sp.down();
                } else if (direction == View.FOCUS_LEFT) {
                    sp.left();
                } else {
                    sp.right();
                }
                return;
            case HERO:
                if (direction == View.FOCUS_RIGHT) focusWeather(View.FOCUS_RIGHT);
                else if (direction == View.FOCUS_UP) focusTabs();
                else if (direction == View.FOCUS_DOWN) focusGrid(lastFocused);
                return;
            case WEATHER:
                // Glances moves between its squares first; the Smart Stack turns with Right
                if (weather.move(direction)) return;
                if (direction == View.FOCUS_LEFT && stage.hasAction()) {
                    focusHero();
                } else if (direction == View.FOCUS_UP) {
                    capsule.setItem(ClockCapsule.CONTROL);
                    focusCapsule();
                }
                else if (direction == View.FOCUS_DOWN) focusGrid(lastFocused);
                return;
            case CAPSULE:
                // Control Center and the avatar take focus in turn; the capsule sits at the right end of
                // the top bar, so Up from it ends on the tabs too
                if (direction == View.FOCUS_DOWN) leaveCapsuleDown();
                else if (direction == View.FOCUS_UP) focusTabs();
                else if (direction == View.FOCUS_RIGHT && capsule.item() == ClockCapsule.CONTROL) capsule.setItem(ClockCapsule.PROFILE);
                else if (direction == View.FOCUS_LEFT && capsule.item() == ClockCapsule.PROFILE) capsule.setItem(ClockCapsule.CONTROL);
                else if (direction == View.FOCUS_LEFT) focusTabs();
                return;
            case CONTROL:
                if (!control.move(direction) && direction == View.FOCUS_UP) closeControlCenter();
                return;
            default:
                moveInGrid(direction);
        }
    }

    /** Adjacent tile left/right, same column up/down, and no wrapping at row or grid edges. */
    private void moveInGrid(int direction) {
        int count = adapter.getItemCount();
        if (count == 0) {
            if (direction == View.FOCUS_UP) focusWeather(View.FOCUS_UP);
            return;
        }
        int pos = focusedPosition();
        if (pos == RecyclerView.NO_POSITION) {
            focusGrid(lastFocused);
            return;
        }
        int column = pos % Ui.COLUMNS, row = pos / Ui.COLUMNS, target = pos;
        switch (direction) {
            case View.FOCUS_LEFT:
                if (column > 0) target = pos - 1;
                break;
            case View.FOCUS_RIGHT:
                if (column < Ui.COLUMNS - 1 && pos + 1 < count) target = pos + 1;
                break;
            case View.FOCUS_UP:
                if (row == 0) {
                    // Into the hero if it has an action, otherwise the widgets
                    flushPendingPresent();
                    if (stage.hasAction()) focusHero();
                    else focusWeather(View.FOCUS_UP);
                    return;
                }
                target = pos - Ui.COLUMNS;
                break;
            case View.FOCUS_DOWN:
                if (pos + Ui.COLUMNS < count) target = pos + Ui.COLUMNS;
                else if ((row + 1) * Ui.COLUMNS < count) target = count - 1;   // shorter row: its nearest tile
                break;
            default:
                break;
        }
        if (target != pos) focusGrid(target);
    }

    private void activate() {
        switch (zone()) {
            case OVERLAY:
                return;
            case SETTINGS:
                panel.choose();
                return;
            case CAPSULE:
                if (capsule.item() == ClockCapsule.CONTROL) openControlCenter();
                else openSettings();
                return;
            case CONTROL:
                control.select();
                return;
            case TABS:
                flushTab();
                focusPage();
                return;
            case PAGE:
                currentPage().select();
                return;
            case PAGE_DETAIL:
                PageModel.Action a = titleDetail.focusedAction();
                if (a != null && a.appKey != null) open(titleDetail.feature() == null ? null : titleDetail.feature().ref, a.appKey);
                return;
            case HERO:
                if (presentedApp != null) launch(presentedApp);
                return;
            case WEATHER:
                openWidget(weather.focusedKind());
                return;
            default:
                int pos = focusedPosition();
                if (pos != RecyclerView.NO_POSITION) onLaunch(adapter.get(pos), pos);
        }
    }

    /**
     * Back unwinds one level: an overlay; a page's shelves back to its hero, then to the tab bar,
     * then to Home; on Home, the hero, weather or capsule back to the apps, then lower rows; at
     * the root it behaves as usual.
     */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        switch (zone()) {
            case OVERLAY:
                closeDetail();
                return;
            case SETTINGS:
                closeSettings();
                return;
            case CONTROL:
                closeControlCenter();
                return;
            case PAGE_DETAIL:
                closeDetailPage();
                return;
            case PAGE:
                if (!currentPage().back()) focusTabs();
                return;
            case TABS:
                if (page != HOME) {
                    selectTab(HOME, false);
                    focusTabs();
                } else {
                    focusHomeContent();
                }
                return;
            case CAPSULE:
                if (page == HOME) focusGrid(lastFocused);
                else focusTabs();
                return;
            case HERO:
            case WEATHER:
                focusGrid(lastFocused);
                return;
            default:
                if (focusedPosition() >= Ui.COLUMNS) {
                    focusGrid(lastDockColumn);
                    return;
                }
                // A home screen has nothing behind it. On Fire TV, Glass Home is the home screen when the Home
                // button leads here, even though Fire OS still names Amazon's launcher as the default
                if (isDefaultHome() || (homeButtonServiceOn() && settings.homeButtonOpensGlassHome())) return;
                super.onBackPressed();
        }
    }

    private boolean isDefaultHome() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo info = getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return info != null && info.activityInfo != null && getPackageName().equals(info.activityInfo.packageName);
    }

    /**
     * Back from an app, what comes over the network (scores, a browse page) is fetched again once
     * the first frames are drawn, so that its answer does not land while Home comes in.
     */
    private final Runnable resumeRefresh = () -> {
        if (!resumed || isFinishing() || isDestroyed()) return;
        if (page != HOME && pages[page].hasModel()) loadPage(page);
        refreshMyTeams();
        HomeServiceRecovery.restore(this);
    };

    // ---- Lifecycle --------------------------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        boolean back = stopped;
        stopped = false;
        resumed = true;
        inFront = true;
        HomeButtonService.glassHomeResumed();
        capsule.updateTime();
        restoreFocus();
        scheduleSlides();
        main.removeCallbacks(weatherTick);
        long age = System.currentTimeMillis() - weather.lastUpdated();
        main.postDelayed(weatherTick, age > 10 * 60 * 1000L ? RESUME_REFRESH_MS : WEATHER_EVERY_MS - age);
        // Back from a trip through Amazon's home (the Home button pressed on Home), nothing else has run
        // meanwhile: nothing is fetched again, and the scores' timer kept going
        main.removeCallbacks(resumeRefresh);
        if (back || (!teamsFetching && !main.hasCallbacks(teamsTick))) main.postDelayed(resumeRefresh, RESUME_REFRESH_MS);
        // Continue Watching is read from the TV itself, quickly: it is up to date before Home shows
        // (under the cover, after the Home button), rather than changing just after
        if (back) refreshWatching();
        main.removeCallbacks(shownFallback);
        main.postDelayed(shownFallback, SHOWN_FALLBACK_MS);
        snapshotLater(SNAPSHOT_IDLE_MS);
    }

    /**
     * Glass Home's window is shown in front: whatever covered the trip back (see HomeButtonService)
     * can go. Not sooner: on the TV the window comes in some 350-500 ms after Glass Home's first
     * frame, and a cover fading meanwhile fades over black.
     */
    @Override
    public void onEnterAnimationComplete() {
        super.onEnterAnimationComplete();
        HomeButtonService.glassHomeShown();
    }

    /** Brought forward by the Home button (see HomeButtonService): back to Home with everything closed. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!intent.getBooleanExtra(HomeButtonService.EXTRA_HOME_BUTTON, false)) return;
        cancelLinkWait();
        // Everything changes at once, under the snapshot of Home that covers the trip back (see HomeSnapshot)
        if (panel.isOpen()) {
            panel.animate().cancel();
            panel.setVisibility(View.GONE);
        }
        if (control.isOpen()) hideControlCenter(true);
        if (detail.isOpen()) {
            detail.animate().cancel();
            detail.setVisibility(View.GONE);
        }
        if (titleDetail.isOpen()) {
            titleDetail.animate().cancel();
            titleDetail.setVisibility(View.GONE);
        }
        if (page != HOME) showHomeAtOnce();
        lastZone = Zone.GRID;
        homeZone = Zone.GRID;
    }

    /**
     * Whether the screen is as the Home button brings it back (the Home tab, nothing open, focus on
     * the apps), so a snapshot of it can stand in for Glass Home while Fire OS makes the trip.
     */
    private boolean showingHomeAsTheHomeButtonLeavesIt() {
        return page == HOME && zone() == Zone.GRID && !barHidden;
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(resumeRefresh);
        resumed = false;
        inFront = false;
        cancelLinkWait();
        main.removeCallbacks(shownFallback);
        main.removeCallbacks(takeSnapshot);
        // Leaving without opening anything is the Home button, as a rule: Home's snapshot covers Amazon's home at once
        HomeButtonService.leftUnexpectedly(showingHomeAsTheHomeButtonLeavesIt());
        // The snapshot shows Home as it is now: copied again only if Home has been drawn since (focus
        // moved on to the app just opened, the clock turned…), as its stand-in on the way back must match it
        if (snapshotDue && showingHomeAsTheHomeButtonLeavesIt()) {
            HomeSnapshot.capture(this);
            snapshotDue = false;
        }
        lastZone = zone();
        selectArmed = false;
        // Nothing animates or polls while another app is in front
        main.removeCallbacks(advanceSlide);
        main.removeCallbacks(weatherTick);
        main.removeCallbacks(sportsTick);
        super.onPause();
    }

    @Override
    protected void onStart() {
        super.onStart();
        main.removeCallbacks(collect);
    }

    /**
     * Out of sight, Glass Home keeps little: the TV has under 2 GB for Fire OS and every app. The
     * Home button service keeps this process in the foreground as far as Android is concerned, so
     * Android never says that Glass Home's screen is hidden (onTrimMemory with UI_HIDDEN does not
     * come); it lets go here instead, once another app has the screen.
     */
    @Override
    protected void onStop() {
        super.onStop();
        stopped = true;
        main.removeCallbacks(teamsTick);
        ImageCache.get(this).trimMemory();
        for (int p = MOVIES; p <= SPORTS; p++) pages[p].trimMemory();
        titleDetail.trimMemory();
        main.removeCallbacks(collect);
        main.postDelayed(collect, COLLECT_AFTER_MS);
        HomeButtonService.glassHomeHidden();
    }

    /**
     * The pictures given up are freed (with their copies on the GPU) only once collected, which an
     * app sitting idle behind another may not do for hours. Not at once: the app just opened is starting.
     */
    private final Runnable collect = () -> worker.execute(() -> Runtime.getRuntime().gc());

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) ImageCache.get(this).trimMemory();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_FOCUS, lastFocused);
        outState.putString(STATE_FOCUS_KEY, lastFocusedKey);
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacks(collect);
        main.removeCallbacks(resumeRefresh);
        unregisterReceiver(packageReceiver);
        unregisterReceiver(timeReceiver);
        worker.shutdownNow();
        network.shutdownNow();
        pagesNet.shutdownNow();
        linksNet.shutdownNow();
        navSounds.release();
        super.onDestroy();
    }
}
