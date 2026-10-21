package app.glasshome.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;

import java.util.List;

/**
 * Draws a feature full screen, for a browse page's hero and for detail pages: the artwork (or, for
 * a game, the two teams' colours and logos), scrims for legibility, and the text block at the
 * left, laid out bottom-up from its button row so it always ends where the caller wants.
 */
final class FeaturePainter {
    private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint meta = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint body = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint factLabel = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint factValue = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint note = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint button = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint score = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint teamName = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint teamRecord = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint status = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint logoShadow = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path icon = new Path();
    private final RectF rect = new RectF();
    private final Rect src = new Rect(), dst = new Rect();
    private StaticLayout bodyLayout;
    private String bodyText;
    private int bodyWidth, bodyLines;

    FeaturePainter(Context c) {
        style(label, c, 600, 20, 0xE6FFFFFF);
        label.setLetterSpacing(0.12f);
        style(title, c, 700, 72, 0xFFFFFFFF);
        title.setLetterSpacing(-0.02f);
        style(meta, c, 500, 24, 0xCCFFFFFF);
        style(body, c, 400, 25, 0xDEFFFFFF);
        style(factLabel, c, 500, 22, 0x8CFFFFFF);
        style(factValue, c, 500, 22, 0xF2FFFFFF);
        style(note, c, 500, 22, 0xB3FFFFFF);
        style(button, c, 600, 23, 0xFFFFFFFF);
        for (TextPaint t : new TextPaint[] {label, title, meta, body, factLabel, factValue, note}) {
            t.setShadowLayer(Ui.px(10), 0, Ui.px(1), 0x99000000);
        }
        style(score, c, 700, 92, 0xFFFFFFFF);
        score.setTextAlign(Paint.Align.CENTER);
        score.setShadowLayer(Ui.px(16), 0, Ui.px(2), 0x80000000);
        style(teamName, c, 600, 30, 0xFFFFFFFF);
        teamName.setTextAlign(Paint.Align.CENTER);
        teamName.setShadowLayer(Ui.px(10), 0, Ui.px(1), 0x80000000);
        style(teamRecord, c, 500, 22, 0xA6FFFFFF);
        teamRecord.setTextAlign(Paint.Align.CENTER);
        style(status, c, 600, 26, 0xFFFFFFFF);
        status.setTextAlign(Paint.Align.CENTER);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1.5f, Ui.px(1.5f)));
    }

    static void style(TextPaint p, Context c, int weight, float size, int color) {
        p.setTypeface(Fonts.inter(c, weight));
        p.setTextSize(Ui.px(size));
        p.setColor(color);
    }

    // ---- Background -------------------------------------------------------------------------

    /** The artwork centre-cropped, a game's colours, or a neutral gradient; then the scrims. */
    void drawBackground(Canvas c, int w, int h, PageModel.Feature f, Bitmap art) {
        if (art != null) {
            float s = Math.max(w / (float) art.getWidth(), h / (float) art.getHeight());
            int sw = Math.round(w / s), sh = Math.round(h / s);
            int sx = (art.getWidth() - sw) / 2, sy = (art.getHeight() - sh) / 2;
            src.set(sx, sy, sx + sw, sy + sh);
            dst.set(0, 0, w, h);
            c.drawBitmap(art, src, dst, image);
        } else if (f != null && f.game != null) {
            String key = f.game.id + "@" + w + "x" + h;
            if (!key.equals(teamKey)) {
                teamKey = key;
                teamShaders = teamShaders(w, h, f.game);
            }
            for (Shader s : teamShaders) {
                bg.setShader(s);
                c.drawRect(0, 0, w, h, bg);
            }
            bg.setShader(null);
        } else {
            bg.setShader(new LinearGradient(0, 0, w, h, 0xFF101216, 0xFF262B34, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, bg);
            bg.setShader(null);
        }
        if (w != scrimW || h != scrimH) {
            scrimW = w;
            scrimH = h;
            scrimLeft = new LinearGradient(0, 0, w * 0.70f, 0, new int[] {0xD9000000, 0x8C000000, 0x00000000},
                    new float[] {0f, 0.45f, 1f}, Shader.TileMode.CLAMP);
            scrimBottom = new LinearGradient(0, h * 0.40f, 0, h, new int[] {0x00000000, 0x4D000000, 0xC7000000},
                    new float[] {0f, 0.43f, 1f}, Shader.TileMode.CLAMP);
        }
        scrim.setShader(scrimLeft);
        c.drawRect(0, 0, w, h, scrim);
        scrim.setShader(scrimBottom);
        c.drawRect(0, 0, w, h, scrim);
    }

    private final Paint scrim = new Paint();
    private Shader scrimLeft, scrimBottom;
    private int scrimW, scrimH;
    private String teamKey;
    private Shader[] teamShaders;

    /** The away team's colour on the left blending into the home team's on the right, each glowing behind its logo. */
    static Shader[] teamShaders(int w, int h, Sports.Game g) {
        int a = shade(g.away.color, 0.42f), b = shade(g.home.color, 0.42f);
        return new Shader[] {
                new LinearGradient(0, 0, w, h * 0.3f, new int[] {0xFF07080B, a, b}, new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP),
                new RadialGradient(w * 0.573f, h * 0.39f, w * 0.24f, withAlpha(g.away.color, 0x70), withAlpha(g.away.color, 0), Shader.TileMode.CLAMP),
                new RadialGradient(w * 0.88f, h * 0.39f, w * 0.24f, withAlpha(g.home.color, 0x70), withAlpha(g.home.color, 0), Shader.TileMode.CLAMP),
        };
    }

    static int shade(int color, float f) {
        int r = Math.round(((color >> 16) & 0xFF) * f), g = Math.round(((color >> 8) & 0xFF) * f), b = Math.round((color & 0xFF) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    /** Both logos large on the right, with the score (or "@") between them and the game's state underneath. */
    void drawGame(Canvas c, Sports.Game g, Bitmap away, Bitmap home, float centreY) {
        float size = Ui.px(220), ax = Ui.px(1100), hx = Ui.px(1690), mid = (ax + hx) / 2f;
        drawLogo(c, away, ax, centreY, size);
        drawLogo(c, home, hx, centreY, size);
        float nameY = centreY + size / 2f + Ui.px(58);
        c.drawText(g.away.label(), ax, nameY, teamName);
        c.drawText(g.home.label(), hx, nameY, teamName);
        if (!g.away.record.isEmpty()) c.drawText(g.away.record, ax, nameY + Ui.px(36), teamRecord);
        if (!g.home.record.isEmpty()) c.drawText(g.home.record, hx, nameY + Ui.px(36), teamRecord);
        Paint.FontMetrics fm = score.getFontMetrics();
        float baseline = centreY - (fm.ascent + fm.descent) / 2f;
        if (g.live() || g.over()) {
            float gap = Ui.px(58);
            score.setAlpha(g.over() && !g.away.winner && g.home.winner ? 140 : 255);
            score.setTextAlign(Paint.Align.RIGHT);
            c.drawText(g.away.score, mid - gap, baseline, score);
            score.setAlpha(g.over() && !g.home.winner && g.away.winner ? 140 : 255);
            score.setTextAlign(Paint.Align.LEFT);
            c.drawText(g.home.score, mid + gap, baseline, score);
            score.setAlpha(255);
            score.setTextAlign(Paint.Align.CENTER);
            score.setTextSize(Ui.px(60));
            c.drawText("–", mid, baseline - Ui.px(8), score);
            score.setTextSize(Ui.px(92));
            drawState(c, g, mid, centreY + Ui.px(92));
        } else {
            status.setTextSize(Ui.px(44));
            status.setColor(0xB3FFFFFF);
            c.drawText("@", mid, baseline - Ui.px(4), status);
            status.setTextSize(Ui.px(26));
            status.setColor(0xFFFFFFFF);
        }
    }

    /** "● LIVE  Q3 4:12" in a red pill, or a quiet "Final". */
    private void drawState(Canvas c, Sports.Game g, float cx, float cy) {
        String text = g.live() ? "LIVE  " + g.status : g.status;
        float w = status.measureText(text) + Ui.px(40), h = Ui.px(46);
        rect.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
        fill.setColor(g.live() ? 0xE6E5262B : 0x40FFFFFF);
        c.drawRoundRect(rect, h / 2f, h / 2f, fill);
        Paint.FontMetrics fm = status.getFontMetrics();
        c.drawText(text, cx, cy - (fm.ascent + fm.descent) / 2f, status);
    }

    private void drawLogo(Canvas c, Bitmap logo, float cx, float cy, float size) {
        if (logo == null) return;
        float s = size / Math.max(logo.getWidth(), logo.getHeight());
        float lw = logo.getWidth() * s, lh = logo.getHeight() * s;
        rect.set(cx - lw / 2f, cy - lh / 2f, cx + lw / 2f, cy + lh / 2f);
        c.drawBitmap(logo, null, rect, logoShadow);
    }

    // ---- Text block -------------------------------------------------------------------------

    /**
     * Label, logo or title, meta line, overview, facts, note and buttons, from {@code bottom} (the
     * bottom of the button row) upwards. {@code focused}: the focused button, or -1.
     */
    void drawText(Canvas c, float left, float bottom, PageModel.Feature f, Bitmap logo, int focused, int maxBodyLines, float textWidth) {
        float y = bottom;
        if (!f.actions.isEmpty()) {
            drawButtons(c, left, bottom, f.actions, focused);
            y -= Ui.px(64);
        }
        if (f.note != null && !f.note.isEmpty()) {
            y -= f.actions.isEmpty() ? Ui.px(6) : Ui.px(30);
            c.drawText(TextUtils.ellipsize(f.note, note, textWidth, TextUtils.TruncateAt.END).toString(), left, y, note);
            y -= Ui.px(10);
        }
        if (!f.facts.isEmpty()) {
            y -= Ui.px(30);
            for (int i = f.facts.size() - 1; i >= 0; i--) {
                String[] fact = f.facts.get(i);
                float lw = factLabel.measureText(fact[0] + "   ");
                c.drawText(fact[0], left, y, factLabel);
                c.drawText(TextUtils.ellipsize(fact[1], factValue, textWidth - lw, TextUtils.TruncateAt.END).toString(), left + lw, y, factValue);
                y -= Ui.px(38);
            }
            y += Ui.px(8);
        }
        StaticLayout layout = bodyLayout(f.body, Math.round(textWidth), maxBodyLines);
        if (layout != null) {
            y -= Ui.px(26) + layout.getHeight();
            c.save();
            c.translate(left, y);
            layout.draw(c);
            c.restore();
        } else {
            y -= Ui.px(10);
        }
        y -= Ui.px(18);
        if (!f.meta.isEmpty()) {
            c.drawText(TextUtils.ellipsize(f.meta, meta, textWidth, TextUtils.TruncateAt.END).toString(), left, y, meta);
            y -= Ui.px(30);
        }
        if (logo != null) {
            float maxW = Ui.px(600), maxH = Ui.px(190);
            float s = Math.min(maxW / logo.getWidth(), maxH / logo.getHeight());
            float lw = logo.getWidth() * s, lh = logo.getHeight() * s;
            y -= Ui.px(8);
            rect.set(left, y - lh, left + lw, y);
            c.drawBitmap(logo, null, rect, image);
            y -= lh + Ui.px(28);
        } else {
            // Long titles step down in size before anything is cut; a game's must clear the team logos
            float limit = f.game != null ? textWidth : Ui.px(1100);
            title.setTextSize(Ui.px(72));
            float tw = title.measureText(f.title);
            if (tw > limit) title.setTextSize(Math.max(Ui.px(50), Ui.px(72) * limit / tw));
            Paint.FontMetrics fm = title.getFontMetrics();
            c.drawText(TextUtils.ellipsize(f.title, title, limit, TextUtils.TruncateAt.END).toString(), left, y, title);
            y += fm.ascent;
            y -= Ui.px(22);
        }
        if (!f.label.isEmpty()) c.drawText(f.label.toUpperCase(), left, y, label);
    }

    private StaticLayout bodyLayout(String text, int width, int lines) {
        if (text == null || text.isEmpty() || lines <= 0) return null;
        if (!text.equals(bodyText) || width != bodyWidth || lines != bodyLines) {
            bodyText = text;
            bodyWidth = width;
            bodyLines = lines;
            bodyLayout = StaticLayout.Builder.obtain(text, 0, text.length(), body, width)
                    .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END)
                    .setLineSpacing(0f, 1.22f).setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
        }
        return bodyLayout;
    }

    /** Pill buttons; the focused one is white with dark text, the others clear glass. */
    void drawButtons(Canvas c, float left, float bottom, List<PageModel.Action> actions, int focused) {
        float h = Ui.px(64), padX = Ui.px(30), gap = Ui.px(18), iconW = Ui.px(18), iconGap = Ui.px(14);
        float x = left;
        Paint.FontMetrics fm = button.getFontMetrics();
        for (int i = 0; i < actions.size(); i++) {
            PageModel.Action a = actions.get(i);
            boolean f = i == focused;
            boolean hasIcon = !a.details;
            float w = padX + (hasIcon ? iconW + iconGap : 0) + button.measureText(a.label) + padX;
            rect.set(x, bottom - h, x + w, bottom);
            fill.setColor(f ? 0xF7FFFFFF : 0x2EFFFFFF);
            if (f) fill.setShadowLayer(Ui.px(20), 0, Ui.px(8), 0x73000000);
            c.drawRoundRect(rect, h / 2f, h / 2f, fill);
            fill.clearShadowLayer();
            if (!f) {
                rim.setColor(0x4DFFFFFF);
                c.drawRoundRect(rect, h / 2f, h / 2f, rim);
            }
            int ink = f ? 0xFF111317 : 0xFFFFFFFF;
            float cy = bottom - h / 2f, tx = x + padX;
            if (hasIcon) {
                icon.reset();
                icon.moveTo(tx, cy - iconW * 0.6f);
                icon.lineTo(tx + iconW, cy);
                icon.lineTo(tx, cy + iconW * 0.6f);
                icon.close();
                fill.setColor(ink);
                c.drawPath(icon, fill);
                tx += iconW + iconGap;
            }
            button.setColor(ink);
            c.drawText(a.label, tx, cy - (fm.ascent + fm.descent) / 2f, button);
            x += w + gap;
        }
    }
}
