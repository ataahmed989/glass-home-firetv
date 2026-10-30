package app.glasshome.launcher;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.LinearInterpolator;

/**
 * One 5:3 app tile, drawn the way layered tvOS icons behave.
 *
 * The art is flat; this view adds the dimension. With layers (an opaque back layer and one or
 * more transparent foreground layers), each foreground layer casts a soft shadow onto what is
 * behind it, and on focus the layers drift apart in one gentle sway (parallax) that dies away
 * and settles, while a soft virtual light glides across the surface and the rim catches a glare.
 * Unfocused tiles keep only a very subtle shadow and edge glare. Everything is clipped to the
 * rounded tile, so nothing spills out.
 *
 * Focus also scales the tile about 6% and lifts it onto a shadow (the view's elevation).
 *
 * Only the focused tile has a live shadow and redraws while it animates. A tile at rest is kept
 * as a ready-drawn GPU layer, so a screenful of tiles costs one textured quad each per frame.
 */
final class TileView extends View {
    private static final float MAX_SHIFT = 5f;        // design px a front layer can drift at full tilt
    private static final long SWAY_MS = 2400;          // the sway on focus, from first swing to rest
    private static final float SWAY_CYCLES = 1.25f;    // swings in that time, each smaller than the last
    private static final float REST_TILT_Y = -0.25f;   // at rest: tipped back a touch so the layers read as separate

    /** Tests switch the sway off, so every render shows the tile at rest. */
    static boolean swayEnabled = true;
    /** Likewise the jiggle of a tile being moved. */
    static boolean jiggleEnabled = true;
    private static final float JIGGLE_DEG = 1.6f;   // iOS's jiggle, at the TV's distance
    private static final long JIGGLE_MS = 260;      // one full wobble

    private final Paint flatPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint emptyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint lightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bloomPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final RectF rim = new RectF();
    private final Matrix matrix = new Matrix();
    private final float radius;

    private Bitmap art;
    private TileLayers layers;
    private Paint[] layerPaints;
    private BitmapShader[] layerShaders;
    private BitmapShader[] shadowShaders;
    private RadialGradient light;

    private float focus;          // 0..1, eased with the focus transition
    private float tiltX, tiltY = REST_TILT_Y;   // -1..1, the sway after focus arrives
    private ValueAnimator focusAnimator, swayAnimator;
    /** While apps are arranged: {@link #NONE}, {@link #LIFTED} (being moved) or {@link #RECEDED} (making room). */
    static final int NONE = 0, LIFTED = 1, RECEDED = 2;
    private int role = NONE;
    private boolean menuRaised, menuDimmed, pressing;
    private android.animation.ObjectAnimator alphaAnim;
    private ValueAnimator jiggle;

