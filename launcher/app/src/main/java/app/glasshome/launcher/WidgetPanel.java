package app.glasshome.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
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
 * - Smart Stack: one card that turns over every ten seconds between the weather card, your
 *   next game, Continue Watching and the date. Right turns it by hand while it has focus.
 * - Glances: four small squares (date and sunset, weather, next game, Continue Watching), each
 *   focused in turn with the arrows.
 *
 * It is one focus target that keeps its own position, moved by HomeActivity. Everything shown
 * is real: the weather from Open-Meteo, games from ESPN, Continue Watching from what played here;
 * a widget with nothing to show says so rather than making something up.
 */
final class WidgetPanel extends View {
    static final String STACK = "stack", GLANCES = "glances";
    static final long STALE_MS = 90 * 60 * 1000L;

    enum Kind { WEATHER, GAME, CONTINUE, DATE }

    private static final Kind[] GLANCE_ORDER = {Kind.DATE, Kind.WEATHER, Kind.GAME, Kind.CONTINUE};

    /** Tests switch the timed turning off, so each render shows a known widget. */
    static boolean rotateEnabled = true;
    private static final long ROTATE_MS = 10_000, TURN_MS = 600;
    private static final int CARD_R = 34, SQUARE = 190, GAP = 25, SQUARE_R = 30;
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
    private final Matrix matrix = new Matrix();
    private final Path path = new Path();
    private final Context context;

    private Weather.Data weather;
    private boolean weatherFailed;
    private Sports.Game game;
    private WatchHistory.Entry watching;
    private String layout = STACK;

    // Smart Stack: the page shown, and the one it is turning from
    private int page, fromPage = -1;
    private float turn = 1f;
    private ValueAnimator turnAnim;
    // Glances: the focused square, eased in
    private int square;
    private float squareT = 1f;
    private ValueAnimator squareAnim;

    private final Runnable rotate = new Runnable() {
        @Override
        public void run() {
            if (STACK.equals(layout) && !isFocused() && pages().size() > 1) turnTo((page + 1) % pages().size());
            schedule();
        }
    };

