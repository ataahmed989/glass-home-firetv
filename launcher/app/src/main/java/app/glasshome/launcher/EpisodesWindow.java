package app.glasshome.launcher;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every season and episode FirePlayer has of a Continue Watching show, from its details window's Episodes button, to
 * play any one of them: the seasons in a rail on the left (as the tab bar's pills), the shown season's episodes on the
 * right (still, number and name, when it aired and how long it runs, its story; how far it was watched in FirePlayer).
 * The same glass card as the details window, which it takes the place of.
 *
 * It opens on the episode the show was left on, in its season. Up and Down move along a column, Left goes to the
 * seasons and Right (or Select) back to the episodes; a season shows as soon as it is moved to. Select on an episode
 * plays it. Like the details window, it leaves focus on the card underneath (see {@link GlassWindow}).
 */
final class EpisodesWindow extends GlassWindow {
    static final String LOADING = "Loading episodes…";
    static final String FAILED = "Couldn't get this show's episodes just now.";
    static final String RETRY = "Press Select to try again.";
    private static final float TOP = 246, BOTTOM = 938;
    private static final float RAIL_L = 232, RAIL_R = 512, RAIL_PITCH = 64, PILL_H = 56;
    private static final float LIST_L = 552, LIST_R = 1688, ROW_PITCH = 168, ROW_H = 156;
    /** Where more lies beyond an edge, the column fades out over this, and focus is kept this far from the edge. */
    private static final float EDGE = 40;
    private static final int SCROLL_MS = 220, FADE_MS = 180;
    /**
     * The row the focused episode sits in, under a pane of the dock's glass that stays where it is while the list glides
     * through it, as the apps move through the dock: the second, so the one before shows above.
     */
    private static final int SLOT = 1;

    private enum State { LOADING, FAILED, READY }

    private final ImageCache images;
    private final DetailsWindow.Pictures pictures = new DetailsWindow.Pictures();
    private final Runnable invalidator = this::invalidate;
    private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mask = new Paint();
    private final TextPaint caption, header, message, retry, season, count, number, name, facts, story, stillLabel;
    private final ArgbEvaluator argb = new ArgbEvaluator();
    private final RectF rect = new RectF();
    private final Map<ContinueWatching.Episode, StaticLayout> stories = new IdentityHashMap<>();

    private ContinueWatching.Item item;
    private String showName = "";
    private State state = State.LOADING;
    /** The seasons, in order (Specials last), and each one's episodes. */
    private final List<Integer> seasons = new ArrayList<>();
    private final List<List<ContinueWatching.Episode>> lists = new ArrayList<>();
    /** The focused (or last focused) episode of each season. */
    private int[] focusIn = new int[0];
    /** The season shown; and where the show was left, by season and episode index (-1 when FirePlayer's list lacks it). */
    private int shown, itsSeason = -1, itsIndex = -1;
    private boolean inRail;
    private final Glide railPill = new Glide(), railScroll = new Glide(), listScroll = new Glide();
    /** The dock's glass, for the pane the focused episode sits under. */
    private final Glass paneGlass;
    private float contentT = 1f;
    private ValueAnimator contentAnim;

    EpisodesWindow(Context context, Glass glass, Glass paneGlass) {
        super(context, glass);
        this.paneGlass = paneGlass;
        images = ImageCache.get(context);
        mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        caption = DetailsWindow.caption(context);
        header = DetailsWindow.text(context, 600, 34, 0xFFFFFFFF);
        message = DetailsWindow.text(context, 500, 30, 0xFFFFFFFF);
        message.setTextAlign(Paint.Align.CENTER);
        retry = DetailsWindow.text(context, 400, 20, 0x8CFFFFFF);
        retry.setTextAlign(Paint.Align.CENTER);
        season = DetailsWindow.text(context, 500, 24, 0xCCFFFFFF);
        count = DetailsWindow.text(context, 500, 19, 0x73FFFFFF);
        count.setTextAlign(Paint.Align.RIGHT);
        number = DetailsWindow.text(context, 600, 26, DetailsWindow.ACCENT);
        name = DetailsWindow.text(context, 600, 26, 0xFFFFFFFF);
        facts = DetailsWindow.text(context, 500, 19, 0x99FFFFFF);
        story = DetailsWindow.text(context, 400, 20, 0xBFFFFFFF);
        stillLabel = DetailsWindow.text(context, 600, 28, 0x80FFFFFF);
        stillLabel.setTextAlign(Paint.Align.CENTER);
    }

    // ---- What it shows ----------------------------------------------------------------------

    /** Opens on {@code item}'s show, its episodes loading. */
    void open(String showName, ContinueWatching.Item item) {
        this.item = item;
        this.showName = showName == null ? "" : showName;
        setLoading();
    }

    /** Asking FirePlayer (again). */
    void setLoading() {
        state = State.LOADING;
        clear();
        invalidate();
    }

    /** TMDB's name for the show came. */
    void setShowName(String name) {
        if (name == null || name.isEmpty() || name.equals(showName)) return;
        showName = name;
        invalidate();
    }

    /** FirePlayer's list came again: the same entry, with where it was left now. */
    void setItem(ContinueWatching.Item fresh) {
        if (item == null || fresh == null || !item.id.equals(fresh.id)) return;
        item = fresh;
        invalidate();
    }

    /** FirePlayer couldn't list them (no answer, or nothing): Select asks again. */
    void setFailed() {
        state = State.FAILED;
        clear();
        invalidate();
    }

    private void clear() {
        seasons.clear();
        lists.clear();
        stories.clear();
        focusIn = new int[0];
        shown = 0;
        itsSeason = itsIndex = -1;
        inRail = false;
    }

    /**
     * FirePlayer's episodes of the show (by season, then number): the seasons come in order with Specials last, and
     * focus is on the episode the show was left on, in the list's second row (the one before it showing above);
     * without it, the first episode of its season, else of the first season.
     */
    void setEpisodes(List<ContinueWatching.Episode> episodes) {
        if (episodes == null || episodes.isEmpty()) {
            setFailed();
            return;
        }
        clear();
        Map<Integer, List<ContinueWatching.Episode>> by = new TreeMap<>((a, b) ->
                a == 0 ? (b == 0 ? 0 : 1) : b == 0 ? -1 : Integer.compare(a, b));
        for (ContinueWatching.Episode e : episodes) {
            List<ContinueWatching.Episode> list = by.get(e.season);
            if (list == null) by.put(e.season, list = new ArrayList<>());
            list.add(e);
        }
        for (Map.Entry<Integer, List<ContinueWatching.Episode>> en : by.entrySet()) {
            List<ContinueWatching.Episode> list = en.getValue();
            Collections.sort(list, Comparator.comparingInt(e -> e.number));   // stable: copies stay in FirePlayer's order
            seasons.add(en.getKey());
            lists.add(list);
        }
        focusIn = new int[seasons.size()];
        ContinueWatching.Episode its = ContinueWatching.itsEpisode(episodes, item);
        for (int s = 0; its != null && s < lists.size() && itsSeason < 0; s++) {
            int i = lists.get(s).indexOf(its);
            if (i >= 0) {
                itsSeason = s;
                itsIndex = i;
            }
        }
        int start = itsSeason >= 0 ? itsSeason : Math.max(0, seasons.indexOf(item.season));
        state = State.READY;
        inRail = false;
        railPill.set(start * Ui.pxf(RAIL_PITCH));
        railScroll.set(0);
        showSeason(start, false);
        if (contentAnim != null) contentAnim.cancel();
        if (Ui.reducedMotion || !isOpen()) {
            contentT = 1f;
        } else {
            contentT = 0f;
            contentAnim = ValueAnimator.ofFloat(0f, 1f).setDuration(FADE_MS);
            contentAnim.setInterpolator(Ui.EASE);
            contentAnim.addUpdateListener(a -> {
                contentT = (float) a.getAnimatedValue();
                invalidate();
            });
            contentAnim.start();
        }
        invalidate();
    }

    /**
     * Shows season {@code s}, its list from the top: focus on the episode the show was left on in its season,
     * otherwise the one last focused there (the first, at first).
     */
    private void showSeason(int s, boolean animate) {
        shown = s;
        long ms = animate ? Ui.FOCUS_MS + 20 : 0;
        railPill.to(s * Ui.pxf(RAIL_PITCH), ms);
        railScroll.to(railScrollFor(s), animate ? SCROLL_MS : 0);
        boolean its = s == itsSeason;
        if (its) focusIn[s] = itsIndex;
        int f = focusIn[s];
        listScroll.set(listScrollFor(f));
        invalidate();
    }

    /** Up, Down, Left or Right (View.FOCUS_*); false when it went nowhere. */
    boolean move(int direction) {
        if (state != State.READY) return false;
        if (inRail) {
            if (direction == View.FOCUS_UP && shown > 0) showSeason(shown - 1, true);
            else if (direction == View.FOCUS_DOWN && shown < seasons.size() - 1) showSeason(shown + 1, true);
            else if (direction == View.FOCUS_RIGHT) inRail = false;
            else return false;
            invalidate();
            return true;
        }
        int f = focusIn[shown], n = lists.get(shown).size();
        if (direction == View.FOCUS_UP && f > 0) focusEpisode(f - 1);
        else if (direction == View.FOCUS_DOWN && f < n - 1) focusEpisode(f + 1);
        else if (direction == View.FOCUS_LEFT) inRail = true;
        else return false;
        invalidate();
        return true;
    }

    /** The list glides to put episode {@code i} under the pane, at the pace the apps move through the dock. */
    private void focusEpisode(int i) {
        focusIn[shown] = i;
        listScroll.to(listScrollFor(i), Ui.SCROLL_MS);
    }

    /**
     * Select: the focused episode, to play; null when it only went from the seasons to the episodes, or there is
     * nothing to choose (still loading, or failed: HomeActivity asks again).
     */
    ContinueWatching.Episode select() {
        if (state != State.READY) return null;
        if (inRail) {
            inRail = false;
            invalidate();
            return null;
        }
        return focusedEpisode();
    }

    boolean failed() {
        return state == State.FAILED;
    }

    boolean loading() {
        return state == State.LOADING;
    }

    /** What it says in place of the episodes, or null when they are shown. */
    String status() {
        return state == State.LOADING ? LOADING : state == State.FAILED ? FAILED : null;
    }

    boolean inRail() {
        return inRail;
    }

    /** The season shown (0 for Specials), or -1 with none. */
    int shownSeason() {
        return state == State.READY ? seasons.get(shown) : -1;
    }

    /** The seasons, in the rail's order (tests). */
    List<Integer> seasons() {
        return new ArrayList<>(seasons);
    }

    ContinueWatching.Episode focusedEpisode() {
        return state == State.READY ? lists.get(shown).get(focusIn[shown]) : null;
    }

    /** The episodes of the season shown (tests). */
    List<ContinueWatching.Episode> shownEpisodes() {
        return state == State.READY ? new ArrayList<>(lists.get(shown)) : new ArrayList<>();
    }

    /** How far the seasons are scrolled, px (tests). */
    float railScroll() {
        return railScroll.target;
    }

    /** Where focus is, for telling a move from none. */
    String focusKey() {
        return isOpen() ? state + "/" + inRail + "/" + shown + "/" + (focusIn.length > shown ? focusIn[shown] : -1) : "";
    }

    void trimMemory() {
        pictures.release();
        stories.clear();
    }

    // ---- Scrolling --------------------------------------------------------------------------

    private float view() {
        return Ui.pxf(BOTTOM - TOP);
    }

    /** The seasons scroll just enough to keep the focused one clear of the faded edges. */
    private float railScrollFor(int s) {
        float pitch = Ui.pxf(RAIL_PITCH), edge = Ui.pxf(EDGE), top = s * pitch, scroll = railScroll.target;
        if (top - scroll < edge) scroll = top - edge;
        if (top + pitch - scroll > view() - edge) scroll = top + pitch - view() + edge;
        return clamp(scroll, Math.max(0f, seasons.size() * pitch - view()));
    }

    /** How far the list scrolls before its last row's bottom meets the bottom edge (below 0 when it all fits). */
    private float listEnd() {
        float total = lists.isEmpty() ? 0 : lists.get(shown).size() * Ui.pxf(ROW_PITCH) - Ui.pxf(ROW_PITCH - ROW_H);
        return total - view();
    }

    /**
     * Episode {@code i} under the pane, always: at the first episode the row above is empty, at the last those below
     * are, as the dock has nothing above or below at the ends of the apps.
     */
    private float listScrollFor(int i) {
        return (i - SLOT) * Ui.pxf(ROW_PITCH);
    }

    private static float clamp(float v, float max) {
        return Math.max(0f, Math.min(v, max));
    }

    // ---- Drawing ----------------------------------------------------------------------------

    @Override
    protected void drawContent(Canvas c) {
        if (item == null) return;
        float x = Ui.px(264);
        c.drawText("EPISODES", x, Ui.px(166), caption);
        c.drawText(TextUtils.ellipsize(showName, header, Ui.px(1392), TextUtils.TruncateAt.END).toString(), x, Ui.px(208), header);
        float cx = Ui.px((264 + 1656) / 2f), cy = Ui.px((TOP + BOTTOM) / 2f);
        if (state == State.LOADING) {
            c.drawText(LOADING, cx, DetailsWindow.centred(message, cy), message);
            return;
        }
        if (state == State.FAILED) {
            c.drawText(FAILED, cx, DetailsWindow.centred(message, cy - Ui.px(18)), message);
            c.drawText(RETRY, cx, DetailsWindow.centred(retry, cy + Ui.px(30)), retry);
            return;
        }
        float top = Ui.px(TOP), bottom = Ui.px(BOTTOM);
        int layer = c.saveLayerAlpha(Ui.px(RAIL_L), top, Ui.px(LIST_R), bottom, Math.round(255 * contentT));
        drawRail(c, top, bottom);
        drawList(c, top, bottom);
        // Where more lies beyond an edge, each column fades out into it rather than being cut
        float railMax = Math.max(0f, seasons.size() * Ui.pxf(RAIL_PITCH) - view());
        fadeEdges(c, Ui.px(RAIL_L), Ui.px(RAIL_R), top, bottom, railScroll.value, railMax);
        fadeEdges(c, Ui.px(LIST_L), Ui.px(LIST_R), top, bottom, listScroll.value, listEnd());
        c.restoreToCount(layer);
    }

    private void fadeEdges(Canvas c, float l, float r, float top, float bottom, float scroll, float max) {
        float fade = Ui.px(EDGE);
        if (scroll > 0.5f) {
            mask.setShader(new LinearGradient(0, top, 0, top + fade, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
            c.drawRect(l, top, r, top + fade, mask);
        }
        if (scroll < max - 0.5f) {
            mask.setShader(new LinearGradient(0, bottom - fade, 0, bottom, 0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
            c.drawRect(l, bottom - fade, r, bottom, mask);
        }
        mask.setShader(null);
    }

    /**
     * The seasons, as the tab bar's pills: the focused one white, sliding from season to season; while the episodes
     * have focus, the season shown in a soft glass pill.
     */
    private void drawRail(Canvas c, float top, float bottom) {
        float pitch = Ui.pxf(RAIL_PITCH), scroll = railScroll.value, l = Ui.px(RAIL_L), r = Ui.px(RAIL_R);
        float pillTop = top + railPill.value - scroll + (pitch - Ui.pxf(PILL_H)) / 2f;
        rect.set(l, pillTop, r, pillTop + Ui.pxf(PILL_H));
        pill.setColor(inRail ? 0xF7FFFFFF : 0x38FFFFFF);
        c.drawRoundRect(rect, Ui.pxf(PILL_H) / 2f, Ui.pxf(PILL_H) / 2f, pill);
        for (int i = 0; i < seasons.size(); i++) {
            float y = top + i * pitch - scroll;
            if (y + pitch < top || y > bottom) continue;
            int ink, countInk;
            if (inRail) {
                float t = Math.max(0f, Math.min(1f, 1f - Math.abs(railPill.value - i * pitch) / pitch));
                ink = (int) argb.evaluate(t, 0xCCFFFFFF, 0xFF111317);
                countInk = (int) argb.evaluate(t, 0x73FFFFFF, 0x8C111317);
            } else if (i == shown) {
                ink = 0xFFFFFFFF;
                countInk = 0x99FFFFFF;
            } else {
                ink = 0xCCFFFFFF;
                countInk = 0x73FFFFFF;
            }
            float mid = y + pitch / 2f;
            int n = seasons.get(i);
            season.setColor(ink);
            c.drawText(n == 0 ? "Specials" : "Season " + n, Ui.px(256), DetailsWindow.centred(season, mid), season);
            count.setColor(countInk);
            c.drawText(String.valueOf(lists.get(i).size()), Ui.px(488), DetailsWindow.centred(count, mid), count);
        }
    }

    /**
     * The season's episodes, gliding through a pane of the dock's glass that stays in the second row: the focused one
     * sits under it, brighter. Stills only for rows in view and the next.
     */
    private void drawList(Canvas c, float top, float bottom) {
        List<ContinueWatching.Episode> eps = lists.get(shown);
        float pitch = Ui.pxf(ROW_PITCH), rowH = Ui.pxf(ROW_H), scroll = listScroll.value, l = Ui.px(LIST_L), r = Ui.px(LIST_R);
        float pane = top + SLOT * pitch;
        if (!inRail) {
            rect.set(l, pane, r, pane + rowH);
            paneGlass.draw(c, rect, Ui.px(26), getLeft(), getTop(), 0f);
        }
        int first = Math.max(0, (int) Math.floor((scroll - pitch) / pitch));
        int last = Math.min(eps.size() - 1, (int) Math.ceil((scroll + view() + pitch) / pitch));
        for (int i = first; i <= last; i++) {
            float y = top + i * pitch - scroll;
            boolean inView = y + rowH >= top && y <= bottom;
            float t = inRail ? 0f : Math.max(0f, Math.min(1f, 1f - Math.abs(y - pane) / pitch));
            drawRow(c, eps.get(i), l, y, t, inView);
        }
    }

    private void drawRow(Canvas c, ContinueWatching.Episode e, float x, float y, float lit, boolean inView) {
        rect.set(x + Ui.px(16), y + Ui.px(16), x + Ui.px(16 + 224), y + Ui.px(16 + 126));
        Bitmap b = null;
        if (e.still != null) {
            if (inView) b = images.get(e.still, Ui.px(224), false, invalidator);
            else images.get(e.still, Ui.px(224), false, null);   // the next row's, fetched ahead
        }
        if (!inView) return;
        float r = Ui.px(14);
        if (b != null) {
            pictures.image(c, b, rect, r);
        } else {
            pictures.placeholder(c, rect, r);
            if (e.number > 0) c.drawText("E" + e.number, rect.centerX(), DetailsWindow.centred(stillLabel, rect.centerY()), stillLabel);
        }
        boolean its = ContinueWatching.resumes(e);   // the entry's progress only on the one that resumes it
        pictures.progress(c, rect, r, its ? item.progress() : e.progress());

        float tx = x + Ui.px(268), tw = Ui.px(844);
        int accent = DetailsWindow.ACCENT;
        String no = e.number > 0 ? "E" + e.number : "";
        String title = !e.name.isEmpty() ? e.name : e.number > 0 ? "Episode " + e.number : "Episode";
        float y1 = y + Ui.px(52), nx = tx;
        if (!no.isEmpty()) {
            number.setColor(accent);
            c.drawText(no, tx, y1, number);
            nx += number.measureText(no + "   ");
        }
        name.setColor((int) argb.evaluate(lit, 0xD9FFFFFF, 0xFFFFFFFF));
        c.drawText(TextUtils.ellipsize(title, name, tx + tw - nx, TextUtils.TruncateAt.END).toString(), nx, y1, name);

        String line = DetailsWindow.episodeFacts(e), left = its ? item.timeLeft() : "";
        float storyTop = y + Ui.px(66);
        if (!line.isEmpty() || !left.isEmpty()) {
            float y2 = y + Ui.px(86);
            facts.setColor((int) argb.evaluate(lit, 0x8CFFFFFF, 0xB3FFFFFF));
            String shownLine = TextUtils.ellipsize(line, facts, tw, TextUtils.TruncateAt.END).toString();
            c.drawText(shownLine, tx, y2, facts);
            if (!left.isEmpty()) {
                // How long is left of the one the show was left on, in the accent
                String more = (line.isEmpty() ? "" : "  ·  ") + left;
                facts.setColor(accent);
                c.drawText(more, tx + facts.measureText(shownLine), y2, facts);
            }
            storyTop = y + Ui.px(100);
        }
        if (e.overview.isEmpty()) return;
        StaticLayout s = stories.get(e);
        if (s == null || s.getWidth() != Math.round(tw)) {
            s = StaticLayout.Builder.obtain(e.overview, 0, e.overview.length(), story, Math.round(tw)).setMaxLines(2)
                    .setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0f, 1.2f)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
            stories.put(e, s);
        }
        story.setColor((int) argb.evaluate(lit, 0xA6FFFFFF, 0xE6FFFFFF));
        c.save();
        c.translate(tx, storyTop);
        s.draw(c);
        c.restore();
    }

    /** A value that glides to where it is sent (at once with reduced motion), drawing the window again as it goes. */
    private final class Glide {
        float value, target;
        private ValueAnimator anim;

        void set(float v) {
            cancel();
            value = target = v;
        }

        void to(float v, long ms) {
            if (v == target && (value == v || (anim != null && anim.isRunning()))) return;
            cancel();
            target = v;
            if (ms <= 0 || Ui.reducedMotion || !isOpen()) {
                value = v;
                invalidate();
                return;
            }
            anim = ValueAnimator.ofFloat(value, v).setDuration(ms);
            anim.setInterpolator(Ui.EASE);
            anim.addUpdateListener(a -> {
                value = (float) a.getAnimatedValue();
                invalidate();
            });
            anim.start();
        }

        void cancel() {
            if (anim != null) anim.cancel();
            anim = null;
        }
    }
}
