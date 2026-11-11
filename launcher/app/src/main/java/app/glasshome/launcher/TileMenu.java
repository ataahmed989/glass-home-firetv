package app.glasshome.launcher;

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.PathInterpolator;

/**
 * What holding Select on a tile brings up: a small glass card that unfolds from under the tile,
 * as wide as the tile, with two rows, their names centred: Move, and More Options (the full
 * menu). The focused row is a white pill that slides between them, as on the tab bar. The tile keeps focus; Up and Down
 * move the pill, Select chooses, Back folds the card away. A Continue Watching card (Home's row, Movies and TV Shows)
 * brings up the same card with rows of its own: Resume, Details and Remove, the card a row taller.
 */
final class TileMenu extends View {
    static final String[] LABELS = {"Move", "More Options"};
    private static final float W = 289, R = 25, PAD = 4, ROW = 42, GAP = 8, EDGE = 24;
    /** The rows show one after another: the first two by 240 ms, each further row 40 ms after. */
    private static final float REVEAL_MS = 240, ROW_STAGGER_MS = 40;

    private final Glass glass;
    private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final ArgbEvaluator argb = new ArgbEvaluator();
    private final RectF r = new RectF();
    private boolean open;
    private int focused;
    private float pillY, restX, restY;
    /** Milliseconds into the rows' reveal ({@link #revealEnd} when shown). */
    private float revealMs = REVEAL_MS;
    private String[] labels = LABELS;
    private Runnable[] actions = new Runnable[0];
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
        label.setTextAlign(Paint.Align.CENTER);
        pill.setColor(0xF7FFFFFF);
        pill.setShadowLayer(Ui.px(14), 0, Ui.px(5), 0x59000000);
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

    /** Two rows: a tile's menu. */
    static int height() {
        return height(2);
    }

    static int height(int rows) {
        return Ui.px(2 * PAD + ROW * rows);
    }

    /** When the last of {@code labels} has come in. */
    private float revealEnd() {
        return REVEAL_MS + ROW_STAGGER_MS * Math.max(0, labels.length - 2);
    }

    /** How far row {@code i} has come in, 0 to 1 (tests). */
    float rowShown(int i) {
        return ease((revealMs - 60 - ROW_STAGGER_MS * i) / 140f);
    }

    boolean isOpen() {
        return open;
    }

    int focusedIndex() {
        return focused;
    }

    /** The rows it shows now. */
    String[] labels() {
        return labels;
    }

    /** Where the card is on screen, for tests. */
    void cardBounds(RectF out) {
        ViewGroup.LayoutParams lp = getLayoutParams();
        out.set(restX, restY, restX + (lp != null ? lp.width : getWidth()), restY + (lp != null ? lp.height : getHeight()));
    }

    private float rowY(int i) {
        return Ui.px(PAD + ROW * i);
    }

    /**
     * Unfolds under a tile centred at {@code tileCx} whose (raised) top and bottom are given; above
     * it instead where there is no room below.
     */
    void show(float tileCx, float tileTop, float tileBottom, Runnable move, Runnable more) {
        show(tileCx, tileTop, tileBottom, width(), LABELS, new Runnable[] {move, more});
    }

    /**
     * As above, under something {@code tileWidth} wide (a Continue Watching card, wider than a tile:
     * the card is as wide as it), with rows of its own: {@code labels[i]} runs {@code chosen[i]} (the
     * card is as tall as its rows).
     */
    void show(float tileCx, float tileTop, float tileBottom, float tileWidth, String[] labels, Runnable[] chosen) {
        this.labels = labels;
        actions = chosen;
        View parent = (View) getParent();
        int screenW = parent.getWidth(), screenH = parent.getHeight();
        int wide = Math.max(width(), Math.round(tileWidth)), tall = height(labels.length);
        ViewGroup.LayoutParams lp = getLayoutParams();
        if (lp != null && (lp.width != wide || lp.height != tall)) {
            lp.width = wide;
            lp.height = tall;
            setLayoutParams(lp);
        }
        float w = wide, h = tall, edge = Ui.px(EDGE);
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
            revealMs = revealEnd();
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
        float end = revealEnd();
        revealAnim = ValueAnimator.ofFloat(0f, end).setDuration(Math.round(end));
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
        if (!open || to < 0 || to >= labels.length) return;
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
        Runnable then = focused < actions.length ? actions[focused] : null;
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
        revealMs = revealEnd();
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
        for (int i = 0; i < labels.length; i++) {
            float shown = rowShown(i);
            if (shown <= 0f) continue;
            float top = rowY(i), dy = -Ui.px(6) * (1f - shown);
            float f = Math.max(0f, Math.min(1f, 1f - Math.abs(pillY - top) / row));
            int ink = (int) argb.evaluate(f, 0xFFFFFFFF, 0xFF111317);
            int alpha = Math.round(255 * shown);
            label.setColor(ink);
            label.setAlpha(alpha * Color.alpha(ink) / 255);
            float cy = top + row / 2f + dy;
            c.drawText(labels[i], getWidth() / 2f, cy - (fm.ascent + fm.descent) / 2f, label);
        }
    }

    private static float ease(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        return Ui.EASE.getInterpolation(t);
    }
}
