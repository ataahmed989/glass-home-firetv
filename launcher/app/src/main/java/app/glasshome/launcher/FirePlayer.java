package app.glasshome.launcher;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Which FirePlayer Glass Home talks to: FirePlayer 2.0 (app.fireplayer.next) when it is installed, else FirePlayer
 * (app.fireplayer). Its Continue Watching, the player it resumes in and the title server it keeps up all follow that
 * package. Chosen once and kept; chosen again (off the main thread) when either is added, removed or replaced.
 */
final class FirePlayer {
    static final String V1 = "app.fireplayer";
    static final String NEXT = "app.fireplayer.next";

    /** The package chosen, null until it has been. */
    private static volatile String chosen;
    private static final ExecutorService CHOOSER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GlassHomeFirePlayer");
        t.setDaemon(true);
        return t;
    });

    private FirePlayer() {}

    static String choose(boolean nextInstalled) {
        return nextInstalled ? NEXT : V1;
    }

    /** Either FirePlayer. */
    static boolean is(String pkg) {
        return V1.equals(pkg) || NEXT.equals(pkg);
    }

    /** Its Continue Watching provider. */
    static String authority(String pkg) {
        return pkg + ".continue";
    }

    static Uri items(String pkg) {
        return Uri.parse("content://" + authority(pkg) + "/items");
    }

    /** The package, chosen now (one PackageManager call) if it hasn't been yet: off the main thread. */
    static String get(Context context) {
        String p = chosen;
        return p != null ? p : choose(context);
    }

    /**
     * The package as last chosen, without asking anything: for the main thread. Chosen before anything of FirePlayer's
     * is shown (the first Continue Watching query does it); FirePlayer until then.
     */
    static String current() {
        String p = chosen;
        return p != null ? p : V1;
    }

    /** Chooses again: when either FirePlayer is added, removed or replaced. Off the main thread. */
    static String choose(Context context) {
        boolean next;
        try {
            context.getPackageManager().getPackageInfo(NEXT, 0);
            next = true;
        } catch (PackageManager.NameNotFoundException e) {
            next = false;
        }
        String p = choose(next);
        chosen = p;
        return p;
    }

    /** {@link #choose(Context)} on a thread of its own: from a package broadcast, on the main thread. */
    static void chooseLater(Context context) {
        Context app = context.getApplicationContext();
        CHOOSER.execute(() -> choose(app));
    }

    /** Tests start with nothing chosen. */
    static void forget() {
        chosen = null;
    }
}
