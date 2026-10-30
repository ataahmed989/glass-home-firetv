package app.glasshome.launcher;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.HashMap;
import java.util.Map;

/**
 * Liquid Glass, after the "Regular" material of the iOS 26 Control Center kit: the scene behind,
 * lightly frosted and lensed toward the rim, lifted by the kit's "plus lighter" tint (+36 on every
 * channel), with light caught along the top-left and bottom-right edges. There is no outline,
 * fill colour or sheen: everything comes from what is behind.
 *
 * - The frost is a small pre-blurred copy of the scene ({@link Backdrop}); API 30 has no live blur.
 * - The lensing draws that copy magnified a touch inside the shape, so its edges bend the scene.
 * - The tint is a colour matrix on the frost (out = c + 36), as the kit's own porting notes give it.
 *   Over a bright scene the matrix also shades the frost, so the white text on the glass keeps its
 *   contrast (the kit swaps to dark text there; Home keeps white text and lets the glass adapt).
 * - "Lit" glass is the kit's On state, nearly white (out = 0.45c + 200, a touch brighter than the
 *   kit's 0.53c + 186 so focus reads from across the room), for whatever has focus.
 * - In "tint" mode (glass: "tint" in apps.json) the frost is skipped for a flat translucent fill.
 */
final class Glass {
    /** The kit's plus-lighter tint: +36 on each channel. */
    static final float LIFT = 36f;
    /** The scene is darkened, at most this much, until the glass over it stays at or below this level. */
    private static final float MAX_SHADE = 0.68f, READABLE = 100f;
    /** How far (design px) the lensing moves the scene at the rim. */
    private static final float LENS = 10f;

    private final Paint backdropPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix backdropMatrix = new Matrix();
    private final RectF local = new RectF();
    private final boolean blur;
    private final Map<Long, ColorMatrixColorFilter> filters = new HashMap<>();
    private final Map<String, Float> shades = new HashMap<>();
    private final Map<String, LinearGradient[]> lights = new HashMap<>();
    private Bitmap scene;
    private BitmapShader backdrop;
    private float screenW = 1, screenH = 1;
    private final float rimWidth, glowWidth;

    Glass(boolean blur, int tintFillColor) {
        this.blur = blur;
        fillPaint.setColor(tintFillColor);
        dimPaint.setColor(0xFF000000);
        rimWidth = Math.max(1.5f, Ui.px(2f));
        glowWidth = Ui.px(7f);
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(rimWidth);
        glowPaint.setStyle(Paint.Style.STROKE);
        glowPaint.setStrokeWidth(glowWidth);
    }

