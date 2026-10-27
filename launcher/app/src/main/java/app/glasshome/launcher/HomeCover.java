package app.glasshome.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;

/**
 * The cover kept up over Glass Home for the Home button pressed there (see {@link HomeButtonService}):
 * Home's snapshot, drawn ahead of time into a surface of its own that the compositor keeps
 * invisible until it is wanted. Showing it is then one message to the compositor, on screen at its
 * next frame, with nothing to lay out or draw: a cover that had to be drawn first came up
 * 30-50 ms after the press was known, and pressed again and again, Amazon's home had now and then
 * put a black frame up by then (1 press in 5). Invisible, it costs the compositor nothing.
 */
final class HomeCover {
    private final WindowManager wm;
    private final SurfaceView view;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect bounds = new Rect();
    private SurfaceControl layer;
    /** The surface holds Home's latest snapshot. */
    private boolean drawn;
    private float alpha;
    private ValueAnimator fade;

    private HomeCover(Context context, WindowManager wm) {
        this.wm = wm;
        view = new SurfaceView(context);
        view.setZOrderOnTop(true);
        view.getHolder().setFormat(PixelFormat.OPAQUE);
        view.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                layer = view.getSurfaceControl();
                apply(alpha);
                drawn = false;
                draw(HomeSnapshot.latest());
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                drawn = false;
                draw(HomeSnapshot.latest());
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                layer = null;
                drawn = false;
            }
        });
    }

    /** Puts one up over the screen, invisible; null when no overlay can be added now. */
    static HomeCover add(Context context, WindowManager wm, WindowManager.LayoutParams lp) {
        HomeCover c = new HomeCover(context, wm);
        try {
            wm.addView(c.view, lp);
        } catch (RuntimeException e) {
            return null;
        }
        return c;
    }

    /** Its picture is up to date and the compositor has it: {@link #show} puts it on screen at once. */
    boolean ready() {
        return layer != null && drawn;
    }

    /** Home's snapshot (one bitmap, copied into again whenever Home settles) changed: drawn again. */
    void draw(Bitmap shot) {
        if (layer == null || shot == null) return;
        SurfaceHolder holder = view.getHolder();
        Canvas canvas;
        try {
            canvas = holder.lockHardwareCanvas();
        } catch (RuntimeException e) {
            return;
        }
        if (canvas == null) return;
        bounds.set(0, 0, canvas.getWidth(), canvas.getHeight());
        canvas.drawBitmap(shot, null, bounds, paint);
        holder.unlockCanvasAndPost(canvas);
        drawn = true;
    }

    /** On screen, at once or fading in over {@code ms}. */
    void show(long ms) {
        animate(1f, ms);
    }

    /** Off screen, at once or fading out over {@code ms}; ready for the next time. */
    void hide(long ms) {
        animate(0f, ms);
    }

    void remove() {
        if (fade != null) fade.cancel();
        try {
            wm.removeView(view);
        } catch (RuntimeException ignored) {
            // already gone with the service
        }
    }

    private void animate(float to, long ms) {
        if (fade != null) fade.cancel();
        fade = null;
        if (ms <= 0 || Ui.reducedMotion) {
            apply(to);
            return;
        }
        fade = ValueAnimator.ofFloat(alpha, to);
        fade.setDuration(ms);
        fade.setInterpolator(Ui.EASE);
        fade.addUpdateListener(a -> apply((float) a.getAnimatedValue()));
        fade.start();
    }

    private void apply(float a) {
        alpha = a;
        if (layer == null || !layer.isValid()) return;
        new SurfaceControl.Transaction().setAlpha(layer, a).apply();
    }
}
