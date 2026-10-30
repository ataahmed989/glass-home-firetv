package app.glasshome.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.View;

/**
 * The glass capsule at the foot of Home while a tile is being moved: which app, and how to put it
 * down. It takes no focus; the arrows keep moving the tile underneath.
 */
final class MoveHint extends View {
    private static final float H = 64, PAD = 34, BOTTOM = 46;

    private final Glass glass;
    private final TextPaint name, how;
    private final RectF r = new RectF();
    private String app = "";

    MoveHint(Context context, Glass glass) {
        super(context);
        this.glass = glass;
        setVisibility(GONE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        name = text(context, 600, 0xFFFFFFFF);
        how = text(context, 450, 0xB3FFFFFF);
    }

    private static TextPaint text(Context c, int weight, int color) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Fonts.inter(c, weight));
        p.setTextSize(Ui.px(24));
        p.setColor(color);
        return p;
    }

    void show(String appName) {
        app = "Moving " + appName;
        setVisibility(VISIBLE);
        invalidate();
    }

    void hide() {
        setVisibility(GONE);
    }

    boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    @Override
    protected void onDraw(Canvas c) {
        String rest = "   Arrows to move, Select to place";
        float tw = name.measureText(app) + how.measureText(rest);
        float w = tw + 2 * Ui.px(PAD), h = Ui.px(H);
        float left = (getWidth() - w) / 2f, top = getHeight() - Ui.px(BOTTOM) - h;
        r.set(left, top, left + w, top + h);
        glass.draw(c, r, h / 2f, getLeft(), getTop(), 0.2f);
        Paint.FontMetrics fm = name.getFontMetrics();
        float base = r.centerY() - (fm.ascent + fm.descent) / 2f, x = left + Ui.px(PAD);
        c.drawText(app, x, base, name);
        c.drawText(rest, x + name.measureText(app), base, how);
    }
}
