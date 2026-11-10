package app.glasshome.launcher;

import android.graphics.Canvas;
import android.graphics.RectF;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** Draws the fixed glass dock behind the focused row. */
final class GlassDock extends RecyclerView.ItemDecoration {
    private final Glass glass;
    private final RectF dock = new RectF();

    /** How far the scene behind is darkened (Home moved up for its Continue Watching row). */
    float dim;

    GlassDock(Glass glass) {
        this.glass = glass;
    }

    @Override
    public void onDraw(@NonNull Canvas canvas, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        if (parent.getChildCount() == 0) return;
        dock.set(Ui.px(Ui.DOCK_INSET_X), Ui.px(Ui.DOCK_TOP), parent.getWidth() - Ui.px(Ui.DOCK_INSET_X), Ui.px(Ui.DOCK_BOTTOM));
        // The grid moves up as a whole for Home's Continue Watching row: the frost follows it, darkened with the background
        glass.draw(canvas, dock, Ui.px(Ui.DOCK_RADIUS), parent.getLeft(), parent.getTop() + parent.getTranslationY(), dim);
    }
}
