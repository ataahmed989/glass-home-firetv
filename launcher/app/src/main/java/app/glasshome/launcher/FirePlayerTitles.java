package app.glasshome.launcher;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PatternMatcher;

/**
 * Keeps FirePlayer's title server up for TiviMate, which reaches its provider through it. FirePlayer
 * starts that server with the TV, but when Home is pressed with the TV short of memory (under 210 MB
 * free, as this TV often is), Fire OS force-stops the app in front, and with FirePlayer the server
 * goes too: TiviMate then can't get its lists ("Failed to get data from server") until FirePlayer is
 * opened again, nor after the TV restarts, since a force-stopped app isn't started with the TV.
 * Fire OS announces each force-stop, so a moment after FirePlayer's (once Home's screen is up) the
 * server is started again; FirePlayer's player itself stays closed. Started from the Home service,
 * which is always running.
 */
final class FirePlayerTitles {
    /** FirePlayer's receiver that starts the server (if it is on in FirePlayer's settings); the same class in 2.0. */
    static ComponentName starter(String pkg) {
        return new ComponentName(pkg, "app.fireplayer.titles.TitleService$Starter");
    }
    static final String ACTION_START = "app.fireplayer.titles.START";
    /** How long after the force-stop the server is started, out of the way of Home coming up. */
    static final long AFTER_MS = 1000;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable start = this::start;
    private final BroadcastReceiver stopped = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            Uri data = intent.getData();
            if (data == null) return;
            if (!Intent.ACTION_PACKAGE_RESTARTED.equals(intent.getAction())) {
                FirePlayer.chooseLater(context);   // a FirePlayer added, removed or replaced: which one may change
                return;
            }
            if (!FirePlayer.current().equals(data.getSchemeSpecificPart())) return;
            handler.removeCallbacks(start);
            handler.postDelayed(start, AFTER_MS);
        }
    };
    private boolean watching;

    FirePlayerTitles(Context context) {
        this.context = context;
    }

    void watch() {
        if (watching) return;
        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_RESTARTED);
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addDataScheme("package");
        filter.addDataSchemeSpecificPart(FirePlayer.V1, PatternMatcher.PATTERN_LITERAL);
        filter.addDataSchemeSpecificPart(FirePlayer.NEXT, PatternMatcher.PATTERN_LITERAL);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(stopped, filter, Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(stopped, filter);
        watching = true;
        // Force-stopped while this wasn't watching (Glass Home was itself stopped, or being updated)
        if (forceStopped()) handler.postDelayed(start, AFTER_MS);
    }

    void unwatch() {
        handler.removeCallbacks(start);
        if (!watching) return;
        watching = false;
        try {
            context.unregisterReceiver(stopped);
        } catch (IllegalArgumentException ignored) {
            // already gone with the service
        }
    }

    /** FirePlayer is installed and force-stopped (or never opened): nothing of it runs until started. */
    private boolean forceStopped() {
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(FirePlayer.get(context), 0);
            return (info.flags & ApplicationInfo.FLAG_STOPPED) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void start() {
        // A force-stopped app hears from no one unless asked by name and told to wake up
        context.sendBroadcast(new Intent(ACTION_START).setComponent(starter(FirePlayer.current()))
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES));
    }
}
