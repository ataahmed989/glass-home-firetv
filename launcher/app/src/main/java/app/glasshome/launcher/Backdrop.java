package app.glasshome.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

/**
 * Fire OS 8 is API 30, which has no live backdrop blur (RenderEffect is API 31). The hero only
 * changes on focus, so the glass instead samples a small pre-blurred copy of the current slide's
 * background, built once per change. Drawing it is a single shader fill.
 */
final class Backdrop {
    static final int W = 240, H = 135;

    private Backdrop() {}

    static Bitmap render(HeroSlide slide) {
        Bitmap small = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        HeroLayer.drawBackground(new Canvas(small), W, H, slide, new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        blur(small, 5);
        return small;
    }

    /** Three box-blur passes approximate a Gaussian; cheap at 240x135. */
    static void blur(Bitmap bitmap, int radius) {
        int w = bitmap.getWidth(), h = bitmap.getHeight();
        int[] a = new int[w * h], b = new int[w * h];
        bitmap.getPixels(a, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(a, b, w, h, radius, true);
            boxBlur(b, a, w, h, radius, false);
        }
        bitmap.setPixels(a, 0, w, 0, 0, w, h);
    }

    private static void boxBlur(int[] src, int[] dst, int w, int h, int r, boolean horizontal) {
        int lines = horizontal ? h : w, len = horizontal ? w : h, div = r * 2 + 1;
        for (int line = 0; line < lines; line++) {
            int sa = 0, sr = 0, sg = 0, sb = 0;
            for (int i = -r; i <= r; i++) {
                int c = src[index(line, clamp(i, len), w, horizontal)];
                sa += c >>> 24; sr += (c >> 16) & 0xFF; sg += (c >> 8) & 0xFF; sb += c & 0xFF;
            }
            for (int i = 0; i < len; i++) {
                dst[index(line, i, w, horizontal)] = (sa / div) << 24 | (sr / div) << 16 | (sg / div) << 8 | (sb / div);
                int add = src[index(line, clamp(i + r + 1, len), w, horizontal)];
                int sub = src[index(line, clamp(i - r, len), w, horizontal)];
                sa += (add >>> 24) - (sub >>> 24);
                sr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                sg += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                sb += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    private static int clamp(int i, int len) {
        return i < 0 ? 0 : (i >= len ? len - 1 : i);
    }

    private static int index(int line, int i, int w, boolean horizontal) {
        return horizontal ? line * w + i : i * w + line;
    }
}
