package app.glasshome.launcher;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.provider.Settings;

import java.util.List;

/**
 * What the Control Center can do on a Fire TV from an ordinary app, and nothing more:
 *
 * - Sleep, and the sleep timer: Android's device-admin "lock now", which turns the screen off and
 *   lets the Fire TV sleep. It needs a one-time grant over ADB
 *   ({@code dpm set-active-admin app.glasshome.launcher/.SleepAdmin}); without it the tile says so.
 * - Wi-Fi and Bluetooth: their status, and Fire TV's own settings screens to change them.
 * - Shortcuts: Fire TV's mirroring, accessibility and display screens.
 */
final class SystemControls {
    static final String MIRRORING_PACKAGE = "com.amazon.cast.sink";

    private SystemControls() {}

    // ---- Sleep --------------------------------------------------------------------------------

    static ComponentName admin(Context c) {
        return new ComponentName(c, SleepAdmin.class);
    }

    static boolean canSleep(Context c) {
        DevicePolicyManager dpm = c.getSystemService(DevicePolicyManager.class);
        return dpm != null && dpm.isAdminActive(admin(c));
    }

    /** Puts the TV to sleep now; false when the one-time permission has not been granted. */
    static boolean sleepNow(Context c) {
        if (!canSleep(c)) return false;
        try {
            c.getSystemService(DevicePolicyManager.class).lockNow();
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    /** Gives the sleep permission back, so the launcher can be uninstalled without ADB. */
    static void releaseSleep(Context c) {
        if (canSleep(c)) c.getSystemService(DevicePolicyManager.class).removeActiveAdmin(admin(c));
    }

    private static PendingIntent timerIntent(Context c) {
        Intent i = new Intent(c, SleepTimerReceiver.class);
        return PendingIntent.getBroadcast(c, 1, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Sets the sleep timer for {@code minutes} from now, or turns it off with 0. Returns when it will go off. */
    static long setSleepTimer(Context c, int minutes) {
        AlarmManager alarms = c.getSystemService(AlarmManager.class);
        HomeSettings settings = new HomeSettings(c);
        alarms.cancel(timerIntent(c));
        if (minutes <= 0) {
            settings.setSleepAt(0);
            return 0;
        }
        long at = System.currentTimeMillis() + minutes * 60_000L;
        // Exact on Fire OS 8 (Android 11); newer Android may require permission for exact alarms
        if (android.os.Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, timerIntent(c));
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, timerIntent(c));
        }
        settings.setSleepAt(at);
        return at;
    }

    /** Minutes left on the sleep timer, or 0 when it is off. */
    static int sleepMinutesLeft(Context c) {
        long at = new HomeSettings(c).sleepAt();
        long left = at - System.currentTimeMillis();
        return at == 0 || left <= 0 ? 0 : (int) Math.ceil(left / 60_000d);
    }

    // ---- Wi-Fi --------------------------------------------------------------------------------

    /** "Connected", "Ethernet" or "Not connected". The network's name needs location access, which this app does not ask for. */
    static String networkStatus(Context c) {
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        NetworkCapabilities caps = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
        if (caps == null) return "Not connected";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet";
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "Connected";
        return "Connected";
    }

    /** Wi-Fi signal from 0 to 4 bars, or -1 when not on Wi-Fi. */
    @SuppressWarnings("deprecation")
    static int wifiBars(Context c) {
        try {
            WifiManager wifi = c.getApplicationContext().getSystemService(WifiManager.class);
            if (wifi == null || !wifi.isWifiEnabled() || wifi.getConnectionInfo() == null) return -1;
            int rssi = wifi.getConnectionInfo().getRssi();
            if (rssi <= -127) return -1;
            return WifiManager.calculateSignalLevel(rssi, 5);
        } catch (SecurityException e) {
            return -1;
        }
    }

    // ---- Bluetooth ----------------------------------------------------------------------------

    interface BluetoothResult {
        void onStatus(String status);
    }

    /** "Off", "On", or the name of the connected headphones or speaker (found asynchronously). */
    @SuppressWarnings("deprecation")
    static void bluetoothStatus(Context c, BluetoothResult result) {
        BluetoothAdapter adapter;
        try {
            adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                result.onStatus("Not available");
                return;
            }
            if (!adapter.isEnabled()) {
                result.onStatus("Off");
                return;
            }
        } catch (SecurityException e) {
            result.onStatus("On");
            return;
        }
        result.onStatus("On");
        try {
            adapter.getProfileProxy(c.getApplicationContext(), new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    try {
                        List<BluetoothDevice> devices = proxy.getConnectedDevices();
                        if (!devices.isEmpty() && devices.get(0).getName() != null) result.onStatus(devices.get(0).getName());
                    } catch (SecurityException ignored) {
                        // stays "On"
                    } finally {
                        adapter.closeProfileProxy(profile, proxy);
                    }
                }

                @Override
                public void onServiceDisconnected(int profile) {
                }
            }, BluetoothProfile.A2DP);
        } catch (SecurityException ignored) {
            // stays "On"
        }
    }

    // ---- Fire TV settings screens --------------------------------------------------------------
    // Fire OS answers some of Android's standard settings actions with an empty stand-in screen that
    // closes at once (or nothing at all), so its own actions come first where it has them.

    static Intent[] wifiSettings() {
        return new Intent[] {new Intent(Settings.ACTION_WIFI_SETTINGS)};
    }

    /** "Controllers & Bluetooth Devices": Fire OS has no screen for Android's Bluetooth action. */
    static Intent[] bluetoothSettings() {
        return new Intent[] {new Intent("com.amazon.device.settings.action.CONTROLLERS"),
                new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)};
    }

    static Intent[] displaySettings() {
        return new Intent[] {new Intent(Settings.ACTION_DISPLAY_SETTINGS)};
    }

    /** Android's accessibility action reaches only Fire OS's empty stand-in, which closes straight away. */
    static Intent[] accessibilitySettings() {
        return new Intent[] {new Intent("com.amazon.device.settings.action.ACCESSIBILITY"),
                new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)};
    }

    /** Fire TV's screen mirroring screen (its DisplayMirroring app exports it, with no permission needed). */
    static Intent mirroring(Context c) {
        Intent launch = c.getPackageManager().getLaunchIntentForPackage(MIRRORING_PACKAGE);
        if (launch != null) return launch;
        return new Intent().setComponent(new ComponentName(MIRRORING_PACKAGE, MIRRORING_PACKAGE + ".DisplayMirroringSinkActivity"));
    }

    /** Opens the first of these that exists, then Fire TV's main settings; false if none would open. */
    static boolean open(Context c, Intent... candidates) {
        HomeButtonService.opening(null);
        for (Intent i : candidates) {
            try {
                c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // try the next one
            }
        }
        try {
            c.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }
}
