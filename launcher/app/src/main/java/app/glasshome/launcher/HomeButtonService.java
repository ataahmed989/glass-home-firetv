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
 *
 * Always running, it also keeps FirePlayer's title server up for TiviMate ({@link FirePlayerTitles}),
 * and opens TiviMate when the remote's Menu button is held, from any app ({@link #MENU_HOLD_MS}).
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
    /** How often a trip under way is looked at, for Home pressed again or the trip not landing. */
    static final long WATCH_MS = 300;
    /** How long after Glass Home goes to the back unexpectedly the service asks what is in front. */
    static final long LEFT_CHECK_MS = 500;
    static final int MAX_RETRIES = 2;
    /** How often, at most, Amazon's redraws during a trip are checked for a further press of Home. */
    static final long PRESS_CHECK_MS = 150;
    /** The service starting this soon after Fire OS has is the TV starting, on Amazon's home. */
    static final long BOOT_MS = 2 * 60_000;
    /** The cover never stays longer than this, whatever happens. */
    static final long COVER_MAX_MS = 2000;
    /** How long after another app has taken the screen the cover is made ready, out of the way of its opening. */
    static final long COVER_READY_MS = 1500;
    /** How long after Glass Home is back (and its cover gone) a cover is made ready over it, for the next press. */
    static final long HOME_COVER_READY_MS = 100;
    /** Fire OS's usage record is asked this often, this many times, whether it was the Home button. */
    static final long PRESS_RETRY_MS = 30;
    static final int PRESS_TRIES = 5;
    /** How long the cover stays once Glass Home is shown again (see {@link #glassHomeShown}). */
    static final long SHOWN_HOLD_MS = 400;
    /** How long Home's cover takes to fade out over Glass Home, and in over a browse page. */
    static final long COVER_OUT_MS = 120, COVER_ACROSS_MS = 150;
    /**
     * Held this long, Menu (☰) opens TiviMate. A press is over in well under half that; Fire OS's own
     * long press is 400 ms, and nothing on Fire TV is held Menu on its own.
     */
    static final long MENU_HOLD_MS = 700;
    /** Back or Left counts as held with Menu for this long at most, should its release never be seen. */
    static final long COMBO_MS = 5000;
    static final String TIVIMATE = "ar.tvplayer.tv";
    /** The press of Menu (its down time) that opened TiviMate, which Glass Home then leaves alone. */
    static volatile long menuHoldTaken = -1;

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
    private final Runnable menuHeld = this::menuHeld;
    // When Back and Left went down and are still held (-1: up): with Menu, Fire OS's own combinations
    // (VoiceView; resetting the remote)
    private long backDownAt = -1, leftDownAt = -1;
    // The Menu press being timed, and the app in front when it went down
    private long menuDownTime;
    private String menuFront = "";
    private FirePlayerTitles titles;
    private String front = "";        // package whose window last came to the front
    /** The last window to come up, Glass Home's aside, was Amazon's home: nothing has come up over it. */
    private boolean amazonHome;
    private long keyAt = -KEY_MS;
    /** Also set from the starts thread (see {@link #pressed}). */
    private static volatile long redirectedAt = -REDIRECT_GAP_MS;
    /**
     * The latest of Amazon's home resuming that a trip has answered, as Fire OS's usage record times it.
     * Its clock is the record's own (wall time worked out from uptime, some way off the wall clock here),
     * so it is only ever compared with other times from the record.
     */
    private static volatile long answeredAt;
    /**
     * Something not Amazon's came up over Amazon's home, which stays resumed beneath it: Fire TV's quick
     * menu on a long press of Home, say. Back closes it, onto Amazon's home again.
     */
    private boolean overAmazonHome;
    private long pressCheckedAt = -PRESS_CHECK_MS;
    private int retries;
    private final Runnable check = this::check;
    /**
     * The cover's window: made ready (hidden) while another app is in front, and shown for a trip back;
     * over Glass Home, when {@link #home} is not ready yet, added at the press.
     */
    private CoverView cover;
    /** The cover kept up, invisible, over Glass Home, for the Home button pressed there; null otherwise. */
    private HomeCover home;
    /** The cover up is {@link #home}. */
    private boolean homeCovering;
    /** Beneath the cover when it fades in (see {@link #addCover}); null otherwise. */
    private View blackout;
    private boolean covering;
    private final Runnable readyCover = this::readyCover;
    private final Runnable readyHomeCover = this::readyHomeCover;
    /** How the cover's window comes up (see {@link #addCover}). */
    private Fade coverFade;

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
        answeredAt = 0;
    }

    /**
     * Glass Home is no longer the top screen, without opening anything: Home pressed on Glass Home, as a
     * rule. Fire OS says so before Glass Home is paused (20-200 ms before, measured), and Amazon's home,
     * pressed for again and again, can already be on screen, black, by the time it is: the cover goes
     * up now. Should Glass Home stay in front after all (Alexa's overlay), it goes again once Glass
     * Home is back on top.
     */
    static void losingTop(boolean asSnapshot) {
        HomeButtonService s = connected;
        if (s == null || opening()) return;
        s.cover(asSnapshot ? Fade.AT_ONCE : Fade.ACROSS);
    }

    /**
     * Glass Home went to the back without opening anything: Home pressed on Glass Home itself, as
     * a rule. Its last frame covers the screen right away, before Amazon's home can show.
     */
    static void leftUnexpectedly(boolean asSnapshot) {
        if (opening()) return;
        leftAt = SystemClock.uptimeMillis();
        HomeButtonService s = connected;
        if (s == null) return;
        // Over Glass Home itself, still on screen: at once when it already looks like the snapshot,
        // otherwise (a browse page, say) Home crossfades in over it
        s.cover(asSnapshot ? Fade.AT_ONCE : Fade.ACROSS);
        // Fire OS has recorded Amazon's home resuming by now, as a rule: asked at once whether that is
        // what happened, the trip back starts well before Amazon's window says so
        starts.execute(s::pressed);
        // Amazon's home may come up without saying so (back over Glass Home before it had quite left)
        s.handler.removeCallbacks(s.check);
        s.handler.postDelayed(s.check, LEFT_CHECK_MS);
    }

    /**
     * Glass Home is drawn and in front again: a moment later the cover fades. Not at once: a trip that
     * starts before Amazon's home has drawn anything (see {@link #pressed}) can leave its window, black,
     * fading out above Glass Home for some 300 ms more (seen on the TV); the cover, Home as it is, hides that.
     */
    static void glassHomeShown() {
        HomeButtonService s = connected;
        if (s == null || !s.covering) return;
        s.handler.removeCallbacks(s.lift);
        s.handler.postDelayed(s.lift, SHOWN_HOLD_MS);
    }

    /** Glass Home is in front: the trip is over, and the next press is a press of its own. */
    static void glassHomeResumed() {
        redirectedAt = -REDIRECT_GAP_MS;
        HomeButtonService s = connected;
        if (s == null) return;
        s.retries = 0;
        s.overAmazonHome = false;
        s.handler.removeCallbacks(s.check);
        s.handler.removeCallbacks(s.readyCover);
        if (!s.covering) {
            s.dropCover();   // back some other way (Back from an app): the cover made ready for that is not needed
            s.handler.removeCallbacks(s.readyHomeCover);
            if (s.home == null) s.handler.postDelayed(s.readyHomeCover, HOME_COVER_READY_MS);
        }
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
        s.handler.removeCallbacks(s.readyHomeCover);
        s.dropCover();
        if (!s.homeCovering) s.dropHomeCover();   // the way back from an app fades in from black instead
        s.handler.postDelayed(s.readyCover, COVER_READY_MS);
    }

    static void snapshotChanged() {
        HomeButtonService s = connected;
        if (s == null || HomeSnapshot.latest() == null) return;
        if (s.cover != null) s.cover.show(HomeSnapshot.latest());
        if (s.home != null) s.home.draw(HomeSnapshot.latest());
    }

    @Override
    protected void onServiceConnected() {
        connected = this;
        titles = new FirePlayerTitles(this);
        titles.watch();
        // The TV has just started, on Amazon's home, which may have come up before this did
        Boolean amazon = amazonHomeInFront();
        if (amazon != null ? amazon : SystemClock.elapsedRealtime() < BOOT_MS) redirect();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        lift();
        dropCover();
        dropHomeCover();
        handler.removeCallbacks(readyCover);
        handler.removeCallbacks(readyHomeCover);
        handler.removeCallbacks(check);
        handler.removeCallbacks(menuHeld);
        if (titles != null) titles.unwatch();
        if (connected == this) connected = null;
        return super.onUnbind(intent);
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        keyAt = SystemClock.uptimeMillis();
        lift();   // someone is using the remote: nothing may stand in front of it
        if (overAmazonHome && event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
            // Back closes what was over Amazon's home (Fire TV's quick menu); a moment later, if Amazon's
            // home is what is left, Glass Home comes back
            overAmazonHome = false;
            amazonHome = true;
            handler.removeCallbacks(check);
            handler.postDelayed(check, LEFT_CHECK_MS);
        }
        // Left on Amazon's home after all: the next key brings Glass Home back
        if (event.getAction() == KeyEvent.ACTION_DOWN && strandedOnAmazonHome()) redirect();
        watchMenuHold(event);
        return false;   // never consumed
    }

    /**
     * Menu held opens TiviMate (Home Screen settings can turn this off). Each key is seen going down
     * and coming up and none is consumed, so a press still reaches the app in front as before: the
     * hold is timed from the key's own down time, and called off when it comes up first, when
     * another key is pressed meanwhile, or when another app's window comes up (Home, say, whose key
     * is never seen). Not with Back or Left held as well (Fire OS's own combinations).
     */
    private void watchMenuHold(KeyEvent event) {
        int code = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        long t = event.getEventTime();
        if (code == KeyEvent.KEYCODE_BACK) backDownAt = down ? t : -1;
        else if (code == KeyEvent.KEYCODE_DPAD_LEFT) leftDownAt = down ? t : -1;
        if (code == KeyEvent.KEYCODE_MENU) {
            if (down && event.getRepeatCount() == 0) {
                handler.removeCallbacks(menuHeld);
                if (!stillHeld(backDownAt, t) && !stillHeld(leftDownAt, t) && new HomeSettings(this).menuHoldOpensTiviMate()) {
                    menuDownTime = event.getDownTime();
                    menuFront = front;
                    handler.postAtTime(menuHeld, menuDownTime + MENU_HOLD_MS);
                }
            } else if (!down) {
                // Let go at or past the hold before its turn came (the main thread was busy): a hold all the same
                boolean pending = handler.hasCallbacks(menuHeld);
                handler.removeCallbacks(menuHeld);
                if (pending && event.getDownTime() == menuDownTime && t - menuDownTime >= MENU_HOLD_MS) menuHeld();
            }
        } else if (down) {
            handler.removeCallbacks(menuHeld);
        }
    }

    /** A key that went down at {@code at} counts as still held at {@code now}; a release missed expires it. */
    private static boolean stillHeld(long at, long now) {
        return at >= 0 && now - at < COMBO_MS;
    }

    private void menuHeld() {
        if (TIVIMATE.equals(front)) return;   // already there
        Intent open = launchIntent(this, TIVIMATE);
        if (open == null) return;
        menuHoldTaken = menuDownTime;
        // Glass Home going to the back for it is not the Home button
        if (HomeActivity.inFront) opening(TIVIMATE);
        try {
            startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            // gone since it was looked up
        }
    }

    static boolean isConnected() {
        return connected != null;
    }

    /**
     * Asks Fire OS to lock the screen, which turns the TV off and lets the Fire TV sleep: Sleep
     * without the device-admin grant. False when the service isn't running or Fire OS says no.
     */
    static boolean lockScreen() {
        HomeButtonService s = connected;
        return s != null && s.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }

    /** {@code pkg}'s TV launch screen, or its phone one; null when it isn't installed. */
    static Intent launchIntent(Context c, String pkg) {
        Intent i = c.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        return i != null ? i : c.getPackageManager().getLaunchIntentForPackage(pkg);
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
        Resumed r = lastResumed(10 * 60_000L);
        return r == null ? null : r.amazonHome();
    }

    /** The activity Fire OS last recorded resuming, and when (wall clock). */
    private static final class Resumed {
        final String pkg, cls;
        final long at;

        Resumed(String pkg, String cls, long at) {
            this.pkg = pkg;
            this.cls = cls;
            this.at = at;
        }

        boolean amazonHome() {
            return AMAZON_LAUNCHER.equals(pkg) && cls != null && cls.endsWith(".HomeActivity_vNext");
        }
    }

    /** From Fire OS's usage record; null when that cannot be told (no usage access, or nothing recorded lately). */
    private Resumed lastResumed(long withinMs) {
        if (!usageAccess()) return null;
        UsageStatsManager usage = getSystemService(UsageStatsManager.class);
        long now = System.currentTimeMillis();
        UsageEvents events;
        try {
            events = usage == null ? null : usage.queryEvents(now - withinMs, now + 1000);
        } catch (RuntimeException e) {
            return null;
        }
        if (events == null) return null;
        UsageEvents.Event e = new UsageEvents.Event();
        String pkg = null, cls = null;
        long at = 0;
        while (events.hasNextEvent()) {
            events.getNextEvent(e);
            if (e.getEventType() != UsageEvents.Event.ACTIVITY_RESUMED) continue;
            pkg = e.getPackageName();
            cls = e.getClassName();
            at = e.getTimeStamp();
        }
        return pkg == null ? null : new Resumed(pkg, cls, at);
    }

    /**
     * Amazon's home came up again after Glass Home was last started: Home pressed again (quickly,
     * several times), not more of the same press. That trip is made too, with retries afresh.
     */
    private boolean pressedAgain() {
        // Asked on Amazon's redraws, which come thick and fast: at most every PRESS_CHECK_MS, of the last seconds only
        long now = SystemClock.uptimeMillis();
        if (now - pressCheckedAt < PRESS_CHECK_MS) return false;
        pressCheckedAt = now;
        Resumed r = lastResumed(5000);
        if (r == null || !r.amazonHome() || r.at <= answeredAt) return false;
        retries = 0;
        return true;
    }

    private void onWindow(String pkg, AccessibilityEvent event) {
        if (!pkg.equals(menuFront)) handler.removeCallbacks(menuHeld);
        String previous = front;
        front = pkg;
        CharSequence window = event.getClassName();
        boolean home = pkg.equals(AMAZON_LAUNCHER) && window != null && window.toString().endsWith(".HomeActivity_vNext");
        if (!isAmazonUi(pkg) && !pkg.equals(getPackageName())) overAmazonHome = amazonHome && !HomeActivity.inFront;
        else overAmazonHome = false;
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
        if (HomeActivity.inFront) return;
        // Within one press's events only one trip, unless Amazon's home has since come up again (Home pressed again,
        // quickly: that comes back with no window event, only redraws)
        if (now - redirectedAt < REDIRECT_GAP_MS && !pressedAgain()) return;
        if (!new HomeSettings(this).homeButtonOpensGlassHome()) return;
        redirectedAt = now;
        handler.removeCallbacks(check);
        handler.postDelayed(check, WATCH_MS);
        cover(fadeIn ? Fade.FROM_BLACK : Fade.AT_ONCE);
        starts.execute(this::startGlassHome);
        // The resume of Amazon's home this trip answers, so that only a later one counts as a press of its own
        Resumed r = lastResumed(5000);
        if (r != null && r.amazonHome()) answeredAt = Math.max(answeredAt, r.at);
    }

    /** On the starts thread. */
    private void startGlassHome() {
        Intent home = new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION)
                .putExtra(EXTRA_HOME_BUTTON, true);
        try {
            startActivity(home);
        } catch (ActivityNotFoundException | SecurityException e) {
            handler.post(this::lift);   // Amazon's home stays; nothing else to do
        }
    }

    /**
     * A moment after a trip, or after Glass Home went to the back unexpectedly: if Amazon's home is
     * in front, the trip is made (again). The usage record decides: Amazon's home come up since
     * Glass Home was last started is Home pressed again (quickly, often with no event), and is
     * followed at once; otherwise the trip is watched every WATCH_MS until it should have landed,
     * then made again. Without the record only a trip already made is checked, by the last window
     * to come up (an app opened by voice may still be on its way).
     */
    private void check() {
        if (HomeActivity.inFront || opening() || library) return;
        PowerManager power = getSystemService(PowerManager.class);
        if (power != null && !power.isInteractive()) return;   // the TV went to sleep
        Resumed last = lastResumed(10 * 60_000L);
        if (last != null) {
            if (!last.amazonHome()) return;   // something else is in front
            if (last.at > answeredAt) {
                retries = 0;   // pressed again: a trip of its own
            } else if (SystemClock.uptimeMillis() - redirectedAt < CHECK_MS) {
                handler.postDelayed(check, WATCH_MS);   // this trip is still on its way
                return;
            } else if (retries++ >= MAX_RETRIES) {
                return;
            }
        } else {
            long since = SystemClock.uptimeMillis() - redirectedAt;
            if (since < CHECK_MS) {
                handler.postDelayed(check, CHECK_MS - since);   // nothing to tell a press from the trip: wait for it to land
                return;
            }
            if (retries >= MAX_RETRIES || !(since < CHECK_MS + REDIRECT_GAP_MS && amazonHome)) return;
            retries++;
        }
        redirectedAt = -REDIRECT_GAP_MS;
        redirect();
    }

    /**
     * On the starts thread, just after Glass Home went to the back unexpectedly: whether Fire OS has
     * recorded Amazon's home resuming (again, since the last trip), which it does before Glass Home is
     * even told it is paused. If so it is the Home button, and the trip back goes at once, from
     * here: not when Amazon's window says so (250-520 ms later, measured), nor once the main
     * thread is free (the cover's relayout holds it 30-120 ms), but before Amazon's home has drawn
     * anything, as a rule. Anything else resuming instead (an app opened by voice, say) is left alone.
     */
    private void pressed() {
        if (!usageAccess()) return;
        for (int tries = 0; tries < PRESS_TRIES; tries++) {
            if (tries > 0) SystemClock.sleep(PRESS_RETRY_MS);   // not in the record yet
            if (HomeActivity.inFront || opening() || library || !new HomeSettings(this).homeButtonOpensGlassHome()) return;
            Resumed r = lastResumed(5000);
            if (r != null && r.amazonHome() && r.at > answeredAt) {
                answeredAt = r.at;
                redirectedAt = SystemClock.uptimeMillis();   // Amazon's window, when it reports in, is this press
                startGlassHome();
                handler.postAtFrontOfQueue(() -> {
                    retries = 0;
                    handler.removeCallbacks(check);
                    handler.postDelayed(check, WATCH_MS);
                });
                return;
            }
            if (r != null && !r.amazonHome() && !r.pkg.equals(getPackageName())) return;
        }
        // After that, Amazon's window and check() decide
    }

    private boolean usageAccess() {
        AppOpsManager ops = getSystemService(AppOpsManager.class);
        return ops != null && ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), getPackageName())
                == AppOpsManager.MODE_ALLOWED;
    }

    // ---- The cover ----------------------------------------------------------------------------

    /** How the cover comes up (see the Cover styles). */
    enum Fade { AT_ONCE, ACROSS, FROM_BLACK }

    /**
     * Puts Home's snapshot over everything. A cover made ready on the way back from an app comes up
     * as it was made, from black; one made ready over Glass Home comes up as {@code fade} asks.
     */
    private void cover(Fade fade) {
        Bitmap shot = HomeSnapshot.latest();
        if (shot == null || !new HomeSettings(this).homeButtonOpensGlassHome()) return;
        handler.removeCallbacks(readyCover);
        handler.removeCallbacks(readyHomeCover);
        handler.removeCallbacks(lift);
        handler.postDelayed(lift, COVER_MAX_MS);
        if (homeCovering || (!covering && cover == null && home != null && home.ready())) {
            boolean across = fade == Fade.ACROSS && !homeCovering;   // over a browse page, Home crossfades in
            covering = homeCovering = true;
            home.show(across ? COVER_ACROSS_MS : 0);
            return;
        }
        if (cover == null && !addCover(fade, View.VISIBLE)) return;   // no overlay possible right now; the redirect still happens
        if (!covering && blackout == null && fade != coverFade && fade != Fade.FROM_BLACK) restyle(fade);
        covering = true;
        cover.show(shot);
        if (blackout != null) blackout.setVisibility(View.VISIBLE);
        cover.setVisibility(View.VISIBLE);
    }

    /**
     * Glass Home is in front with no cover up: one is put up over it, invisible ({@link HomeCover}),
     * and the Home button pressed here only has to make it visible. Added at the press instead, its
     * first frame came 59-292 ms after the press, and past some 150 ms Amazon's home had put a black
     * frame on screen first (1 press in 5).
     */
    private void readyHomeCover() {
        if (!HomeActivity.inFront || covering || cover != null || home != null || HomeSnapshot.latest() == null
                || !new HomeSettings(this).homeButtonOpensGlassHome()) return;
        WindowManager.LayoutParams lp = overlay(PixelFormat.TRANSLUCENT);
        lp.windowAnimations = 0;
        home = HomeCover.add(this, getSystemService(WindowManager.class), lp);
    }

    private void dropHomeCover() {
        if (home == null) return;
        home.remove();
        home = null;
        homeCovering = false;
    }

    /**
     * A cover made ready over Glass Home takes the style {@code fade} asks for, in the same relayout
     * that shows it.
     */
    private void restyle(Fade fade) {
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) cover.getLayoutParams();
        lp.windowAnimations = style(fade);
        try {
            getSystemService(WindowManager.class).updateViewLayout(cover, lp);
            coverFade = fade;
        } catch (RuntimeException ignored) {
            // the window went with the service; shown as it is
        }
    }

    private static int style(Fade fade) {
        if (Ui.reducedMotion) return 0;
        return fade == Fade.FROM_BLACK ? R.style.CoverFadeIn : fade == Fade.ACROSS ? R.style.CoverAcross : R.style.CoverAtOnce;
    }

    /** See {@link #glassHomeHidden}. */
    private void readyCover() {
        if (cover != null || HomeActivity.inFront || HomeSnapshot.latest() == null
                || !new HomeSettings(this).homeButtonOpensGlassHome()) return;
        addCover(Fade.FROM_BLACK, View.GONE);
    }

    /**
     * Adds the cover's window. Fire OS fades it (see the Cover styles), on its own animation thread,
     * which Glass Home coming in cannot hold up: out when it is taken away and, as {@code fade} says,
     * in. From black, a black window beneath it, up at once, hides whatever Amazon's home has drawn by then.
     */
    private boolean addCover(Fade fade, int visibility) {
        boolean fadeIn = fade == Fade.FROM_BLACK && !Ui.reducedMotion;
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
        lp.windowAnimations = style(fade);
        try {
            wm.addView(v, lp);   // after the black, so above it
        } catch (RuntimeException e) {
            if (black != null) remove(black);
            return false;
        }
        cover = v;
        blackout = black;
        coverFade = fade;
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
        if (homeCovering) {
            homeCovering = false;
            // Over Glass Home it fades out and stays, ready for the next press; over anything else it goes
            if (HomeActivity.inFront) home.hide(COVER_OUT_MS);
            else dropHomeCover();
            return;
        }
        removeCover();
        if (HomeActivity.inFront) {
            handler.removeCallbacks(readyHomeCover);
            handler.postDelayed(readyHomeCover, HOME_COVER_READY_MS);
        }
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
