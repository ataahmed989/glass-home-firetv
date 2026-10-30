package app.glasshome.launcher;

import android.content.Context;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.List;

/**
 * The Amazon apps turned off over ADB at setup (firetv/disabled-packages.txt: telemetry, ads,
 * Ambient and the like). A Fire OS update can turn them back on; Glass Home can't turn them off
 * again itself (that takes ADB), but it can see it happen and say so.
 */
final class FireOsCleanup {
    static final String[] PACKAGES = {
            "com.amazon.client.metrics.api",
            "com.amazon.device.telemetry.emitter",
            "com.amazon.dp.logger",
            "com.amazon.ceviche",
            "com.amazon.perfc",
            "com.amazon.perfcollection",
            "com.amazon.neo.minerva",
            "com.amazon.neo.minerva.logcat",
            "com.amazon.wirelessmetrics.service",
            "com.amazon.kso.blackbird",
            "com.amazon.hybridadidservice",
            "com.amazon.tv.acr",
            "com.amazon.bueller.photos",
            "com.amazon.bueller.music",
            "com.amazon.tv.ftvambient",
            "com.amazon.sneakpeek",
            "com.amazon.shoptv.client",
            "com.amazon.shoptv.firetv.client",
            "com.amazon.d3",
            "com.amazon.logan",
            "com.amazon.media.recommendations",
            "com.amazon.minitv.android.app",
            "com.amazon.gamehub",
            "com.amazon.tv.earlyaccess",
            "com.amazon.tv.releasenotes",
            "com.amazon.storm.lightning.tutorial",
            "com.amazon.tmm.tutorial",
            "com.amazon.smarthomemapviewapp",
            "com.android.protips",
            "com.android.printservice.recommendation",
            "com.amazon.tv.matter",
            "com.amazon.tv.mattercohost",
            "com.amazon.ods.kindleconnect",
            "com.amazon.rtcsessioncontroller",
            "com.amazon.tv.ffsprovisioneeclient",
            "com.android.traceur",
            "com.amazon.cloud9",
            "com.amazon.tahoe"
    };

    private FireOsCleanup() {}

    /** What became of the list on this TV. */
    static final class Status {
        /** On this TV and still off. */
        int off;
        /** On this TV and on again. */
        final List<String> backOn = new ArrayList<>();
    }

    /**
     * {@code seenOff}: the apps Glass Home has seen turned off, added to here. Only those count as
     * turned back on, so a TV never cleaned up (or turned back on on purpose, with restore.sh) is
     * never told Fire OS did it.
     */
    static Status check(Context context, java.util.Set<String> seenOff) {
        PackageManager pm = context.getPackageManager();
        Status status = new Status();
        for (String pkg : PACKAGES) {
            int state;
            try {
                state = pm.getApplicationEnabledSetting(pkg);
            } catch (IllegalArgumentException notOnThisTv) {
                continue;
            }
            if (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT || state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                if (seenOff.contains(pkg)) status.backOn.add(pkg);
            } else {
                status.off++;
                seenOff.add(pkg);
            }
        }
        return status;
    }

    /** An app's name, where Amazon gave it one; null where its name is only its package. */
    static String name(Context context, String pkg) {
        PackageManager pm = context.getPackageManager();
        try {
            String label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString().trim();
            return label.isEmpty() || label.equals(pkg) || label.startsWith("com.") ? null : label;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /** "3 Amazon apps", and the names of up to two of them. */
    static String describe(Context context, List<String> packages) {
        List<String> names = new ArrayList<>();
        for (String p : packages) {
            String n = name(context, p);
            if (n != null && !names.contains(n)) names.add(n);
        }
        int n = packages.size();
        String count = n == 1 ? "1 Amazon app" : n + " Amazon apps";
        if (names.isEmpty()) return count;
        if (names.size() == 1) return count + " (" + names.get(0) + (n > 1 ? " and others" : "") + ")";
        return count + " (" + names.get(0) + ", " + names.get(1) + (n > 2 ? " and others" : "") + ")";
    }
}
