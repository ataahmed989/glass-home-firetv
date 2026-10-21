package app.glasshome.launcher;

import android.content.Intent;
import android.graphics.Bitmap;

final class AppEntry {
    static final String SETTINGS = "settings";

    final String key;          // package name, or SETTINGS
    final String label;
    final Intent launchIntent;
    Bitmap art;               // flat composite, for hero cards and non-layered tiles
    TileLayers layers;        // back-to-front layers for the focus effect, when the art provides them

    AppEntry(String key, String label, Intent launchIntent) {
        this.key = key;
        this.label = label;
        this.launchIntent = launchIntent;
    }
}
