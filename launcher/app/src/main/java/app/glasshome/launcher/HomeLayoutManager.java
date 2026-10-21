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
    private final DecelerateInterpolator ease = new DecelerateInterpolator(2f);

    HomeLayoutManager(Context context) {
        super(context, Ui.COLUMNS);
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
