package app.glasshome.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

/**
 * Control Center, after the iOS 26 Control Center kit: separate Liquid Glass modules on the kit's
 * grid (a 68 px unit, 15 px gutters, four columns), scaled for the TV and hung under the status
 * capsule. Wi-Fi and Bluetooth are 2×1 pills, Sleep and the Sleep Timer 2×2 tiles, and the four
 * shortcuts (Fire TV's screen mirroring, accessibility and display settings, and this launcher's
 * Home Screen settings) 1×1 circles. Each module is its own glass; the focused one lights up, the
 * kit's On look (near-white glass, dark content). Symbol wells follow the kit too: blue while
 * connected, white while a timer runs.
 *
 * One focus target with its own focus position; the arrows move between modules by where they
 * sit, Select acts, Back (or Up from the top) closes it.
 */
final class ControlCenter extends View {
    enum Item { SLEEP, WIFI, BLUETOOTH, TIMER, MIRRORING, ACCESSIBILITY, DISPLAY, HOME_SCREEN }

    interface Listener {
        void onControl(Item item);
    }

    /** Design px per kit px. */
    private static final float K = 1.5f;
    // In kit px: the grid's right edge lines up with the capsule's, under it
    private static final float GRID_W = 318, TOP = 152 / K, RIGHT = 57 / K;
    private static final float WELL = 40, TILE_R = 30;
    private static final String[] ROUND_NAMES = {"Screen Mirroring", "Accessibility", "Display & Sounds", "Home Screen"};
    private static final int ACCENT_BLUE = 0xFF0088FF;

    private final Glass glass;
    private final RectF[] rects = new RectF[Item.values().length];   // kit px, relative to the grid
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyphFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint scrim = new Paint();
    // Erases a figure out of a solid glyph, so the glass shows through it
    private final Paint cutout = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cutoutFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint detail = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint caption = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF(), well = new RectF();
    private final Path path = new Path();
    private float gridLeft, gridTop;
    private Listener listener;
    private Item focused = Item.SLEEP;
    private float focusT = 1f;
    private ValueAnimator focusAnim;

    // Status shown on the modules
    private boolean canSleep;
    private String network = "", bluetooth = "";
    private int wifiBars = -1, timerMinutes;
    // Free memory and storage at the foot, in bytes; none shown while memTotal is 0
    private long memFree, memTotal, diskFree, diskTotal;
    private final RectF stats = new RectF(0, 332, 318, 400);   // kit px: the 4×1 strip under the shortcuts

