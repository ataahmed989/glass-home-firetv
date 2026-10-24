package app.glasshome.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.View;

/** Source, at the top left opposite the clock capsule: the same dark glass, lit white when focused. */
final class SourcePill extends View {
    private final Glass glass;
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();
    private final Path path = new Path();

    SourcePill(Context context, Glass glass) {
        super(context);
        this.glass = glass;
        setFocusable(true);
        setDefaultFocusHighlightEnabled(false);
        setContentDescription("Source");
        FeaturePainter.style(label, context, 500, Ui.px(30), 0xFFFFFFFF);
        icon.setStyle(Paint.Style.STROKE);
        icon.setStrokeCap(Paint.Cap.ROUND);
        icon.setStrokeJoin(Paint.Join.ROUND);
        icon.setStrokeWidth(Ui.px(3.5f));
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(Math.round(Ui.px(96) + label.measureText("Source")), MeasureSpec.getSize(h));
    }

    @Override
    protected void onFocusChanged(boolean gain, int direction, Rect previous) {
        super.onFocusChanged(gain, direction, previous);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        bounds.set(0, 0, getWidth(), getHeight());
        float lit = isFocused() ? 1f : 0f;
        glass.drawLit(c, bounds, getHeight() / 2f, getLeft() + getTranslationX(), getTop() + getTranslationY(), lit);
        int ink = isFocused() ? 0xFF111215 : 0xFFFFFFFF;
        float cy = getHeight() / 2f, cx = Ui.px(46), h = Ui.px(14);
        icon.setColor(ink);
        path.reset();
        path.moveTo(cx - h * 0.2f, cy - h * 0.85f);
        path.lineTo(cx + h * 0.85f, cy - h * 0.85f);
        path.lineTo(cx + h * 0.85f, cy + h * 0.85f);
        path.lineTo(cx - h * 0.2f, cy + h * 0.85f);
        c.drawPath(path, icon);
        c.drawLine(cx - h * 0.9f, cy, cx + h * 0.3f, cy, icon);
        path.reset();
        path.moveTo(cx - h * 0.08f, cy - h * 0.38f);
        path.lineTo(cx + h * 0.3f, cy);
        path.lineTo(cx - h * 0.08f, cy + h * 0.38f);
        c.drawPath(path, icon);
        label.setColor(ink);
        Paint.FontMetrics fm = label.getFontMetrics();
        c.drawText("Source", Ui.px(74), cy - (fm.ascent + fm.descent) / 2f, label);
    }
}
