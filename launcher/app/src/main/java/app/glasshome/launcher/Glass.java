package app.glasshome.launcher;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Shared glass material for the dock and the time capsule: blurred hero underneath, a
 * translucent fill, a faint sheen across the top and a rim that is brightest on the upper edge.
 * In "tint" mode the blurred layer is skipped and the fill is denser.
 */
final class Glass {
    private final Paint backdropPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix backdropMatrix = new Matrix();
    private final RectF local = new RectF();
    private final boolean blur;
    private BitmapShader backdrop;
    private float screenW = 1, screenH = 1, gradientHeight = -1;

    Glass(boolean blur, int fillColor, int tintFillColor) {
        this.blur = blur;
        fillPaint.setColor(blur ? fillColor : tintFillColor);
        dimPaint.setColor(0xFF000000);
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(Math.max(1.5f, Ui.px(1.5f)));
    }

    void setBackdrop(Bitmap small, int screenWidth, int screenHeight) {
        screenW = screenWidth;
        screenH = screenHeight;
        backdrop = small == null ? null : new BitmapShader(small, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        backdropPaint.setShader(backdrop);
    }

    /**
     * Draws the material into {@code rect}. {@code originX/Y} is where the canvas origin sits on
     * screen, so the blurred hero lines up with the real one behind it. {@code dim} darkens the
     * glass together with the hero when the grid is scrolled.
     */
    void draw(Canvas canvas, RectF rect, float radius, float originX, float originY, float dim) {
        draw(canvas, rect, radius, originX, originY, dim, null);
    }

    /** As above, with {@code tint} (e.g. a gradient) replacing the flat translucent fill. */
    void draw(Canvas canvas, RectF rect, float radius, float originX, float originY, float dim, Paint tint) {
        if (blur && backdrop != null) {
            backdropMatrix.setScale(screenW / Backdrop.W, screenH / Backdrop.H);
            backdropMatrix.postTranslate(-originX, -originY);
            backdrop.setLocalMatrix(backdropMatrix);
            canvas.drawRoundRect(rect, radius, radius, backdropPaint);
        }
        canvas.drawRoundRect(rect, radius, radius, tint != null ? tint : fillPaint);
        if (dim > 0f) {
            dimPaint.setAlpha(Math.round(255 * dim));
            canvas.drawRoundRect(rect, radius, radius, dimPaint);
        }
        if (rect.height() != gradientHeight) {
            gradientHeight = rect.height();
            sheenPaint.setShader(new LinearGradient(0, 0, 0, gradientHeight,
                    new int[] {0x1AFFFFFF, 0x00FFFFFF}, new float[] {0f, 0.5f}, Shader.TileMode.CLAMP));
            rimPaint.setShader(new LinearGradient(0, 0, 0, gradientHeight,
                    new int[] {0x73FFFFFF, 0x24FFFFFF, 0x14FFFFFF}, new float[] {0f, 0.35f, 1f}, Shader.TileMode.CLAMP));
        }
        canvas.save();
        canvas.translate(rect.left, rect.top);
        local.set(0, 0, rect.width(), rect.height());
        canvas.drawRoundRect(local, radius, radius, sheenPaint);
        float inset = rimPaint.getStrokeWidth() / 2f;
        local.inset(inset, inset);
        canvas.drawRoundRect(local, radius - inset, radius - inset, rimPaint);
        canvas.restore();
    }
}
