package app.glasshome.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

/**
 * tvOS-style Control Center, hanging under the status capsule: Sleep (tall), Wi-Fi, Bluetooth,
 * the Sleep Timer, and round shortcuts to Fire TV's mirroring, accessibility, display settings
 * and this launcher's Home Screen settings. One focus target with its own focus position; the
 * arrows move between tiles by where they sit, Select acts, Back (or Up from the top) closes it.
 */
final class ControlCenter extends View {
    enum Item { SLEEP, WIFI, BLUETOOTH, TIMER, MIRRORING, ACCESSIBILITY, DISPLAY, HOME_SCREEN }

    interface Listener {
        void onControl(Item item);
    }

    // Design px, relative to the panel; the panel's right edge lines up with the capsule's
    private static final int PANEL_W = 490, PAD = 18, COL = 220, GAP = 14, TILE_H = 96, ROUND = 84;
    private static final int PANEL_TOP = 138, PANEL_RIGHT = 57;
    private static final String[] ROUND_NAMES = {"Screen Mirroring", "Accessibility", "Display & Sounds", "Home Screen"};

    private final Glass glass;
    private final RectF[] rects = new RectF[Item.values().length];
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrim = new Paint();
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint subtitle = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint caption = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF panel = new RectF(), r = new RectF();
    private final Path path = new Path();
    private Listener listener;
    private Item focused = Item.SLEEP;
    private float focusT = 1f;
    private ValueAnimator focusAnim;

    // Status shown on the tiles
    private boolean canSleep;
    private String network = "", bluetooth = "";
    private int wifiBars = -1, timerMinutes;

    ControlCenter(Context context, Glass glass) {
        super(context);
        this.glass = glass;
        setFocusable(true);
        setDefaultFocusHighlightEnabled(false);
        setContentDescription("Control Center");
        setVisibility(GONE);
        FeaturePainter.style(title, context, 600, 21, 0xFFFFFFFF);
        FeaturePainter.style(subtitle, context, 400, 17, 0xA6FFFFFF);
        FeaturePainter.style(caption, context, 500, 16, 0xB3FFFFFF);
        caption.setTextAlign(Paint.Align.CENTER);
        glyph.setStyle(Paint.Style.STROKE);
        glyph.setStrokeCap(Paint.Cap.ROUND);
        glyph.setStrokeJoin(Paint.Join.ROUND);
        shadow.setColor(0xFF000000);
        scrim.setColor(0x33000000);
        float x0 = PAD, x1 = PAD + COL + GAP;
        rects[Item.SLEEP.ordinal()] = new RectF(x0, PAD, x0 + COL, PAD + TILE_H * 2 + GAP);
        rects[Item.WIFI.ordinal()] = new RectF(x1, PAD, x1 + COL, PAD + TILE_H);
        rects[Item.BLUETOOTH.ordinal()] = new RectF(x1, PAD + TILE_H + GAP, x1 + COL, PAD + TILE_H * 2 + GAP);
        float timerTop = PAD + TILE_H * 2 + GAP * 2;
        rects[Item.TIMER.ordinal()] = new RectF(x0, timerTop, x1 + COL, timerTop + TILE_H);
        float roundTop = timerTop + TILE_H + PAD, step = (COL * 2 + GAP - ROUND) / 3f;
        for (int i = 0; i < 4; i++) {
            float x = x0 + i * step;
            rects[Item.MIRRORING.ordinal() + i] = new RectF(x, roundTop, x + ROUND, roundTop + ROUND);
        }
    }

    void setListener(Listener l) {
        listener = l;
    }

    boolean isOpen() {
        return getVisibility() == VISIBLE;
    }

    Item focusedItem() {
        return focused;
    }

    /** Fresh status each time it opens. */
    void setStatus(boolean sleepAllowed, String networkStatus, int bars, String bluetoothStatus, int sleepMinutesLeft) {
        canSleep = sleepAllowed;
        network = networkStatus;
        wifiBars = bars;
        bluetooth = bluetoothStatus;
        timerMinutes = sleepMinutesLeft;
        invalidate();
    }

    void setBluetooth(String status) {
        bluetooth = status;
        invalidate();
    }