    WidgetPanel(Context context, Glass glass) {
        super(context);
        this.context = context;
        this.glass = glass;
        images = ImageCache.get(context);
        setFocusable(true);
        setDefaultFocusHighlightEnabled(false);
        setContentDescription("Widgets");
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                if (STACK.equals(layout)) outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), Ui.px(CARD_R));
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
    }

    // ---- Content ----------------------------------------------------------------------------

    void setLayout(String l) {
        String next = GLANCES.equals(l) ? GLANCES : STACK;
        if (next.equals(layout)) return;
        layout = next;
        page = 0;
        fromPage = -1;
        turn = 1f;
        setScaleX(1f);
        setScaleY(1f);
        invalidateOutline();
        schedule();
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
        Kind shown = currentKind();
        game = g;
        keepPage(shown);
        invalidate();
    }

    Sports.Game game() {
        return game;
    }

    /** The most recent unfinished title watched on this TV, or null. */
    void setWatching(WatchHistory.Entry e) {
        Kind shown = currentKind();
        watching = e;
        keepPage(shown);
        invalidate();
    }

    WatchHistory.Entry watching() {
        return watching;
    }

    /** The Smart Stack's pages: only widgets with something to show (weather and the date always do). */
    private List<Kind> pages() {
        List<Kind> list = new ArrayList<>();
        list.add(Kind.WEATHER);
        if (game != null) list.add(Kind.GAME);
        if (watching != null) list.add(Kind.CONTINUE);
        list.add(Kind.DATE);
        return list;
    }

    private Kind currentKind() {
        List<Kind> p = pages();
        return p.get(Math.min(page, p.size() - 1));
    }

    /** When pages come and go, the stack stays on the widget it was showing. */
    private void keepPage(Kind shown) {
        int i = pages().indexOf(shown);
        page = i >= 0 ? i : 0;
        fromPage = -1;
        turn = 1f;
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
            // Turning the stack by hand
            int n = pages().size();
            if (n > 1) turnTo((page + 1) % n);
            return true;
        }
        return false;
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

    private void turnTo(int next) {
        if (next == page) return;
        fromPage = page;
        page = next;
        if (turnAnim != null) turnAnim.cancel();
        if (Ui.reducedMotion) {
            turn = 1f;
            fromPage = -1;
            invalidate();
            return;
        }
        turn = 0f;
        turnAnim = ValueAnimator.ofFloat(0f, 1f);
        turnAnim.setDuration(TURN_MS);
        turnAnim.setInterpolator(Ui.EASE);
        turnAnim.addUpdateListener(a -> {
            turn = (float) a.getAnimatedValue();
            if (turn >= 1f) fromPage = -1;
            invalidate();
        });
        turnAnim.start();
    }

    private void schedule() {
        removeCallbacks(rotate);
        if (STACK.equals(layout) && rotateEnabled && !Ui.reducedMotion && isAttachedToWindow() && getVisibility() == VISIBLE) {
            postDelayed(rotate, ROTATE_MS);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        schedule();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(rotate);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        schedule();
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
        schedule();
        invalidate();
    }

    // ---- Drawing ----------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        if (GLANCES.equals(layout)) {
            drawGlances(c);
            return;
        }
        int w = getWidth(), h = getHeight();
        List<Kind> p = pages();
        if (fromPage >= 0 && fromPage < p.size() && turn < 1f) {
            drawCard(c, p.get(fromPage), w, h);
            int layer = c.saveLayerAlpha(0, 0, w, h, Math.round(255 * turn));
            drawCard(c, currentKind(), w, h);
            c.restoreToCount(layer);
        } else {
            drawCard(c, currentKind(), w, h);
        }
        if (isFocused()) {
            float in = edge.getStrokeWidth() / 2f;
            r.set(in, in, w - in, h - in);
            c.drawRoundRect(r, Ui.px(CARD_R) - in, Ui.px(CARD_R) - in, edge);
        }
        if (p.size() > 1) drawDots(c, p.size(), w, h);
    }

    /** One full-size Smart Stack card. */
    private void drawCard(Canvas c, Kind kind, int w, int h) {
        r.set(0, 0, w, h);
        float radius = Ui.px(CARD_R);
        glass.draw(c, r, radius, getLeft(), getTop(), 0f, tintFor(kind, w, h));
        switch (kind) {
            case WEATHER:
                drawWeatherLarge(c, w, h);
                break;
            case GAME:
                drawGameLarge(c, w, h);
                break;
            case CONTINUE:
                drawContinueLarge(c, w, h);
                break;
            default:
                drawDateLarge(c, w, h);
                break;
        }
    }

    private void drawDots(Canvas c, int n, int w, int h) {
        float d = Ui.px(7), gap = Ui.px(9), x = w - Ui.px(16);
        float total = n * d + (n - 1) * gap, y = h / 2f - total / 2f;
        for (int i = 0; i < n; i++) {
            fill.setColor(i == page ? 0xF2FFFFFF : 0x59FFFFFF);
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
        glass.draw(c, r, radius, getLeft() + x, getTop() + y, 0f, tintFor(kind, s, s));
        switch (kind) {
            case WEATHER:
                drawWeatherSmall(c, s);
                break;
            case GAME:
                drawGameSmall(c, s);
                break;
            case CONTINUE:
                drawContinueSmall(c, s);
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

    /** The card's colour: the weather's own tint, team colours for a game, quiet graphite otherwise. */
    private Paint tintFor(Kind kind, float w, float h) {
        int top, bottom;
        if (kind == Kind.WEATHER && weather != null) {
            int[] tc = tintColors(weather);
            top = tc[0];
            bottom = tc[1];
        } else if (kind == Kind.DATE) {
            top = 0xA6262B3A;
            bottom = 0xC7161A24;
        } else {
            top = 0xA6242730;
            bottom = 0xCC121419;
        }
        tint.setShader(new LinearGradient(0, 0, 0, h, top, bottom, Shader.TileMode.CLAMP));
        return tint;
    }

    /**
     * Top and bottom tint for the conditions. Translucent so the blurred backdrop still shows
     * through, and darker than the source colours so white text stays readable: sky blue when
     * clear by day, grey when cloudy or wet (after Jack Labbe's weather-app-clone, MIT), and the
     * mockup's slate blue at night.
     */
    static int[] tintColors(Weather.Data d) {
        if (d == null || !d.day) return new int[] {0xB3364663, 0xC71E2638};
        if (d.code <= 1) return new int[] {0xCC6F86D6, 0xD9004FB8};
        return new int[] {0xCC7D8792, 0xD9434B55};
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
        float ax = w * 0.27f, hx = w * 0.73f, ly = Ui.px(150), logo = Ui.px(108);
        teamGlow(c, ax, hx, ly, Ui.px(120));
        drawLogo(c, game.away.logo, ax, ly, logo);
        drawLogo(c, game.home.logo, hx, ly, logo);
        if (game.live() || game.over()) {
            text(700, 44, 0xFFFFFFFF, Paint.Align.CENTER);
            c.drawText(game.away.score + "–" + game.home.score, w / 2f, ly + Ui.px(16), t);
        } else {
            text(400, 30, 0x99FFFFFF, Paint.Align.CENTER);
            c.drawText("@", w / 2f, ly + Ui.px(10), t);
        }
        text(600, 23, 0xFFFFFFFF, Paint.Align.CENTER);
        draw(c, game.away.label(), ax, Ui.px(248), w * 0.44f);
        draw(c, game.home.label(), hx, Ui.px(248), w * 0.44f);
        text(400, 17, 0xA6FFFFFF, Paint.Align.CENTER);
        if (!game.away.record.isEmpty()) c.drawText(game.away.record, ax, Ui.px(274), t);
        if (!game.home.record.isEmpty()) c.drawText(game.home.record, hx, Ui.px(274), t);

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
        float ax = s * 0.27f, hx = s * 0.73f, ly = Ui.px(84), logo = Ui.px(56);
        teamGlow(c, ax, hx, ly, Ui.px(64));
        drawLogo(c, game.away.logo, ax, ly, logo);
        drawLogo(c, game.home.logo, hx, ly, logo);
        if (game.live() || game.over()) {
            text(700, 20, 0xFFFFFFFF, Paint.Align.CENTER);
            c.drawText(game.away.score + "–" + game.home.score, s / 2f, ly + Ui.px(7), t);
        } else {
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

    private void drawLogo(Canvas c, String url, float cx, float cy, float size) {
        Bitmap b = images.get(Sports.logo(url, 200), Math.round(size), true, invalidator);
        if (b == null) return;
        float sc = size / Math.max(b.getWidth(), b.getHeight());
        box.set(cx - b.getWidth() * sc / 2f, cy - b.getHeight() * sc / 2f, cx + b.getWidth() * sc / 2f, cy + b.getHeight() * sc / 2f);
        c.drawBitmap(b, null, box, art);
    }

    // ---- Continue Watching ------------------------------------------------------------------

    private void drawContinueLarge(Canvas c, int w, int h) {
        float pad = Ui.px(24);
        caps(700, 14, 0xCCFFFFFF, Paint.Align.LEFT);
        c.drawText("CONTINUE WATCHING", pad, Ui.px(44), t);
        if (watching == null) return;
        float aw = w - 2 * Ui.px(20);
        box.set(Ui.px(20), Ui.px(64), Ui.px(20) + aw, Ui.px(64) + aw * 9f / 16f);
        drawArtwork(c, box, Ui.px(18), Math.round(aw));
        text(600, 26, 0xFFFFFFFF, Paint.Align.LEFT);
        draw(c, watching.name(), pad, box.bottom + Ui.px(50), w - 2 * pad);
        text(400, 19, 0xB3FFFFFF, Paint.Align.LEFT);
        draw(c, watching.detail(), pad, box.bottom + Ui.px(82), w - 2 * pad);
        text(500, 17, 0x8CFFFFFF, Paint.Align.LEFT);
        c.drawText(Brand.of(watching.pkg, watching.pkg).name, pad, box.bottom + Ui.px(112), t);
    }

    private void drawContinueSmall(Canvas c, float s) {
        float pad = Ui.px(14);
        if (watching == null) {
            caps(700, 11, 0xCCFFFFFF, Paint.Align.LEFT);
            c.drawText("CONTINUE", Ui.px(16), Ui.px(30), t);
            float cx = s / 2f, cy = Ui.px(92), ring = Ui.px(22);
            rim.setColor(0x80FFFFFF);
            rim.setStrokeWidth(Math.max(1.5f, Ui.px(2)));
            c.drawCircle(cx, cy, ring, rim);
            rim.setStrokeWidth(Math.max(1f, Ui.px(1)));
            rim.setColor(0x24FFFFFF);
            path.reset();
            path.moveTo(cx - Ui.px(6), cy - Ui.px(9));
            path.lineTo(cx + Ui.px(10), cy);
            path.lineTo(cx - Ui.px(6), cy + Ui.px(9));
            path.close();
            fill.setColor(0x99FFFFFF);
            c.drawPath(path, fill);
            text(400, 14, 0xA6FFFFFF, Paint.Align.CENTER);
            c.drawText("Nothing yet", cx, Ui.px(150), t);
            return;
        }
        float aw = s - 2 * Ui.px(12);
        box.set(Ui.px(12), Ui.px(12), Ui.px(12) + aw, Ui.px(12) + aw * 9f / 16f);
        drawArtwork(c, box, Ui.px(16), Math.round(aw));
        text(600, 16, 0xFFFFFFFF, Paint.Align.LEFT);
        draw(c, watching.name(), pad, box.bottom + Ui.px(30), s - 2 * pad);
        text(400, 13, 0xB3FFFFFF, Paint.Align.LEFT);
        draw(c, watching.detail(), pad, box.bottom + Ui.px(52), s - 2 * pad);
    }

    /** The title's artwork (or its app's colours and tile), with a progress bar along the bottom. */
    private void drawArtwork(Canvas c, RectF area, float radius, int width) {
        String url = watching.backdrop.isEmpty() ? null : Tmdb.IMG + "w780" + watching.backdrop;
        Bitmap b = url == null ? null : images.get(url, width, false, invalidator);
        RectF a = new RectF(area);
        if (b != null) {
            BitmapShader shader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float sc = Math.max(a.width() / b.getWidth(), a.height() / b.getHeight());
            matrix.setScale(sc, sc);
            matrix.postTranslate(a.left + (a.width() - b.getWidth() * sc) / 2f, a.top + (a.height() - b.getHeight() * sc) / 2f);
            shader.setLocalMatrix(matrix);
            art.setShader(shader);
            c.drawRoundRect(a, radius, radius, art);
            art.setShader(null);
        } else {
            Brand brand = Brand.of(watching.pkg, watching.pkg);
            fill.setShader(new LinearGradient(a.left, a.top, a.right, a.bottom, brand.start, brand.end, Shader.TileMode.CLAMP));
            c.drawRoundRect(a, radius, radius, fill);
            fill.setShader(null);
            text(700, 22, 0xE6FFFFFF, Paint.Align.CENTER);
            c.drawText(brand.name, a.centerX(), a.centerY() + Ui.px(8), t);
        }
        fill.setShader(new LinearGradient(0, a.bottom - a.height() * 0.4f, 0, a.bottom, 0x00000000, 0x8C000000, Shader.TileMode.CLAMP));
        c.drawRoundRect(a, radius, radius, fill);
        fill.setShader(null);
        float progress = watching.progress();
        if (progress >= 0f) {
            float pad = Ui.px(12), bh = Ui.px(5), y = a.bottom - Ui.px(12) - bh;
            RectF bar = new RectF(a.left + pad, y, a.right - pad, y + bh);
            fill.setColor(0x59FFFFFF);
            c.drawRoundRect(bar, bh / 2f, bh / 2f, fill);
            bar.right = bar.left + Math.max(bh, (a.width() - 2 * pad) * progress);
            fill.setColor(0xFFFFFFFF);
            c.drawRoundRect(bar, bh / 2f, bh / 2f, fill);
        }
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
        if (weather != null) {
            text(400, 14, 0xBFFFFFFF, Paint.Align.LEFT);
            c.drawText("Sunset " + weather.sunset, left, Ui.px(168), t);
        }
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
