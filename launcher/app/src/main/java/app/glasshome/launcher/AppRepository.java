package app.glasshome.launcher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds the home screen list: configured order first, then any other user-installed apps. */
final class AppRepository {
    private AppRepository() {}

    static List<AppEntry> load(Context context, HomeConfig config) {
        PackageManager pm = context.getPackageManager();
        Map<String, ActivityInfo> launchable = new LinkedHashMap<>();
        Map<String, String> categories = new HashMap<>();
        collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, launchable, categories);
        collect(pm, Intent.CATEGORY_LAUNCHER, launchable, categories);

        List<AppEntry> apps = new ArrayList<>();
        Set<String> added = new HashSet<>();
        for (String key : config.order) {
            if (config.hidden.contains(key) || added.contains(key)) continue;
            AppEntry entry = AppEntry.SETTINGS.equals(key)
                    ? settings(context)
                    : entryFor(pm, key, launchable.get(key), categories.get(key));
            if (entry != null) {
                apps.add(entry);
                added.add(key);
            }
        }

        // Newly installed apps appear automatically; Amazon's own extras stay out unless listed.
        List<AppEntry> extras = new ArrayList<>();
        for (Map.Entry<String, ActivityInfo> app : launchable.entrySet()) {
            String pkg = app.getKey();
            if (added.contains(pkg) || config.hidden.contains(pkg) || pkg.equals(context.getPackageName())) continue;
            if ((app.getValue().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            AppEntry entry = entryFor(pm, pkg, app.getValue(), categories.get(pkg));
            if (entry != null) extras.add(entry);
        }
        extras.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        apps.addAll(extras);
        return apps;
    }

    private static void collect(PackageManager pm, String category, Map<String, ActivityInfo> into, Map<String, String> categories) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
        for (ResolveInfo info : pm.queryIntentActivities(query, 0)) {
            String pkg = info.activityInfo.packageName;
            if (!into.containsKey(pkg)) {
                into.put(pkg, info.activityInfo);
                categories.put(pkg, category);
            }
        }
    }

    private static AppEntry entryFor(PackageManager pm, String pkg, ActivityInfo activity, String category) {
        try {
            Intent launch;
            ApplicationInfo appInfo;
            Intent direct = direct(pkg);
            ResolveInfo directly = direct == null ? null : pm.resolveActivity(direct, 0);
            if (directly != null) {
                launch = direct;
                appInfo = directly.activityInfo.applicationInfo;
            } else if (activity != null) {
                launch = new Intent(Intent.ACTION_MAIN).addCategory(category).setClassName(activity.packageName, activity.name);
                appInfo = activity.applicationInfo;
            } else {
                launch = pm.getLeanbackLaunchIntentForPackage(pkg);
                if (launch == null) launch = pm.getLaunchIntentForPackage(pkg);
                if (launch == null) return null;
                appInfo = pm.getApplicationInfo(pkg, 0);
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            return new AppEntry(pkg, pm.getApplicationLabel(appInfo).toString(), launch);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /**
     * Amazon apps whose launcher entry does not open them: the Appstore's hands over to its app
     * library on Amazon's home screen, and Live TV has none at all. These open the Appstore's
     * storefront and Live TV's channel guide directly, when the TV has them.
     */
    private static Intent direct(String pkg) {
        switch (pkg) {
            case HomeButtonService.APPSTORE:
                return new Intent("com.amazon.venezia.ade.FEATURED_LAUNCH").setPackage(pkg);
            case LIVE_TV:
                return new Intent(Intent.ACTION_VIEW, Uri.parse("livetv://showEpg")).setPackage(pkg);
            case SMARTTUBE:
                // Its search screen is TV-launchable too: the tile opens the app itself
                return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                        .setClassName(pkg, "com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity");
            default:
                return null;
        }
    }

    private static final String LIVE_TV = "com.amazon.tv.livetv";
    private static final String SMARTTUBE = "org.smarttube.stable";

    /**
     * Fire TV's Settings. Android TV's settings intent opens Fire OS's settings screen directly, in
     * Glass Home's own task, so Back comes straight back here; Android's generic one passes through
     * Amazon's home screen first, so it is only the fallback.
     */
    private static AppEntry settings(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(LEANBACK_SETTINGS);
        Intent launch = tv.resolveActivity(pm) != null ? tv : new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (launch.resolveActivity(pm) == null) return null;
        return new AppEntry(AppEntry.SETTINGS, "Settings", launch);
    }

    private static final String LEANBACK_SETTINGS = "android.intent.category.LEANBACK_SETTINGS";
}