    void setTimerMinutes(int minutes) {
        timerMinutes = minutes;
        invalidate();
    }

    void reset() {
        focused = Item.SLEEP;
        focusT = 1f;
    }

    private float panelHeight() {
        RectF last = rects[Item.HOME_SCREEN.ordinal()];
        return last.bottom + 12 + 22 + PAD;
    }

    private void layoutPanel() {
        float right = getWidth() - Ui.px(PANEL_RIGHT);
        panel.set(right - Ui.px(PANEL_W), Ui.px(PANEL_TOP), right, Ui.px(PANEL_TOP) + Ui.px(panelHeight()));
    }

    private RectF screenRect(Item item) {
        RectF d = rects[item.ordinal()];
        r.set(panel.left + Ui.px(d.left), panel.top + Ui.px(d.top), panel.left + Ui.px(d.right), panel.top + Ui.px(d.bottom));
        return r;
    }

    /**
     * Moves to the nearest tile in {@code direction} (tiles of different sizes sit side by side, so
     * this goes by position rather than by a grid). False when there is nothing that way.
     */
    boolean move(int direction) {
        RectF from = rects[focused.ordinal()];
        float fx = from.centerX(), fy = from.centerY();
        Item best = null;
        float bestScore = Float.MAX_VALUE;
        for (Item item : Item.values()) {
            if (item == focused) continue;
            RectF to = rects[item.ordinal()];
            float dx = to.centerX() - fx, dy = to.centerY() - fy;
            float along, across;
            switch (direction) {
                case View.FOCUS_LEFT:
                    if (to.right > from.left + 1) continue;
                    along = -dx;
                    across = overlap(from.top, from.bottom, to.top, to.bottom) ? 0 : Math.abs(dy);
                    break;
                case View.FOCUS_RIGHT:
                    if (to.left < from.right - 1) continue;
                    along = dx;
                    across = overlap(from.top, from.bottom, to.top, to.bottom) ? 0 : Math.abs(dy);
                    break;
                case View.FOCUS_UP:
                    if (to.bottom > from.top + 1) continue;
                    along = -dy;
                    across = overlap(from.left, from.right, to.left, to.right) ? Math.abs(dx) * 0.25f : Math.abs(dx);
                    break;
                default:
                    if (to.top < from.bottom - 1) continue;
                    along = dy;
                    across = overlap(from.left, from.right, to.left, to.right) ? Math.abs(dx) * 0.25f : Math.abs(dx);
                    break;
            }
            float score = along + across * 2f;
            if (score < bestScore) {
                bestScore = score;
                best = item;
            }
        }
        if (best == null) return false;
        setFocused(best);
        return true;
    }

    private static boolean overlap(float a0, float a1, float b0, float b1) {
        return Math.min(a1, b1) - Math.max(a0, b0) > 1;
    }

    private void setFocused(Item item) {
        focused = item;
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

    void select() {
        if (listener != null) listener.onControl(focused);
    }

    // ---- Drawing ----------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        c.drawRect(0, 0, getWidth(), getHeight(), scrim);
        layoutPanel();
        tint.setShader(new LinearGradient(0, panel.top, 0, panel.bottom, 0x9E3A3F4A, 0xB8262A33, Shader.TileMode.CLAMP));
        shadow.setShadowLayer(Ui.px(40), 0, Ui.px(16), 0x73000000);
        c.drawRoundRect(panel, Ui.px(40), Ui.px(40), shadow);
        shadow.clearShadowLayer();
        glass.draw(c, panel, Ui.px(40), getLeft(), getTop(), 0f, tint);
        for (Item item : Item.values()) {
            if (item != focused) drawItem(c, item, 0f);
        }
        drawItem(c, focused, isFocused() ? focusT : 0f);
        // The focused round button's name, under the row
        int i = focused.ordinal() - Item.MIRRORING.ordinal();
        if (i >= 0 && isFocused()) {
            RectF row = screenRect(Item.MIRRORING);
            c.drawText(ROUND_NAMES[i], panel.centerX(), row.bottom + Ui.px(30), caption);
        }
    }

