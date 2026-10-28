package app.glasshome.launcher;

import android.content.Context;
import android.content.SharedPreferences;

/** The user's home-screen choices, kept on the device. */
final class HomeSettings {
    private final SharedPreferences prefs;

    HomeSettings(Context context) {
        prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE);
    }

    /** "Featured on …" titles on the streaming apps: on by default, and only possible with a TMDB token. */
    boolean featuredTitles(boolean tmdbAvailable) {
        return tmdbAvailable && prefs.getBoolean("featured", true);
    }

    void setFeaturedTitles(boolean on) {
        prefs.edit().putBoolean("featured", on).apply();
    }

    /** The constant background behind every app; null until chosen, which means the first one. */
    String backgroundName() {
        return prefs.getString("background", null);
    }

    void setBackground(String name) {
        prefs.edit().putString("background", name).apply();
    }

    /** The widgets' layout: {@link WidgetPanel#STACK} (the default) or {@link WidgetPanel#GLANCES}. */
    String widgetLayout() {
        return prefs.getString("widgets", WidgetPanel.STACK);
    }

    void setWidgetLayout(String layout) {
        prefs.edit().putString("widgets", layout).apply();
    }

    /** Sleep turned off in Home Screen settings (it is on wherever it can work, unless turned off). */
    boolean sleepOff() {
        return prefs.getBoolean("sleep_off", false);
    }

    void setSleepOff(boolean off) {
        prefs.edit().putBoolean("sleep_off", off).apply();
    }

    /** Holding the remote's Menu button opens TiviMate (see {@link HomeButtonService}); on unless turned off. */
    boolean menuHoldOpensTiviMate() {
        return prefs.getBoolean("menu_hold", true);
    }

    void setMenuHoldOpensTiviMate(boolean on) {
        prefs.edit().putBoolean("menu_hold", on).apply();
    }

    /** How the Sources menu looks: {@link SourcesPanel#TILES} (the default) or {@link SourcesPanel#LIST}. */
    String sourcesLook() {
        return prefs.getString("sources", SourcesPanel.TILES);
    }

    void setSourcesLook(String look) {
        prefs.edit().putString("sources", look).apply();
    }

    /** The widget the Smart Stack was left on ({@link WidgetPanel.Kind} name), or null for the first. */
    String stackWidget() {
        return prefs.getString("stack_widget", null);
    }

    void setStackWidget(String kind) {
        prefs.edit().putString("stack_widget", kind).apply();
    }

    /** Whether the remote's Home button brings up Glass Home (on) or leaves Amazon's home screen (off). */
    boolean homeButtonOpensGlassHome() {
        return prefs.getBoolean("home_button", true);
    }

    void setHomeButtonOpensGlassHome(boolean on) {
        prefs.edit().putBoolean("home_button", on).apply();
    }

    /** When the sleep timer goes off (ms since the epoch), or 0 when it is off. */
    long sleepAt() {
        return prefs.getLong("sleep_at", 0L);
    }

    void setSleepAt(long millis) {
        prefs.edit().putLong("sleep_at", millis).apply();
    }
}
