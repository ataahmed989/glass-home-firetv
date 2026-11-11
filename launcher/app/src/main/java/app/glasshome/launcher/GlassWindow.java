package app.glasshome.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * A window on Home's glass, over a darkened screen: the card the detailed forecast also uses, and what a subclass
 * shows on it ({@link #drawContent}). The details and the episodes of a Continue Watching title are such windows, and
 * take each other's place in a crossfade that leaves the scrim and the glass where they are (see {@link #replace}).
 *
 * It never takes focus: what it was opened from keeps it, and keys reach the window through HomeActivity, as a tile's
 * menu's do. {@link #isOpen} is whether it has the remote, which ends as it starts to go.
 */
abstract class GlassWindow extends View {
    /** The card, in design px (1920 x 1080). */
    static final float L = 200, T = 110, R = 1720, B = 970, RADIUS = 44;

    private final Glass glass;
    private final Paint scrim = new Paint();
    private final RectF card = new RectF();
    private boolean open;
    /** Draws the scrim and the glass; not while it comes in over another window, which has them already. */
    private boolean frame = true;
    /** What it shows fades out over its own glass as another window takes its place. */
    private float contentAlpha = 1f;
    private ValueAnimator contentAnim;

    GlassWindow(Context context, Glass glass) {
        super(context);
        this.glass = glass;
        setFocusable(false);
        setDefaultFocusHighlightEnabled(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setVisibility(GONE);
        // Over a tile's menu (18), under the update alert (64)
        setTranslationZ(Ui.px(40));
        scrim.setColor(0x8C000000);
    }

    boolean isOpen() {
        return open;
    }

    /** Comes in over {@code ms} (at once with reduced motion). */
    void appear(long ms) {
        open = true;
        settle();
        setVisibility(VISIBLE);
        if (ms <= 0 || Ui.reducedMotion) {
            setAlpha(1f);
            return;
        }
        setAlpha(0f);
        animate().alpha(1f).setDuration(ms).setInterpolator(Ui.EASE).withLayer().start();
    }

    /** Closed at once as far as the remote goes; gone from the screen over {@code ms}. */
    void disappear(long ms) {
        open = false;
        boolean shown = getVisibility() == VISIBLE && frame;
        settle();
        if (ms <= 0 || Ui.reducedMotion || !shown) {
            setVisibility(GONE);
            return;
        }
        animate().alpha(0f).setDuration(ms).setInterpolator(Ui.EASE).withLayer().withEndAction(() -> {
            if (!open) setVisibility(GONE);
        }).start();
    }

    /**
     * Takes {@code other}'s place: its content fades out over {@code outMs} on its own glass, this window's fades in
     * over {@code inMs} on top, and then this one has the scrim and glass (the same, in the same place) and the other
     * goes. The darkened screen behind stays as it is throughout.
     */
    void replace(GlassWindow other, long outMs, long inMs) {
        other.open = false;
        open = true;
        settle();
        other.settle();
        if (other.getVisibility() != VISIBLE || inMs <= 0 || Ui.reducedMotion) {
            other.setVisibility(GONE);
            setVisibility(VISIBLE);
            setAlpha(1f);
            return;
        }
        other.fadeContent(outMs);
        frame = false;
        setVisibility(VISIBLE);
        setAlpha(0f);
        animate().alpha(1f).setDuration(inMs).setInterpolator(Ui.EASE).withLayer().withEndAction(() -> {
            frame = true;
            invalidate();
            if (!other.open) {
                other.settle();
                other.setVisibility(GONE);
            }
        }).start();
    }

    /** Any fade stopped, and drawn whole: scrim, glass and content. */
    private void settle() {
        animate().cancel();
        if (contentAnim != null) contentAnim.cancel();
        contentAnim = null;
        frame = true;
        contentAlpha = 1f;
        setAlpha(1f);
        invalidate();
    }

    private void fadeContent(long ms) {
        contentAnim = ValueAnimator.ofFloat(1f, 0f).setDuration(ms);
        contentAnim.setInterpolator(Ui.EASE);
        contentAnim.addUpdateListener(a -> {
            contentAlpha = (float) a.getAnimatedValue();
            invalidate();
        });
        contentAnim.start();
    }

    @Override
    protected final void onDraw(Canvas c) {
        card.set(Ui.px(L), Ui.px(T), Ui.px(R), Ui.px(B));
        if (frame) {
            c.drawRect(0, 0, getWidth(), getHeight(), scrim);
            glass.draw(c, card, Ui.px(RADIUS), getLeft(), getTop(), 0.35f);
        }
        if (contentAlpha <= 0f) return;
        int layer = contentAlpha < 1f ? c.saveLayerAlpha(card, Math.round(255 * contentAlpha)) : -1;
        drawContent(c);
        if (layer >= 0) c.restoreToCount(layer);
    }

    /** What the window shows on its glass. */
    protected abstract void drawContent(Canvas c);
}