    ControlCenter(Context context, Glass glass) {
        super(context);
        this.glass = glass;
        setFocusable(true);
        setDefaultFocusHighlightEnabled(false);
        setContentDescription("Control Center");
        setVisibility(GONE);
        // The kit's tile title (Semibold 14) and detail (Medium 14), at the TV's scale
        FeaturePainter.style(title, context, 600, 14 * K, 0xFFFFFFFF);
        FeaturePainter.style(detail, context, 500, 14 * K, 0x8CFFFFFF);
        FeaturePainter.style(caption, context, 500, 15 * K, 0xCCFFFFFF);
        caption.setTextAlign(Paint.Align.CENTER);
        glyph.setStyle(Paint.Style.STROKE);
        glyph.setStrokeCap(Paint.Cap.ROUND);
        glyph.setStrokeJoin(Paint.Join.ROUND);
        shadow.setColor(0xFF000000);
        scrim.setColor(0x33000000);
        cutout.setStyle(Paint.Style.STROKE);
        cutout.setStrokeCap(Paint.Cap.ROUND);
        cutout.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        cutoutFill.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
        // The kit's grid: columns at 0, 83, 167 and 250, rows at 0, 83 and 249
        rects[Item.WIFI.ordinal()] = new RectF(0, 0, 151, 68);
        rects[Item.BLUETOOTH.ordinal()] = new RectF(167, 0, 318, 68);
        rects[Item.SLEEP.ordinal()] = new RectF(0, 83, 151, 234);
        rects[Item.TIMER.ordinal()] = new RectF(167, 83, 318, 234);
        float[] cols = {0, 83, 167, 250};
        for (int i = 0; i < 4; i++) rects[Item.MIRRORING.ordinal() + i] = new RectF(cols[i], 249, cols[i] + 68, 317);
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

    /** Free memory and storage for the strip at the foot; a total of 0 leaves the strip out. */
    void setStats(long memoryFree, long memoryTotal, long storageFree, long storageTotal) {
        memFree = memoryFree;
        memTotal = memoryTotal;
        diskFree = storageFree;
        diskTotal = storageTotal;
        invalidate();
    }

    boolean showsStats() {
        return memTotal > 0;
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

    private void layoutGrid() {
        gridLeft = getWidth() - Ui.px(RIGHT * K) - Ui.px(GRID_W * K);
        gridTop = Ui.px(TOP * K);
    }

    /** A module's place on screen. */
    private RectF screenRect(Item item) {
        RectF d = rects[item.ordinal()];
        r.set(gridLeft + Ui.px(d.left * K), gridTop + Ui.px(d.top * K), gridLeft + Ui.px(d.right * K), gridTop + Ui.px(d.bottom * K));
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
        layoutGrid();
        for (Item item : Item.values()) {
            if (item != focused) drawItem(c, item, 0f);
        }
        drawItem(c, focused, isFocused() ? focusT : 0f);
        if (memTotal > 0) drawStats(c);
        // The focused shortcut's name, under the row of circles; beside it while the strip is under them
        int i = focused.ordinal() - Item.MIRRORING.ordinal();
        if (i >= 0 && isFocused()) {
            RectF row = screenRect(Item.MIRRORING);
            if (memTotal > 0) {
                Paint.FontMetrics fm = caption.getFontMetrics();
                caption.setTextAlign(Paint.Align.RIGHT);
                c.drawText(ROUND_NAMES[i], gridLeft - Ui.px(16 * K), row.centerY() - (fm.ascent + fm.descent) / 2f, caption);
                caption.setTextAlign(Paint.Align.CENTER);
            } else {
                float centre = gridLeft + Ui.px(GRID_W * K) / 2f;
                c.drawText(ROUND_NAMES[i], centre, row.bottom + Ui.px(22 * K), caption);
            }
        }
    }

    private void drawItem(Canvas c, Item item, float focus) {
        RectF box = new RectF(screenRect(item));
        boolean round = item.ordinal() >= Item.MIRRORING.ordinal();
        boolean pill = item == Item.WIFI || item == Item.BLUETOOTH;
        float radius = round || pill ? box.height() / 2f : Ui.px(TILE_R * K);
        c.save();
        float s = 1f + 0.06f * focus;
        c.scale(s, s, box.centerX(), box.centerY());
        if (focus > 0f) {
            // The kit's "Clear" shadow (black 20 %, blur 40), under the lifted module only
            shadow.setShadowLayer(Ui.px(40), 0, Ui.px(10), Math.round(0x40 * focus) << 24);
            c.drawRoundRect(box, radius, radius, shadow);
            shadow.clearShadowLayer();
        }
        glass.drawLit(c, box, radius, getLeft(), getTop(), focus);
        int ink = blend(0xFFFFFFFF, 0xFF111215, focus);
        int muted = blend(0x8CFFFFFF, 0x99111215, focus);
        if (round) {
            drawGlyph(c, item, box.centerX(), box.centerY(), Ui.px(26 * K), ink);
        } else if (pill) {
            float pad = Ui.px(14 * K), d = Ui.px(WELL * K);
            well.set(box.left + pad, box.centerY() - d / 2f, box.left + pad + d, box.centerY() + d / 2f);
            drawWell(c, item, focus);
            float tx = well.right + Ui.px(10 * K), maxW = box.right - Ui.px(12 * K) - tx;
            String[] text = tileText(item);
            title.setColor(ink);
            detail.setColor(muted);
            c.drawText(TextUtils.ellipsize(text[0], title, maxW, TextUtils.TruncateAt.END).toString(), tx, box.top + Ui.px(30.5f * K), title);
            c.drawText(TextUtils.ellipsize(text[1], detail, maxW, TextUtils.TruncateAt.END).toString(), tx, box.top + Ui.px(47.5f * K), detail);
        } else {
            // 2×2 tile: the well at the top, the title and detail at the bottom (the kit's space-between)
            float pad = Ui.px(16 * K), d = Ui.px(WELL * K);
            well.set(box.left + pad, box.top + pad, box.left + pad + d, box.top + pad + d);
            drawWell(c, item, focus);
            float maxW = box.width() - 2 * pad;
            String[] text = tileText(item);
            title.setColor(ink);
            detail.setColor(muted);
            c.drawText(TextUtils.ellipsize(text[0], title, maxW, TextUtils.TruncateAt.END).toString(), box.left + pad, box.top + Ui.px(114.5f * K), title);
            c.drawText(TextUtils.ellipsize(text[1], detail, maxW, TextUtils.TruncateAt.END).toString(), box.left + pad, box.top + Ui.px(131.5f * K), detail);
        }
        c.restore();
    }

    /**
     * Free memory and storage: one 4×1 glass strip under the shortcuts that takes no focus, each
     * half a well with a ring filled as far as that space is used (blue, as the kit's wells are
     * while something is on; orange past 90 %), then its name and what is free.
     */
    private void drawStats(Canvas c) {
        RectF box = new RectF(gridLeft + Ui.px(stats.left * K), gridTop + Ui.px(stats.top * K),
                gridLeft + Ui.px(stats.right * K), gridTop + Ui.px(stats.bottom * K));
        glass.draw(c, box, box.height() / 2f, getLeft(), getTop(), 0f);
        float half = box.width() / 2f;
        drawMeter(c, box.left, box, half, "Memory", memFree, memTotal);
        drawMeter(c, box.left + half, box, half, "Storage", diskFree, diskTotal);
    }

    private void drawMeter(Canvas c, float left, RectF box, float w, String name, long free, long total) {
        float pad = Ui.px(14 * K), d = Ui.px(WELL * K);
        well.set(left + pad, box.centerY() - d / 2f, left + pad + d, box.centerY() + d / 2f);
        fill.setColor(0x0FFFFFFF);
        c.drawOval(well, fill);
        float stroke = Ui.px(4 * K), inset = Ui.px(9 * K);
        RectF ring = new RectF(well.left + inset, well.top + inset, well.right - inset, well.bottom - inset);
        glyph.setStrokeWidth(stroke);
        glyph.setColor(0x33FFFFFF);
        c.drawOval(ring, glyph);
        float used = total <= 0 ? 0f : Math.max(0f, Math.min(1f, 1f - (float) free / total));
        glyph.setColor(used > 0.9f ? 0xFFFF9F0A : ACCENT_BLUE);
        if (used > 0f) c.drawArc(ring, -90f, Math.max(6f, 360f * used), false, glyph);
        float tx = well.right + Ui.px(10 * K), maxW = left + w - Ui.px(8 * K) - tx;
        title.setColor(0xFFFFFFFF);
        detail.setColor(0x8CFFFFFF);
        c.drawText(name, tx, box.top + Ui.px(30.5f * K), title);
        c.drawText(TextUtils.ellipsize(gb(free) + " free", detail, maxW, TextUtils.TruncateAt.END).toString(),
                tx, box.top + Ui.px(47.5f * K), detail);
    }

    /** Bytes as gigabytes, one decimal place (megabytes under 1 GB). */
    static String gb(long bytes) {
        double g = bytes / (1024.0 * 1024 * 1024);
        if (g < 1) return Math.round(bytes / (1024.0 * 1024)) + " MB";
        return (g >= 10 ? String.valueOf(Math.round(g)) : String.format(java.util.Locale.US, "%.1f", g)) + " GB";
    }

    /**
     * The symbol well, as in the kit: white 6 % at rest (dark on lit glass); blue with a white
     * symbol while Wi-Fi or Bluetooth is on; white (dark on lit glass) while the timer runs.
     */
    private void drawWell(Canvas c, Item item, float focus) {
        boolean connected = item == Item.WIFI ? !network.startsWith("Not") : item == Item.BLUETOOTH && !bluetooth.equals("Off") && !bluetooth.isEmpty() && !bluetooth.equals("Not available");
        boolean on = item == Item.TIMER && timerMinutes > 0;
        int wellColour, symbol;
        if (connected) {
            wellColour = ACCENT_BLUE;
            symbol = 0xFFFFFFFF;
        } else if (on) {
            wellColour = blend(0xFFFFFFFF, 0xFF111215, focus);
            symbol = blend(0xFF111215, 0xFFFFFFFF, focus);
        } else {
            wellColour = blend(0x0FFFFFFF, 0x14000000, focus);
            symbol = blend(0xFFFFFFFF, 0xFF111215, focus);
        }
        fill.setColor(wellColour);
        c.drawOval(well, fill);
        drawGlyph(c, item, well.centerX(), well.centerY(), well.width() * 0.5f, symbol);
    }

    private String[] tileText(Item item) {
        switch (item) {
            case WIFI:
                return new String[] {network.equals("Ethernet") ? "Network" : "Wi-Fi", network};
            case BLUETOOTH:
                return new String[] {"Bluetooth", bluetooth};
            case SLEEP:
                return new String[] {"Sleep", canSleep ? "Turn the TV off" : "Not set up"};
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
        glyph.setStrokeWidth(Math.max(1.5f, s * 0.105f));
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
                // Screen mirroring, as in the kit (rectangle.on.rectangle): one screen in front of another.
                // Kit geometry: two 24.54 × 19.59 rects, the front one 6.16 right and 5.79 down, in a 30.7 × 25.38 group
                float k = s / 30.7f, ox = cx - 15.35f * k, oy = cy - 12.69f * k;
                float rw = 24.54f * k, rh = 19.59f * k, rr = 4.4f * k;
                RectF front = new RectF(ox + 6.16f * k, oy + 5.79f * k, ox + 6.16f * k + rw, oy + 5.79f * k + rh);
                RectF back = new RectF(ox, oy, ox + rw, oy + rh);
                float gap = glyph.getStrokeWidth() * 1.4f;
                c.save();
                c.clipOutRect(front.left - gap, front.top - gap, front.right + gap, front.bottom + gap);
                c.drawRoundRect(back, rr, rr, glyph);
                c.restore();
                c.drawRoundRect(front, rr, rr, glyph);
                break;
            }
            case ACCESSIBILITY: {
                // The accessibility figure cut out of a solid disc: head, arms open and level, body, legs apart
                float u = h * 0.98f;
                int layer = c.saveLayer(cx - u - 2, cy - u - 2, cx + u + 2, cy + u + 2, null);
                c.drawCircle(cx, cy, u, glyphFill);
                cutout.setStrokeWidth(u * 0.17f);
                c.drawCircle(cx, cy - u * 0.50f, u * 0.15f, cutoutFill);
                c.drawLine(cx - u * 0.50f, cy - u * 0.20f, cx + u * 0.50f, cy - u * 0.20f, cutout);
                c.drawLine(cx, cy - u * 0.20f, cx, cy + u * 0.16f, cutout);
                c.drawLine(cx, cy + u * 0.14f, cx - u * 0.26f, cy + u * 0.58f, cutout);
                c.drawLine(cx, cy + u * 0.14f, cx + u * 0.26f, cy + u * 0.58f, cutout);
                c.restoreToCount(layer);
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
                float q = h * 0.70f, g = h * 0.30f;
                for (int i = 0; i < 4; i++) {
                    float x = cx + (i % 2 == 0 ? -q - g / 2f : g / 2f), y = cy + (i < 2 ? -q - g / 2f : g / 2f);
                    c.drawRoundRect(new RectF(x, y, x + q, y + q), q * 0.28f, q * 0.28f, glyph);
                }
                break;
            }
        }
    }
}
