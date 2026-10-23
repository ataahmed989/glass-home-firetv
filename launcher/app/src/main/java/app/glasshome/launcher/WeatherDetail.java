package app.glasshome.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.view.View;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Full-screen detailed forecast, opened by selecting the weather card: current conditions and
 * stats, the next 12 hours and the next 7 days. It is the only focus target while open; Back
 * closes it. Everything shown comes from the same live Open-Meteo reading as the card.
 */
final class WeatherDetail extends View {
    private static final DateTimeFormatter UPDATED = DateTimeFormatter.ofPattern("h:mm a", Locale.US).withZone(Weather.ZONE);

    private final Glass glass;
    private final Paint scrim = new Paint();
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panelFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panelRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint city, state, temp, condition, range, caption, value, heading, small, smallTemp, chance, footer, message;
    private final RectF rect = new RectF();
    private Weather.Data data;
    private boolean failed;

    WeatherDetail(Context context, Glass glass) {
        super(context);
        this.glass = glass;
        setFocusable(true);
        setDefaultFocusHighlightEnabled(false);
        setVisibility(GONE);
        scrim.setColor(0x8C000000);
        panelFill.setColor(0x26000000);
        panelRim.setStyle(Paint.Style.STROKE);
        panelRim.setStrokeWidth(Math.max(1f, Ui.px(1)));
        panelRim.setColor(0x24FFFFFF);
        city = text(context, 400, 44, 0xFFFFFFFF, Paint.Align.LEFT);
        state = text(context, 400, 24, 0xB3FFFFFF, Paint.Align.LEFT);
        temp = text(context, 200, 150, 0xFFFFFFFF, Paint.Align.LEFT);
        condition = text(context, 500, 32, 0xFFFFFFFF, Paint.Align.LEFT);
        range = text(context, 400, 26, 0xD9FFFFFF, Paint.Align.LEFT);
        caption = text(context, 600, 15, 0xA6FFFFFF, Paint.Align.LEFT);
        caption.setLetterSpacing(0.14f);
        value = text(context, 400, 36, 0xFFFFFFFF, Paint.Align.LEFT);
        heading = text(context, 600, 15, 0xB3FFFFFF, Paint.Align.LEFT);
        heading.setLetterSpacing(0.16f);
        small = text(context, 500, 19, 0xD9FFFFFF, Paint.Align.CENTER);
        smallTemp = text(context, 400, 25, 0xFFFFFFFF, Paint.Align.CENTER);
        chance = text(context, 600, 16, 0xFF7ED0FA, Paint.Align.CENTER);
        footer = text(context, 400, 16, 0x8CFFFFFF, Paint.Align.CENTER);
        message = text(context, 500, 30, 0xFFFFFFFF, Paint.Align.CENTER);
    }