    private void drawItem(Canvas c, Item item, float focus) {
        RectF box = new RectF(screenRect(item));
        boolean round = item.ordinal() >= Item.MIRRORING.ordinal();
        float radius = round ? box.width() / 2f : Ui.px(26);
        c.save();
        float s = 1f + 0.05f * focus;
        c.scale(s, s, box.centerX(), box.centerY());
        if (focus > 0f) {
            shadow.setShadowLayer(Ui.px(20), 0, Ui.px(8), Math.round(120 * focus) << 24);
            c.drawRoundRect(box, radius, radius, shadow);
            shadow.clearShadowLayer();
        }
        // Dark translucent at rest, white when focused
        int rest = 0x47000000, lit = 0xF7FFFFFF;
        fill.setColor(blend(rest, lit, focus));
        c.drawRoundRect(box, radius, radius, fill);
        int ink = blend(0xFFFFFFFF, 0xFF16181D, focus);
        int muted = blend(0xA6FFFFFF, 0xA616181D, focus);
        if (round) {
            drawGlyph(c, item, box.centerX(), box.centerY(), Ui.px(34), ink);
        } else if (item == Item.SLEEP) {
            drawGlyph(c, item, box.centerX(), box.top + box.height() * 0.40f, Ui.px(64), ink);
            title.setColor(ink);
            title.setTextAlign(Paint.Align.CENTER);
            c.drawText("Sleep", box.centerX(), box.top + box.height() * 0.74f, title);
            if (!canSleep) {
                subtitle.setColor(muted);
                subtitle.setTextAlign(Paint.Align.CENTER);
                c.drawText("Not set up", box.centerX(), box.top + box.height() * 0.74f + Ui.px(26), subtitle);
            }
            title.setTextAlign(Paint.Align.LEFT);
            subtitle.setTextAlign(Paint.Align.LEFT);
        } else {
            float d = Ui.px(48), cx = box.left + Ui.px(18) + d / 2f, cy = box.centerY();
            fill.setColor(blend(0x26FFFFFF, 0x1416181D, focus));
            c.drawCircle(cx, cy, d / 2f, fill);
            drawGlyph(c, item, cx, cy, Ui.px(26), ink);
            float tx = cx + d / 2f + Ui.px(14), maxW = box.right - Ui.px(16) - tx;
            String[] text = tileText(item);
            title.setColor(ink);
            subtitle.setColor(muted);
            c.drawText(TextUtils.ellipsize(text[0], title, maxW, TextUtils.TruncateAt.END).toString(), tx, cy - Ui.px(3), title);
            c.drawText(TextUtils.ellipsize(text[1], subtitle, maxW, TextUtils.TruncateAt.END).toString(), tx, cy + Ui.px(22), subtitle);
        }
        c.restore();
    }

    private String[] tileText(Item item) {
        switch (item) {
            case WIFI:
                return new String[] {network.equals("Ethernet") ? "Network" : "Wi-Fi", network};
            case BLUETOOTH:
                return new String[] {"Bluetooth", bluetooth};
            default:
                String status = !canSleep ? "Not set up" : timerMinutes > 0 ? sleepLeft(timerMinutes) : "Off";
                return new String[] {"Sleep Timer", status};
        }
    }

    private static String sleepLeft(int minutes) {
        return (minutes >= 60 ? minutes / 60 + " hr " + (minutes % 60 == 0 ? "" : minutes % 60 + " min ") : minutes + " min ") + "left";
    }

    private static int blend(int from, int to, float t) {
        int a = Math.round(((from >>> 24) & 0xFF) + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * t);
        int rr = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int g = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return a << 24 | rr << 16 | g << 8 | b;
    }

