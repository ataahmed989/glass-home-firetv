package app.glasshome.launcher;

import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;
import android.view.animation.PathInterpolator;

/**
 * The glass chevrons around a tile being moved: one in each gutter the tile can go towards,
 * pointing out. They slide out from under the tile, breathe twice and hold still; they follow it
 * as it glides, and fade from the sides it can no longer go. No text, nothing else.
 */
final class MoveIndicator extends View {
    static final int LEFT = 0, UP = 1, RIGHT = 2, DOWN = 3;
    private static final float W = 374, H = 257;

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final boolean[] dirs = new boolean[4];
    private final float[] shown = new float[4];
    private final float[] from = new float[4];
    private float halfW, halfH;
    private ValueAnimator fade;
    private ObjectAnimator breath;

    MoveIndicator(Context context) {
        super(context);
        setVisibility(GONE);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Ui.pxf(3.5f));
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setShadowLayer(Ui.pxf(4.5f), 0, Ui.pxf(1.5f), 0x73000000);
    }

    static int width() {
        return Ui.px(W);
    }

    static int height() {
        return Ui.px(H);
    }

    boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    /** Which way the tile can go: {@link #LEFT}, {@link #UP}, {@link #RIGHT}, {@link #DOWN}. */
    boolean[] directions() {
        return dirs.clone();
    }

    void show(boolean[] can) {
        cancelAll();
        for (int k = 0; k < 4; k++) {
            dirs[k] = can[k];
            shown[k] = can[k] ? 1f : 0f;
        }
        setVisibility(VISIBLE);
        invalidate();
        if (Ui.reducedMotion) {
            setAlpha(1f);
            setScaleX(1f);
            setScaleY(1f);
            return;
        }
        setAlpha(0f);
        setScaleX(0.92f);
        setScaleY(0.92f);
        animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(90).setDuration(260).setInterpolator(Ui.EASE)
                .withEndAction(this::breathe).start();
    }

    /** After a step: chevrons fade in or out where the way opened or closed. */
    void setDirections(boolean[] can) {
        boolean same = true;
        for (int k = 0; k < 4; k++) same &= dirs[k] == can[k];
        if (same) return;
        for (int k = 0; k < 4; k++) {
            dirs[k] = can[k];
            from[k] = shown[k];
        }
        if (fade != null) fade.cancel();
        if (Ui.reducedMotion) {
            for (int k = 0; k < 4; k++) shown[k] = dirs[k] ? 1f : 0f;
            invalidate();
            return;
        }
        fade = ValueAnimator.ofFloat(0f, 1f).setDuration(160);
        fade.setInterpolator(Ui.EASE);
        fade.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            for (int k = 0; k < 4; k++) shown[k] = from[k] + ((dirs[k] ? 1f : 0f) - from[k]) * t;
            invalidate();
        });
        fade.start();
    }

    /** Centred on the tile (its visual half-size given); redrawn only while the tile's size changes. */
    void follow(float cx, float cy, float hw, float hh) {
        setTranslationX(cx - getWidth() / 2f);
        setTranslationY(cy - getHeight() / 2f);
        if (Math.abs(hw - halfW) > 0.25f || Math.abs(hh - halfH) > 0.25f) {
            halfW = hw;
            halfH = hh;
            invalidate();
        }
    }

    /** Two slow breaths, then still. */
    void breathe() {
        stopBreathing();
        if (!isShowing() || Ui.reducedMotion || !TileView.swayEnabled) return;
        breath = ObjectAnimator.ofPropertyValuesHolder(this,
                PropertyValuesHolder.ofFloat(SCALE_X, 1f, 1.02f), PropertyValuesHolder.ofFloat(SCALE_Y, 1f, 1.02f));
        breath.setDuration(700);
        breath.setInterpolator(new PathInterpolator(0.37f, 0f, 0.63f, 1f));
        breath.setRepeatMode(ValueAnimator.REVERSE);
        breath.setRepeatCount(3);
        breath.start();
    }

    void stopBreathing() {
        if (breath == null) return;
        breath.cancel();
        breath = null;
        setScaleX(1f);
        setScaleY(1f);
    }

    void hide(boolean animate) {
        if (!isShowing()) return;
        cancelAll();
        if (!animate || Ui.reducedMotion) {
            setVisibility(GONE);
            return;
        }
        animate().alpha(0f).scaleX(0.94f).scaleY(0.94f).setStartDelay(0).setDuration(160)
                .setInterpolator(new PathInterpolator(0.4f, 0f, 1f, 1f))
                .withEndAction(() -> setVisibility(GONE)).start();
    }

    private void cancelAll() {
        animate().cancel();
        if (breath != null) breath.cancel();
        breath = null;
        if (fade != null) fade.cancel();
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelAll();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float a = Ui.px(3), b = Ui.px(8), retreat = Ui.px(6);
        for (int k = 0; k < 4; k++) {
            if (shown[k] <= 0.01f) continue;
            float off = (1f - shown[k]) * retreat;
            float x = cx, y = cy;
            path.rewind();
            if (k == LEFT || k == RIGHT) {
                float s = k == RIGHT ? 1f : -1f;
                x = cx + s * (halfW + Ui.px(10) - off);
                path.moveTo(x - s * a, y - b);
                path.lineTo(x + s * a, y);
                path.lineTo(x - s * a, y + b);
            } else {
                float s = k == DOWN ? 1f : -1f;
                y = cy + s * (halfH + Ui.px(14) - off);
                path.moveTo(x - b, y - s * a);
                path.lineTo(x, y + s * a);
                path.lineTo(x + b, y - s * a);
            }
            // Lit glass: a bright rim fading to a softer base
            stroke.setShader(new LinearGradient(0, y - b, 0, y + b, 0xF2FFFFFF, 0xC7FFFFFF, Shader.TileMode.CLAMP));
            stroke.setAlpha(Math.round(255 * shown[k]));
            c.drawPath(path, stroke);
        }
    }
}
