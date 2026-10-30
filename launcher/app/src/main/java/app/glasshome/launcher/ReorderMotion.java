package app.glasshome.launcher;

import android.animation.TimeAnimator;
import android.view.View;
import android.view.animation.AnimationUtils;

import androidx.recyclerview.widget.RecyclerView;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Tiles making room for one being moved, as icons do on an iPhone's home screen. Each step of
 * the move lays the grid out afresh (the tile being moved stays in the dock); every cell then
 * starts from where it was on screen and springs into its new place, keeping its speed when the
 * next step comes before it has settled, so a held arrow glides. One clock drives them all, and
 * it stops when they rest.
 */
final class ReorderMotion implements TimeAnimator.TimeListener, HomeLayoutManager.Hooks {
    private static final class Track {
        Spring spring;
        float x0, y0, vx, vy;
        long t0;
    }

    private final RecyclerView grid;
    private final TimeAnimator ticker = new TimeAnimator();
    private final Map<View, Track> tracks = new HashMap<>();
    private final Map<Long, int[]> before = new HashMap<>();
    /** The order before the steps not yet laid out; null when there are none. */
    private List<Long> orderBefore;
    /** On while apps are being arranged: only then are layouts turned into motion. */
    boolean active;
    long liftedId = RecyclerView.NO_ID;
    /** Run each time every tile has come to rest. */
    Runnable onIdle;

    ReorderMotion(RecyclerView grid) {
        this.grid = grid;
        ticker.setTimeListener(this);
    }

    /** Before the adapter changes: the order as it was, for cells the new layout brings into view. */
    void noteMove(List<Long> ids) {
        if (orderBefore == null) orderBefore = ids;
    }

    @Override
    public void beforeLayout() {
        if (!active) return;
        before.clear();
        for (int i = 0; i < grid.getChildCount(); i++) {
            View c = grid.getChildAt(i);
            before.put(grid.getChildItemId(c), new int[] {c.getLeft(), c.getTop()});
        }
    }

    @Override
    public void afterLayout() {
        if (!active) return;
        if (Ui.reducedMotion) {
            finishNow();
            orderBefore = null;
            before.clear();
            return;
        }
        long now = AnimationUtils.currentAnimationTimeMillis();
        Float frame = frameBefore();
        int pitchX = Ui.px(Ui.COL_PITCH), pitchY = Ui.px(Ui.ROW_PITCH);
        for (int i = 0; i < grid.getChildCount(); i++) {
            View c = grid.getChildAt(i);
            long id = grid.getChildItemId(c);
            int[] b = before.get(id);
            float dx, dy;
            if (b != null) {
                dx = b[0] - c.getLeft();
                dy = b[1] - c.getTop();
            } else {
                // Not on screen before: it comes from its old place in the grid, wherever that was
                int j = orderBefore == null ? -1 : orderBefore.indexOf(id);
                if (j < 0 || frame == null) continue;
                dx = grid.getPaddingLeft() + (j % Ui.COLUMNS) * pitchX - c.getLeft();
                dy = frame + (j / Ui.COLUMNS) * pitchY - c.getTop();
            }
            if (dx != 0 || dy != 0) retarget(c, id == liftedId ? Spring.LIFT : Spring.REORDER, dx, dy, 0, 0, now);
        }
        orderBefore = null;
        before.clear();
        if (!tracks.isEmpty() && !ticker.isStarted()) ticker.start();
    }

    /** Where the first row was before these steps, from any cell laid out then. */
    private Float frameBefore() {
        if (orderBefore == null) return null;
        for (Map.Entry<Long, int[]> e : before.entrySet()) {
            int j = orderBefore.indexOf(e.getKey());
            if (j >= 0) return (float) (e.getValue()[1] - (j / Ui.COLUMNS) * Ui.px(Ui.ROW_PITCH));
        }
        return null;
    }

    /** A push with no new place: the tile being moved bumps against the edge it can't pass, and springs back. */
    void kick(View cell, float vx, float vy) {
        if (Ui.reducedMotion || cell == null) return;
        retarget(cell, Spring.LIFT, 0, 0, vx, vy, AnimationUtils.currentAnimationTimeMillis());
        if (!ticker.isStarted()) ticker.start();
    }

    /** Jumps the cell's track by (dx, dy), keeping where it is on screen this instant and how fast it is going. */
    private void retarget(View v, Spring s, float dx, float dy, float dvx, float dvy, long now) {
        Track t = tracks.get(v);
        float x, y, vx, vy;
        if (t != null) {
            float dt = (now - t.t0) / 1000f;
            x = t.spring.x(t.x0, t.vx, dt);
            y = t.spring.x(t.y0, t.vy, dt);
            vx = t.spring.v(t.x0, t.vx, dt);
            vy = t.spring.v(t.y0, t.vy, dt);
        } else {
            t = new Track();
            tracks.put(v, t);
            x = v.getTranslationX();
            y = v.getTranslationY();
            vx = vy = 0;
        }
        t.spring = s;
        t.x0 = x + dx;
        t.y0 = y + dy;
        t.vx = vx + dvx;
        t.vy = vy + dvy;
        t.t0 = now;
        v.setTranslationX(t.x0);
        v.setTranslationY(t.y0);
    }

    @Override
    public void onTimeUpdate(TimeAnimator animation, long totalTime, long deltaTime) {
        long now = AnimationUtils.currentAnimationTimeMillis();
        float still = Ui.px(0.35f);
        for (Iterator<Map.Entry<View, Track>> it = tracks.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<View, Track> e = it.next();
            Track t = e.getValue();
            float s = (now - t.t0) / 1000f;
            float x = t.spring.x(t.x0, t.vx, s), y = t.spring.x(t.y0, t.vy, s);
            float vx = t.spring.v(t.x0, t.vx, s), vy = t.spring.v(t.y0, t.vy, s);
            if (Math.abs(x) < still && Math.abs(y) < still && Math.abs(vx) < 4 && Math.abs(vy) < 4) {
                x = y = 0;
                it.remove();
            }
            e.getKey().setTranslationX(x);
            e.getKey().setTranslationY(y);
        }
        if (tracks.isEmpty()) {
            ticker.end();
            if (onIdle != null) onIdle.run();
        }
    }

    /** A cell leaving the grid (recycled) is no longer moved. */
    void forget(View v) {
        tracks.remove(v);
    }

    /** Every tile in its place at once. */
    void finishNow() {
        for (View v : tracks.keySet()) {
            v.setTranslationX(0f);
            v.setTranslationY(0f);
        }
        tracks.clear();
        if (ticker.isStarted()) ticker.end();
    }

    boolean isIdle() {
        return tracks.isEmpty();
    }
}
