package app.glasshome.launcher;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Paint;

import java.util.List;

/**
 * A tile's layers, back to front, plus a soft shadow for each foreground layer. The art itself
 * carries no shadows or lighting; these shadows are generated here from each layer's shape, so the
 * renderer can cast them between layers.
 */
final class TileLayers {
    static final int SHADOW_DOWNSCALE = 4;

    final Bitmap[] layers;
    final Bitmap[] shadows;        // ALPHA_8, quarter size; index 0 (the back layer) has none
    final int[][] shadowOffsets;   // where each shadow sits relative to its quarter-size layer

    private TileLayers(Bitmap[] layers, Bitmap[] shadows, int[][] shadowOffsets) {
        this.layers = layers;
        this.shadows = shadows;
        this.shadowOffsets = shadowOffsets;
    }

    /** Built once per app off the main thread: blurring a quarter-size alpha mask is cheap. */
    static TileLayers of(List<Bitmap> list) {
        Bitmap[] layers = list.toArray(new Bitmap[0]);
        Bitmap[] shadows = new Bitmap[layers.length];
        int[][] offsets = new int[layers.length][];
        Paint blur = new Paint();
        blur.setMaskFilter(new BlurMaskFilter(3.5f, BlurMaskFilter.Blur.NORMAL));
        for (int i = 1; i < layers.length; i++) {
            Bitmap small = Bitmap.createScaledBitmap(layers[i],
                    Math.max(1, layers[i].getWidth() / SHADOW_DOWNSCALE), Math.max(1, layers[i].getHeight() / SHADOW_DOWNSCALE), true);
            int[] offset = new int[2];
            shadows[i] = small.extractAlpha(blur, offset);
            offsets[i] = offset;
            small.recycle();
        }
        return new TileLayers(layers, shadows, offsets);
    }
}
