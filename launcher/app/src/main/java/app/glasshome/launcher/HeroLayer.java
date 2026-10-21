package app.glasshome.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

/**
 * Draws one {@link HeroSlide} full screen: the background, and for a featured title its text in
 * the content column on the left (x 106–1415); the column to its right is reserved for the widgets.
 */
final class HeroLayer extends View {
    private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint label = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint meta = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint body = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint credit = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint action = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint actionFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint actionRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path play = new Path();
    private final RectF rect = new RectF();
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

    /**
     * Background and scrims only, in one pass (the scrims multiply into the picture's shader rather
     * than being layers of their own); also used at 240x135 to build the glass backdrop.
     */
    static void drawBackground(Canvas c, int w, int h, HeroSlide s, Paint p) {
        Shader picture;
        if (s.background != null) {
            Bitmap b = s.background;
            float scale = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
            BitmapShader art = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setScale(scale, scale);
            m.postTranslate((w - b.getWidth() * scale) / 2f, (h - b.getHeight() * scale) / 2f);
            art.setLocalMatrix(m);
            picture = art;
            if (s.isFeatured()) {
                // Left scrim keeps the featured text readable on bright artwork
                picture = new ComposeShader(picture, new LinearGradient(0, 0, w * 0.70f, 0,
                        new int[] {FeaturePainter.through(0xD9), FeaturePainter.through(0x8C), FeaturePainter.through(0)},
                        new float[] {0f, 0.45f, 1f}, Shader.TileMode.CLAMP), PorterDuff.Mode.MULTIPLY);
            }
        } else {
            // Until the background has decoded: quiet graphite, never the app's colours
            picture = new LinearGradient(0, 0, w, h, 0xFF16181E, 0xFF0B0C10, Shader.TileMode.CLAMP);
        }
        // Dark gradient toward the bottom, behind the dock
        p.setShader(new ComposeShader(picture, new LinearGradient(0, h * 0.40f, 0, h,
                new int[] {FeaturePainter.through(0), FeaturePainter.through(0x4D), FeaturePainter.through(0xC7)},
                new float[] {0f, 0.43f, 1f}, Shader.TileMode.CLAMP), PorterDuff.Mode.MULTIPLY));
        c.drawRect(0, 0, w, h, p);
        p.setShader(null);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (slide == null) return;
        drawBackground(canvas, getWidth(), getHeight(), slide, background);
        if (slide.isFeatured()) drawFeatured(canvas);
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
}