    void setBackdrop(Bitmap small, int screenWidth, int screenHeight) {
        screenW = screenWidth;
        screenH = screenHeight;
        scene = small;
        backdrop = small == null ? null : new BitmapShader(small, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        backdropPaint.setShader(backdrop);
        shades.clear();
    }

    /**
     * Draws the material into {@code rect}. {@code originX/Y} is where the canvas origin sits on
     * screen, so the frosted scene lines up with the real one behind it. {@code dim} darkens the
     * glass together with the scene when the grid is scrolled.
     */
    void draw(Canvas canvas, RectF rect, float radius, float originX, float originY, float dim) {
        draw(canvas, rect, radius, originX, originY, dim, null);
    }

    /** As above, with {@code tint} (a translucent colour, e.g. the weather's) washed over the frost. */
    void draw(Canvas canvas, RectF rect, float radius, float originX, float originY, float dim, Paint tint) {
        drawGlass(canvas, rect, radius, originX, originY, dim, tint, 0f);
    }

    /**
     * Lit glass, the kit's On state: {@code lit} 0 is the plain material, 1 nearly white with a
     * hint of the scene, for whatever has focus. Content on it turns dark.
     */
    void drawLit(Canvas canvas, RectF rect, float radius, float originX, float originY, float lit) {
        drawGlass(canvas, rect, radius, originX, originY, 0f, null, lit);
    }

    private void drawGlass(Canvas canvas, RectF rect, float radius, float originX, float originY, float dim, Paint tint, float lit) {
        if (blur && backdrop != null) {
            // The scene behind, mapped from the small copy to the screen, then magnified about the
            // shape's centre so the rim bends what is behind it: every edge moves out by LENS
            float lens = Ui.px(LENS);
            float sx = 1f + lens / Math.max(1f, rect.width() / 2f), sy = 1f + lens / Math.max(1f, rect.height() / 2f);
            backdropMatrix.setScale(screenW / scene.getWidth(), screenH / scene.getHeight());
            backdropMatrix.postTranslate(-originX, -originY);
            backdropMatrix.postScale(1f / sx, 1f / sy, rect.centerX(), rect.centerY());
            backdrop.setLocalMatrix(backdropMatrix);
            float shade = shadeFor(rect, originX, originY);
            // Lit is a touch brighter than the kit's On (0.53c + 186) so focus reads from across the room
            float scale = (1f - shade) * (1f - lit) + 0.45f * lit, offset = LIFT * (1f - lit) + 200f * lit;
            backdropPaint.setColorFilter(filter(scale, offset));
            canvas.drawRoundRect(rect, radius, radius, backdropPaint);
        } else {
            fillPaint.setAlpha(0xE0);
            canvas.drawRoundRect(rect, radius, radius, fillPaint);
            if (lit > 0f) {
                dimPaint.setColor(0xFFFFFFFF);
                dimPaint.setAlpha(Math.round(242 * lit));
                canvas.drawRoundRect(rect, radius, radius, dimPaint);
                dimPaint.setColor(0xFF000000);
            }
        }
        if (tint != null) canvas.drawRoundRect(rect, radius, radius, tint);
        if (dim > 0f) {
            dimPaint.setAlpha(Math.round(255 * dim));
            canvas.drawRoundRect(rect, radius, radius, dimPaint);
        }
        drawLight(canvas, rect, radius, 1f - 0.45f * lit);
    }

    /**
     * Light along the rim, brightest where the edge faces the top-left or the bottom-right (the
     * kit's light angle, -45°) and gone at the other two corners: a thin bright line, and a wider
     * faint glow inside it that gives the glass its thickness.
     */
    private void drawLight(Canvas canvas, RectF rect, float radius, float strength) {
        int peak = Math.round(0xB3 * strength), soft = Math.round(0x1F * strength);
        // One pair of gradients per shape and strength, made once
        String key = rect.left + "," + rect.top + "," + rect.right + "," + rect.bottom + "," + peak;
        LinearGradient[] g = lights.get(key);
        if (g == null) {
            g = new LinearGradient[] {
                    new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                            new int[] {peak << 24 | 0xFFFFFF, 0x00FFFFFF, 0x00FFFFFF, (peak * 4 / 5) << 24 | 0xFFFFFF},
                            new float[] {0f, 0.38f, 0.62f, 1f}, Shader.TileMode.CLAMP),
                    new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                            new int[] {soft << 24 | 0xFFFFFF, 0x00FFFFFF, 0x00FFFFFF, soft << 24 | 0xFFFFFF},
                            new float[] {0f, 0.3f, 0.7f, 1f}, Shader.TileMode.CLAMP)};
            if (lights.size() > 64) lights.clear();   // shapes that animate their size do not pile up
            lights.put(key, g);
        }
        rimPaint.setShader(g[0]);
        glowPaint.setShader(g[1]);
        local.set(rect);
        local.inset(glowWidth / 2f, glowWidth / 2f);
        canvas.drawRoundRect(local, Math.max(0f, radius - glowWidth / 2f), Math.max(0f, radius - glowWidth / 2f), glowPaint);
        local.set(rect);
        local.inset(rimWidth / 2f, rimWidth / 2f);
        canvas.drawRoundRect(local, Math.max(0f, radius - rimWidth / 2f), Math.max(0f, radius - rimWidth / 2f), rimPaint);
    }

    /** out = c·scale + offset on each channel, one shared filter per pair. */
    private ColorMatrixColorFilter filter(float scale, float offset) {
        int s = Math.round(scale * 200), o = Math.round(offset);
        long key = (long) s << 32 | (o & 0xFFFFFFFFL);
        ColorMatrixColorFilter f = filters.get(key);
        if (f == null) {
            float k = s / 200f;
            f = new ColorMatrixColorFilter(new ColorMatrix(new float[] {
                    k, 0, 0, 0, o,
                    0, k, 0, 0, o,
                    0, 0, k, 0, o,
                    0, 0, 0, 1, 0}));
            filters.put(key, f);
        }
        return f;
    }

    /**
     * How much to darken the scene under {@code rect}: none over a dark scene (the pure kit
     * material), more over a bright one, until the lifted glass sits near {@link #READABLE}.
     * Sampled once per shape and scene.
     */
    private float shadeFor(RectF rect, float originX, float originY) {
        String key = Math.round(rect.left + originX) + "," + Math.round(rect.top + originY) + "," + Math.round(rect.width()) + "," + Math.round(rect.height());
        Float cached = shades.get(key);
        if (cached != null) return cached;
        float kx = scene.getWidth() / screenW, ky = scene.getHeight() / screenH, sum = 0f;
        int n = 0;
        for (int iy = 0; iy < 6; iy++) {
            for (int ix = 0; ix < 6; ix++) {
                float x = (rect.left + originX + rect.width() * (ix + 0.5f) / 6f) * kx;
                float y = (rect.top + originY + rect.height() * (iy + 0.5f) / 6f) * ky;
                int px = scene.getPixel(clamp(Math.round(x), scene.getWidth()), clamp(Math.round(y), scene.getHeight()));
                sum += 0.2126f * ((px >> 16) & 0xFF) + 0.7152f * ((px >> 8) & 0xFF) + 0.0722f * (px & 0xFF);
                n++;
            }
        }
        float c = sum / n;
        float shade = c <= READABLE - LIFT ? 0f : Math.min(MAX_SHADE, 1f - (READABLE - LIFT) / c);
        if (shades.size() > 64) shades.clear();
        shades.put(key, shade);
        return shade;
    }

    private static int clamp(int v, int size) {
        return Math.max(0, Math.min(size - 1, v));
    }
}
