package app.glasshome.launcher;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** Draws the fixed glass dock behind the focused row, plus the small chevron above it on the first row. */
final class GlassDock extends RecyclerView.ItemDecoration {
    private final Glass glass;
    private final RectF dock = new RectF();
    private final Paint chevronPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path chevron = new Path();
    private boolean chevronVisible = true;

    GlassDock(Glass glass) {
        this.glass = glass;
        chevronPaint.setStyle(Paint.Style.STROKE);
        chevronPaint.setStrokeWidth(Math.max(2f, Ui.px(3)));
        chevronPaint.setStrokeCap(Paint.Cap.ROUND);
        chevronPaint.setStrokeJoin(Paint.Join.ROUND);
        chevronPaint.setColor(0xFFFFFFFF);
    }

    /** The chevron hints that Up shows the content full screen, which only applies on the first row. */
    void setChevronVisible(boolean visible) {
        chevronVisible = visible;
    }

    @Override
    public void onDraw(@NonNull Canvas canvas, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        if (parent.getChildCount() == 0) return;
        dock.set(Ui.px(Ui.DOCK_INSET_X), Ui.px(Ui.DOCK_TOP), parent.getWidth() - Ui.px(Ui.DOCK_INSET_X), Ui.px(Ui.DOCK_BOTTOM));
        glass.draw(canvas, dock, Ui.px(Ui.DOCK_RADIUS), parent.getLeft(), parent.getTop(), 0f);

        if (chevronVisible) {
            float cx = parent.getWidth() / 2f, cy = dock.top - Ui.px(30), hw = Ui.px(16), hh = Ui.px(7);
            chevron.reset();
            chevron.moveTo(cx - hw, cy + hh);
            chevron.lineTo(cx, cy - hh);
            chevron.lineTo(cx + hw, cy + hh);
            chevronPaint.setAlpha(150);
            canvas.drawPath(chevron, chevronPaint);
        }
    }
}
