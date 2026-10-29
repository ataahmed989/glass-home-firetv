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
 * Dunes' dune does: the glass is cut away there, so the dune is the stage's own picture.
 *
 * Drawn once a minute into a bitmap the size of the clock, and only that bitmap is drawn after
 * that. It fades away while a featured title is on Home, which has its own text there.
 */
final class HomeClock extends View {
    /** Where it sits, in design px: centred on the screen, the date's baseline and the numerals' top and height. */
    static final int CENTER_X = 960, DATE_BASELINE = 176, DIGITS_TOP = 192, DIGITS_H = 156, WIDTH = 760, HEIGHT = 420;
    /** Inter Display's numerals drawn this much taller, to the kit's proportions (the 9 is 1.33 times as tall as wide). */
    private static final float STRETCH = 1.06f;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US);

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Runnable tick = this::tickNow;
    private HeroSlide slide;
    private Bitmap depth;
    /** The slide, small and blurred hard: what the frosted numerals show of the scene behind. */
    private Bitmap frost;
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
        if (s != slide) frost = s != null && s.background != null ? frosted(s) : null;
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

    /** The kit blurs the scene by 80 pt under the numerals: about 100 px here, 25 at the backdrop's quarter size. */
    private static Bitmap frosted(HeroSlide s) {
        Bitmap small = Backdrop.render(s);
        Backdrop.blur(small, 8);
        return small;
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
        paint.setTextSize(Ui.px(33));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setColor(0x59000000);
        paint.setMaskFilter(new BlurMaskFilter(Ui.px(6), BlurMaskFilter.Blur.NORMAL));
        c.drawText(date, cx, Ui.px(DATE_BASELINE) + Ui.px(2), paint);
        paint.setMaskFilter(null);
        paint.setColor(0xF0F2F5FF);
        c.drawText(date, cx, Ui.px(DATE_BASELINE), paint);
    }

    /**
     * The time as one outline, laid out as Apple's Lock Screen kit has "9:41": bold numerals a
     * little taller than wide (102 high, the 9 77 wide), and a colon of two round dots a fifth of
     * their height, at a quarter and three quarters of it, set 0.08 of the height from each side.
     */
    private Path numerals(String t, float cx) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Fonts.interDisplay(getContext(), 700));
        p.setLetterSpacing(-0.02f);
        // The size at which a numeral, stretched, stands DIGITS_H tall
        p.setTextSize(100f);
        RectF probe = new RectF();
        Path zero = new Path();
        p.getTextPath("0", 0, 1, 0, 0, zero);
        zero.computeBounds(probe, true);
        float h = Ui.px(DIGITS_H);
        float size = 100f * h / (probe.height() * STRETCH);
        p.setTextSize(size);

        float top = Ui.px(DIGITS_TOP), baseline = top + h;
        // Even-odd, as the merged outlines are: their holes (the 4's, the 0's) keep no winding of their own
        Path out = new Path();
        out.setFillType(Path.FillType.EVEN_ODD);
        Path glyph = new Path();
        Matrix m = new Matrix();
        RectF r = new RectF();
        float x = 0f, right = 0f;
        boolean afterColon = false;
        for (int i = 0; i < t.length(); i++) {
            String ch = t.substring(i, i + 1);
            if (ch.equals(":")) {
                float d = h * 0.204f, left = right + h * 0.087f;
                out.addCircle(left + d / 2f, -h * 0.745f, d / 2f, Path.Direction.CW);
                out.addCircle(left + d / 2f, -h * 0.258f, d / 2f, Path.Direction.CW);
                right = left + d;
                afterColon = true;
                continue;
            }
            glyph.reset();
            p.getTextPath(ch, 0, 1, 0, 0, glyph);
            // One outline per numeral: the font builds some (the 4's crossbar over its stem) from
            // overlapping pieces, whose hidden edges the rim would otherwise trace
            Path merged = new Path();
            if (merged.op(glyph, new Path(), Path.Op.UNION)) glyph.set(merged);
            m.setScale(1f, STRETCH);
            glyph.transform(m);
            glyph.computeBounds(r, true);
            // Numerals run on by their advance; after the colon, the next starts its gap from the dot
            float shift = afterColon ? right + h * 0.075f - r.left : x;
            m.setTranslate(shift, 0);
            glyph.transform(m);
            glyph.computeBounds(r, true);
            out.addPath(glyph);
            right = r.right;
            x = shift + p.measureText(ch);
            afterColon = false;
        }
        out.computeBounds(r, true);
        m.setTranslate(cx - r.centerX(), baseline);
        out.transform(m);
        return out;
    }

    /**
     * Frosted glass, after the kit's "9:41": the scene behind blurred heavily and lifted (the kit's
     * Linear Dodge is +64 on a light wallpaper; over these night skies the iPhone lifts more, about +90), light gathered along the top and bottom inner edges (its
     * inner shadows, +40), a fine rim lit from the top left and the bottom right (its glass), and a
     * faint shadow outside.
     */
    private void drawGlass(Canvas c, Path digits) {
        RectF b = new RectF();
        digits.computeBounds(b, true);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        RectF frame = frame();

        // Shadow, outside the glass only
        c.save();
        c.clipOutPath(digits);
        p.setColor(0x40000814);
        p.setMaskFilter(new BlurMaskFilter(Ui.px(12), BlurMaskFilter.Blur.NORMAL));
        c.translate(0, Ui.px(3));
        c.drawPath(digits, p);
        c.restore();
        p.setMaskFilter(null);
        p.setColor(0xFF000000);

        // The frost: the blurred scene where the glass is, +64
        c.save();
        c.clipPath(digits);
        if (frost != null) {
            Matrix m = new Matrix();
            m.setScale(screenW / (float) frost.getWidth(), screenH / (float) frost.getHeight());
            m.postTranslate(-frame.left, -frame.top);
            p.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(new float[] {
                    0.9f, 0, 0, 0, 88,
                    0, 0.9f, 0, 0, 90,
                    0, 0, 0.9f, 0, 96,
                    0, 0, 0, 1, 0})));
            c.drawBitmap(frost, m, p);
            p.setColorFilter(null);
        } else {
            p.setColor(0xFF52565E);
            c.drawPath(digits, p);
        }

        // Light just inside the top and bottom edges
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(Ui.px(8));
        p.setShader(new LinearGradient(0, b.top, 0, b.bottom, new int[] {0x52FFFFFF, 0x0AFFFFFF, 0x0AFFFFFF, 0x3DFFFFFF},
                new float[] {0f, 0.3f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        p.setMaskFilter(new BlurMaskFilter(Ui.px(3), BlurMaskFilter.Blur.NORMAL));
        c.drawPath(digits, p);
        p.setMaskFilter(null);

        // The rim, brightest where the edge faces the top left or the bottom right
        p.setStrokeWidth(Math.max(2f, Ui.px(2.5f)));
        p.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                new int[] {0xF2FFFFFF, 0x40FFFFFF, 0x33FFFFFF, 0xBFFFFFFF}, new float[] {0f, 0.4f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        c.drawPath(digits, p);
        p.setShader(null);
        c.restore();
    }

    /**
     * What stands in front of the clock: the glass is cut away wherever the mask is white, so the
     * stage's own picture (the dune) shows there, fading with it whenever the background changes.
     */
    private void drawInFront(Canvas c, RectF frame, int w, int h) {
        // The mask covers the picture as the picture covers the screen
        float scale = Math.max(screenW / (float) depth.getWidth(), screenH / (float) depth.getHeight());
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        m.postTranslate((screenW - depth.getWidth() * scale) / 2f - frame.left, (screenH - depth.getHeight() * scale) / 2f - frame.top);
        Paint cut = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        // The mask's grey becomes alpha: white erases the glass, black keeps it
        cut.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(new float[] {
                0, 0, 0, 0, 0,
                0, 0, 0, 0, 0,
                0, 0, 0, 0, 0,
                1, 0, 0, 0, 0})));
        cut.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        c.drawBitmap(depth, m, cut);
    }
}
