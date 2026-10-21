package app.glasshome.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.format.DateFormat;
import android.view.View;

import java.util.Date;

/**
 * The glass status capsule in the top-right corner, as on tvOS 26: the time, a Control Center
 * button and the profile avatar. The two buttons take focus in turn (the focused one turns
 * white); Select on Control Center opens it, Select on the avatar opens Home Screen settings.
 */
final class ClockCapsule extends View {
    static final int CONTROL = 0, PROFILE = 1;

    private final Glass glass;
    private final Paint timePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint avatarPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint initialPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint button = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF(), r = new RectF();
    private final int padStart, gap, size, between, padEnd;
    private String time = "";
    private String initial = "";
    private Bitmap avatar;
    private int item = CONTROL;
    private boolean controlOpen;
    private float focusT;
    private ValueAnimator focusAnim;

    ClockCapsule(Context context, Glass glass) {
        super(context);
        // Redrawn only when the time or focus changes; otherwise reused from its GPU layer every frame
        setLayerType(LAYER_TYPE_HARDWARE, null);
        this.glass = glass;
        padStart = Ui.px(28);
        gap = Ui.px(20);
        size = Ui.px(52);
        between = Ui.px(10);
        padEnd = Ui.px(10);
        timePaint.setColor(0xFFFFFFFF);
        timePaint.setTextSize(Ui.px(30));
        timePaint.setTypeface(Fonts.inter(context, 450));
        initialPaint.setColor(0xFFFFFFFF);
        initialPaint.setTextSize(Ui.px(25));
        initialPaint.setTextAlign(Paint.Align.CENTER);
        initialPaint.setTypeface(Fonts.inter(context, 600));
        glyph.setStyle(Paint.Style.STROKE);
        glyph.setStrokeWidth(Math.max(1.5f, Ui.px(2.5f)));
        glyph.setStrokeCap(Paint.Cap.ROUND);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(Math.max(2f, Ui.px(3f)));
        ring.setColor(0xFFFFFFFF);
        setFocusable(true);
        setDefaultFocusHighlightEnabled(false);
        setContentDescription("Control Center and Home Screen settings");
    }

    int item() {
        return item;
    }

    void setItem(int i) {
        item = i;
        invalidate();
    }

    /** While Control Center is open its button stays lit, as on tvOS. */
    void setControlOpen(boolean open) {
        controlOpen = open;
        invalidate();
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        if (focusAnim != null) focusAnim.cancel();
        if (Ui.reducedMotion) {
            focusT = gainFocus ? 1f : 0f;
            invalidate();
            return;
        }
        focusAnim = ValueAnimator.ofFloat(focusT, gainFocus ? 1f : 0f);
        focusAnim.setDuration(Ui.FOCUS_MS);
        focusAnim.setInterpolator(Ui.EASE);
        focusAnim.addUpdateListener(a -> {
            focusT = (float) a.getAnimatedValue();
            invalidate();
        });
        focusAnim.start();
    }

    void setProfile(Bitmap image, String fallbackInitial) {
        avatar = image;
        initial = fallbackInitial == null ? "" : fallbackInitial;
        if (image != null) {
            BitmapShader shader = new BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            float s = size / (float) Math.min(image.getWidth(), image.getHeight());
            m.setScale(s, s);
            shader.setLocalMatrix(m);
            avatarPaint.setShader(shader);
        } else {
            avatarPaint.setShader(new LinearGradient(0, 0, size, size, 0xFF5AB0FF, 0xFF1F5FD6, Shader.TileMode.CLAMP));
        }
        invalidate();
    }

    void updateTime() {
        String now = DateFormat.getTimeFormat(getContext()).format(new Date());
        if (now.equals(time)) return;
        boolean widthChanged = timePaint.measureText(now) != timePaint.measureText(time);
        time = now;
        if (widthChanged) requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = padStart + Math.round(timePaint.measureText(time)) + gap + size + between + size + padEnd;
        setMeasuredDimension(w, Ui.px(Ui.CAPSULE_H));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        bounds.set(0, 0, getWidth(), getHeight());
        glass.draw(canvas, bounds, getHeight() / 2f, getLeft() + getTranslationX(), getTop() + getTranslationY(), 0f);

        Paint.FontMetrics fm = timePaint.getFontMetrics();
        float cy = getHeight() / 2f;
        canvas.drawText(time, padStart, cy - (fm.ascent + fm.descent) / 2f, timePaint);

        // Control Center: two switches, on a white circle when focused or open
        float ccx = getWidth() - padEnd - size - between - size / 2f;
        float lit = item == CONTROL ? focusT : 0f;
        if (controlOpen) lit = 1f;
        if (lit > 0f) {
            button.setColor(Math.round(0xF7 * lit) << 24 | 0xFFFFFF);
            canvas.drawCircle(ccx, cy, size / 2f, button);
        }
        int ink = lit > 0.5f ? 0xFF16181D : 0xFFFFFFFF;
        drawSwitches(canvas, ccx, cy, ink);

        // Profile avatar; a white ring when focused
        float ax = getWidth() - padEnd - size / 2f;
        float ringT = item == PROFILE ? focusT : 0f;
        canvas.save();
        float s = 1f + 0.06f * ringT;
        canvas.scale(s, s, ax, cy);
        canvas.save();
        canvas.translate(ax - size / 2f, cy - size / 2f);
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, avatarPaint);
        canvas.restore();
        if (avatar == null && !initial.isEmpty()) {
            Paint.FontMetrics im = initialPaint.getFontMetrics();
            canvas.drawText(initial, ax, cy - (im.ascent + im.descent) / 2f, initialPaint);
        }
        if (ringT > 0f) {
            ring.setAlpha(Math.round(255 * ringT));
            canvas.drawCircle(ax, cy, size / 2f + Ui.px(4), ring);
        }
        canvas.restore();
    }

    /** Two stacked toggle switches, the Control Center symbol drawn here. */
    private void drawSwitches(Canvas c, float cx, float cy, int ink) {
        glyph.setColor(ink);
        button.setColor(ink);
        float w = size * 0.44f, h = size * 0.16f, gapY = size * 0.10f;
        for (int i = 0; i < 2; i++) {
            float top = cy - h - gapY / 2f + i * (h + gapY);
            r.set(cx - w / 2f, top, cx + w / 2f, top + h);
            c.drawRoundRect(r, h / 2f, h / 2f, glyph);
            float knobX = i == 0 ? r.right - h / 2f : r.left + h / 2f;
            c.drawCircle(knobX, top + h / 2f, h * 0.30f, button);
        }
    }
}
