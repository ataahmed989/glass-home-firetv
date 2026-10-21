package app.glasshome.launcher;

import android.accessibilityservice.AccessibilityService;
import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Makes the remote's Home button land on Glass Home. Fire OS always sends Home to Amazon's own
 * home screen, whatever the default home app is set to, and keeps the Home key itself from every
 * app, so this watches for Amazon's home screen coming up and brings Glass Home straight back:
 * when Amazon's home window takes focus, or sooner, on its first redraw, when Glass Home has just
 * gone to the back without opening anything (Home pressed on Glass Home itself).
 *
 * Amazon's home must never be left on screen. Fire OS says nothing when Amazon's home comes back
 * over Glass Home before it has quite left (Home pressed twice, quickly), nor when Home is pressed
 * on Amazon's home, so a moment after Glass Home goes to the back unexpectedly, and after each trip,
 * the service asks which screen is in front: Fire OS's usage record says (with usage access, granted
 * once over ADB), and if it is Amazon's home the trip is made (again). Without that access, Amazon's
 * home being the last window to come up has to do, after a trip only. Should Amazon's home stay up
 * anyway, its next redraw or the next key brings Glass Home back. Fire OS also starts on Amazon's
 * home: Glass Home comes forward once the service starts, if Amazon's home is in front.
 *
 * So that the trip through Amazon's home never shows, Glass Home's last frame ({@link HomeSnapshot})
 * covers the screen from the first sign of it until Glass Home is drawn again; then the cover fades.
 * Back from another app, it fades in over the black Fire OS shows on the way, from a window made
 * ready (hidden) while that app was in front, and Glass Home is started meanwhile.
 * The cover lifts at once for anything else that comes up instead (Fire TV's quick menu on a long
 * press, the screensaver, an app opened by voice), on any key, and after two seconds regardless.
 *
 * Amazon's launcher also hosts screens that are opened on purpose: Settings and the Appstore's
 * app library can pass through it (Glass Home opens Settings and the Appstore's storefront
 * directly, so only where those cannot be). When Glass Home opens one of those, Amazon's
 * launcher is left alone until that screen is up, and while the remote moves around the library;
 * Home pressed while something opened from Glass Home is still starting is followed once its
 * opening time is over, if Amazon's home is in front by then.
 *
 * What it is told: which app's window comes to the front, that Amazon's launcher redrew, and when
 * a remote key is pressed (never consumed; Home never arrives); what it asks, at those moments
 * only: which screen is in front. It reads no screen content and keeps nothing. Turned on once over ADB; Home Screen settings can switch the redirect off.
 */
public final class HomeButtonService extends AccessibilityService {
    static final String AMAZON_LAUNCHER = "com.amazon.tv.launcher";
    static final String APPSTORE = "com.amazon.venezia";
    /** The Appstore's launcher entry, which hands over to its app library on Amazon's home screen. */
    private static final String APPSTORE_ENTRY = ".grid.AppsGridLauncherActivity";
    static final String EXTRA_HOME_BUTTON = "app.glasshome.launcher.HOME_BUTTON";
    /** Amazon's own screens, between which the remote moves without leaving Amazon's UI. */
    private static final String[] AMAZON_UI = {AMAZON_LAUNCHER, APPSTORE, "com.amazon.tv.settings.v2"};
    /** How long the Appstore or Settings may take to come up after Glass Home opens it. */
    static final long OPENING_MS = 6000;
    /** Amazon's launcher redrawing this soon after Glass Home went to the back is the Home button. */
    static final long LEFT_MS = 1000;
    /** A change of Amazon's screen this soon after a key the service saw is the remote moving around in it. */
    static final long KEY_MS = 1000;
    /** One start per press: the events that follow, until Glass Home is back, belong to the same press. */
    static final long REDIRECT_GAP_MS = 1000;
    /** How long after a trip Glass Home should be back; if it is not, the trip is made again. */
    static final long CHECK_MS = 1500;
    /** How long after Glass Home goes to the back unexpectedly the service asks what is in front. */
    static final long LEFT_CHECK_MS = 500;
    static final int MAX_RETRIES = 2;
    /** The service starting this soon after Fire OS has is the TV starting, on Amazon's home. */
    static final long BOOT_MS = 2 * 60_000;
    /** The cover never stays longer than this, whatever happens. */
    static final long COVER_MAX_MS = 2000;
    /** How long after another app has taken the screen the cover is made ready, out of the way of its opening. */
    static final long COVER_READY_MS = 1500;

