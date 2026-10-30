package app.glasshome.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Home-screen settings, opened from the profile capsule: a "Featured titles" switch and the
 * constant background, chosen from the user's images with a preview of each. Up/Down move, Select
 * toggles or chooses, Back closes. It is the only focus target while open.
 */
final class SettingsPanel extends View {
    static final class Option {
        final boolean toggle;      // a switch, rather than one choice of several
        final boolean action;      // a row that does something when chosen (checks for updates), neither
        final String section, title, subtitle, value;
        final Bitmap thumb;
        final boolean enabled;
        boolean checked;

        private Option(boolean toggle, String section, String title, String subtitle, String value, Bitmap thumb,
                       boolean enabled, boolean checked) {
            this(toggle, false, section, title, subtitle, value, thumb, enabled, checked);
        }

        private Option(boolean toggle, boolean action, String section, String title, String subtitle, String value,
                       Bitmap thumb, boolean enabled, boolean checked) {
            this.toggle = toggle;
            this.action = action;
            this.section = section;
            this.title = title;
            this.subtitle = subtitle;
            this.value = value;
            this.thumb = thumb;
            this.enabled = enabled;
            this.checked = checked;
        }

        static Option toggle(String section, String title, String subtitle, boolean enabled, boolean on) {
            return new Option(true, section, title, subtitle, null, null, enabled, on);
        }

        static Option action(String section, String title, String subtitle) {
            return action(section, title, subtitle, true);
        }

        static Option action(String section, String title, String subtitle, boolean enabled) {
            return new Option(false, true, section, title, subtitle, null, null, enabled, false);
        }

        static Option choice(String section, String title, String subtitle, String value, Bitmap thumb, boolean chosen) {
            return new Option(false, section, title, subtitle, value, thumb, true, chosen);
        }
    }

    interface Listener {
        void onChosen(Option option);
    }

    private final Glass glass;
    private final Paint scrim = new Paint();
    private final Paint row = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint thumbRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint heading, section, title, subtitle;
    private final RectF rect = new RectF();
    private final Matrix matrix = new Matrix();
    private final List<Option> options = new ArrayList<>();
    private Listener listener;
    private int focused;

