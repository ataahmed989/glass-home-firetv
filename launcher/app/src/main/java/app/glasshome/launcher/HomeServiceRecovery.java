package app.glasshome.launcher;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Optional recovery after Fire OS clears accessibility on reboot.
 * Enabled only by an explicit ADB WRITE_SECURE_SETTINGS grant and the Home Button setting.
 * No polling, network access, or activity launch from this receiver.
 */
public final class HomeServiceRecovery extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) restore(context);
    }

    static String withService(String current, ComponentName own) {
        Set<String> services = new LinkedHashSet<>();
        if (current != null && !"null".equals(current)) {
            for (String entry : current.split(":")) {
                entry = entry.trim();
                if (!entry.isEmpty()) services.add(entry);
            }
        }
        for (String entry : services) {
            if (own.equals(ComponentName.unflattenFromString(entry))) return String.join(":", services);
        }
        services.add(own.flattenToString());
        return String.join(":", services);
    }

    static void restore(Context context) {
        if (!new HomeSettings(context).homeButtonOpensGlassHome()
                || context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) return;
        try {
            String old = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String updated = withService(old, new ComponentName(context, HomeButtonService.class));
            if (!updated.equals(old) && !Settings.Secure.putString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)) {
                Log.w("HomeServiceRecovery", "Could not restore Home service");
                return;
            }
            if (Settings.Secure.getInt(context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                Settings.Secure.putInt(context.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            }
        } catch (SecurityException e) {
            Log.w("HomeServiceRecovery", "Fire OS denied Home service recovery");
        }
    }
}