    /** Line icons drawn here (no symbol fonts): each fits a square of {@code s} around (cx, cy). */
    private void drawGlyph(Canvas c, Item item, float cx, float cy, float s, int color) {
        glyph.setColor(color);
        glyph.setStrokeWidth(Math.max(1.5f, s * 0.085f));
        glyphFill.setColor(color);
        float h = s / 2f;
        path.reset();
        switch (item) {
            case SLEEP: {
                // Power: an open ring and a bar through its gap
                RectF ring = new RectF(cx - h * 0.78f, cy - h * 0.70f, cx + h * 0.78f, cy + h * 0.86f);
                c.drawArc(ring, -60, 300, false, glyph);
                c.drawLine(cx, cy - h * 0.95f, cx, cy - h * 0.05f, glyph);
                break;
            }
            case WIFI: {
                // Three arcs over a dot; arcs dim when the signal is weak
                float baseY = cy + h * 0.62f;
                for (int i = 0; i < 3; i++) {
                    float rad = h * (0.42f + i * 0.40f);
                    boolean on = wifiBars < 0 || wifiBars >= i + 2;
                    glyph.setAlpha(on ? Color.alpha(color) : Color.alpha(color) / 3);
                    c.drawArc(new RectF(cx - rad, baseY - rad, cx + rad, baseY + rad), -135, 90, false, glyph);
                }
                glyph.setAlpha(Color.alpha(color));
                c.drawCircle(cx, baseY, s * 0.07f, glyphFill);
                break;
            }
            case BLUETOOTH: {
                float x0 = cx - h * 0.42f, x1 = cx + h * 0.42f, top = cy - h * 0.92f, bottom = cy + h * 0.92f;
                path.moveTo(x0, cy - h * 0.46f);
                path.lineTo(x1, cy + h * 0.46f);
                path.lineTo(cx, bottom);
                path.lineTo(cx, top);
                path.lineTo(x1, cy - h * 0.46f);
                path.lineTo(x0, cy + h * 0.46f);
                c.drawPath(path, glyph);
                break;
            }
            case TIMER: {
                c.drawCircle(cx, cy + h * 0.10f, h * 0.78f, glyph);
                c.drawLine(cx, cy + h * 0.10f, cx, cy - h * 0.38f, glyph);
                c.drawLine(cx, cy + h * 0.10f, cx + h * 0.30f, cy + h * 0.30f, glyph);
                c.drawLine(cx - h * 0.26f, cy - h * 0.92f, cx + h * 0.26f, cy - h * 0.92f, glyph);
                break;
            }
            case MIRRORING: {
                // A screen with a triangle rising into it from below
                RectF screen = new RectF(cx - h * 0.92f, cy - h * 0.78f, cx + h * 0.92f, cy + h * 0.38f);
                c.drawRoundRect(screen, h * 0.12f, h * 0.12f, glyph);
                path.moveTo(cx, cy + h * 0.02f);
                path.lineTo(cx + h * 0.48f, cy + h * 0.86f);
                path.lineTo(cx - h * 0.48f, cy + h * 0.86f);
                path.close();
                c.drawPath(path, glyphFill);
                break;
            }
            case ACCESSIBILITY: {
                c.drawCircle(cx, cy, h * 0.92f, glyph);
                c.drawCircle(cx, cy - h * 0.48f, s * 0.07f, glyphFill);
                c.drawLine(cx - h * 0.50f, cy - h * 0.20f, cx + h * 0.50f, cy - h * 0.20f, glyph);
                c.drawLine(cx, cy - h * 0.20f, cx, cy + h * 0.18f, glyph);
                c.drawLine(cx, cy + h * 0.18f, cx - h * 0.30f, cy + h * 0.62f, glyph);
                c.drawLine(cx, cy + h * 0.18f, cx + h * 0.30f, cy + h * 0.62f, glyph);
                break;
            }
            case DISPLAY: {
                RectF screen = new RectF(cx - h * 0.92f, cy - h * 0.70f, cx + h * 0.92f, cy + h * 0.42f);
                c.drawRoundRect(screen, h * 0.12f, h * 0.12f, glyph);
                c.drawLine(cx, cy + h * 0.42f, cx, cy + h * 0.74f, glyph);
                c.drawLine(cx - h * 0.40f, cy + h * 0.80f, cx + h * 0.40f, cy + h * 0.80f, glyph);
                break;
            }
            default: {
                // Home Screen: four rounded squares, like a grid of apps
                float q = h * 0.78f, g = h * 0.16f;
                for (int i = 0; i < 4; i++) {
                    float x = cx + (i % 2 == 0 ? -q - g / 2f : g / 2f), y = cy + (i < 2 ? -q - g / 2f : g / 2f);
                    c.drawRoundRect(new RectF(x, y, x + q, y + q), q * 0.28f, q * 0.28f, glyph);
                }
                break;
            }
        }
    }
}