    /**
     * Glass Home is started from here, so that the cover is drawn meanwhile: the start waits on the
     * window manager, busy with Amazon's home, for up to 150 ms (measured). Tests run it in place.
     */
    static volatile Executor starts = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Home button");
        t.setDaemon(true);
        return t;
    });

    // Main thread only
    private static HomeButtonService connected;
    private static long openingAt = -OPENING_MS;
    /** In the Appstore's app library, opened from Glass Home: that is Amazon's home window itself. */
    private static boolean library;
    private static long leftAt = -LEFT_MS;   // when Glass Home last went to the back without opening anything
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable lift = this::lift;
    private String front = "";        // package whose window last came to the front
    /** The last window to come up, Glass Home's aside, was Amazon's home: nothing has come up over it. */
    private boolean amazonHome;
    private long keyAt = -KEY_MS;
    private static long redirectedAt = -REDIRECT_GAP_MS;
    private int retries;
    private final Runnable check = this::check;
    /** The cover's window: made ready (hidden) while another app is in front, shown for a trip back. */
    private CoverView cover;
    /** Beneath the cover when it fades in (see {@link #addCover}); null otherwise. */
    private View blackout;
    private boolean covering;
    private final Runnable readyCover = this::readyCover;

    /** Glass Home is opening {@code pkg}, which may pass through Amazon's launcher on its way up. */
    static void opening(String pkg) {
        openingAt = SystemClock.uptimeMillis();
        library = false;
    }

    static boolean opening() {
        return SystemClock.uptimeMillis() - openingAt < OPENING_MS;
    }

    /** Tests start each case with nothing being opened, Glass Home not just gone to the back, and no trip made. */
    static void forgetTrips() {
        openingAt = -OPENING_MS;
        library = false;
        leftAt = -LEFT_MS;
        redirectedAt = -REDIRECT_GAP_MS;
    }

    /**
     * Glass Home went to the back without opening anything: Home pressed on Glass Home itself, as
     * a rule. Its last frame covers the screen right away, before Amazon's home can show.
     */
    static void leftUnexpectedly() {
        if (opening()) return;
        leftAt = SystemClock.uptimeMillis();
        HomeButtonService s = connected;
        if (s == null) return;
        s.cover(false);   // over Glass Home itself, still on screen: at once
        // Amazon's home may come up without saying so (back over Glass Home before it had quite left)
        s.handler.removeCallbacks(s.check);
        s.handler.postDelayed(s.check, LEFT_CHECK_MS);
    }

    /** Glass Home is drawn and in front again: the cover fades. */
    static void glassHomeShown() {
        HomeButtonService s = connected;
        if (s != null) s.lift();
    }

    /** Glass Home is in front: the trip is over, and the next press is a press of its own. */
    static void glassHomeResumed() {
        redirectedAt = -REDIRECT_GAP_MS;
        HomeButtonService s = connected;
        if (s == null) return;
        s.retries = 0;
        s.handler.removeCallbacks(s.check);
        s.handler.removeCallbacks(s.readyCover);
        if (!s.covering) s.dropCover();   // back some other way (Back from an app): the ready cover is not needed
    }

    /**
     * Glass Home is out of sight, another app in front: a moment later the cover's window is added,
     * hidden, so that a trip back can put it up at once rather than first wait on the window manager
     * (24-208 ms measured, while Amazon's home comes up). Hidden, it has no surface and is never drawn.
     */
    static void glassHomeHidden() {
        HomeButtonService s = connected;
        if (s == null) return;
        s.handler.removeCallbacks(s.readyCover);
        s.handler.postDelayed(s.readyCover, COVER_READY_MS);
    }

    static void snapshotChanged() {
        HomeButtonService s = connected;
        if (s != null && s.cover != null && HomeSnapshot.latest() != null) s.cover.show(HomeSnapshot.latest());
    }

    @Override
    protected void onServiceConnected() {
        connected = this;
        // The TV has just started, on Amazon's home, which may have come up before this did
        Boolean amazon = amazonHomeInFront();
        if (amazon != null ? amazon : SystemClock.elapsedRealtime() < BOOT_MS) redirect();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        lift();
        dropCover();
        handler.removeCallbacks(readyCover);
        handler.removeCallbacks(check);
        if (connected == this) connected = null;
        return super.onUnbind(intent);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        keyAt = SystemClock.uptimeMillis();
        lift();   // someone is using the remote: nothing may stand in front of it
        // Left on Amazon's home after all: the next key brings Glass Home back
        if (event.getAction() == KeyEvent.ACTION_DOWN && strandedOnAmazonHome()) redirect();
        return false;   // never consumed
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getPackageName() == null) return;
        String pkg = event.getPackageName().toString();
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            onWindow(pkg, event);
        } else if (pkg.equals(AMAZON_LAUNCHER) && !HomeActivity.inFront
                && (SystemClock.uptimeMillis() - leftAt < LEFT_MS || strandedOnAmazonHome())) {
            redirect();
        }
    }

    /**
     * Amazon's home is on screen and nothing was opened through it: the last window to come up
     * (Glass Home's aside, which can report in as it goes to the back) was Amazon's home itself.
     */
    private boolean strandedOnAmazonHome() {
        if (!amazonHome || HomeActivity.inFront || opening() || library) return false;
        Boolean amazon = amazonHomeInFront();
        return amazon == null || amazon;
    }

    /**
     * Whether Amazon's home is the screen in front, from Fire OS's usage record of what last came
     * up; null when that cannot be told (no usage access, or nothing recorded lately).
     */
    private Boolean amazonHomeInFront() {
        AppOpsManager ops = getSystemService(AppOpsManager.class);
        if (ops == null || ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName())
                != AppOpsManager.MODE_ALLOWED) return null;
        UsageStatsManager usage = getSystemService(UsageStatsManager.class);
        long now = System.currentTimeMillis();
        UsageEvents events;
        try {
            events = usage == null ? null : usage.queryEvents(now - 10 * 60_000L, now + 1000);
        } catch (RuntimeException e) {
            return null;
        }
        if (events == null) return null;
        UsageEvents.Event e = new UsageEvents.Event();
        String pkg = null, cls = null;
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            if (e.getEventType() != UsageEvents.Event.ACTIVITY_RESUMED) continue;
            pkg = e.getPackageName();
            cls = e.getClassName();
        }
        if (pkg == null) return null;
        return AMAZON_LAUNCHER.equals(pkg) && cls != null && cls.endsWith(".HomeActivity_vNext");
    }

    private void onWindow(String pkg, AccessibilityEvent event) {
        String previous = front;
        front = pkg;
        CharSequence window = event.getClassName();
        boolean home = pkg.equals(AMAZON_LAUNCHER) && window != null && window.toString().endsWith(".HomeActivity_vNext");
        if (!pkg.equals(getPackageName())) amazonHome = home;
        if (!home && !pkg.equals(getPackageName())) handler.removeCallbacks(check);   // something else came up: no second trip
        if (!isAmazonUi(pkg)) {
            // Another app, or Glass Home, came up: whatever was opened has been left
            openingAt = -OPENING_MS;
            library = false;
            if (!pkg.equals(getPackageName())) lift();   // something else came up instead: show it
            return;
        }
        if (opening()) {
            // Settings and the Appstore's storefront show windows of their own: once one is up, the
            // opening is over. The Appstore's launcher entry (the fallback when the storefront cannot
            // be opened) hands over to its library instead, which is Amazon's home window itself and
            // reports in more than once as it comes up, so that is left alone for the whole opening time.
            boolean entry = window != null && window.toString().endsWith(APPSTORE_ENTRY);
            if (entry) library = true;
            if (!home && !entry) {
                openingAt = -OPENING_MS;
                library = false;
            } else if (home && !library) {
                // Amazon's home, and not on the way to the library: Home was pressed while the app
                // was still starting. Once the opening time is over, the usage record says where things are.
                handler.removeCallbacks(check);
                handler.postDelayed(check, Math.max(0, openingAt + OPENING_MS - SystemClock.uptimeMillis()));
            }
            return;
        }
        if (!home) {
            lift();   // Amazon's other screens (Settings, the quick menu…) are wanted: show them
            return;
        }
        // Back to the Appstore's library from one of its pages
        if (library && isAmazonUi(previous) && SystemClock.uptimeMillis() - keyAt < KEY_MS) return;
        redirect(true);
    }

    private void redirect() {
        redirect(false);
    }

    /** {@code fadeIn}: Amazon's home has only just come up, still black, and Home fades in over it. */
    private void redirect(boolean fadeIn) {
        long now = SystemClock.uptimeMillis();
        if (HomeActivity.inFront || now - redirectedAt < REDIRECT_GAP_MS) return;
        if (!new HomeSettings(this).homeButtonOpensGlassHome()) return;
        redirectedAt = now;
        handler.removeCallbacks(check);
        handler.postDelayed(check, CHECK_MS);
        cover(fadeIn);
        Intent home = new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(EXTRA_HOME_BUTTON, true);
        starts.execute(() -> {
            try {
                startActivity(home);
            } catch (ActivityNotFoundException | SecurityException e) {
                handler.post(() -> lift());   // Amazon's home stays; nothing else to do
            }
        });
    }

    /**
     * A moment after a trip, or after Glass Home went to the back unexpectedly: if Amazon's home is
     * in front, the trip is made (again). The usage record decides; without it only a trip already
     * made is checked, by the last window to come up (an app opened by voice may still be on its way).
     */
    private void check() {
        if (HomeActivity.inFront || retries >= MAX_RETRIES || opening() || library) return;
        PowerManager power = getSystemService(PowerManager.class);
        if (power != null && !power.isInteractive()) return;   // the TV went to sleep
        Boolean amazon = amazonHomeInFront();
        boolean afterTrip = SystemClock.uptimeMillis() - redirectedAt < CHECK_MS + REDIRECT_GAP_MS;
        if (amazon != null ? !amazon : !(afterTrip && amazonHome)) return;
        retries++;
        redirectedAt = -REDIRECT_GAP_MS;
        redirect();
    }

    // ---- The cover ----------------------------------------------------------------------------

    /** Puts Home's snapshot over everything; {@code fadeIn} over the black on the way back from another app. */
    private void cover(boolean fadeIn) {
        Bitmap shot = HomeSnapshot.latest();
        if (shot == null || !new HomeSettings(this).homeButtonOpensGlassHome()) return;
        handler.removeCallbacks(readyCover);
        handler.removeCallbacks(lift);
        handler.postDelayed(lift, COVER_MAX_MS);
        if (cover == null && !addCover(fadeIn, View.VISIBLE)) return;   // no overlay possible right now; the redirect still happens
        covering = true;
        cover.show(shot);
        if (blackout != null) blackout.setVisibility(View.VISIBLE);
        cover.setVisibility(View.VISIBLE);
    }

    /** See {@link #glassHomeHidden}. */
    private void readyCover() {
        if (cover != null || HomeActivity.inFront || HomeSnapshot.latest() == null
                || !new HomeSettings(this).homeButtonOpensGlassHome()) return;
        addCover(true, View.GONE);
    }

    /**
     * Adds the cover's window. Fire OS fades it (see the Cover styles), on its own animation thread,
     * which Glass Home coming in cannot hold up: out when it is taken away and, {@code fadeIn}, in.
     * Fading in, it does so over black: a black window beneath it, up at once, hides whatever
     * Amazon's home has drawn by then.
     */
    private boolean addCover(boolean fadeIn, int visibility) {
        fadeIn &= !Ui.reducedMotion;
        WindowManager wm = getSystemService(WindowManager.class);
        View black = null;
        if (fadeIn) {
            black = new View(this);
            black.setBackgroundColor(Color.BLACK);
            black.setVisibility(visibility);
            try {
                wm.addView(black, overlay(PixelFormat.OPAQUE));
            } catch (RuntimeException e) {
                return false;
            }
        }
        CoverView v = new CoverView(this);
        v.setVisibility(visibility);
        WindowManager.LayoutParams lp = overlay(PixelFormat.TRANSLUCENT);   // so that its fade shows Glass Home beneath
        if (!Ui.reducedMotion) lp.windowAnimations = fadeIn ? R.style.CoverFadeIn : R.style.CoverAtOnce;
        try {
            wm.addView(v, lp);   // after the black, so above it
        } catch (RuntimeException e) {
            if (black != null) remove(black);
            return false;
        }
        cover = v;
        blackout = black;
        return true;
    }

    private static WindowManager.LayoutParams overlay(int format) {
        return new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                format);
    }

    /**
     * Takes the cover away: the black at once, the snapshot faded out by Fire OS (over Glass Home,
     * once it is drawn). A cover made ready but not up stays ready.
     */
    private void lift() {
        handler.removeCallbacks(lift);
        if (!covering) return;
        covering = false;
        removeCover();
    }

    /** Removes a cover made ready and not used. */
    private void dropCover() {
        if (covering || cover == null) return;
        removeCover();
    }

    private void removeCover() {
        if (blackout != null) remove(blackout);
        if (cover != null) remove(cover);
        blackout = null;
        cover = null;
    }

    private void remove(View v) {
        try {
            getSystemService(WindowManager.class).removeView(v);
        } catch (RuntimeException ignored) {
            // already gone with the service
        }
    }

    private static final class CoverView extends View {
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Rect bounds = new Rect();
        private Bitmap shot;

        CoverView(Context context) {
            super(context);
        }

        void show(Bitmap b) {
            shot = b;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (shot == null) return;
            bounds.set(0, 0, getWidth(), getHeight());
            canvas.drawBitmap(shot, null, bounds, paint);
        }
    }

    private static boolean isAmazonUi(String pkg) {
        for (String p : AMAZON_UI) if (p.equals(pkg)) return true;
        return false;
    }

    @Override
    public void onInterrupt() {
    }
}
