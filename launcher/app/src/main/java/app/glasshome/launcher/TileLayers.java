package app.glasshome.launcher;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.ColorSpace;
import android.graphics.Paint;

import java.util.List;

/**
 * A tile's layers, back to front, plus a soft shadow for each foreground layer. The art itself
 * carries no shadows or lighting; these shadows are generated here from each layer's shape, so the
 * renderer can cast them between layers.
 *
 * Kept small, as each layer is also a texture on the GPU: a back layer of one colour throughout
 * is kept as one pixel of that colour ({@link #solidBack}), and each foreground layer only as far
 * as it is drawn, cut to its shape with a pixel to spare ({@link #origins} says where the cut sits
 * in the whole layer).
 */
final class TileLayers {
    static final int SHADOW_DOWNSCALE = 4;
    /** Tests switch this off to compare the kept-small layers with the whole ones. */
    static boolean compact = true;

    final Bitmap[] layers;
    final Bitmap[] shadows;        // ALPHA_8, quarter size; index 0 (the back layer) has none
    final int[][] shadowOffsets;   // where each shadow sits relative to its quarter-size layer
    /** The back layer's decoded size (400x240 as a rule), which the tile scales to its width. */
    final int width, height;
    /** Each layer's whole size, before it was cut down. */
    final int[][] sizes;
    /** Where each (cut-down) layer's top left sits in the whole layer. */
    final int[][] origins;
    /** The back is of one colour throughout, and kept as one pixel of it. */
    final boolean solidBack;

    private TileLayers(Bitmap[] layers, Bitmap[] shadows, int[][] shadowOffsets, int[][] sizes, int[][] origins,
            boolean solidBack) {
        this.layers = layers;
        this.shadows = shadows;
        this.shadowOffsets = shadowOffsets;
        this.sizes = sizes;
        this.origins = origins;
        this.width = sizes[0][0];
        this.height = sizes[0][1];
        this.solidBack = solidBack;
    }

    /** Built once per app off the main thread: blurring a quarter-size alpha mask is cheap. */
    static TileLayers of(List<Bitmap> list) {
        Bitmap[] layers = list.toArray(new Bitmap[0]);
        Bitmap[] shadows = new Bitmap[layers.length];
        int[][] offsets = new int[layers.length][];
        int[][] sizes = new int[layers.length][];
        int[][] origins = new int[layers.length][];
        Paint blur = new Paint();
        blur.setMaskFilter(new BlurMaskFilter(3.5f, BlurMaskFilter.Blur.NORMAL));
        // The shadows come from the whole layers, before any is cut down
        for (int i = 1; i < layers.length; i++) {
            Bitmap small = Bitmap.createScaledBitmap(layers[i],
                    Math.max(1, layers[i].getWidth() / SHADOW_DOWNSCALE), Math.max(1, layers[i].getHeight() / SHADOW_DOWNSCALE), true);
            int[] offset = new int[2];
            shadows[i] = small.extractAlpha(blur, offset);
            offsets[i] = offset;
            small.recycle();
        }
        for (int i = 0; i < layers.length; i++) {
            sizes[i] = new int[] {layers[i].getWidth(), layers[i].getHeight()};
            origins[i] = new int[2];
        }
        boolean solid = false;
        if (compact) {
            Integer uniform = uniformColor(layers[0]);
            if (uniform != null) {
                // One pixel of it, which the tile's clamped shader spreads over the whole back as before
                solid = true;
                Bitmap dot = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
                dot.setPixel(0, 0, uniform);
                layers[0].recycle();
                layers[0] = dot;
            }
            for (int i = 1; i < layers.length; i++) {
                origins[i] = crop(layers, i);
            }
        }
        return new TileLayers(layers, shadows, offsets, sizes, origins, solid);
    }

    /** The one opaque colour every pixel of {@code b} has, or null (a gradient, transparency, another colour space). */
    private static Integer uniformColor(Bitmap b) {
        if (b.getConfig() != Bitmap.Config.ARGB_8888) return null;
        ColorSpace cs = b.getColorSpace();
        if (cs != null && !cs.equals(ColorSpace.get(ColorSpace.Named.SRGB))) return null;
        int w = b.getWidth(), h = b.getHeight();
        int first = b.getPixel(0, 0);
        if (first >>> 24 != 0xFF) return null;
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            b.getPixels(row, 0, w, 0, y, w, 1);
            for (int c : row) if (c != first) return null;
        }
        return first;
    }

    /**
     * Cuts layer {@code i} down to where it is not clear, with a clear pixel to spare on each side
     * (as the whole layer has around its shape, so filtering and the edge's clamp read the same),
     * and returns where the cut sits. A layer clear throughout keeps one pixel.
     */
    private static int[] crop(Bitmap[] layers, int i) {
        Bitmap layer = layers[i];
        int w = layer.getWidth(), h = layer.getHeight();
        int x0 = w, y0 = h, x1 = -1, y1 = -1;
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            layer.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                if (row[x] >>> 24 == 0) continue;
                if (x < x0) x0 = x;
                if (x > x1) x1 = x;
                if (y < y0) y0 = y;
                if (y > y1) y1 = y;
            }
        }
        if (x1 < 0) {
            x0 = y0 = 0;
            x1 = y1 = 0;
        } else {
            x0 = Math.max(0, x0 - 1);
            y0 = Math.max(0, y0 - 1);
            x1 = Math.min(w - 1, x1 + 1);
            y1 = Math.min(h - 1, y1 + 1);
        }
        int cw = x1 - x0 + 1, ch = y1 - y0 + 1;
        if (cw == w && ch == h) return new int[] {0, 0};
        Bitmap cut = Bitmap.createBitmap(layer, x0, y0, cw, ch);
        if (cut != layer) layer.recycle();
        layers[i] = cut;
        return new int[] {x0, y0};
    }
}
