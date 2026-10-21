package app.glasshome.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
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
import android.view.View;

/**
 * Draws one {@link HeroSlide} full screen. Text sits in the content column on the left
 * (x 106–1415); the column to its right is reserved for the weather card.
 */
final class HeroLayer extends View {
    private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint card = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint cardShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint meta = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint body = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint credit = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint name = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint description = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint action = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint actionFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint actionRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path play = new Path();
    private final RectF rect = new RectF();
    private final Matrix matrix = new Matrix();
    private HeroSlide slide;
    private StaticLayout bodyLayout;
    private boolean actionFocused;

    HeroLayer(Context context) {
        super(context);
        setWillNotDraw(false);
        style(label, context, 600, 20, 0xD9FFFFFF);
        label.setLetterSpacing(0.14f);
        style(title, context, 650, 64, 0xFFFFFFFF);
        title.setLetterSpacing(-0.02f);
        style(meta, context, 500, 24, 0xB8FFFFFF);
        style(body, context, 400, 25, 0xD1FFFFFF);
        style(credit, context, 400, 16, 0x73FFFFFF);
        // A soft shadow under every line of featured text, for artwork with bright patches
        for (TextPaint t : new TextPaint[] {label, title, meta, body}) t.setShadowLayer(Ui.px(10), 0, Ui.px(1), 0x99000000);
        style(name, context, 600, 52, 0xFFFFFFFF);
        name.setTextAlign(Paint.Align.CENTER);
        name.setLetterSpacing(-0.02f);
        style(description, context, 400, 26, 0xC2FFFFFF);
        description.setTextAlign(Paint.Align.CENTER);
        cardShadow.setColor(0xFF000000);
        cardShadow.setShadowLayer(Ui.px(34), 0, Ui.px(14), 0x99000000);
        cardRim.setStyle(Paint.Style.STROKE);
        cardRim.setStrokeWidth(Math.max(1.5f, Ui.px(1.5f)));
        cardRim.setColor(0x33FFFFFF);
        style(action, context, 600, 22, 0xFFFFFFFF);
        actionRim.setStyle(Paint.Style.STROKE);
        actionRim.setStrokeWidth(Math.max(1.5f, Ui.px(1.5f)));
    }

    private static void style(TextPaint p, Context c, int weight, int size, int color) {
        p.setTypeface(Fonts.inter(c, weight));
        p.setTextSize(Ui.px(size));
        p.setColor(color);
    }

    HeroSlide getSlide() {
        return slide;
    }

    /** The hero's only action is the "Open …" button on featured slides; it lights up when the hero has focus. */
    void setActionFocused(boolean focused) {
        if (actionFocused == focused) return;
        actionFocused = focused;
        invalidate();
    }

