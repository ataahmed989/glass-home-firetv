package app.glasshome.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.View;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Home's clock, as the iOS 26 Lock Screen has it: the date ("Wed Oct 28") over the time in tall
 * glass numerals, centred under the tab bar. The numerals are clear glass: the background shows
 * through them, lifted, with light along their rims and a soft shadow behind. A background that
 * comes with a depth mask (see {@link Backgrounds#depth}) stands in front of them, as the Night
 * Dunes' dune does, cut from the same picture so the two meet exactly.
 *
 * Drawn once a minute into a bitmap the size of the clock, and only that bitmap is drawn after
 * that. It fades away while a featured title is on Home, which has its own text there.
 */
final class HomeClock extends View {
    /** Where it sits, in design px: centred on the screen, the date's baseline and the numerals' top and height. */
    static final int CENTER_X = 960, DATE_BASELINE = 176, DIGITS_TOP = 194, DIGITS_H = 176, WIDTH = 760, HEIGHT = 420;
    /** The numerals are Inter Display drawn this much taller than wide, as the Lock Screen's are. */
    private static final float STRETCH = 1.38f;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US);

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Runnable tick = this::tickNow;
    private HeroSlide slide;
    private Bitmap depth;
    private Bitmap drawn;
    private String time = "", date = "";
    private float presence = 1f;
    private boolean shown = true;
    private ValueAnimator fade;
    private boolean running, dirty;
    private int screenW = 1920, screenH = 1080;

    HomeClock(Context context) {
        super(context);
        setWillNotDraw(false);
    }

    /** Where the clock's view goes: a WIDTH x HEIGHT box from the top of the screen. */
    static RectF frame() {
        float w = Ui.px(WIDTH);
        return new RectF(Ui.px(CENTER_X) - w / 2f, 0, Ui.px(CENTER_X) + w / 2f, Ui.px(HEIGHT));
    }

    void setScreen(int w, int h) {
        screenW = w;
        screenH = h;
    }

    /**
     * The slide on Home now: the clock shows over the user's background (standing behind it where
     * {@code depthMask} says), and fades away over a featured title.
     */
    void setSlide(HeroSlide s, Bitmap depthMask) {
        boolean featured = s != null && s.isFeatured();
        show(!featured);
        if (featured) return;
        if (s == slide && depthMask == depth) return;
        slide = s;
        depth = depthMask;
        dirty = true;
        render();
    }

    String time() {
        return time;
    }

    String date() {
        return date;
    }

    /** Showing, or on its way in (a featured title takes it away). */
    boolean showing() {
        return shown;
    }

    boolean hasDepth() {
        return depth != null;
    }

    /** Keeps the time while Home is in front: redrawn on the minute. */
    void start() {
        running = true;
        tickNow();
    }

    void stop() {
        running = false;
        main.removeCallbacks(tick);
    }

    /** The time or the time zone changed. */
    void timeChanged() {
        if (running) tickNow();
        else render();
    }

    private void tickNow() {
        main.removeCallbacks(tick);
        render();
        if (!running) return;
        long now = Clock.now();
        main.postDelayed(tick, 60_000L - now % 60_000L + 50L);
    }

    private void show(boolean shown) {
        this.shown = shown;
        float target = shown ? 1f : 0f;
        if (fade != null) fade.cancel();
        fade = null;
        if (presence == target) return;
        if (Ui.reducedMotion) {
            presence = target;
            invalidate();
            return;
        }
        ValueAnimator a = ValueAnimator.ofFloat(presence, target);
        a.setDuration(Ui.CONTENT_FADE_MS);
        a.setInterpolator(Ui.EASE);
        a.addUpdateListener(v -> {
            presence = (float) v.getAnimatedValue();
            invalidate();
        });
        fade = a;
        a.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (drawn == null || presence <= 0f) return;
        paint.setAlpha(Math.round(255 * presence));
        canvas.drawBitmap(drawn, 0, 0, paint);
    }

    // ---- Drawing ----------------------------------------------------------------------------

    private void render() {
        ZonedDateTime now = Clock.at(Clock.now());
        String t = now.format(DateTimeFormatter.ofPattern(DateFormat.is24HourFormat(getContext()) ? "H:mm" : "h:mm", Locale.US));
        String d = now.format(DATE);
        RectF frame = frame();
        int w = Math.round(frame.width()), h = Math.round(frame.height());
        if (t.equals(time) && d.equals(date) && drawn != null && drawn.getWidth() == w && !dirty) return;
        time = t;
        date = d;
        dirty = false;
        Bitmap b = drawn != null && drawn.getWidth() == w && drawn.getHeight() == h ? drawn : Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b.eraseColor(0);
        Canvas c = new Canvas(b);
        drawDate(c, w / 2f);
        Path digits = numerals(t, w / 2f);
        drawGlass(c, digits);
        if (depth != null && slide != null && slide.background != null) drawInFront(c, frame, w, h);
        drawn = b;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        dirty = true;
        render();
    }

    private void drawDate(Canvas c, float cx) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Fonts.interDisplay(getContext(), 600));
        paint.setTextSize(Ui.px(31));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(0x59000000);
        paint.setMaskFilter(new BlurMaskFilter(Ui.px(6), BlurMaskFilter.Blur.NORMAL));
        c.drawText(date, cx, Ui.px(DATE_BASELINE) + Ui.px(2), paint);
        paint.setMaskFilter(null);
        paint.setColor(0xF0F2F5FF);
        c.drawText(date, cx, Ui.px(DATE_BASELINE), paint);
    }

    /**
     * The time as one outline: each numeral stretched upward from its baseline, the colon's dots
     * kept round and centred on the numerals' height.
     */
    private Path numerals(String t, float cx) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Fonts.interDisplay(getContext(), 560));
        p.setLetterSpacing(-0.01f);
        // The size at which a numeral, stretched, stands DIGITS_H tall
        p.setTextSize(100f);
        RectF probe = new RectF();
        Path zero = new Path();
        p.getTextPath("0", 0, 1, 0, 0, zero);
        zero.computeBounds(probe, true);
        float size = 100f * Ui.px(DIGITS_H) / (probe.height() * STRETCH);
        p.setTextSize(size);
        float gap = size * 0.035f;

        float baseline = Ui.px(DIGITS_TOP) + Ui.px(DIGITS_H);
        Path out = new Path();
        Path glyph = new Path();
        Matrix m = new Matrix();
        float x = 0f;
        for (int i = 0; i < t.length(); i++) {
            String ch = t.substring(i, i + 1);
            glyph.reset();
            p.getTextPath(ch, 0, 1, x, 0, glyph);
            m.reset();
            if (ch.equals(":")) {
                RectF r = new RectF();
                glyph.computeBounds(r, true);
                // Round dots, their middle at the numerals' middle
                m.setTranslate(0, -Ui.px(DIGITS_H) / 2f - r.centerY());
            } else {
                m.setScale(1f, STRETCH);
            }
            glyph.transform(m);
            out.addPath(glyph);
            x += p.measureText(ch) + gap;
        }
        RectF r = new RectF();
        out.computeBounds(r, true);
        m.reset();
        m.setTranslate(cx - r.centerX(), baseline);
        out.transform(m);
        return out;
    }

    /**
     * Clear glass: the background through the numerals, lifted toward white, a soft brighter band
     * just inside their edges for the glass's thickness, a fine rim lit from the top left and the
     * bottom right, and a shadow outside them only.
     */
    private void drawGlass(Canvas c, Path digits) {
        RectF b = new RectF();
        digits.computeBounds(b, true);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        // Shadow, outside the glass (inside, it would darken what shows through)
        c.save();
        c.clipOutPath(digits);
        p.setColor(0x4D000814);
        p.setMaskFilter(new BlurMaskFilter(Ui.px(14), BlurMaskFilter.Blur.NORMAL));
        c.translate(0, Ui.px(4));
        c.drawPath(digits, p);
        c.restore();
        p.setMaskFilter(null);

        // The lift: a pale blue-white wash, lighter toward the top
        p.setShader(new LinearGradient(0, b.top, 0, b.bottom, 0x7AE8EEFF, 0x5CD8E2FF, Shader.TileMode.CLAMP));
        c.drawPath(digits, p);
        p.setShader(null);

        // Thickness: light gathering just inside the edges
        c.save();
        c.clipPath(digits);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Ui.px(10));
        p.setColor(0x38FFFFFF);
        p.setMaskFilter(new BlurMaskFilter(Ui.px(5), BlurMaskFilter.Blur.NORMAL));
        c.drawPath(digits, p);
        p.setMaskFilter(null);

        // The rim, brightest where the edge faces the top left or the bottom right
        p.setStrokeWidth(Math.max(2f, Ui.px(3)));
        p.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                new int[] {0xE6FFFFFF, 0x40FFFFFF, 0x33FFFFFF, 0xB3FFFFFF}, new float[] {0f, 0.4f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        c.drawPath(digits, p);
        p.setShader(null);
        c.restore();
    }

    /**
     * What stands in front of the clock: the background where its mask is white, drawn exactly as
     * the stage draws it beneath, over the numerals.
     */
    private void drawInFront(Canvas c, RectF frame, int w, int h) {
        Bitmap front = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas fc = new Canvas(front);
        fc.translate(-frame.left, -frame.top);
        HeroLayer.drawBackground(fc, screenW, screenH, slide, new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG));
        // The mask covers the picture as the picture covers the screen
        float scale = Math.max(screenW / (float) depth.getWidth(), screenH / (float) depth.getHeight());
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        m.postTranslate((screenW - depth.getWidth() * scale) / 2f, (screenH - depth.getHeight() * scale) / 2f);
        Paint keep = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        // The mask's grey becomes alpha: white keeps the picture, black clears it
        keep.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(new float[] {
                0, 0, 0, 0, 0,
                0, 0, 0, 0, 0,
                0, 0, 0, 0, 0,
                1, 0, 0, 0, 0})));
        keep.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        fc.drawBitmap(depth, m, keep);
        c.drawBitmap(front, 0, 0, null);
        front.recycle();
    }
}