    SettingsPanel(Context context, Glass glass) {
        super(context);
        this.glass = glass;
        setFocusable(true);
        setDefaultFocusHighlightEnabled(false);
        setVisibility(GONE);
        scrim.setColor(0x73000000);
        mark.setStyle(Paint.Style.STROKE);
        mark.setStrokeWidth(Math.max(2f, Ui.px(3)));
        mark.setStrokeCap(Paint.Cap.ROUND);
        mark.setStrokeJoin(Paint.Join.ROUND);
        thumbRim.setStyle(Paint.Style.STROKE);
        thumbRim.setStrokeWidth(Math.max(1f, Ui.px(1.5f)));
        heading = text(context, 600, 34, 0xFFFFFFFF);
        section = text(context, 600, 15, 0xA6FFFFFF);
        section.setLetterSpacing(0.14f);
        title = text(context, 500, 24, 0xFFFFFFFF);
        subtitle = text(context, 400, 17, 0xA6FFFFFF);
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

    /** Rebuilds the list and puts focus on {@code start} (or the nearest available row). */
    void setOptions(List<Option> list, int start) {
        options.clear();
        options.addAll(list);
        scroll = 0;
        focused = Math.max(0, Math.min(start, options.size() - 1));
        if (!options.isEmpty() && !options.get(focused).enabled) moveFocus(1);
        invalidate();
    }

    boolean isOpen() {
        return getVisibility() == VISIBLE;
    }

    int focusedIndex() {
        return focused;
    }

    Option option(int index) {
        return options.get(index);
    }

    /** Moves within the list, skipping options that are unavailable; stops at the ends. */
    void moveFocus(int delta) {
        int i = focused;
        do {
            i += delta;
        } while (i >= 0 && i < options.size() && !options.get(i).enabled);
        if (i >= 0 && i < options.size()) {
            focused = i;
            invalidate();
        }
    }

    void choose() {
        if (focused < 0 || focused >= options.size() || !options.get(focused).enabled) return;
        Option o = options.get(focused);
        if (o.toggle) {
            o.checked = !o.checked;
        } else if (!o.action) {
            // One choice per section: picking a background leaves the widget layout alone, and so on
            for (Option other : options) if (!other.toggle && other.section.equals(o.section)) other.checked = other == o;
        }
        invalidate();
        if (listener != null) listener.onChosen(o);
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawRect(0, 0, getWidth(), getHeight(), scrim);
        float rowH = Ui.px(76), pad = Ui.px(32), sectionH = Ui.px(46), header = Ui.px(92), footer = Ui.px(20);
        // Where each row sits in the list, with a heading wherever the section changes
        float[] rowY = new float[options.size()];
        boolean[] headed = new boolean[options.size()];
        float listH = 0;
        String last = null;
        for (int i = 0; i < options.size(); i++) {
            Option o = options.get(i);
            if (!o.section.equals(last)) {
                listH += sectionH;
                headed[i] = true;
                last = o.section;
            }
            rowY[i] = listH;
            listH += rowH;
        }
        // Hangs under the status capsule, like Control Center; a longer list scrolls inside it
        float top = Ui.px(138), margin = Ui.px(40);
        float panelH = Math.min(header + listH + footer, getHeight() - margin - top);
        float left = getWidth() - Ui.px(56) - Ui.px(640), right = getWidth() - Ui.px(56);
        float bottom = top + panelH, listTop = top + header, listBottom = bottom - footer, view = listBottom - listTop;
        // A list taller than the screen scrolls just enough to keep the focused row (and its heading) in view
        // (with room for the edge fade, so the focused row itself is never faded)
        float edge = Ui.px(40);
        if (!options.isEmpty() && focused < rowY.length) {
            float rowTop = rowY[focused] - (headed[focused] ? sectionH : 0);
            if (rowTop - scroll < edge) scroll = rowTop - edge;
            if (rowY[focused] + rowH - scroll > view - edge) scroll = rowY[focused] + rowH - view + edge;
        }
        scroll = Math.max(0, Math.min(scroll, listH - view));
        rect.set(left, top, right, bottom);
        glass.draw(c, rect, Ui.px(40), getLeft(), getTop(), 0.35f);
        c.drawText("Home Screen", left + pad, top + Ui.px(62), heading);

        int layer = c.saveLayer(left, listTop, right, listBottom, null);
        c.clipRect(left, listTop, right, listBottom);
        for (int i = 0; i < options.size(); i++) {
            Option o = options.get(i);
            float y = listTop + rowY[i] - scroll;
            if (headed[i]) {
                c.drawText(o.section, left + pad, y - sectionH + Ui.px(32), section);
            }
            if (y > listBottom || y + rowH < listTop) continue;
            boolean f = i == focused;
            rect.set(left + Ui.px(16), y, right - Ui.px(16), y + rowH - Ui.px(8));
            if (f) {
                row.setColor(0xF2FFFFFF);
                c.drawRoundRect(rect, Ui.px(18), Ui.px(18), row);
            }
            int ink = f ? 0xFF111317 : (o.enabled ? 0xFFFFFFFF : 0x66FFFFFF);
            title.setColor(ink);
            subtitle.setColor(f ? 0xB3111317 : (o.enabled ? 0xA6FFFFFF : 0x4DFFFFFF));
            float textLeft = left + pad + Ui.px(44);
            float textRight = right - pad - (o.toggle ? Ui.px(76) : o.thumb != null ? Ui.px(124) : 0);
            c.drawText(TextUtils.ellipsize(o.title, title, textRight - textLeft, TextUtils.TruncateAt.END).toString(),
                    textLeft, y + Ui.px(31), title);
            c.drawText(TextUtils.ellipsize(o.subtitle, subtitle, textRight - textLeft, TextUtils.TruncateAt.END).toString(),
                    textLeft, y + Ui.px(56), subtitle);
            float midY = y + (rowH - Ui.px(8)) / 2f;
            if (o.toggle) {
                drawSwitch(c, right - pad - Ui.px(60), midY, o.checked, o.enabled, f);
            } else {
                if (o.checked) drawCheck(c, left + pad + Ui.px(14), midY, ink);
                if (o.thumb != null) drawThumb(c, o.thumb, right - pad - Ui.px(112), midY - Ui.px(32), Ui.px(112), Ui.px(63), f);
            }
        }
        // Where more of the list lies beyond an edge, the rows fade out into it instead of being cut
        float fade = Ui.px(40);
        if (scroll > 0.5f) {
            mask.setShader(new LinearGradient(0, listTop, 0, listTop + fade, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
            c.drawRect(left, listTop, right, listTop + fade, mask);
        }
        if (scroll < listH - view - 0.5f) {
            mask.setShader(new LinearGradient(0, listBottom - fade, 0, listBottom, 0xFF000000, 0x00000000, Shader.TileMode.CLAMP));
            c.drawRect(left, listBottom - fade, right, listBottom, mask);
        }
        c.restoreToCount(layer);
    }

    private float scroll;
    private final Paint mask = new Paint();

    {
        mask.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN));
    }

    private void drawCheck(Canvas c, float cx, float cy, int color) {
        mark.setColor(color);
        c.drawLine(cx - Ui.px(9), cy, cx - Ui.px(2), cy + Ui.px(8), mark);
        c.drawLine(cx - Ui.px(2), cy + Ui.px(8), cx + Ui.px(11), cy - Ui.px(9), mark);
    }

    private void drawSwitch(Canvas c, float left, float cy, boolean on, boolean enabled, boolean focused) {
        float w = Ui.px(60), h = Ui.px(34);
        rect.set(left, cy - h / 2f, left + w, cy + h / 2f);
        int onColor = 0xFF34C759, offColor = focused ? 0x33111317 : 0x40FFFFFF;
        track.setColor(on ? onColor : offColor);
        if (!enabled) track.setAlpha(90);
        c.drawRoundRect(rect, h / 2f, h / 2f, track);
        track.setColor(0xFFFFFFFF);
        if (!enabled) track.setAlpha(140);
        float knob = h / 2f - Ui.px(3);
        c.drawCircle(on ? rect.right - h / 2f : rect.left + h / 2f, cy, knob, track);
    }

    private void drawThumb(Canvas c, Bitmap b, float left, float top, float w, float h, boolean focused) {
        BitmapShader shader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        float s = Math.max(w / b.getWidth(), h / b.getHeight());
        matrix.setScale(s, s);
        matrix.postTranslate(left + (w - b.getWidth() * s) / 2f, top + (h - b.getHeight() * s) / 2f);
        shader.setLocalMatrix(matrix);
        thumbPaint.setShader(shader);
        rect.set(left, top, left + w, top + h);
        c.drawRoundRect(rect, Ui.px(10), Ui.px(10), thumbPaint);
        thumbRim.setColor(focused ? 0x33111317 : 0x40FFFFFF);
        c.drawRoundRect(rect, Ui.px(10), Ui.px(10), thumbRim);
    }
}