    TileView(Context context) {
        super(context);
        radius = Ui.px(Ui.TILE_RADIUS);
        setFocusable(true);
        setClickable(true);
        setDefaultFocusHighlightEnabled(false);

        emptyPaint.setColor(0x26FFFFFF);
        shadowPaint.setColor(0xFF000000);
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setStrokeWidth(Math.max(1.5f, Ui.px(2f)));
        // OUTER keeps the faint bloom outside the tile, so the artwork itself is untouched
        bloomPaint.setColor(0x33FFFFFF);
        bloomPaint.setMaskFilter(new BlurMaskFilter(Ui.px(9), BlurMaskFilter.Blur.OUTER));
        // Light source: a unit radial gradient, positioned and sized with its local matrix each frame
        light = new RadialGradient(0, 0, 1, new int[] {0x40FFFFFF, 0x14FFFFFF, 0x00FFFFFF},
                new float[] {0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
        lightPaint.setShader(light);

        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    void setArt(Bitmap flat, TileLayers layered) {
        if (flat == art && layered == layers) return;
        art = flat;
        flatPaint.setShader(flat == null ? null : new BitmapShader(flat, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        layers = layered;
        if (layered != null) {
            int n = layered.layers.length;
            layerPaints = new Paint[n];
            layerShaders = new BitmapShader[n];
            shadowShaders = new BitmapShader[n];
            for (int i = 0; i < n; i++) {
                layerShaders[i] = new BitmapShader(layered.layers[i], Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                layerPaints[i] = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                layerPaints[i].setShader(layerShaders[i]);
                if (layered.shadows[i] != null) {
                    shadowShaders[i] = new BitmapShader(layered.shadows[i], Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                }
            }
        }
        invalidate();
    }

    /** Puts a recycled tile back to its resting state without animating. */
    void resetFocusState() {
        animate().cancel();
        stopAnimators();
        if (alphaAnim != null) alphaAnim.cancel();
        stopJiggle();
        setRotation(0f);
        role = NONE;
        menuRaised = menuDimmed = pressing = false;
        boolean f = isFocused();
        setScaleX(targetScale());
        setScaleY(targetScale());
        setTranslationZ(targetZ());
        setTranslationY(0f);
        setAlpha(1f);
        focus = f ? 1f : 0f;
        tiltX = 0f;
        tiltY = REST_TILT_Y;
        setLayerType(f ? LAYER_TYPE_NONE : LAYER_TYPE_HARDWARE, null);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        bounds.set(0, 0, w, h);
        float in = rimPaint.getStrokeWidth() / 2f;
        rim.set(in, in, w - in, h - in);
        // Glare is brightest along the top edge and fades down the sides
        rimPaint.setShader(new LinearGradient(0, 0, 0, h, new int[] {0xFFFFFFFF, 0x99FFFFFF, 0x59FFFFFF},
                new float[] {0f, 0.4f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (focus > 0f) canvas.drawRoundRect(bounds, radius, radius, bloomPaint);

        if (layers != null) {
            drawLayers(canvas, w, h);
        } else if (art != null) {
            float s = w / art.getWidth();
            matrix.setScale(s, s);
            flatPaint.getShader().setLocalMatrix(matrix);
            canvas.drawRoundRect(bounds, radius, radius, flatPaint);
        } else {
            canvas.drawRoundRect(bounds, radius, radius, emptyPaint);
        }

        if (focus > 0f) {
            // A soft virtual light, on the side away from the tilt
            float lx = w * (0.5f - tiltX * 0.35f), ly = h * (0.12f - tiltY * 0.25f), lr = w * 0.85f;
            matrix.setScale(lr, lr);
            matrix.postTranslate(lx, ly);
            light.setLocalMatrix(matrix);
            lightPaint.setAlpha(Math.round(255 * focus));
            canvas.drawRoundRect(bounds, radius, radius, lightPaint);
        }
        // Edge glare: barely there at rest, a clear bright rim when focused
        rimPaint.setAlpha(Math.round(255 * (0.16f + 0.69f * focus)));
        canvas.drawRoundRect(rim, radius - rim.left, radius - rim.top, rimPaint);
    }

    /** Back to front: each foreground layer first casts its shadow, then draws, each offset by its depth. */
    private void drawLayers(Canvas canvas, float w, float h) {
        int n = layers.layers.length;
        float base = w / layers.layers[0].getWidth();
        float shift = Ui.px(MAX_SHIFT) * focus;
        for (int i = 0; i < n; i++) {
            Bitmap layer = layers.layers[i];
            float depth = i / (float) (n - 1);              // 0 = back, 1 = front
            float travel = (depth - 0.3f) * 2f;             // the back drifts slightly against the tilt
            float dx = tiltX * shift * travel, dy = tiltY * shift * travel;
            // The back layer is enlarged a touch while focused so its edges never show as it moves
            float s = base * (i == 0 ? 1f + 0.05f * focus : 1f);
            float ox = (w - layer.getWidth() * s) / 2f + dx, oy = (h - layer.getHeight() * s) / 2f + dy;

            if (i > 0 && shadowShaders[i] != null) {
                int[] off = layers.shadowOffsets[i];
                float ss = s * TileLayers.SHADOW_DOWNSCALE;
                float drop = Ui.px(2f + 4f * focus);
                matrix.setScale(ss, ss);
                matrix.postTranslate(ox + off[0] * ss - tiltX * shift * 0.5f, oy + off[1] * ss + drop - tiltY * shift * 0.5f);
                shadowShaders[i].setLocalMatrix(matrix);
                shadowPaint.setShader(shadowShaders[i]);
                shadowPaint.setAlpha(Math.round(255 * (0.22f + 0.23f * focus)));
                canvas.drawRoundRect(bounds, radius, radius, shadowPaint);
            }
            matrix.setScale(s, s);
            matrix.postTranslate(ox, oy);
            layerShaders[i].setLocalMatrix(matrix);
            canvas.drawRoundRect(bounds, radius, radius, layerPaints[i]);
        }
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        if (!gainFocus) pressing = false;
        long ms = Ui.reducedMotion ? 0 : Ui.FOCUS_MS;
        apply(true, Ui.EASE, ms);
        // Lift the whole cell so the bloom draws over neighbouring tiles rather than under them
        if (getParent() instanceof View) ((View) getParent()).setTranslationZ(gainFocus ? 1f : 0f);

        if (focusAnimator != null) focusAnimator.cancel();
        // Drawn directly while it changes (its bloom reaches past its edges); a layer again once it rests
        if (gainFocus) setLayerType(LAYER_TYPE_NONE, null);
        focusAnimator = ValueAnimator.ofFloat(focus, gainFocus ? 1f : 0f);
        focusAnimator.setDuration(ms);
        focusAnimator.setInterpolator(Ui.EASE);
        focusAnimator.addUpdateListener(a -> {
            focus = (float) a.getAnimatedValue();
            invalidate();
        });
        focusAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (!isFocused() && focus == 0f) setLayerType(LAYER_TYPE_HARDWARE, null);
            }
        });
        focusAnimator.start();
        if (gainFocus) startSway();
        else if (swayAnimator != null) swayAnimator.cancel();
    }

    /**
     * One sway when focus arrives, each swing smaller than the last until the tile is still: it
     * starts and ends at rest, so nothing jumps, and the focused tile stops redrawing once settled.
     * None at all with reduced motion.
     */
    private void startSway() {
        if (swayAnimator != null) swayAnimator.cancel();
        tiltX = 0f;
        tiltY = REST_TILT_Y;
        if (Ui.reducedMotion || !swayEnabled) return;
        swayAnimator = ValueAnimator.ofFloat(0f, 1f);
        swayAnimator.setDuration(SWAY_MS);
        swayAnimator.setInterpolator(new LinearInterpolator());
        swayAnimator.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            float fade = (1f - t) * (1f - t) * (1f - t);   // dies away smoothly, reaching rest at the end
            float phase = (float) (2 * Math.PI * SWAY_CYCLES * t);
            tiltX = (float) Math.sin(phase) * 0.5f * fade;
            tiltY = REST_TILT_Y + (float) Math.sin(phase * 0.5f) * 0.3f * fade;
            invalidate();
        });
        swayAnimator.start();
    }

    // ---- Arranging apps ---------------------------------------------------------------------

    private float targetScale() {
        if (role == LIFTED) return Ui.LIFT_SCALE;
        if (role == RECEDED) return Ui.RECEDE_SCALE;
        if (!isFocused()) return 1f;
        return pressing ? Ui.PRESS_SCALE : Ui.FOCUS_SCALE;
    }

    private float targetZ() {
        if (role == LIFTED) return Ui.px(Ui.LIFT_Z);
        return isFocused() ? Ui.px(Ui.FOCUS_LIFT) : 0;
    }

    private float targetY() {
        return menuRaised ? -Ui.px(Ui.MENU_RISE) : 0;
    }

    /** Scale, lift and rise to where the tile's state puts them: at once, or over {@code ms} on {@code curve}. */
    private void apply(boolean animate, TimeInterpolator curve, long ms) {
        animate().cancel();
        if (!animate || Ui.reducedMotion || ms == 0) {
            setScaleX(targetScale());
            setScaleY(targetScale());
            setTranslationZ(targetZ());
            setTranslationY(targetY());
            if (role != LIFTED) setRotation(0f);
            return;
        }
        android.view.ViewPropertyAnimator a = animate().scaleX(targetScale()).scaleY(targetScale())
                .translationZ(targetZ()).translationY(targetY());
        if (role != LIFTED) a.rotation(0f);
        a.setInterpolator(curve).setDuration(ms).start();
    }

    /** Lifted to be moved, receded to make room, or back to normal ({@link #NONE}). */
    void setRole(int r, boolean animate) {
        if (role == r) return;
        Spring spring = r == LIFTED || role == LIFTED ? Spring.LIFT : Spring.REORDER;
        role = r;
        if (r == LIFTED) startJiggle();
        else stopJiggle();
        apply(animate, spring.interpolator(), spring.settleMs());
    }

    int role() {
        return role;
    }

    /**
     * The tile being moved wobbles as icons do in iOS's jiggle mode: a quick, small rock about its
     * centre, easing in over the first wobble. Only its rotation changes, so nothing is redrawn.
     */
    private void startJiggle() {
        stopJiggle();
        if (Ui.reducedMotion || !jiggleEnabled) return;
        jiggle = ValueAnimator.ofFloat(0f, 1f);
        jiggle.setDuration(JIGGLE_MS);
        jiggle.setRepeatCount(ValueAnimator.INFINITE);
        jiggle.setInterpolator(new LinearInterpolator());
        final long start = android.view.animation.AnimationUtils.currentAnimationTimeMillis();
        jiggle.addUpdateListener(a -> {
            float ramp = Math.min(1f, (android.view.animation.AnimationUtils.currentAnimationTimeMillis() - start) / (float) JIGGLE_MS);
            setRotation(JIGGLE_DEG * ramp * (float) Math.sin(2 * Math.PI * (float) a.getAnimatedValue()));
        });
        jiggle.start();
    }

    private void stopJiggle() {
        if (jiggle != null) jiggle.cancel();
        jiggle = null;
    }

    boolean isJiggling() {
        return jiggle != null;
    }

    /** The held tile rises for its menu ({@code raised}); the others dim while it is open. */
    void setMenuState(boolean raised, boolean dimmed, boolean animate) {
        if (raised != menuRaised) {
            menuRaised = raised;
            Spring spring = raised ? Spring.POP : Spring.MENU;
            apply(animate, spring.interpolator(), spring.settleMs());
        }
        if (dimmed != menuDimmed) {
            menuDimmed = dimmed;
            float to = dimmed ? Ui.MENU_DIM : 1f;
            if (alphaAnim != null) alphaAnim.cancel();
            if (!animate || Ui.reducedMotion) {
                setAlpha(to);
            } else {
                alphaAnim = android.animation.ObjectAnimator.ofFloat(this, ALPHA, getAlpha(), to).setDuration(200);
                alphaAnim.setInterpolator(Ui.EASE);
                alphaAnim.start();
            }
        }
    }

    boolean menuRaised() {
        return menuRaised;
    }

    /** Select held down on the tile: it sinks a little, so the hold is felt before its menu opens. */
    void setPressing(boolean on) {
        if (pressing == on) return;
        pressing = on;
        if (on) apply(true, new android.view.animation.PathInterpolator(0.33f, 0f, 0.67f, 1f), 450);
        else apply(true, Ui.EASE, 140);
    }

    private void stopAnimators() {
        if (focusAnimator != null) focusAnimator.cancel();
        if (swayAnimator != null) swayAnimator.cancel();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimators();
        if (alphaAnim != null) alphaAnim.cancel();
        stopJiggle();
        super.onDetachedFromWindow();
    }
}
