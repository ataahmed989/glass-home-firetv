package app.glasshome.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * The Sources menu, hung under the {@link SourceButton} in the top-left corner: the TV's inputs.
 * Two looks, chosen in Home Screen settings as the widgets' are:
 *
 * - {@link #TILES}, Control Center's: each input a Liquid Glass pill from the iOS 26 Control
 *   Center kit (a symbol well, a title and a detail), two by two on the kit's grid. The input on
 *   screen has the kit's "connected" blue well.
 * - {@link #LIST}, Home Screen settings': one glass panel with a heading and a row per input,
 *   a check by the one on screen.
 *
 * Arrows move, Select switches to the focused input, Back closes. It is the only focus target
 * while open.
 */
final class SourcesPanel extends View {
    static final String TILES = "tiles", LIST = "list";

    enum Kind { FIRE_TV, HDMI, AV }

    static final class Source {
        final Kind kind;
        /** {@code detail} goes under the title in the list; the tiles have room for {@code shortDetail} only. */
        final String title, detail, shortDetail;
        final boolean current;

        Source(Kind kind, String title, String detail, String shortDetail, boolean current) {
            this.kind = kind;
            this.title = title;
            this.detail = detail;
            this.shortDetail = shortDetail;
            this.current = current;
        }
    }

    interface Listener {
        void onSource(Source source);
    }

    /** Design px per kit px, as in Control Center. */
    private static final float K = 1.5f;
    private static final float PILL_W = 151, PILL_H = 68, GUTTER = 15, WELL = 40;
    private static final int ACCENT_BLUE = 0xFF0088FF;
    // The plugs of a composite cable: video, left and right audio
    private static final int[] AV_COLOURS = {0xFFFFD60A, 0xFFF2F2F7, 0xFFFF453A};

    private final Glass glass;
    private final List<Source> sources = new ArrayList<>();
    private final Paint scrim = new Paint();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint row = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint detail = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint heading, rowTitle, rowDetail;
    private final RectF r = new RectF(), well = new RectF(), box = new RectF();
    private final Path path = new Path();
    private Listener listener;
    private String look = TILES;
    private int focused;
    private float focusT = 1f;
    private ValueAnimator focusAnim;

    SourcesPanel(Context context, Glass glass) {
        super(context);
        this.glass = glass;
        setFocusable(true);
        setDefaultFocusHighlightEnabled(false);
        setContentDescription("Sources");
        setVisibility(GONE);
        // The kit's tile title (Semibold 14) and detail (Medium 14), at the TV's scale
        FeaturePainter.style(title, context, 600, 14 * K, 0xFFFFFFFF);
        FeaturePainter.style(detail, context, 500, 14 * K, 0x8CFFFFFF);
        heading = text(context, 600, 34, 0xFFFFFFFF);
        rowTitle = text(context, 500, 24, 0xFFFFFFFF);
        rowDetail = text(context, 400, 17, 0xA6FFFFFF);
        glyph.setStyle(Paint.Style.STROKE);
        glyph.setStrokeCap(Paint.Cap.ROUND);
        glyph.setStrokeJoin(Paint.Join.ROUND);
        mark.setStyle(Paint.Style.STROKE);
        mark.setStrokeWidth(Math.max(2f, Ui.px(3)));
        mark.setStrokeCap(Paint.Cap.ROUND);
        mark.setStrokeJoin(Paint.Join.ROUND);
        shadow.setColor(0xFF000000);
    }

    private static TextPaint text(Context c, int weight, int size, int color) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Fonts.inter(c, weight));
        p.setTextSize(Ui.px(size));
        p.setColor(color);
        return p;
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** A TV with three HDMI ports and an AV input, the Fire TV on the first port. */
    static List<Source> sample() {
        List<Source> list = new ArrayList<>();
        list.add(new Source(Kind.FIRE_TV, "Fire TV", "HDMI 1 · On now", "On now", true));
        list.add(new Source(Kind.HDMI, "HDMI 2", "Port 2", "Port 2", false));
        list.add(new Source(Kind.HDMI, "HDMI 3", "Port 3", "Port 3", false));
        list.add(new Source(Kind.AV, "AV", "Composite video", "Composite", false));
        return list;
    }

    /** The inputs to offer; focus starts on the one on screen. */
    void setSources(List<Source> list) {
        sources.clear();
        sources.addAll(list);
        focused = 0;
        for (int i = 0; i < sources.size(); i++) if (sources.get(i).current) focused = i;
        focusT = 1f;
        invalidate();
    }

    boolean isOpen() {
        return getVisibility() == VISIBLE;
    }

    /** {@link #TILES} or {@link #LIST}; anything else is taken as the tiles. */
    void setLook(String l) {
        look = LIST.equals(l) ? LIST : TILES;
        invalidate();
    }

    String look() {
        return look;
    }

    int focusedIndex() {
        return focused;
    }

    /** Moves focus; false when there is nothing that way (Up from the top closes the menu). */
    boolean move(int direction) {
        int n = sources.size(), to = focused;
        if (LIST.equals(look)) {
            if (direction == FOCUS_UP) to = focused - 1;
            else if (direction == FOCUS_DOWN) to = focused + 1;
            else return true;   // nothing beside a list row; the press is used up
        } else {
            // Two columns, filled row by row
            if (direction == FOCUS_LEFT) to = focused % 2 == 1 ? focused - 1 : -1;
            else if (direction == FOCUS_RIGHT) to = focused % 2 == 0 && focused + 1 < n ? focused + 1 : -1;
            else if (direction == FOCUS_UP) to = focused - 2;
            else to = focused + 2 < n ? focused + 2 : -1;
            if (to < 0 && direction != FOCUS_UP) return true;
        }
        if (to < 0 || to >= n) return to >= n;
        setFocused(to);
        return true;
    }

    private void setFocused(int index) {
        focused = index;
        if (focusAnim != null) focusAnim.cancel();
        if (Ui.reducedMotion) {
            focusT = 1f;
            invalidate();
            return;
        }
        focusT = 0f;
        focusAnim = ValueAnimator.ofFloat(0f, 1f);
        focusAnim.setDuration(Ui.FOCUS_MS);
        focusAnim.setInterpolator(Ui.EASE);
        focusAnim.addUpdateListener(a -> {
            focusT = (float) a.getAnimatedValue();
            invalidate();
        });
        focusAnim.start();
    }

    void choose() {
        if (listener != null && focused < sources.size()) listener.onSource(sources.get(focused));
    }

    // ---- Drawing ----------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        if (LIST.equals(look)) drawList(c);
        else drawTiles(c);
    }

    /** Control Center's look: the inputs as glass pills, two by two under the button. */
    private void drawTiles(Canvas c) {
        scrim.setColor(0x33000000);
        c.drawRect(0, 0, getWidth(), getHeight(), scrim);
        for (int i = 0; i < sources.size(); i++) if (i != focused) drawPill(c, i, 0f);
        if (focused < sources.size()) drawPill(c, focused, isFocused() ? focusT : 0f);
    }

    private void drawPill(Canvas c, int i, float focus) {
        Source s = sources.get(i);
        float left = Ui.px(Ui.CAPSULE_RIGHT) + Ui.px((i % 2) * (PILL_W + GUTTER + 1) * K);
        float top = Ui.px(152) + Ui.px((i / 2) * (PILL_H + GUTTER) * K);
        box.set(left, top, left + Ui.px(PILL_W * K), top + Ui.px(PILL_H * K));
        float radius = box.height() / 2f;
        c.save();
        float sc = 1f + 0.06f * focus;
        c.scale(sc, sc, box.centerX(), box.centerY());
        if (focus > 0f) {
            // The kit's "Clear" shadow (black 20 %, blur 40), under the lifted module only
            shadow.setShadowLayer(Ui.px(40), 0, Ui.px(10), Math.round(0x40 * focus) << 24);
            c.drawRoundRect(box, radius, radius, shadow);
            shadow.clearShadowLayer();
        }
        glass.drawLit(c, box, radius, getLeft(), getTop(), focus);
        int ink = blend(0xFFFFFFFF, 0xFF111215, focus);
        int muted = blend(0x8CFFFFFF, 0x99111215, focus);
        float pad = Ui.px(14 * K), d = Ui.px(WELL * K);
        well.set(box.left + pad, box.centerY() - d / 2f, box.left + pad + d, box.centerY() + d / 2f);
        int wellColour, symbol;
        if (s.current) {
            wellColour = ACCENT_BLUE;
            symbol = 0xFFFFFFFF;
        } else {
            wellColour = blend(0x0FFFFFFF, 0x14000000, focus);
            symbol = ink;
        }
        fill.setColor(wellColour);
        c.drawOval(well, fill);
        drawGlyph(c, s.kind, well.centerX(), well.centerY(), well.width() * 0.5f, symbol, focus);
        float tx = well.right + Ui.px(10 * K), maxW = box.right - Ui.px(12 * K) - tx;
        title.setColor(ink);
        detail.setColor(muted);
        // Kept inside the pill whatever the names
        c.clipRect(tx, box.top, box.right - Ui.px(12 * K), box.bottom);
        c.drawText(TextUtils.ellipsize(s.title, title, maxW, TextUtils.TruncateAt.END).toString(), tx, box.top + Ui.px(30.5f * K), title);
        c.drawText(TextUtils.ellipsize(s.shortDetail, detail, maxW, TextUtils.TruncateAt.END).toString(), tx, box.top + Ui.px(47.5f * K), detail);
        c.restore();
    }

    /** Home Screen settings' look: a glass panel with a row per input. */
    private void drawList(Canvas c) {
        scrim.setColor(0x73000000);
        c.drawRect(0, 0, getWidth(), getHeight(), scrim);
        float rowH = Ui.px(84), pad = Ui.px(32), header = Ui.px(92), footer = Ui.px(16);
        float top = Ui.px(138), left = Ui.px(56), right = left + Ui.px(560);
        float bottom = top + header + rowH * sources.size() + footer;
        r.set(left, top, right, bottom);
        glass.draw(c, r, Ui.px(40), getLeft(), getTop(), 0.35f);
        c.drawText("Sources", left + pad, top + Ui.px(62), heading);
        for (int i = 0; i < sources.size(); i++) {
            Source s = sources.get(i);
            float y = top + header + i * rowH;
            boolean f = i == focused && isFocused();
            box.set(left + Ui.px(16), y, right - Ui.px(16), y + rowH - Ui.px(8));
            if (f) {
                row.setColor(0xF2FFFFFF);
                c.drawRoundRect(box, Ui.px(18), Ui.px(18), row);
            }
            int ink = f ? 0xFF111317 : 0xFFFFFFFF;
            float midY = box.centerY(), d = Ui.px(52);
            well.set(left + pad, midY - d / 2f, left + pad + d, midY + d / 2f);
            int wellColour = s.current ? ACCENT_BLUE : f ? 0x14000000 : 0x1AFFFFFF;
            fill.setColor(wellColour);
            c.drawOval(well, fill);
            drawGlyph(c, s.kind, well.centerX(), well.centerY(), d * 0.5f, s.current ? 0xFFFFFFFF : ink, f ? 1f : 0f);
            float textLeft = well.right + Ui.px(20), textRight = right - pad - Ui.px(48);
            rowTitle.setColor(ink);
            rowDetail.setColor(f ? 0xB3111317 : 0xA6FFFFFF);
            c.drawText(TextUtils.ellipsize(s.title, rowTitle, textRight - textLeft, TextUtils.TruncateAt.END).toString(),
                    textLeft, y + Ui.px(34), rowTitle);
            c.drawText(TextUtils.ellipsize(s.detail, rowDetail, textRight - textLeft, TextUtils.TruncateAt.END).toString(),
                    textLeft, y + Ui.px(59), rowDetail);
            if (s.current) {
                mark.setColor(ink);
                float cx = right - pad - Ui.px(14);
                c.drawLine(cx - Ui.px(9), midY, cx - Ui.px(2), midY + Ui.px(8), mark);
                c.drawLine(cx - Ui.px(2), midY + Ui.px(8), cx + Ui.px(11), midY - Ui.px(9), mark);
            }
        }
    }

    /** Line icons drawn here, as Control Center's: each fits a square of {@code s} around (cx, cy). */
    private void drawGlyph(Canvas c, Kind kind, float cx, float cy, float s, int color, float lit) {
        glyph.setColor(color);
        glyph.setStrokeWidth(Math.max(1.5f, s * 0.105f));
        float h = s / 2f;
        switch (kind) {
            case FIRE_TV: {
                // A TV on its stand
                r.set(cx - h * 0.92f, cy - h * 0.70f, cx + h * 0.92f, cy + h * 0.42f);
                c.drawRoundRect(r, h * 0.12f, h * 0.12f, glyph);
                c.drawLine(cx - h * 0.40f, cy + h * 0.80f, cx + h * 0.40f, cy + h * 0.80f, glyph);
                break;
            }
            case HDMI: {
                // The HDMI plug's face: straight sides, the lower corners cut away, a row of contacts
                float w = h * 0.95f, t = cy - h * 0.42f, m = cy + h * 0.05f, b = cy + h * 0.42f, in = h * 0.58f;
                path.reset();
                path.moveTo(cx - w, t);
                path.lineTo(cx + w, t);
                path.lineTo(cx + w, m);
                path.lineTo(cx + in, b);
                path.lineTo(cx - in, b);
                path.lineTo(cx - w, m);
                path.close();
                c.drawPath(path, glyph);
                c.drawLine(cx - w * 0.55f, cy - h * 0.08f, cx + w * 0.55f, cy - h * 0.08f, glyph);
                break;
            }
            default: {
                // Composite: the cable's three plugs, in their colours
                float rad = h * 0.30f, step = h * 0.72f;
                fill.setStyle(Paint.Style.FILL);
                int ring = blend(0x59FFFFFF, 0x59111215, lit);
                for (int i = 0; i < 3; i++) {
                    float x = cx + (i - 1) * step;
                    fill.setColor(AV_COLOURS[i]);
                    c.drawCircle(x, cy, rad, fill);
                    glyph.setColor(ring);
                    glyph.setStrokeWidth(Math.max(1f, s * 0.05f));
                    c.drawCircle(x, cy, rad, glyph);
                }
                break;
            }
        }
    }

    private static int blend(int from, int to, float t) {
        int a = Math.round(((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * t);
        int rr = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return a << 24 | rr << 16 | g << 8 | b;
    }
}
