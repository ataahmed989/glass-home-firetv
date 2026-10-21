package app.glasshome.launcher;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.View;

/**
 * The glass tab bar centred at the top: Home, Movies, TV Shows, Sports. It is a single focus
 * target and, as on tvOS, the focused tab is the selected one: while the bar has focus a white
 * pill slides from tab to tab; otherwise a soft glass pill marks the page on screen.
 */
final class TabBar extends View {
    static final String[] TABS = {"Home", "Movies", "TV Shows", "Sports"};

    private final Glass glass;
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final ArgbEvaluator argb = new ArgbEvaluator();
    private final float[] tabX = new float[TABS.length], tabW = new float[TABS.length];
    private final float inset, padX;
    private int selected;
    private float pillX, pillW, focusT;
    private ValueAnimator pillAnim, focusAnim;

    TabBar(Context context, Glass glass) {
        super(context);
        this.glass = glass;
        inset = Ui.px(8);
        padX = Ui.px(30);
        text.setTypeface(Fonts.inter(context, 500));
        text.setTextSize(Ui.px(26));
        setFocusable(true);
        setDefaultFocusHighlightEnabled(false);
        setContentDescription("Tabs");
        float x = inset;
        for (int i = 0; i < TABS.length; i++) {
            tabX[i] = x;
            tabW[i] = padX * 2 + text.measureText(TABS[i]);
            x += tabW[i];
        }
        pillX = tabX[0];
        pillW = tabW[0];
    }

    int selected() {
        return selected;
    }

    int count() {
        return TABS.length;
    }

    /** Moves the selection; the pill slides there (or jumps, with reduced motion). */
    void select(int index) {
        if (index < 0 || index >= TABS.length || index == selected) return;
        selected = index;
        if (pillAnim != null) pillAnim.cancel();
        if (Ui.reducedMotion) {
            pillX = tabX[index];
            pillW = tabW[index];
            invalidate();
            return;
        }
        final float fromX = pillX, fromW = pillW;
        pillAnim = ValueAnimator.ofFloat(0f, 1f);
        pillAnim.setDuration(Ui.FOCUS_MS + 60);
        pillAnim.setInterpolator(Ui.EASE);
        pillAnim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            pillX = fromX + (tabX[index] - fromX) * t;
            pillW = fromW + (tabW[index] - fromW) * t;
            invalidate();
        });
        pillAnim.start();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float w = inset * 2;
        for (float t : tabW) w += t;
        setMeasuredDimension(Math.round(w), Ui.px(Ui.CAPSULE_H));
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        if (focusAnim != null) focusAnim.cancel();
        if (Ui.reducedMotion) {
            focusT = gainFocus ? 1f : 0f;
            invalidate();
            return;
        }
        focusAnim = ValueAnimator.ofFloat(focusT, gainFocus ? 1f : 0f);
        focusAnim.setDuration(Ui.FOCUS_MS);
        focusAnim.setInterpolator(Ui.EASE);
        focusAnim.addUpdateListener(a -> {
            focusT = (float) a.getAnimatedValue();
            invalidate();
        });
        focusAnim.start();
    }

    @Override
    protected void onDraw(Canvas c) {
        float h = getHeight();
        rect.set(0, 0, getWidth(), h);
        glass.draw(c, rect, h / 2f, getLeft() + getTranslationX(), getTop() + getTranslationY(), 0f);

        // The pill: soft glass at rest, white while the bar has focus
        rect.set(pillX, inset, pillX + pillW, h - inset);
        pill.setColor((int) argb.evaluate(focusT, 0x38FFFFFF, 0xF7FFFFFF));
        if (focusT > 0f) pill.setShadowLayer(Ui.px(14), 0, Ui.px(5), Math.round(0x59 * focusT) << 24);
        else pill.clearShadowLayer();
        c.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, pill);

        Paint.FontMetrics fm = text.getFontMetrics();
        float baseline = h / 2f - (fm.ascent + fm.descent) / 2f;
        for (int i = 0; i < TABS.length; i++) {
            // Ink follows the pill: tabs it covers turn dark as it brightens
            float covered = Math.max(0f, Math.min(1f, 1f - Math.abs(tabX[i] - pillX) / tabW[i]));
            int ink = (int) argb.evaluate(focusT * covered, i == selected ? 0xFFFFFFFF : 0xCCFFFFFF, 0xFF111317);
            text.setColor(ink);
            c.drawText(TABS[i], tabX[i] + padX, baseline, text);
        }
    }
}
