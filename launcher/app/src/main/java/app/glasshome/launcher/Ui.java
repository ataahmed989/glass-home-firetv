package app.glasshome.launcher;

/**
 * Home screen geometry, measured from the target mockup at 1920x1080 and scaled to the real
 * screen width at runtime, so the layout does not depend on the device's density bucket.
 */
final class Ui {
    static final float DESIGN_WIDTH = 1920f;
    static final int COLUMNS = 6;

    static final int TILE_W = 258;              // 5:3 tiles; the height is derived from the width
    static final int TILE_RADIUS = 18;
    static final int COL_PITCH = 290;           // tile + 32 gap
    static final int GRID_SIDE = 90;            // (1920 - 6 * 290) / 2, so the first tile starts at x = 106
    static final int ROW_PITCH = 224;           // the next row starts at y = 1025, leaving a 55 px peek
    static final int DOCK_ROW_TOP = 801;        // every focused row settles here, inside the fixed dock
    static final int DOCK_INSET_X = 56;
    static final int DOCK_TOP = 767;
    static final int DOCK_BOTTOM = 1004;        // 48 below the tiles: room for the focused app's name
    static final int LABEL_GAP = 12;            // tile bottom to label top
    static final int LABEL_H = 30;
    static final int DOCK_RADIUS = 46;

    // Focused-app content column, and the weather column reserved to its right
    static final int CONTENT_LEFT = 106;
    static final int CONTENT_RIGHT = 1415;
    static final int CONTENT_BOTTOM = 735;
    // Weather card, measured from the supplied mockup: right edge aligned with the dock and the capsule
    static final int WEATHER_LEFT = 1459;
    static final int WEATHER_TOP = 199;
    static final int WEATHER_W = 405;
    static final int WEATHER_H = 465;

    static final int CAPSULE_TOP = 53;
    static final int CAPSULE_RIGHT = 57;
    static final int CAPSULE_H = 71;

    static final float FOCUS_SCALE = 1.06f;
    static final int FOCUS_LIFT = 16;
    static final int FOCUS_MS = 180;
    static final int HERO_DWELL_MS = 400;       // focus must rest this long before the hero changes
    static final int KEY_REPEAT_DELAY_MS = 300; // holding a direction: wait, then step at a steady rate
    static final int KEY_REPEAT_MS = 110;
    static final int SCROLL_MS = 380;
    static final int CONTENT_FADE_MS = 280;
    static final int SLIDE_FADE_MS = 700;
    static final int SLIDE_INTERVAL_MS = 9000;
    static final int TAB_DWELL_MS = 300;        // a tab passed over quickly does not load its page
    static final int PAGE_FADE_MS = 180;
    static final int BAR_HIDE_Y = 150;          // the top bar slides up this far while browsing shelves

    /** System animations off, or "reduce_motion" in apps.json: no fades, slides or animated scrolling. */
    static boolean reducedMotion;

    private static float scale = 1f;

    /** Restrained ease-out used by every focus transition. */
    static final android.view.animation.Interpolator EASE = new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f);

    private Ui() {}

    static void init(int screenWidth) {
        scale = screenWidth / DESIGN_WIDTH;
    }

    static int px(float designPx) {
        return Math.round(designPx * scale);
    }

    static int tileW() {
        return px(TILE_W);
    }

    /** Exactly 5:3, matching the 800x480 artwork, so tiles are never stretched. */
    static int tileH() {
        return Math.round(tileW() * 480f / 800f);
    }
}
