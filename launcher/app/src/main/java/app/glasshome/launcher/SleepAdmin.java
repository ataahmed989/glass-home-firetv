package app.glasshome.launcher;

import android.app.admin.DeviceAdminReceiver;

/**
 * Device-admin entry that allows exactly one thing: turning the screen off ("force-lock"), for the
 * Control Center's Sleep and sleep timer. Granted once over ADB; Home Screen settings can give it
 * back, so the launcher can always be uninstalled normally.
 */
public final class SleepAdmin extends DeviceAdminReceiver {
}
