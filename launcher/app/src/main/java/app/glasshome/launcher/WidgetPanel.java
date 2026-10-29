package app.glasshome.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewOutlineProvider;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The widgets beside the focused app's content, in the layout chosen in Home Screen settings:
 *
 * - Smart Stack: one card showing one widget at a time (the weather card, your next game or the
 *   date, with sunrise and sunset). It never turns by itself: with focus on it, Play/Pause (or
 *   Right, or fast-forward) scrolls to the next widget and rewind to the previous one, and it
 *   stays on the widget chosen, across restarts too.
 * - Glances: four small squares (date, weather, next game, sunrise and sunset), each
 *   focused in turn with the arrows.
 *
 * It is one focus target that keeps its own position, moved by HomeActivity. It draws into its
 * own GPU layer, redrawn only when something on it changes; the view reaches {@link #PAD} past the
 * widgets on every side so a focused square's growth and shadow fit inside that layer. Everything
 * shown is real: the weather (and the sun's times) from Open-Meteo, games from ESPN;
 * a widget with nothing to show says so rather than making something up.
 */
final class WidgetPanel extends View {
    static final String STACK = "stack", GLANCES = "glances";
    static final long STALE_MS = 90 * 60 * 1000L;

    /** SUN (sunrise and sunset) is a Glance only: the Smart Stack's date card shows both already. */
    enum Kind { WEATHER, GAME, SUN, DATE }

    private static final Kind[] GLANCE_ORDER = {Kind.DATE, Kind.WEATHER, Kind.GAME, Kind.SUN};

    private static final long TURN_MS = 520;
    private static final int CARD_R = 40, SQUARE = 190, GAP = 25, SQUARE_R = 38;
    /** Room around the widgets (design px), part of the view, for the focused square's growth and shadow. */
    static final int PAD = 40;
    private static final DateTimeFormatter UPDATED = DateTimeFormatter.ofPattern("h:mm a", Locale.US).withZone(Weather.ZONE);

    private final Glass glass;
    private final ImageCache images;
    private final Runnable invalidator = this::invalidate;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint art = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint tint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panelFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint t = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF(), box = new RectF();
    private final Path path = new Path();
    private final Path card = new Path();
    private final Context context;
    private final HomeSettings settings;
    private final int pad = Ui.px(PAD);

    private Weather.Data weather;
    private boolean weatherFailed;
    private Sports.Game game;
    private String layout = STACK;

    // Smart Stack: the widget chosen (null: the first), and the one it is scrolling away from
    private Kind chosen;
    private Kind fromKind;
    private int turnDirection = 1;   // 1: the next widget comes up from below; -1: down from above
    private float turn = 1f;
    private ValueAnimator turnAnim;
    // Glances: the focused square, eased in
    private int square;
    private float squareT = 1f;
    private ValueAnimator squareAnim;

    WidgetPanel(Context context, Glass glass) {
        super(context);
        this.context = context;
        this.glass = glass;
        settings = new HomeSettings(context);
        images = ImageCache.get(context);
        try {
            chosen = settings.stackWidget() == null ? null : Kind.valueOf(settings.stackWidget());
        } catch (IllegalArgumentException e) {
            chosen = null;
        }
        setFocusable(true);
        setDefaultFocusHighlightEnabled(false);
        setContentDescription("Widgets");
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                if (STACK.equals(layout)) outline.setRoundRect(pad, pad, view.getWidth() - pad, view.getHeight() - pad, Ui.px(CARD_R));
                else outline.setEmpty();
            }
        });
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.max(1.5f, Ui.px(2f)));
        edge.setColor(0xD9FFFFFF);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1f, Ui.px(1)));
        rim.setColor(0x24FFFFFF);
        panelFill.setColor(0x26000000);
        shadow.setColor(0xFF000000);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    // ---- Content ----------------------------------------------------------------------------

    void setLayout(String l) {
        String next = GLANCES.equals(l) ? GLANCES : STACK;
        if (next.equals(layout)) return;
        layout = next;
        fromKind = null;
        turn = 1f;
        setScaleX(1f);
        setScaleY(1f);
        invalidateOutline();
        invalidate();
    }

    String layout() {
        return layout;
    }

    void setData(Weather.Data d) {
        weather = d;
        weatherFailed = false;
        invalidate();
    }

    /** A failed refresh keeps the last good reading (marked stale once old); with none, shows unavailable. */
    void setFailed() {
        weatherFailed = true;
        invalidate();
    }

    long lastUpdated() {
        return weather == null ? 0 : weather.fetchedAtMillis;
    }

    Weather.Data data() {
        return weather;
    }

    boolean hasFailed() {
        return weatherFailed && weather == null;
    }

    /** Your team's live or next game, or null when none of them has one coming up. */
    void setGame(Sports.Game g) {
        game = g;
        invalidate();
    }

    Sports.Game game() {
        return game;
    }

    /** The Smart Stack's pages: only widgets with something to show (weather and the date always do). */
    private List<Kind> pages() {
        List<Kind> list = new ArrayList<>();
        list.add(Kind.WEATHER);
        if (game != null) list.add(Kind.GAME);
        list.add(Kind.DATE);
        return list;
    }

    /** The chosen widget while it has something to show (a game, say); otherwise the first, until it does again. */
    private Kind currentKind() {
        List<Kind> p = pages();
        return chosen != null && p.contains(chosen) ? chosen : p.get(0);
    }

    /** The widget that Select acts on. */
    Kind focusedKind() {
        return GLANCES.equals(layout) ? GLANCE_ORDER[square] : currentKind();
    }

    // ---- Focus inside the panel ------------------------------------------------------------

    /** Where focus lands when it arrives from {@code direction} (e.g. FOCUS_UP: from the apps below). */
    void enter(int direction) {
        if (!GLANCES.equals(layout)) return;
        if (direction == View.FOCUS_UP) setSquare(2);
        else if (direction == View.FOCUS_DOWN) setSquare(1);
        else if (direction == View.FOCUS_RIGHT) setSquare(0);
    }

    /** Moves inside the panel; false when the move leaves it (the caller then moves focus on). */
    boolean move(int direction) {
        if (GLANCES.equals(layout)) {
            int col = square % 2, row = square / 2;
            if (direction == View.FOCUS_LEFT && col == 1) setSquare(square - 1);
            else if (direction == View.FOCUS_RIGHT && col == 0) setSquare(square + 1);
            else if (direction == View.FOCUS_UP && row == 1) setSquare(square - 2);
            else if (direction == View.FOCUS_DOWN && row == 0) setSquare(square + 2);
            else return direction == View.FOCUS_RIGHT;   // the right edge of the screen: stay
            return true;
        }
        if (direction == View.FOCUS_RIGHT) {
            turn(1);   // the right edge of the screen: Right turns the stack instead
            return true;
        }
        return false;
    }

    /**
     * Play/Pause on the Smart Stack: scrolls to the next widget ({@code step} 1) or the previous
     * (-1). False in Glances, where every widget is already in view.
     */
    boolean turn(int step) {
        if (GLANCES.equals(layout)) return false;
        List<Kind> p = pages();
        if (p.size() < 2) return true;
        Kind from = currentKind();
        int n = p.size();
        Kind next = p.get(((p.indexOf(from) + step) % n + n) % n);
        chosen = next;
        settings.setStackWidget(next.name());
        if (turnAnim != null) turnAnim.cancel();
        if (Ui.reducedMotion) {
            fromKind = null;
            turn = 1f;
            invalidate();
            return true;
        }
        fromKind = from;
        turnDirection = step >= 0 ? 1 : -1;
        turn = 0f;
        turnAnim = ValueAnimator.ofFloat(0f, 1f);
        turnAnim.setDuration(TURN_MS);
        turnAnim.setInterpolator(Ui.EASE);
        turnAnim.addUpdateListener(a -> {
            turn = (float) a.getAnimatedValue();
            if (turn >= 1f) fromKind = null;
            invalidate();
        });
        turnAnim.start();
        return true;
    }

    int focusedSquare() {
        return square;
    }

    private void setSquare(int s) {
        if (s == square) return;
        square = s;
        if (squareAnim != null) squareAnim.cancel();
        if (Ui.reducedMotion) {
            squareT = 1f;
            invalidate();
            return;
        }
        squareT = 0f;
        squareAnim = ValueAnimator.ofFloat(0f, 1f);
        squareAnim.setDuration(Ui.FOCUS_MS);
        squareAnim.setInterpolator(Ui.EASE);
        squareAnim.addUpdateListener(a -> {
            squareT = (float) a.getAnimatedValue();
            invalidate();
        });
        squareAnim.start();
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        // The stack lifts as one card; in Glances only the focused square grows
        float s = gainFocus && STACK.equals(layout) ? 1.03f : 1f;
        animate().scaleX(s).scaleY(s).translationZ(gainFocus && STACK.equals(layout) ? Ui.px(Ui.FOCUS_LIFT) : 0)
                .setDuration(Ui.reducedMotion ? 0 : Ui.FOCUS_MS).setInterpolator(Ui.EASE).start();
        if (gainFocus && GLANCES.equals(layout)) {
            squareT = 0f;
            int s0 = square;
            square = -1;
            setSquare(s0);
        }
        invalidate();
    }

    // ---- Drawing ----------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        int save = c.save();
        c.translate(pad, pad);
        if (GLANCES.equals(layout)) drawGlances(c);
        else drawStack(c);
        c.restoreToCount(save);
    }

    private void drawStack(Canvas c) {
        int w = getWidth() - 2 * pad, h = getHeight() - 2 * pad;
        List<Kind> p = pages();
        Kind now = currentKind();
        boolean turning = fromKind != null && turn < 1f && p.contains(fromKind);
        float radius = Ui.px(CARD_R);
        // One glass card; its tint follows the widgets across a turn
        r.set(0, 0, w, h);
        glass.draw(c, r, radius, getLeft() + pad, getTop() + pad, 0f, turning ? tintBetween(fromKind, now, turn, h) : tintFor(now, w, h));
        if (turning) {
            // The widgets scroll past each other inside the card, the old one fading as it goes
            card.reset();
            card.addRoundRect(r, radius, radius, Path.Direction.CW);
            int save = c.save();
            c.clipPath(card);
            float travel = h * 0.45f * turnDirection;
            drawScrolled(c, fromKind, w, h, -travel * turn, Math.max(0f, 1f - turn * 1.6f));
            drawScrolled(c, now, w, h, travel * (1f - turn), Math.min(1f, turn * 1.25f));
            c.restoreToCount(save);
        } else {
            drawCard(c, now, w, h);
        }
        if (isFocused()) {
            float in = edge.getStrokeWidth() / 2f;
            r.set(in, in, w - in, h - in);
            c.drawRoundRect(r, radius - in, radius - in, edge);
        }
        if (p.size() > 1) drawDots(c, p, now, w, h);
    }

    private void drawScrolled(Canvas c, Kind kind, int w, int h, float dy, float alpha) {
        if (alpha <= 0f) return;
        int layer = c.saveLayerAlpha(0, 0, w, h, Math.round(255 * alpha));
        c.translate(0, dy);
        drawCard(c, kind, w, h);
        c.restoreToCount(layer);
    }

    /** One full-size Smart Stack widget, drawn over the card's glass. */
    private void drawCard(Canvas c, Kind kind, int w, int h) {
        switch (kind) {
            case WEATHER:
                drawWeatherLarge(c, w, h);
                break;
            case GAME:
                drawGameLarge(c, w, h);
                break;
            default:
                drawDateLarge(c, w, h);
                break;
        }
    }

    private void drawDots(Canvas c, List<Kind> p, Kind now, int w, int h) {
        int n = p.size();
        float d = Ui.px(7), gap = Ui.px(9), x = w - Ui.px(16);
        float total = n * d + (n - 1) * gap, y = h / 2f - total / 2f;
        for (int i = 0; i < n; i++) {
            fill.setColor(p.get(i) == now ? 0xF2FFFFFF : 0x59FFFFFF);
            c.drawCircle(x, y + d / 2f, d / 2f, fill);
            y += d + gap;
        }
    }

    private void drawGlances(Canvas c) {
        float s = Ui.px(SQUARE), g = Ui.px(GAP);
        // Unfocused squares first, the focused one last so it can grow over its neighbours
        for (int i = 0; i < 4; i++) if (!(isFocused() && i == square)) drawSquare(c, i, s, g, 0f);
        if (isFocused() && square >= 0) drawSquare(c, square, s, g, squareT);
    }

    private void drawSquare(Canvas c, int i, float s, float g, float focus) {
        float x = (i % 2) * (s + g), y = (i / 2) * (s + g);
        c.save();
        float scale = 1f + 0.06f * focus;
        c.scale(scale, scale, x + s / 2f, y + s / 2f);
        c.translate(x, y);
        float radius = Ui.px(SQUARE_R);
        r.set(0, 0, s, s);
        if (focus > 0f) {
            shadow.setShadowLayer(Ui.px(22), 0, Ui.px(10), Math.round(140 * focus) << 24);
            c.drawRoundRect(r, radius, radius, shadow);
            shadow.clearShadowLayer();
        }
        Kind kind = GLANCE_ORDER[i];
        glass.draw(c, r, radius, getLeft() + pad + x, getTop() + pad + y, 0f, tintFor(kind, s, s));
        switch (kind) {
            case WEATHER:
                drawWeatherSmall(c, s);
                break;
            case GAME:
                drawGameSmall(c, s);
                break;
            case SUN:
                drawSunSmall(c, s);
                break;
            default:
                drawDateSmall(c, s);
                break;
        }
        if (focus > 0f) {
            edge.setAlpha(Math.round(0xD9 * focus));
            float in = edge.getStrokeWidth() / 2f;
            r.set(in, in, s - in, s - in);
            c.drawRoundRect(r, radius - in, radius - in, edge);
            edge.setAlpha(0xD9);
        }
        c.restore();
    }

    /** The weather's own colour washed over the glass; the other widgets are plain glass. */
    private Paint tintFor(Kind kind, float w, float h) {
        int[] tc = tintOf(kind);
        tint.setShader(new LinearGradient(0, 0, 0, h, tc[0], tc[1], Shader.TileMode.CLAMP));
        return tint;
    }

    /** Partway through a turn: the two widgets' tints blended. */
    private Paint tintBetween(Kind from, Kind to, float t, float h) {
        int[] a = tintOf(from), b = tintOf(to);
        tint.setShader(new LinearGradient(0, 0, 0, h, blend(a[0], b[0], t), blend(a[1], b[1], t), Shader.TileMode.CLAMP));
        return tint;
    }

    private int[] tintOf(Kind kind) {
        if (kind == Kind.WEATHER && weather != null) return tintColors(weather);
        return new int[] {0x00000000, 0x00000000};
    }

    private static int blend(int a, int b, float t) {
        int r = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int x = (a >>> shift) & 0xFF, y = (b >>> shift) & 0xFF;
            r |= (Math.round(x + (y - x) * t) & 0xFF) << shift;
        }
        return r;
    }

    /**
     * Top and bottom tint for the conditions, a wash over the glass that still lets the scene show
     * through: sky blue when clear by day, grey when cloudy or wet (after Jack Labbe's
     * weather-app-clone, MIT), and the mockup's slate blue at night.
     */
    static int[] tintColors(Weather.Data d) {
        if (d == null || !d.day) return new int[] {0x66364663, 0x8C1E2638};
        if (d.code <= 1) return new int[] {0x736F86D6, 0x99004FB8};
        return new int[] {0x737D8792, 0x99434B55};
    }

    private void text(int weight, float size, int color, Paint.Align align) {
        t.setTypeface(Fonts.inter(context, weight));
        t.setTextSize(Ui.px(size));
        t.setColor(color);
        t.setTextAlign(align);
        t.setLetterSpacing(0f);
    }

    private void caps(int weight, float size, int color, Paint.Align align) {
        text(weight, size, color, align);
        t.setLetterSpacing(0.14f);
    }

    private void draw(Canvas c, String s, float x, float y, float maxWidth) {
        c.drawText(TextUtils.ellipsize(s, t, maxWidth, TextUtils.TruncateAt.END).toString(), x, y, t);
    }

    // ---- Weather ------------------------------------------------------------------------------

    /** The full weather card, as before: place, temperature, conditions and the next four hours. */
    private void drawWeatherLarge(Canvas c, int w, int h) {
        float cx = w / 2f;
        text(400, 32, 0xFFFFFFFF, Paint.Align.CENTER);
        c.drawText("Centerville", cx, Ui.px(49), t);
        text(400, 19, 0xB3FFFFFF, Paint.Align.CENTER);
        c.drawText("Ohio", cx, Ui.px(78), t);
        if (weather == null) {
            if (weatherFailed) {
                WeatherIcons.draw(c, 3, true, cx, Ui.px(178), Ui.px(64), 230);
                text(500, 22, 0xFFFFFFFF, Paint.Align.CENTER);
                c.drawText("Weather unavailable", cx, Ui.px(262), t);
                text(400, 17, 0x99FFFFFF, Paint.Align.CENTER);
                c.drawText("Retrying automatically", cx, Ui.px(292), t);
            } else {
                text(400, 17, 0x99FFFFFF, Paint.Align.CENTER);
                c.drawText("Loading weather…", cx, Ui.px(232), t);
            }
            text(400, 13, 0x8CFFFFFF, Paint.Align.CENTER);
            c.drawText("°F", cx, Ui.px(451), t);
            return;
        }
        text(200, 96, 0xFFFFFFFF, Paint.Align.CENTER);
        t.setLetterSpacing(-0.02f);
        c.drawText(weather.temp + "°", cx + Ui.px(10), Ui.px(166), t);
        WeatherIcons.draw(c, weather.code, weather.day, cx, Ui.px(197), Ui.px(50), 255);
        text(500, 22, 0xFFFFFFFF, Paint.Align.CENTER);
        c.drawText(weather.condition(), cx, Ui.px(248), t);
        text(400, 19, 0xD9FFFFFF, Paint.Align.CENTER);
        c.drawText("H: " + weather.high + "°   L: " + weather.low + "°", cx, Ui.px(273), t);

        float inset = Ui.px(15);
        box.set(inset, Ui.px(288), w - inset, Ui.px(433));
        c.drawRoundRect(box, Ui.px(16), Ui.px(16), panelFill);
        c.drawRoundRect(box, Ui.px(16), Ui.px(16), rim);
        caps(600, 13, 0xB3FFFFFF, Paint.Align.CENTER);
        t.setLetterSpacing(0.16f);
        c.drawText("HOURLY FORECAST", cx, Ui.px(309), t);
        float col = box.width() / 4f;
        for (int i = 0; i < weather.hours.length; i++) {
            Weather.Hour hour = weather.hours[i];
            float x = box.left + col * (i + 0.5f);
            text(500, 15, 0xD9FFFFFF, Paint.Align.CENTER);
            c.drawText(hour.label, x, Ui.px(340), t);
            WeatherIcons.draw(c, hour.code, hour.day, x, Ui.px(366), Ui.px(34), 255);
            // Chance of rain or snow, only for wet hours and only when it is worth noting
            if (hour.code >= 51 && hour.precipitationChance >= 20) {
                text(600, 14, 0xFF7ED0FA, Paint.Align.CENTER);
                c.drawText(hour.precipitationChance + "%", x, Ui.px(396), t);
            }
            text(400, 21, 0xFFFFFFFF, Paint.Align.CENTER);
            c.drawText(hour.temp + "°", x, Ui.px(420), t);
        }
        boolean stale = System.currentTimeMillis() - weather.fetchedAtMillis > STALE_MS;
        text(400, 13, stale ? 0xE6FFD27A : 0x8CFFFFFF, Paint.Align.CENTER);
        c.drawText("Updated " + UPDATED.format(Instant.ofEpochMilli(weather.fetchedAtMillis)) + "  ·  °F", cx, Ui.px(451), t);
    }

    /** Compact weather: place, temperature, symbol, conditions, high and low. */
    private void drawWeatherSmall(Canvas c, float s) {
        float left = Ui.px(18);
        text(500, 16, 0xF2FFFFFF, Paint.Align.LEFT);
        c.drawText("Centerville", left, Ui.px(34), t);
        if (weather == null) {
            text(400, 15, 0x99FFFFFF, Paint.Align.LEFT);
            c.drawText(weatherFailed ? "Unavailable" : "Loading…", left, Ui.px(100), t);
            return;
        }
        WeatherIcons.draw(c, weather.code, weather.day, s - Ui.px(38), Ui.px(40), Ui.px(38), 255);
        text(300, 56, 0xFFFFFFFF, Paint.Align.LEFT);
        t.setLetterSpacing(-0.02f);
        c.drawText(weather.temp + "°", left - Ui.px(2), Ui.px(102), t);
        text(500, 16, 0xFFFFFFFF, Paint.Align.LEFT);
        draw(c, weather.condition(), left, Ui.px(144), s - 2 * left);
        text(500, 14, 0xCCFFFFFF, Paint.Align.LEFT);
        c.drawText("H:" + weather.high + "°  L:" + weather.low + "°", left, Ui.px(168), t);
    }

    // ---- Next game --------------------------------------------------------------------------

    private String gameHeader() {
        if (game == null) return "NEXT GAME";
        if (game.live()) return "LIVE";
        if (game.over()) return "FINAL";
        return "NEXT GAME";
    }

    /** Both teams' colours glowing faintly behind their logos. */
    private void teamGlow(Canvas c, float ax, float hx, float cy, float radius) {
        glow.setShader(new RadialGradient(ax, cy, radius, FeaturePainter.withAlpha(game.away.color, 0x66),
                FeaturePainter.withAlpha(game.away.color, 0), Shader.TileMode.CLAMP));
        c.drawCircle(ax, cy, radius, glow);
        glow.setShader(new RadialGradient(hx, cy, radius, FeaturePainter.withAlpha(game.home.color, 0x66),
                FeaturePainter.withAlpha(game.home.color, 0), Shader.TileMode.CLAMP));
        c.drawCircle(hx, cy, radius, glow);
        glow.setShader(null);
    }

    private void drawGameLarge(Canvas c, int w, int h) {
        float pad = Ui.px(24);
        caps(700, 14, game != null && game.live() ? 0xFFFF453A : 0xCCFFFFFF, Paint.Align.LEFT);
        c.drawText(gameHeader(), pad, Ui.px(44), t);
        if (game == null) {
            text(400, 19, 0xB3FFFFFF, Paint.Align.CENTER);
            c.drawText("No games coming up for your teams", w / 2f, h / 2f, t);
            return;
        }
        caps(600, 13, 0x99FFFFFF, Paint.Align.RIGHT);
        draw(c, (game.league.name + (game.week.isEmpty() ? "" : "  ·  " + game.week)).toUpperCase(), w - pad, Ui.px(44), w * 0.5f);
        float ax = w * 0.27f, hx = w * 0.73f, column = w * 0.44f;
        if (game.live() || game.over()) {
            // A scoreboard: each team's logo, name and score in its own column, so no score can reach a logo
            float ly = Ui.px(132), logo = Ui.px(96);
            teamGlow(c, ax, hx, ly, Ui.px(112));
            drawLogo(c, game.away.logo, ax, ly, logo);
            drawLogo(c, game.home.logo, hx, ly, logo);
            text(600, 23, 0xFFFFFFFF, Paint.Align.CENTER);
            draw(c, game.away.label(), ax, Ui.px(222), column);
            draw(c, game.home.label(), hx, Ui.px(222), column);
            drawScore(c, game.away, 48, ax, Ui.px(278), column);
            drawScore(c, game.home, 48, hx, Ui.px(278), column);
        } else {
            float ly = Ui.px(150), logo = Ui.px(108);
            teamGlow(c, ax, hx, ly, Ui.px(120));
            drawLogo(c, game.away.logo, ax, ly, logo);
            drawLogo(c, game.home.logo, hx, ly, logo);
            text(400, 30, 0x99FFFFFF, Paint.Align.CENTER);
            c.drawText("@", w / 2f, ly + Ui.px(10), t);
            text(600, 23, 0xFFFFFFFF, Paint.Align.CENTER);
            draw(c, game.away.label(), ax, Ui.px(248), column);
            draw(c, game.home.label(), hx, Ui.px(248), column);
            text(400, 17, 0xA6FFFFFF, Paint.Align.CENTER);
            if (!game.away.record.isEmpty()) c.drawText(game.away.record, ax, Ui.px(274), t);
            if (!game.home.record.isEmpty()) c.drawText(game.home.record, hx, Ui.px(274), t);
        }

        String when = game.live() || game.over() ? game.status : shortStart(game.start);
        text(600, 23, 0xFFFFFFFF, Paint.Align.CENTER);
        draw(c, when, w / 2f, Ui.px(334), w - 2 * pad);
        String network = game.network();
        if (!network.isEmpty()) chip(c, network.toUpperCase(), w / 2f, Ui.px(370), true);
        if (!game.venue.isEmpty()) {
            text(400, 16, 0x99FFFFFF, Paint.Align.CENTER);
            draw(c, game.venue, w / 2f, Ui.px(430), w - 2 * pad);
        }
    }

    /** "Sun, Oct 25  ·  1:00 PM". */
    private static String shortStart(long millis) {
        ZonedDateTime z = Clock.at(millis);
        return z.format(DateTimeFormatter.ofPattern("EEE, MMM d  ·  h:mm a", Locale.US));
    }

    private void drawGameSmall(Canvas c, float s) {
        float pad = Ui.px(16);
        caps(700, 11, game != null && game.live() ? 0xFFFF453A : 0xCCFFFFFF, Paint.Align.LEFT);
        c.drawText(gameHeader(), pad, Ui.px(30), t);
        if (game == null) {
            text(400, 14, 0xA6FFFFFF, Paint.Align.LEFT);
            c.drawText("None coming up", pad, Ui.px(100), t);
            return;
        }
        caps(600, 10, 0x99FFFFFF, Paint.Align.RIGHT);
        c.drawText(game.league.name.length() <= 4 ? game.league.name : "", s - pad, Ui.px(30), t);
        float ax = s * 0.27f, hx = s * 0.73f;
        if (game.live() || game.over()) {
            // Each score under its team's logo: between them there is no room for one
            float ly = Ui.px(64), logo = Ui.px(44);
            teamGlow(c, ax, hx, ly, Ui.px(56));
            drawLogo(c, game.away.logo, ax, ly, logo);
            drawLogo(c, game.home.logo, hx, ly, logo);
            drawScore(c, game.away, 26, ax, Ui.px(118), s * 0.44f);
            drawScore(c, game.home, 26, hx, Ui.px(118), s * 0.44f);
        } else {
            float ly = Ui.px(84), logo = Ui.px(56);
            teamGlow(c, ax, hx, ly, Ui.px(64));
            drawLogo(c, game.away.logo, ax, ly, logo);
            drawLogo(c, game.home.logo, hx, ly, logo);
            text(400, 16, 0x99FFFFFF, Paint.Align.CENTER);
            c.drawText("@", s / 2f, ly + Ui.px(6), t);
        }
        String when = game.live() || game.over() ? game.status : Sports.startText(game.start);
        text(600, 15, 0xFFFFFFFF, Paint.Align.CENTER);
        draw(c, when, s / 2f, Ui.px(146), s - 2 * pad);
        String network = game.network();
        if (!network.isEmpty()) chip(c, network.toUpperCase(), s / 2f, Ui.px(168), false);
    }

    private void chip(Canvas c, String text, float cx, float cy, boolean large) {
        caps(700, large ? 13 : 10, 0xFFFFFFFF, Paint.Align.CENTER);
        t.setLetterSpacing(0.06f);
        float w = t.measureText(text) + Ui.px(large ? 18 : 12), h = Ui.px(large ? 26 : 20);
        box.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
        fill.setColor(0x40FFFFFF);
        c.drawRoundRect(box, Ui.px(7), Ui.px(7), fill);
        Paint.FontMetrics fm = t.getFontMetrics();
        c.drawText(text, cx, cy - (fm.ascent + fm.descent) / 2f, t);
    }

    /** A team's score, centred in its column (smaller, should it ever be too wide); the loser's dimmed once it's over. */
    private void drawScore(Canvas c, Sports.Team team, float size, float cx, float y, float maxWidth) {
        boolean lost = game.over() && !team.winner && (game.away.winner || game.home.winner);
        text(700, size, lost ? 0x8CFFFFFF : 0xFFFFFFFF, Paint.Align.CENTER);
        float width = t.measureText(team.score);
        if (width > maxWidth) t.setTextSize(t.getTextSize() * maxWidth / width);
        c.drawText(team.score, cx, y, t);
    }

    private void drawLogo(Canvas c, String url, float cx, float cy, float size) {
        Bitmap b = images.get(Sports.logo(url, 200), Math.round(size), true, invalidator);
        if (b == null) return;
        float sc = size / Math.max(b.getWidth(), b.getHeight());
        box.set(cx - b.getWidth() * sc / 2f, cy - b.getHeight() * sc / 2f, cx + b.getWidth() * sc / 2f, cy + b.getHeight() * sc / 2f);
        c.drawBitmap(b, null, box, art);
    }

    // ---- Date and sunset --------------------------------------------------------------------

    private void drawDateLarge(Canvas c, int w, int h) {
        ZonedDateTime now = Clock.at(Clock.now());
        float cx = w / 2f;
        caps(700, 22, 0xFFFF453A, Paint.Align.CENTER);
        c.drawText(now.format(DateTimeFormatter.ofPattern("EEEE", Locale.US)).toUpperCase(), cx, Ui.px(84), t);
        text(200, 168, 0xFFFFFFFF, Paint.Align.CENTER);
        t.setLetterSpacing(-0.03f);
        c.drawText(String.valueOf(now.getDayOfMonth()), cx, Ui.px(256), t);
        text(500, 27, 0xE6FFFFFF, Paint.Align.CENTER);
        c.drawText(now.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)), cx, Ui.px(306), t);
        if (weather == null) return;
        fill.setColor(0x2EFFFFFF);
        c.drawRect(Ui.px(30), Ui.px(340), w - Ui.px(30), Ui.px(340) + Math.max(1, Ui.px(1)), fill);
        float lx = w * 0.27f, rx = w * 0.73f;
        drawSunMark(c, lx, Ui.px(380), true);
        drawSunMark(c, rx, Ui.px(380), false);
        text(500, 15, 0x99FFFFFF, Paint.Align.CENTER);
        c.drawText("Sunrise", lx, Ui.px(414), t);
        c.drawText("Sunset", rx, Ui.px(414), t);
        text(600, 23, 0xFFFFFFFF, Paint.Align.CENTER);
        c.drawText(weather.sunrise, lx, Ui.px(444), t);
        c.drawText(weather.sunset, rx, Ui.px(444), t);
    }

    private void drawDateSmall(Canvas c, float s) {
        ZonedDateTime now = Clock.at(Clock.now());
        float left = Ui.px(18);
        caps(700, 12, 0xFFFF453A, Paint.Align.LEFT);
        c.drawText(now.format(DateTimeFormatter.ofPattern("EEEE", Locale.US)).toUpperCase(), left, Ui.px(32), t);
        text(300, 64, 0xFFFFFFFF, Paint.Align.LEFT);
        t.setLetterSpacing(-0.03f);
        c.drawText(String.valueOf(now.getDayOfMonth()), left - Ui.px(3), Ui.px(102), t);
        text(500, 16, 0xE6FFFFFF, Paint.Align.LEFT);
        c.drawText(now.format(DateTimeFormatter.ofPattern("MMMM", Locale.US)), left, Ui.px(130), t);
    }

    /** Today's sunrise above its sunset, each with its sun on the horizon. */
    private void drawSunSmall(Canvas c, float s) {
        float left = Ui.px(18), mark = Ui.px(38), label = Ui.px(66);
        caps(700, 12, 0xCCFFFFFF, Paint.Align.LEFT);
        c.drawText("SUN", left, Ui.px(32), t);
        String rise = weather == null ? "—" : weather.sunrise, set = weather == null ? "—" : weather.sunset;
        drawSunMark(c, mark, Ui.px(78), true);
        drawSunMark(c, mark, Ui.px(146), false);
        text(500, 13, 0x99FFFFFF, Paint.Align.LEFT);
        c.drawText("Sunrise", label, Ui.px(66), t);
        c.drawText("Sunset", label, Ui.px(134), t);
        text(600, 20, 0xFFFFFFFF, Paint.Align.LEFT);
        c.drawText(rise, label, Ui.px(90), t);
        c.drawText(set, label, Ui.px(158), t);
    }

    /** A small sun on a horizon line, with an arrow for rising or setting. */
    private void drawSunMark(Canvas c, float cx, float cy, boolean rising) {
        float rr = Ui.px(9);
        fill.setColor(0xFFF9D64A);
        c.drawCircle(cx, cy + Ui.px(2), rr, fill);
        fill.setColor(0x33000000);
        c.drawRect(cx - rr - Ui.px(4), cy + Ui.px(4), cx + rr + Ui.px(4), cy + rr + Ui.px(4), fill);
        fill.setColor(0xB3FFFFFF);
        c.drawRect(cx - Ui.px(18), cy + Ui.px(3), cx + Ui.px(18), cy + Ui.px(3) + Math.max(1, Ui.px(1.5f)), fill);
        path.reset();
        float ay = cy - Ui.px(14), d = Ui.px(5);
        if (rising) {
            path.moveTo(cx - d, ay + d / 2f);
            path.lineTo(cx, ay - d / 2f);
            path.lineTo(cx + d, ay + d / 2f);
        } else {
            path.moveTo(cx - d, ay - d / 2f);
            path.lineTo(cx, ay + d / 2f);
            path.lineTo(cx + d, ay - d / 2f);
        }
        stroke.setStrokeWidth(Math.max(1.5f, Ui.px(2)));
        stroke.setColor(0xCCFFFFFF);
        c.drawPath(path, stroke);
    }

    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

    {
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
    }
}