    private static TextPaint text(Context c, int weight, int size, int color, Paint.Align align) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Fonts.inter(c, weight));
        p.setTextSize(Ui.px(size));
        p.setColor(color);
        p.setTextAlign(align);
        return p;
    }

    void setData(Weather.Data d, boolean failed) {
        this.data = d;
        this.failed = failed;
        invalidate();
    }

    boolean isOpen() {
        return getVisibility() == VISIBLE;
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawRect(0, 0, getWidth(), getHeight(), scrim);
        float left = Ui.px(200), top = Ui.px(110), right = getWidth() - Ui.px(200), bottom = Ui.px(970);
        rect.set(left, top, right, bottom);
        int[] colors = WidgetPanel.tintColors(data);
        tint.setShader(new LinearGradient(0, top, 0, bottom, colors[0], colors[1], Shader.TileMode.CLAMP));
        glass.draw(c, rect, Ui.px(44), getLeft(), getTop(), 0f, tint);

        float x = left + Ui.px(64);
        c.drawText("Columbus", x, top + Ui.px(86), city);
        c.drawText("Ohio", x, top + Ui.px(122), state);
        if (data == null) {
            float cx = (left + right) / 2f;
            c.drawText(failed ? "Weather unavailable" : "Loading weather…", cx, (top + bottom) / 2f, message);
            if (failed) c.drawText("It will retry automatically.", cx, (top + bottom) / 2f + Ui.px(44), footer);
            return;
        }

        // Current conditions, left column
        c.drawText(data.temp + "°", x - Ui.px(8), top + Ui.px(290), temp);
        WeatherIcons.draw(c, data.code, data.day, x + Ui.px(300), top + Ui.px(232), Ui.px(96), 255);
        c.drawText(data.condition(), x, top + Ui.px(346), condition);
        c.drawText("H: " + data.high + "°   L: " + data.low + "°", x, top + Ui.px(386), range);

        // Stats, 2 x 2, right of the current conditions
        float sx = left + Ui.px(640), colW = Ui.px(380), rowH = Ui.px(130), sy = top + Ui.px(70);
        stat(c, sx, sy, "FEELS LIKE", data.feelsLike + "°");
        stat(c, sx + colW, sy, "HUMIDITY", data.humidity + "%");
        stat(c, sx, sy + rowH, "WIND", data.windMph + " mph" + (data.windFrom.isEmpty() ? "" : " " + data.windFrom));
        stat(c, sx + colW, sy + rowH, "SUNRISE · SUNSET", data.sunrise + " · " + data.sunset);

        // Next 12 hours
        float px0 = left + Ui.px(40), px1 = right - Ui.px(40);
        float hTop = top + Ui.px(420), hBottom = hTop + Ui.px(178);
        panel(c, px0, hTop, px1, hBottom, "NEXT 12 HOURS");
        float col = (px1 - px0) / 12f;
        for (int i = 0; i < data.next12.length; i++) {
            Weather.Hour h = data.next12[i];
            float cx = px0 + col * (i + 0.5f);
            c.drawText(h.label, cx, hTop + Ui.px(70), small);
            WeatherIcons.draw(c, h.code, h.day, cx, hTop + Ui.px(102), Ui.px(40), 255);
            if (h.code >= 51 && h.precipitationChance >= 20) c.drawText(h.precipitationChance + "%", cx, hTop + Ui.px(134), chance);
            c.drawText(h.temp + "°", cx, hTop + Ui.px(162), smallTemp);
        }

        // Next 7 days
        float dTop = hBottom + Ui.px(20), dBottom = bottom - Ui.px(50);
        panel(c, px0, dTop, px1, dBottom, "7-DAY FORECAST");
        float dcol = (px1 - px0) / data.days.length;
        for (int i = 0; i < data.days.length; i++) {
            Weather.Day d = data.days[i];
            float cx = px0 + dcol * (i + 0.5f);
            c.drawText(d.label, cx, dTop + Ui.px(64), small);
            WeatherIcons.draw(c, d.code, true, cx, dTop + Ui.px(102), Ui.px(44), 255);
            if (d.code >= 51 && d.precipitationChance >= 20) c.drawText(d.precipitationChance + "%", cx, dTop + Ui.px(140), chance);
            c.drawText(d.high + "°  " + d.low + "°", cx, dTop + Ui.px(172), smallTemp);
        }

        boolean stale = System.currentTimeMillis() - data.fetchedAtMillis > WidgetPanel.STALE_MS;
        footer.setColor(stale ? 0xE6FFD27A : 0x8CFFFFFF);
        c.drawText("Updated " + UPDATED.format(Instant.ofEpochMilli(data.fetchedAtMillis)) + "  ·  Open-Meteo  ·  °F",
                (left + right) / 2f, bottom - Ui.px(18), footer);
    }

    private void stat(Canvas c, float x, float y, String label, String text) {
        c.drawText(label, x, y + Ui.px(26), caption);
        c.drawText(text, x, y + Ui.px(74), value);
    }

    private void panel(Canvas c, float l, float t, float r, float b, String title) {
        rect.set(l, t, r, b);
        c.drawRoundRect(rect, Ui.px(22), Ui.px(22), panelFill);
        c.drawRoundRect(rect, Ui.px(22), Ui.px(22), panelRim);
        c.drawText(title, l + Ui.px(28), t + Ui.px(36), heading);
    }
}
