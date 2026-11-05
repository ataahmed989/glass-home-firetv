package app.glasshome.launcher;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.View;

/**
 * A frame of the Home screen as the Home button brings it back (see
 * HomeActivity#showingHomeAsTheHomeButtonLeavesIt), copied off the GPU whenever it has settled.
 * When the Home button sends Fire OS to Amazon's home, {@link HomeButtonService} shows this frame
 * over everything at once and keeps it there until Glass Home is back in front, so the trip
 * through Amazon's home (a black screen while it starts) never shows.
 *
 * One full-size bitmap, rewritten in place: a frame partly replaced while it is on show differs
 * from the old one only where Home itself changed. Between copies, the minute turning draws the
 * clock and the capsule again into it (HomeActivity#retimeSnapshot). Main thread only.
 */
final class HomeSnapshot {
    private static Bitmap frame;
    private static boolean copying, complete;
    /** Copies asked for so far, made or not (tests). */
    static int requests;
    /** Times the clock and the capsule were drawn again into the frame (tests; see {@link #redrawn}). */
    static int retimes;

    private HomeSnapshot() {}

    /** The latest complete frame, or null before the first copy. */
    static Bitmap latest() {
        return complete ? frame : null;
    }

    /**
     * The latest complete frame, to be drawn into in place (see HomeActivity#retimeSnapshot); null
     * while a copy is being made into it, or when it is not a bitmap that software can draw into.
     */
    static Bitmap drawable() {
        Bitmap f = latest();
        if (f == null || copying || f.isRecycled() || !f.isMutable() || f.getConfig() != Bitmap.Config.ARGB_8888) return null;
        return f;
    }

    /** Parts of the frame were drawn again in place: onto the GPU again, and into the covers made ready. */
    static void redrawn() {
        retimes++;
        frame.prepareToDraw();
        HomeButtonService.snapshotChanged();
    }

    /** Tests start with no frame. */
    static void forget() {
        frame = null;
        copying = complete = false;
    }

    /** Copies what the activity's window shows now. */
    static void capture(Activity activity) {
        requests++;
        View decor = activity.getWindow().peekDecorView();
        if (copying || decor == null || decor.getWidth() == 0 || decor.getHeight() == 0) return;
        int w = decor.getWidth(), h = decor.getHeight();
        if (frame == null || frame.getWidth() != w || frame.getHeight() != h) {
            frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            complete = false;
        }
        copying = true;
        try {
            PixelCopy.request(activity.getWindow(), frame, result -> {
                copying = false;
                // A copy that fails (the window going as Glass Home goes to the back) leaves the bitmap as it
                // was: the last complete frame still stands in, rather than none
                if (result != PixelCopy.SUCCESS) return;
                complete = true;
                frame.prepareToDraw();   // onto the GPU now, so the cover has nothing to upload when it is needed
                HomeButtonService.snapshotChanged();
            }, new Handler(Looper.getMainLooper()));
        } catch (RuntimeException e) {
            copying = false;   // no surface to copy yet (or none at all, as in tests)
        }
    }
}
