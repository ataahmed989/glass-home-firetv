package app.glasshome.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.View;

/**
 * The glass Sources button in the top-left corner, the status capsule's counterpart: an input
 * symbol, "Sources" and a chevron. Focused, it lights up like a focused tab (near-white glass,
 * dark content), and stays lit while its menu ({@link SourcesPanel}) is open. Select opens the
 * menu.
 */
final class SourceButton extends View {
    static final String LABEL = "Sources";

    private final Glass glass;
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint well = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF(), r = new RectF();
    private final Path path = new Path();
    private final int padStart, size, gap, chevronGap, chevronW, padEnd;
    private boolean open;
    private float focusT;
    private ValueAnimator focusAnim;

    SourceButton(Context context, Glass glass) {
        super(context);
        // Redrawn only when focus changes; otherwise reused from its GPU layer every frame
        setLayerType(LAYER_TYPE_HARDWARE, null);
        this.glass = glass;
        padStart = Ui.px(10);
        size = Ui.px(52);
        gap = Ui.px(14);
        chevronGap = Ui.px(14);
        chevronW = Ui.px(16);
        padEnd = Ui.px(26);
        text.setTypeface(Fonts.inter(context, 500));
        text.setTextSize(Ui.px(26));
        glyph.setStyle(Paint.Style.STROKE);
        glyph.setStrokeWidth(Math.max(1.5f, Ui.px(2.5f)));
        glyph.setStrokeCap(Paint.Cap.ROUND);
        glyph.setStrokeJoin(Paint.Join.ROUND);
        setFocusable(true);
        setDefaultFocusHighlightEnabled(false);
        setContentDescription(LABEL);
    }

    /** While the menu is open the button stays lit, as Control Center's does. */
    void setOpen(boolean isOpen) {
        open = isOpen;
        invalidate();
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
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = padStart + size + gap + Math.round(text.measureText(LABEL)) + chevronGap + chevronW + padEnd;
        setMeasuredDimension(w, Ui.px(Ui.CAPSULE_H));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float lit = open ? 1f : focusT;
        bounds.set(0, 0, getWidth(), getHeight());
        glass.drawLit(canvas, bounds, getHeight() / 2f, getLeft() + getTranslationX(), getTop() + getTranslationY(), lit);
        int ink = lit > 0.5f ? 0xFF16181D : 0xFFFFFFFF;

        // The symbol sits in a round well, like the capsule's buttons
        float cy = getHeight() / 2f, cx = padStart + size / 2f;
        well.setColor(lit > 0.5f ? 0x14000000 : 0x24FFFFFF);
        canvas.drawCircle(cx, cy, size / 2f, well);
        glyph.setColor(ink);
        drawInput(canvas, glyph, path, r, cx, cy, size);

        text.setColor(ink);
        Paint.FontMetrics fm = text.getFontMetrics();
        float tx = padStart + size + gap;
        canvas.drawText(LABEL, tx, cy - (fm.ascent + fm.descent) / 2f, text);

        // A small chevron: this opens a menu below
        float chx = tx + text.measureText(LABEL) + chevronGap + chevronW / 2f, h = chevronW * 0.32f;
        path.reset();
        path.moveTo(chx - chevronW / 2f, cy - h / 2f);
        path.lineTo(chx, cy + h / 2f);
        path.lineTo(chx + chevronW / 2f, cy - h / 2f);
        canvas.drawPath(path, glyph);
    }

    /**
     * The input symbol: a screen, open on its left side, with an arrow coming into it. Shared with
     * the menu, which draws it a little smaller.
     */
    static void drawInput(Canvas c, Paint p, Path path, RectF r, float cx, float cy, float size) {
        float w = size * 0.50f, h = size * 0.36f, rad = size * 0.06f;
        float left = cx - w * 0.30f, right = cx + w * 0.55f, top = cy - h / 2f, bottom = cy + h / 2f;
        // The screen, its left edge left open where the arrow comes in
        path.reset();
        path.moveTo(left, top + h * 0.28f);
        path.lineTo(left, top + rad);
        path.quadTo(left, top, left + rad, top);
        path.lineTo(right - rad, top);
        path.quadTo(right, top, right, top + rad);
        path.lineTo(right, bottom - rad);
        path.quadTo(right, bottom, right - rad, bottom);
        path.lineTo(left + rad, bottom);
        path.quadTo(left, bottom, left, bottom - rad);
        path.lineTo(left, bottom - h * 0.28f);
        c.drawPath(path, p);
        // The arrow
        float ax0 = cx - w * 0.62f, ax1 = cx + w * 0.18f, head = size * 0.085f;
        c.drawLine(ax0, cy, ax1, cy, p);
        path.reset();
        path.moveTo(ax1 - head, cy - head);
        path.lineTo(ax1, cy);
        path.lineTo(ax1 - head, cy + head);
        c.drawPath(path, p);
    }
}
