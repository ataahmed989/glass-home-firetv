package app.glasshome.launcher;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Six-column grid that settles whichever row has focus inside the fixed glass dock, instead of
 * scrolling the minimum amount. Down glides the next row up into the dock.
 */
final class HomeLayoutManager extends GridLayoutManager {
    /** Told around each layout, so a reorder can glide every tile from where it was (see {@link ReorderMotion}). */
    interface Hooks {
        void beforeLayout();

        void afterLayout();
    }

    private final DecelerateInterpolator ease = new DecelerateInterpolator(2f);
    private Hooks hooks;
    private boolean arranging;

    HomeLayoutManager(Context context) {
        super(context, Ui.COLUMNS);
    }

    void setHooks(Hooks h) {
        hooks = h;
    }

    /** While apps are arranged, a row below the dock stays laid out, so a tile sliding out of view isn't recycled mid-glide. */
    void setArranging(boolean on) {
        if (arranging == on) return;
        arranging = on;
        requestLayout();
    }

    @Override
    public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
        if (hooks != null && !state.isPreLayout()) hooks.beforeLayout();
        super.onLayoutChildren(recycler, state);
    }

    @Override
    public void onLayoutCompleted(RecyclerView.State state) {
        super.onLayoutCompleted(state);
        if (hooks != null) hooks.afterLayout();
    }

    @Override
    protected void calculateExtraLayoutSpace(@NonNull RecyclerView.State state, @NonNull int[] extra) {
        super.calculateExtraLayoutSpace(state, extra);
        if (arranging) extra[1] = Math.max(extra[1], Ui.px(Ui.ROW_PITCH));
    }

    @Override
    public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child, @NonNull Rect rect,
                                                 boolean immediate, boolean focusedChildVisible) {
        int dy = child.getTop() - Ui.px(Ui.DOCK_ROW_TOP);
        if (dy == 0) return false;
        if (immediate || Ui.reducedMotion) {
            parent.scrollBy(0, dy);
        } else {
            parent.smoothScrollBy(0, dy, ease, Ui.SCROLL_MS);
        }
        return true;
    }
}
