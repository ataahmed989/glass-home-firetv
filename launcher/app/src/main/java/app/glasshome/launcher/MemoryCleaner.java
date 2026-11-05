package app.glasshome.launcher;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Home Screen settings > Free Up Memory: closes the apps left running in the background, as
 * Android does when it needs the memory back. A Fire TV Stick has under 2 GB for everything, and
 * Fire OS keeps Prime Video and Netflix loaded after starting them on its own; with the memory
 * full, the video decoder stalls (the picture freezes while the sound plays on). Only apps not in
 * front can be closed, so nothing playing is touched, and each opens again as usual.
 */
final class MemoryCleaner {
    /**
     * Left running: FirePlayer (and its title server) and TiviMate keep their connection; Amazon's
     * home answers the Home button on its way here; Tailscale holds the TV's VPN.
     */
    static final Set<String> KEEP = new HashSet<>(Arrays.asList(
            "app.fireplayer", HomeButtonService.TIVIMATE, "com.amazon.tv.launcher", "com.tailscale.ipn"));

    /** Amazon's apps that start on their own and stay loaded, launchable or not. */
    private static final String[] AMAZON = {
            "com.amazon.firebat", "com.amazon.tv.livetv", "com.amazon.venezia", "com.amazon.tv.settings.v2",
    };

    private MemoryCleaner() {}

    /** The apps it would close: every app with a launcher entry, and Amazon's own, but those it keeps. */
    static Set<String> candidates(Context c) {
        PackageManager pm = c.getPackageManager();
        Set<String> out = new LinkedHashSet<>(Arrays.asList(AMAZON));
        for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            List<ResolveInfo> apps = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0);
            for (ResolveInfo r : apps) out.add(r.activityInfo.packageName);
        }
        out.removeAll(KEEP);
        out.remove(c.getPackageName());
        return out;
    }

    /** Closes them and says how much memory came free, in bytes. Blocks while it settles: off the main thread. */
    static long free(Context c) {
        ActivityManager am = c.getSystemService(ActivityManager.class);
        if (am == null) return 0;
        long before = available(am);
        for (String pkg : candidates(c)) {
            try {
                am.killBackgroundProcesses(pkg);
            } catch (SecurityException e) {
                // not allowed for this one: the rest still go
            }
        }
        try {
            Thread.sleep(1500);   // the memory comes back as the processes end
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Math.max(0, available(am) - before);
    }

    private static long available(ActivityManager am) {
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(info);
        return info.availMem;
    }
}
