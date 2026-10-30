package app.glasshome.launcher;

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.animation.PathInterpolator;

/**
 * What holding Select on a tile brings up: a small glass card that unfolds from under the tile,
 * as wide as the tile, with two rows: Move, and More Options (the full menu). The focused row is
 * a white pill that slides between them, as on the tab bar. The tile keeps focus; Up and Down
 * move the pill, Select chooses, Back folds the card away.
 */
final class TileMenu extends View {
    static final String[] LABELS = {"Move", "More Options"};
    private static final float W = 289, H = 92, R = 25, PAD = 4, ROW = 42, GAP = 8, EDGE = 24;

    private final Glass glass;
    private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final ArgbEvaluator argb = new ArgbEvaluator();
    private final Path moveGlyph = new Path();
    private final RectF r = new RectF();
    private boolean open;
    private int focused;
    private float pillY, restX, restY;
    /** Milliseconds into the rows' reveal (240 when shown). */
    private float revealMs = 240;
    private Runnable onMove, onMore;
    private ValueAnimator pillAnim, revealAnim;
    private ObjectAnimator alphaAnim;

    TileMenu(Context context, Glass glass) {
        super(context);
        this.glass = glass;
        setVisibility(GONE);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        label.setTypeface(Fonts.inter(context, 500));
        label.setTextSize(Ui.px(22));
        pill.setColor(0xF7FFFFFF);
        pill.setShadowLayer(Ui.px(14), 0, Ui.px(5), 0x59000000);
        glyph.setStyle(Paint.Style.STROKE);
        glyph.setStrokeWidth(Ui.px(2f));
        glyph.setStrokeCap(Paint.Cap.ROUND);
        glyph.setStrokeJoin(Paint.Join.ROUND);
        // Move: a four-way arrow, in an 18 px box
        float a = Ui.px(7), h = Ui.px(3);
        moveGlyph.moveTo(-a, 0);
        moveGlyph.lineTo(a, 0);
        moveGlyph.moveTo(0, -a);
        moveGlyph.lineTo(0, a);
        moveGlyph.moveTo(-a + h, -h);
        moveGlyph.lineTo(-a, 0);
        moveGlyph.lineTo(-a + h, h);
        moveGlyph.moveTo(a - h, -h);
        moveGlyph.lineTo(a, 0);
        moveGlyph.lineTo(a - h, h);
        moveGlyph.moveTo(-h, -a + h);
        moveGlyph.lineTo(0, -a);
        moveGlyph.lineTo(h, -a + h);
        moveGlyph.moveTo(-h, a - h);
        moveGlyph.lineTo(0, a);
        moveGlyph.lineTo(h, a - h);
        setElevation(Ui.px(18));
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), Ui.px(R));
            }
        });
    }

    /** Its size, for the layout params. */
    static int width() {
        return Ui.px(W);
    }

    static int height() {
        return Ui.px(H);
    }

    boolean isOpen() {
        return open;
    }

    int focusedIndex() {
        return focused;
    }

    /** Where the card is on screen, for tests. */
    void cardBounds(RectF out) {
        out.set(restX, restY, restX + getWidth(), restY + getHeight());
    }

    private float rowY(int i) {
        return Ui.px(PAD + ROW * i);
    }

    /**
     * Unfolds under a tile centred at {@code tileCx} whose (raised) top and bottom are given; above
     * it instead where there is no room below.
     */
    void show(float tileCx, float tileTop, float tileBottom, Runnable move, Runnable more) {
        onMove = move;
        onMore = more;
        View parent = (View) getParent();
        int screenW = parent.getWidth(), screenH = parent.getHeight();
        float w = width(), h = height(), edge = Ui.px(EDGE);
        restX = Math.max(edge, Math.min(tileCx - w / 2f, screenW - edge - w));
        restY = tileBottom + Ui.px(GAP);
        boolean flip = restY + h > screenH - edge;
        if (flip) restY = tileTop - Ui.px(GAP) - h;
        open = true;
        focused = 0;
        pillY = rowY(0);
        cancelAnimations();
        setVisibility(VISIBLE);
        setPivotX(w / 2f);
        setPivotY(flip ? h : 0);
        setTranslationX(restX);
        if (Ui.reducedMotion) {
            setTranslationY(restY);
            setScaleX(1f);
            setScaleY(1f);
            setAlpha(1f);
            revealMs = 240;
            invalidate();
            return;
        }
        setTranslationY(restY + (flip ? Ui.px(10) : -Ui.px(10)));
        setScaleX(0.94f);
        setScaleY(0.60f);
        setAlpha(0f);
        animate().translationY(restY).scaleX(1f).scaleY(1f).setDuration(Spring.MENU.settleMs())
                .setInterpolator(Spring.MENU.interpolator()).start();
        alphaAnim = ObjectAnimator.ofFloat(this, ALPHA, 0f, 1f).setDuration(140);
        alphaAnim.setInterpolator(Ui.EASE);
        alphaAnim.start();
        revealMs = 0;
        revealAnim = ValueAnimator.ofFloat(0f, 240f).setDuration(240);
        revealAnim.setInterpolator(null);
        revealAnim.addUpdateListener(an -> {
            revealMs = (float) an.getAnimatedValue();
            invalidate();
        });
        revealAnim.start();
        invalidate();
    }

    /** Up (-1) or Down (+1); nothing past the ends. */
    void move(int delta) {
        int to = focused + delta;
        if (!open || to < 0 || to >= LABELS.length) return;
        focused = to;
        if (pillAnim != null) pillAnim.cancel();
        if (Ui.reducedMotion) {
            pillY = rowY(to);
            invalidate();
            return;
        }
        pillAnim = ValueAnimator.ofFloat(pillY, rowY(to)).setDuration(Ui.FOCUS_MS + 20);
        pillAnim.setInterpolator(Ui.EASE);
        pillAnim.addUpdateListener(an -> {
            pillY = (float) an.getAnimatedValue();
            invalidate();
        });
        pillAnim.start();
    }

    void choose() {
        if (!open) return;
        Runnable then = focused == 0 ? onMove : onMore;
        if (then != null) then.run();
    }

    /** Folds the card away (closed at once as far as the remote goes). */
    void dismiss(boolean animate) {
        if (getVisibility() != VISIBLE) {
            open = false;
            return;
        }
        open = false;
        cancelAnimations();
        if (!animate || Ui.reducedMotion) {
            setVisibility(GONE);
            return;
        }
        animate().alpha(0f).scaleX(0.96f).scaleY(0.80f).translationY(restY - Ui.px(8)).setDuration(160)
                .setInterpolator(new PathInterpolator(0.4f, 0f, 1f, 1f))
                .withEndAction(() -> {
                    if (!open) setVisibility(GONE);
                }).start();
    }

    void dismissNow() {
        dismiss(false);
    }

    private void cancelAnimations() {
        animate().cancel();
        if (alphaAnim != null) alphaAnim.cancel();
        if (revealAnim != null) revealAnim.cancel();
        if (pillAnim != null) pillAnim.cancel();
        revealMs = 240;
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelAnimations();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        r.set(0, 0, getWidth(), getHeight());
        glass.draw(c, r, Ui.px(R), restX, restY, 0.2f);
        float pad = Ui.px(PAD), row = Ui.px(ROW), right = getWidth() - pad;
        if (revealMs >= 60) {
            pill.setAlpha(Math.round(0xF7 * ease((revealMs - 60) / 140f)));
            r.set(pad, pillY, right, pillY + row);
            c.drawRoundRect(r, row / 2f, row / 2f, pill);
        }
        Paint.FontMetrics fm = label.getFontMetrics();
        for (int i = 0; i < LABELS.length; i++) {
            float shown = ease((revealMs - 60 - 40 * i) / 140f);
            if (shown <= 0f) continue;
            float top = rowY(i), dy = -Ui.px(6) * (1f - shown);
            float f = Math.max(0f, Math.min(1f, 1f - Math.abs(pillY - top) / row));
            int ink = (int) argb.evaluate(f, 0xFFFFFFFF, 0xFF111317);
            int glyphInk = (int) argb.evaluate(f, 0xB3FFFFFF, 0xFF111317);
            int alpha = Math.round(255 * shown);
            label.setColor(ink);
            label.setAlpha(alpha * Color.alpha(ink) / 255);
            float cy = top + row / 2f + dy;
            c.drawText(LABELS[i], pad + Ui.px(20), cy - (fm.ascent + fm.descent) / 2f, label);
            float gx = right - Ui.px(26);
            if (i == 0) {
                glyph.setColor(glyphInk);
                glyph.setAlpha(alpha * Color.alpha(glyphInk) / 255);
                c.save();
                c.translate(gx, cy);
                c.drawPath(moveGlyph, glyph);
                c.restore();
            } else {
                dot.setColor(glyphInk);
                dot.setAlpha(alpha * Color.alpha(glyphInk) / 255);
                for (int k = -1; k <= 1; k++) c.drawCircle(gx + k * Ui.pxf(6.5f), cy, Ui.pxf(2.2f), dot);
            }
        }
    }

    private static float ease(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        return Ui.EASE.getInterpolation(t);
    }
}