    void setSlide(HeroSlide s) {
        slide = s;
        bodyLayout = null;
        if (s != null && s.body != null && !s.body.isEmpty()) {
            bodyLayout = StaticLayout.Builder.obtain(s.body, 0, s.body.length(), body, Ui.px(1000))
                    .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END)
                    .setLineSpacing(0f, 1.22f).setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
        }
        invalidate();
    }

    /** Background, motif and scrims only; also used at 240x135 to build the glass backdrop. */
    static void drawBackground(Canvas c, int w, int h, HeroSlide s, Paint p) {
        if (s.background != null) {
            Bitmap b = s.background;
            float scale = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
            int sw = Math.round(w / scale), sh = Math.round(h / scale);
            int sx = (b.getWidth() - sw) / 2, sy = (b.getHeight() - sh) / 2;
            c.drawBitmap(b, new Rect(sx, sy, sx + sw, sy + sh), new Rect(0, 0, w, h), p);
            // Left scrim keeps the featured text readable on bright artwork
            p.setShader(new LinearGradient(0, 0, w * 0.70f, 0, new int[] {0xD9000000, 0x8C000000, 0x00000000},
                    new float[] {0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
        } else {
            Brand b = s.brand;
            p.setShader(new LinearGradient(0, 0, w, h, b.start, b.end, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(new RadialGradient(w * 0.80f, h * 0.22f, w * 0.62f, withAlpha(b.glow, 0x59), withAlpha(b.glow, 0), Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(new RadialGradient(w * 0.10f, h * 0.95f, w * 0.50f, withAlpha(b.glow, 0x24), withAlpha(b.glow, 0), Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);
            motif(c, w, h, b, p);
        }
        // Dark gradient toward the bottom, behind the dock
        p.setShader(new LinearGradient(0, h * 0.40f, 0, h, new int[] {0x00000000, 0x4D000000, 0xC7000000},
                new float[] {0f, 0.43f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
    }

    /** Static, subtle texture per kind of app; nothing here animates. */
    private static void motif(Canvas c, int w, int h, Brand b, Paint p) {
        p.setStyle(Paint.Style.STROKE);
        switch (b.kind) {
            case MUSIC:
                // Faint concentric grooves
                p.setStrokeWidth(Math.max(1f, w * 0.0014f));
                p.setColor(0x10FFFFFF);
                for (float r = w * 0.05f; r < w * 0.46f; r += w * 0.026f) c.drawCircle(w * 0.80f, h * 0.34f, r, p);
                break;
            case GAMES:
                // Two soft diagonal light bands
                p.setStyle(Paint.Style.FILL);
                c.save();
                c.rotate(-24f, w / 2f, h / 2f);
                for (float x : new float[] {0.58f, 0.70f}) {
                    p.setShader(new LinearGradient(w * x, 0, w * (x + 0.06f), 0,
                            new int[] {0x00FFFFFF, 0x14FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
                    c.drawRect(w * x, -h, w * (x + 0.06f), h * 2, p);
                }
                p.setShader(null);
                c.restore();
                break;
            case LIVE:
                // Fine broadcast lines across the upper half
                p.setStrokeWidth(Math.max(1f, h * 0.0012f));
                p.setColor(0x0AFFFFFF);
                for (float y = h * 0.05f; y < h * 0.55f; y += h * 0.012f) c.drawLine(0, y, w, y, p);
                break;
            default:
                break;
        }
        p.setStyle(Paint.Style.FILL);
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (slide == null) return;
        drawBackground(canvas, getWidth(), getHeight(), slide, background);
        if (slide.isFeatured()) {
            drawFeatured(canvas);
        } else if (slide.brand.kind == Brand.Kind.UTILITY) {
            drawUtility(canvas);
        } else {
            // Branded fallback: the app's logo card, low on the left, above the dock
            int w = Ui.px(400), h = Math.round(w * 0.6f);
            drawCard(canvas, Ui.px(Ui.CONTENT_LEFT), Ui.px(Ui.CONTENT_BOTTOM) - h, w, h, slide.logo);
        }
    }

    /** Laid out bottom-up from y = 735 so the block always ends just above the dock. */
    private void drawFeatured(Canvas canvas) {
        float x = Ui.px(Ui.CONTENT_LEFT);
        float y = Ui.px(Ui.CONTENT_BOTTOM);
        float buttonRight = drawAction(canvas, x, y);
        canvas.drawText("Images and data: TMDB", buttonRight + Ui.px(24), y - Ui.px(22), credit);
        y -= Ui.px(56 + 30);
        if (bodyLayout != null) {
            y -= bodyLayout.getHeight();
            canvas.save();
            canvas.translate(x, y);
            bodyLayout.draw(canvas);
            canvas.restore();
            y -= Ui.px(18);
        }
        canvas.drawText(slide.meta, x, y, meta);
        y -= Ui.px(42);
        canvas.drawText(TextUtils.ellipsize(slide.title, title, Ui.px(1180), TextUtils.TruncateAt.END).toString(), x, y, title);
        y -= Ui.px(78);
        canvas.drawText(slide.label.toUpperCase(), x, y, label);
    }

    /** "▶ Open Netflix": opens the app itself (catalogue titles carry no deep link). Returns its right edge. */
    private float drawAction(Canvas canvas, float left, float bottom) {
        String text = "Open " + slide.brand.name;
        float h = Ui.px(56), padX = Ui.px(26), icon = Ui.px(16), gap = Ui.px(12);
        float w = padX + icon + gap + action.measureText(text) + padX;
        rect.set(left, bottom - h, left + w, bottom);
        actionFill.setColor(actionFocused ? 0xF7FFFFFF : 0x2EFFFFFF);
        if (actionFocused) actionFill.setShadowLayer(Ui.px(18), 0, Ui.px(8), 0x66000000);
        else actionFill.clearShadowLayer();
        canvas.drawRoundRect(rect, h / 2f, h / 2f, actionFill);
        if (!actionFocused) {
            actionRim.setColor(0x47FFFFFF);
            canvas.drawRoundRect(rect, h / 2f, h / 2f, actionRim);
        }
        int ink = actionFocused ? 0xFF111317 : 0xFFFFFFFF;
        float cy = bottom - h / 2f, ix = left + padX;
        play.reset();
        play.moveTo(ix, cy - icon * 0.6f);
        play.lineTo(ix + icon, cy);
        play.lineTo(ix, cy + icon * 0.6f);
        play.close();
        actionFill.clearShadowLayer();
        actionFill.setColor(ink);
        canvas.drawPath(play, actionFill);
        action.setColor(ink);
        Paint.FontMetrics fm = action.getFontMetrics();
        canvas.drawText(text, ix + icon + gap, cy - (fm.ascent + fm.descent) / 2f, action);
        return rect.right;
    }

    /** Centred icon, name and a one-line description in the content column. */
    private void drawUtility(Canvas canvas) {
        float cx = Ui.px((Ui.CONTENT_LEFT + Ui.CONTENT_RIGHT) / 2f);
        int w = Ui.px(360), h = Math.round(w * 0.6f);
        drawCard(canvas, Math.round(cx - w / 2f), Ui.px(250), w, h, slide.logo);
        canvas.drawText(slide.brand.name, cx, Ui.px(250) + h + Ui.px(86), name);
        if (slide.brand.description != null) {
            CharSequence d = TextUtils.ellipsize(slide.brand.description, description, Ui.px(1100), TextUtils.TruncateAt.END);
            canvas.drawText(d.toString(), cx, Ui.px(250) + h + Ui.px(134), description);
        }
    }

    /** The app's 800x480 art at a modest size, rounded, never stretched or blurred. */
    private void drawCard(Canvas canvas, int left, int top, int w, int h, Bitmap art) {
        float r = Ui.px(24);
        rect.set(left, top, left + w, top + h);
        canvas.drawRoundRect(rect, r, r, cardShadow);
        if (art != null) {
            BitmapShader shader = new BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float s = w / (float) art.getWidth();
            matrix.setScale(s, s);
            matrix.postTranslate(left, top);
            shader.setLocalMatrix(matrix);
            card.setShader(shader);
            canvas.drawRoundRect(rect, r, r, card);
        }
        float inset = cardRim.getStrokeWidth() / 2f;
        rect.inset(inset, inset);
        canvas.drawRoundRect(rect, r - inset, r - inset, cardRim);
    }
}
