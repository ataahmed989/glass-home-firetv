package app.glasshome.launcher;

import android.content.Context;
import android.graphics.Typeface;
import android.util.SparseArray;

/** Inter variable font (SIL OFL 1.1) bundled in assets, cached per weight. */
final class Fonts {
    private static final SparseArray<Typeface> CACHE = new SparseArray<>();
    private static final SparseArray<Typeface> DISPLAY = new SparseArray<>();

    private Fonts() {}

    static synchronized Typeface inter(Context context, int weight) {
        Typeface cached = CACHE.get(weight);
        if (cached != null) return cached;
        Typeface face = new Typeface.Builder(context.getAssets(), "fonts/Inter-Variable.ttf")
                .setFontVariationSettings("'wght' " + weight)
                .setFallback("sans-serif")
                .build();
        CACHE.put(weight, face);
        return face;
    }

    /** Inter's display cut (its optical size at 32): tighter and finer, for type far above text size. */
    static synchronized Typeface interDisplay(Context context, int weight) {
        Typeface cached = DISPLAY.get(weight);
        if (cached != null) return cached;
        Typeface face = new Typeface.Builder(context.getAssets(), "fonts/Inter-Variable.ttf")
                .setFontVariationSettings("'opsz' 32, 'wght' " + weight)
                .setFallback("sans-serif")
                .build();
        DISPLAY.put(weight, face);
        return face;
    }
}
