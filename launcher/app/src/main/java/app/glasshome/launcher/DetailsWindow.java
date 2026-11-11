package app.glasshome.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * A Continue Watching title's details, from holding Select on its card (Resume, Details, Remove): its poster, what
 * TMDB says of it (year, length or seasons, genres, tagline, rating, story), where FirePlayer plays it from, and for a
 * show the episode it was left on. The same facts FirePlayer's own details give, on the same glass card as the
 * detailed forecast. Resume plays on where it was left; for a show FirePlayer can list, Episodes opens every season
 * and episode to pick from ({@link EpisodesWindow}).
 *
 * It opens at once with what the card knew and fills in as TMDB answers (usually at once, from the day's disk cache).
 * Focus stays on the card underneath, which stays raised for the way back (see {@link GlassWindow}). Left and Right
 * move between its buttons.
 */
final class DetailsWindow extends GlassWindow {
    static final String LOADING = "Loading details…";
    /** What is laid out on the card, in design px (1920 x 1080). */
    private static final float POSTER_L = 264, POSTER_T = 174, POSTER_R = 564, POSTER_B = 624;
    private static final float TEXT_L = 628, TEXT_R = 1656;
    private static final float FACTS_TOP = 668, FACT_PITCH = 78, FACT_VALUE = 36;
    private static final float BUTTONS_BOTTOM = 906;
    /** How long the text and facts take to come in, once TMDB has answered. */
    private static final int FADE_MS = 180;

    private final FeaturePainter painter;
    private final ImageCache images;
    private final Pictures pictures = new Pictures();
    private final Runnable invalidator = this::invalidate;
    private final Paint panelFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panelRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint posterRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgeRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint star = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path starPath = new Path();
    private final TextPaint title, meta, genres, tagline, body, statusInk, caption, badge, rating, source, posterTitle,
            epWhere, epName, epFacts, epStory, stillLabel, left;
    private final RectF rect = new RectF();
    private final List<PageModel.Action> actions = new ArrayList<>();

    private ContinueWatching.Item item;
    private Tmdb.About about;
    /** What stands in the story's place while there are no details: loading, or why there are none. */
    private String status = LOADING;
    /** The episode it was left on, as FirePlayer (or TMDB) has it; null for what the entry says of it. */
    private ContinueWatching.Episode episode;
    /** TMDB has answered (or couldn't): the facts under the poster show, Source included, so nothing moves after. */
    private boolean answered;
    private int button;
    private float fade = 1f;
    private ValueAnimator fadeAnim;
    private StaticLayout bodyLayout, posterLayout, storyLayout;
    private String bodyText, posterText, storyText;
    private int bodyLines;

    DetailsWindow(Context context, Glass glass) {
        super(context, glass);
        painter = new FeaturePainter(context);
        images = ImageCache.get(context);
        panelFill.setColor(0x26000000);
        panelRim.setStyle(Paint.Style.STROKE);
        panelRim.setStrokeWidth(Math.max(1f, Ui.px(1)));
        panelRim.setColor(0x24FFFFFF);
        posterRim.setStyle(Paint.Style.STROKE);
        posterRim.setStrokeWidth(Math.max(1f, Ui.px(1.5f)));
        posterRim.setColor(0x24FFFFFF);
        badgeRim.setStyle(Paint.Style.STROKE);
        badgeRim.setStrokeWidth(Math.max(1f, Ui.px(1.5f)));
        badgeRim.setColor(0x99FFFFFF);
        star.setColor(0xFFFFD60A);
        title = text(context, 700, 56, 0xFFFFFFFF);
        title.setLetterSpacing(-0.02f);
        meta = text(context, 500, 24, 0xCCFFFFFF);
        genres = text(context, 500, 22, 0x8CFFFFFF);
        tagline = text(context, 500, 26, 0xF2FFFFFF);
        body = text(context, 400, 24, 0xD9FFFFFF);
        statusInk = text(context, 500, 24, 0x99FFFFFF);
        caption = caption(context);
        badge = text(context, 700, 20, 0xFFFFFFFF);
        badge.setTextAlign(Paint.Align.CENTER);
        rating = text(context, 500, 26, 0xFFFFFFFF);
        source = text(context, 500, 24, 0xFFFFFFFF);
        posterTitle = text(context, 600, 26, 0x99FFFFFF);
        epWhere = text(context, 600, 24, ACCENT);
        epName = text(context, 600, 24, 0xFFFFFFFF);
        epFacts = text(context, 500, 19, 0x99FFFFFF);
        epStory = text(context, 400, 20, 0xCCFFFFFF);
        stillLabel = text(context, 600, 28, 0x80FFFFFF);
        stillLabel.setTextAlign(Paint.Align.CENTER);
        left = text(context, 500, 21, 0x99FFFFFF);
    }

    /** Control Center's blue, on glass; on white (a focused row) it is {@link #ACCENT_ON_WHITE}. */
    static final int ACCENT = 0xFF4DB0FF, ACCENT_ON_WHITE = 0xFF0088FF;

    static TextPaint text(Context c, int weight, float size, int color) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Fonts.inter(c, weight));
        p.setTextSize(Ui.px(size));
        p.setColor(color);
        return p;
    }

    /** The small spaced capitals over a fact or a section ("RATED", "THIS EPISODE"), as on the detailed forecast. */
    static TextPaint caption(Context c) {
        TextPaint p = text(c, 600, 15, 0xA6FFFFFF);
        p.setLetterSpacing(0.14f);
        return p;
    }

    // ---- What it shows ----------------------------------------------------------------------

    /**
     * Opens on {@code item} with what its card knows (the title, its year and kind, the episode, the time left), the
     * story's place saying it is loading; Resume has focus. {@code canListEpisodes}: an Episodes button (a show
     * FirePlayer can list), there from the start so nothing comes in beside Resume later.
     */
    void show(ContinueWatching.Item item, boolean canListEpisodes) {
        this.item = item;
        about = null;
        status = LOADING;
        episode = null;
        answered = false;
        button = 0;
        if (fadeAnim != null) fadeAnim.cancel();
        fade = 0f;
        actions.clear();
        actions.add(new PageModel.Action("Resume", null, false));
        if (canListEpisodes && item.episode) actions.add(new PageModel.Action("Episodes", null, true));
        invalidate();
    }

    /** TMDB's details: the poster, the facts and the story come in. */
    void setAbout(Tmdb.About a) {
        about = a;
        status = null;
        reveal();
    }

    /** No details to show ({@code message} says why, in the story's place); the facts that are known come in. */
    void setStatus(String message) {
        about = null;
        status = message;
        reveal();
    }

    /** The episode it was left on, as FirePlayer lists it (or TMDB has it, when FirePlayer can't say). */
    void setEpisode(ContinueWatching.Episode e) {
        episode = e;
        invalidate();
    }

    /** FirePlayer's list came again: the same entry, with where it was left now. */
    void setItem(ContinueWatching.Item fresh) {
        if (item == null || fresh == null || !item.id.equals(fresh.id)) return;
        item = fresh;
        invalidate();
    }

    void setButton(int i) {
        button = Math.max(0, Math.min(i, actions.size() - 1));
        invalidate();
    }

    private void reveal() {
        answered = true;
        if (fadeAnim != null) fadeAnim.cancel();
        if (Ui.reducedMotion || !isOpen() || fade >= 1f) {
            fade = 1f;
            invalidate();
            return;
        }
        fadeAnim = ValueAnimator.ofFloat(fade, 1f).setDuration(FADE_MS);
        fadeAnim.setInterpolator(Ui.EASE);
        fadeAnim.addUpdateListener(an -> {
            fade = (float) an.getAnimatedValue();
            invalidate();
        });
        fadeAnim.start();
    }

    void left() {
        if (button > 0) setButton(button - 1);
    }

    void right() {
        if (button < actions.size() - 1) setButton(button + 1);
    }

    int focusedButton() {
        return isOpen() ? button : -1;
    }

    String[] buttons() {
        String[] out = new String[actions.size()];
        for (int i = 0; i < out.length; i++) out[i] = actions.get(i).label;
        return out;
    }

    /** What stands in the story's place, or null when TMDB's details are shown. */
    String status() {
        return status;
    }

    ContinueWatching.Item item() {
        return item;
    }

    /** A FirePlayer episode is on its "This episode" (not TMDB's, nor only what the entry says). */
    boolean hasListedEpisode() {
        return episode != null && !episode.id.isEmpty();
    }

    /** "2023  ·  Movie  ·  2 hr 5 min" with TMDB's details; until then the entry's year (if known) and kind. */
    String meta() {
        if (about != null) return about.facts();
        String kind = item.episode ? "TV Show" : "Movie";
        return item.year > 0 ? item.year + "  ·  " + kind : kind;
    }

    String tagline() {
        return about == null ? "" : about.tagline;
    }

    String genreLine() {
        return about == null ? "" : String.join(", ", about.genres);
    }

    /** The facts under the poster, each {caption, value}; one with nothing to say is left out. Empty until TMDB answered. */
    List<String[]> facts() {
        List<String[]> out = new ArrayList<>();
        if (!answered || item == null) return out;
        if (about != null && !about.ageRating.isEmpty()) out.add(new String[] {"RATED", about.ageRating});
        if (about != null && about.rating > 0) out.add(new String[] {"TMDB", about.ratingText()});
        if (item.source != null) out.add(new String[] {"SOURCE", item.source});
        return out;
    }

    /** "S24 E5  ·  Hard Cases" on its "This episode", or null when it has none (a film, or nothing known of it). */
    String episodeLine() {
        if (item == null || !item.episode) return null;
        String where = ContinueWatching.where(episodeSeason(), episodeNumber()), name = episodeName();
        if (where.isEmpty() && name.isEmpty()) return null;
        if (where.isEmpty()) return name;
        return name.isEmpty() ? where : where + "  ·  " + name;
    }

    /** "Oct 3, 2024  ·  42 min" under it; "" when neither is known. */
    String episodeFacts() {
        return episode == null ? "" : episodeFacts(episode);
    }

    private int episodeSeason() {
        return episode != null ? episode.season : item.season;
    }

    private int episodeNumber() {
        return episode != null ? episode.number : item.number;
    }

    private String episodeName() {
        if (episode != null && !episode.name.isEmpty()) return episode.name;
        return item.episodeTitle == null ? "" : item.episodeTitle.trim();
    }

    // ---- Opening and closing ----------------------------------------------------------------

    /** Another app is in front: the pictures' shaders are made again when next drawn. */
    void trimMemory() {
        pictures.release();
        painter.release();
    }

    // ---- Drawing ----------------------------------------------------------------------------

    @Override
    protected void drawContent(Canvas c) {
        if (item == null) return;
        drawPoster(c);
        drawFacts(c);
        boolean card = drawEpisode(c);
        drawText(c, card);
        drawButtons(c);
    }

    private void drawPoster(Canvas c) {
        rect.set(Ui.px(POSTER_L), Ui.px(POSTER_T), Ui.px(POSTER_R), Ui.px(POSTER_B));
        float r = Ui.px(18);
        Bitmap b = about == null || about.poster == null ? null : images.get(about.poster, Ui.px(300), false, invalidator);
        if (b != null) {
            pictures.image(c, b, rect, r);
        } else {
            // Graphite with the title, until (or unless) TMDB's poster is there
            pictures.placeholder(c, rect, r);
            String name = about != null ? about.name : item.title;
            int w = Math.round(rect.width() - Ui.px(48));
            if (!name.equals(posterText) || posterLayout == null || posterLayout.getWidth() != w) {
                posterText = name;
                posterLayout = StaticLayout.Builder.obtain(name, 0, name.length(), posterTitle, w)
                        .setMaxLines(3).setEllipsize(TextUtils.TruncateAt.END)
                        .setAlignment(Layout.Alignment.ALIGN_CENTER).build();
            }
            c.save();
            c.translate(rect.left + Ui.px(24), rect.centerY() - posterLayout.getHeight() / 2f);
            posterLayout.draw(c);
            c.restore();
        }
        float inset = posterRim.getStrokeWidth() / 2f;
        rect.inset(inset, inset);
        c.drawRoundRect(rect, r - inset, r - inset, posterRim);
    }

    /** Rated, TMDB and Source under the poster, a caption over each; those without a value are left out. */
    private void drawFacts(Canvas c) {
        int a = Math.round(255 * fade);
        if (a <= 0) return;
        float x = Ui.px(POSTER_L), y = Ui.px(FACTS_TOP), width = Ui.px(POSTER_R - POSTER_L);
        for (String[] f : facts()) {
            setAlpha(caption, 0xA6FFFFFF, a);
            c.drawText(f[0], x, y, caption);
            float baseline = y + Ui.px(FACT_VALUE), cy = baseline - Ui.px(9);
            switch (f[0]) {
                case "RATED": {
                    float w = badge.measureText(f[1]) + 2 * Ui.px(10), h = badge.getTextSize() + 2 * Ui.px(4);
                    rect.set(x, cy - h / 2f, x + w, cy + h / 2f);
                    badgeRim.setAlpha(Math.round(0x99 * a / 255f));
                    c.drawRoundRect(rect, Ui.px(6), Ui.px(6), badgeRim);
                    setAlpha(badge, 0xFFFFFFFF, a);
                    c.drawText(f[1], rect.centerX(), centred(badge, cy), badge);
                    break;
                }
                case "TMDB": {
                    float radius = Ui.px(11);
                    star.setAlpha(a);
                    c.drawPath(star(x + radius, cy, radius), star);
                    setAlpha(rating, 0xFFFFFFFF, a);
                    c.drawText(f[1], x + 2 * radius + Ui.px(8), baseline, rating);
                    break;
                }
                default:
                    setAlpha(source, 0xFFFFFFFF, a);
                    c.drawText(TextUtils.ellipsize(f[1], source, width, TextUtils.TruncateAt.END).toString(), x, baseline, source);
                    break;
            }
            y += Ui.px(FACT_PITCH);
        }
    }

    /** A five-pointed star, its points on a circle of {@code r} around (cx, cy). */
    private Path star(float cx, float cy, float r) {
        starPath.reset();
        for (int k = 0; k < 10; k++) {
            double angle = Math.toRadians(-90 + 36 * k);
            float radius = k % 2 == 0 ? r : r * 0.45f;
            float px = cx + (float) Math.cos(angle) * radius, py = cy + (float) Math.sin(angle) * radius;
            if (k == 0) starPath.moveTo(px, py);
            else starPath.lineTo(px, py);
        }
        starPath.close();
        return starPath;
    }

    /** Title, its facts, genres, tagline and story, top-down from the top of the card; {@code card}: an episode follows. */
    private void drawText(Canvas c, boolean card) {
        float x = Ui.px(TEXT_L), w = Ui.px(TEXT_R - TEXT_L);
        String name = about != null ? about.name : item.title;
        // A long title steps down in size before it is cut
        title.setTextSize(Ui.px(56));
        float tw = title.measureText(name);
        if (tw > w) title.setTextSize(Math.max(Ui.px(44), Ui.px(56) * w / tw));
        c.drawText(TextUtils.ellipsize(name, title, w, TextUtils.TruncateAt.END).toString(), x, Ui.px(224), title);
        c.drawText(TextUtils.ellipsize(meta(), meta, w, TextUtils.TruncateAt.END).toString(), x, Ui.px(268), meta);
        if (about == null) {
            if (status != null) c.drawText(TextUtils.ellipsize(status, statusInk, w, TextUtils.TruncateAt.END).toString(),
                    x, Ui.px(330) - statusInk.ascent(), statusInk);
            return;
        }
        int a = Math.round(255 * fade);
        if (a <= 0) return;
        String g = genreLine();
        if (!g.isEmpty()) {
            setAlpha(genres, 0x8CFFFFFF, a);
            c.drawText(TextUtils.ellipsize(g, genres, w, TextUtils.TruncateAt.END).toString(), x, Ui.px(302), genres);
        }
        float top = Ui.px(330);
        if (!about.tagline.isEmpty()) {
            setAlpha(tagline, 0xF2FFFFFF, a);
            c.drawText(TextUtils.ellipsize(about.tagline, tagline, w, TextUtils.TruncateAt.END).toString(), x, Ui.px(352), tagline);
            top = Ui.px(380);
        }
        // As many lines as fit above what follows: the episode, or the buttons
        int lines = card ? 4 : 9;
        StaticLayout layout = bodyLayout(about.overview, Math.round(w), lines);
        if (layout == null) return;
        setAlpha(body, 0xD9FFFFFF, a);
        c.save();
        c.translate(x, top);
        layout.draw(c);
        c.restore();
    }

    private StaticLayout bodyLayout(String text, int width, int lines) {
        if (text == null || text.isEmpty()) return null;
        if (!text.equals(bodyText) || bodyLayout == null || bodyLayout.getWidth() != width || lines != bodyLines) {
            bodyText = text;
            bodyLines = lines;
            bodyLayout = StaticLayout.Builder.obtain(text, 0, text.length(), body, width).setMaxLines(lines)
                    .setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0f, 1.25f)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
        }
        return bodyLayout;
    }

    /**
     * For a show, the episode it was left on, in a panel of its own just above the buttons (so it never moves): its
     * still with how far it was watched, "S24 E5  ·  Hard Cases", when it aired and how long it runs, and its story.
     * False when there is none to show.
     */
    private boolean drawEpisode(Canvas c) {
        String line = episodeLine();
        if (line == null) return false;
        float x = Ui.px(TEXT_L);
        caption.setColor(0xA6FFFFFF);
        c.drawText("THIS EPISODE", x, Ui.px(600), caption);
        rect.set(x, Ui.px(618), Ui.px(TEXT_R), Ui.px(802));
        c.drawRoundRect(rect, Ui.px(22), Ui.px(22), panelFill);
        c.drawRoundRect(rect, Ui.px(22), Ui.px(22), panelRim);

        rect.set(Ui.px(648), Ui.px(638), Ui.px(904), Ui.px(782));
        float r = Ui.px(14);
        String still = episode == null ? null : episode.still;
        Bitmap b = still == null ? null : images.get(still, Ui.px(256), false, invalidator);
        if (b != null) {
            pictures.image(c, b, rect, r);
        } else {
            pictures.placeholder(c, rect, r);
            int n = episodeNumber();
            if (n > 0) c.drawText("E" + n, rect.centerX(), centred(stillLabel, rect.centerY()), stillLabel);
        }
        pictures.progress(c, rect, r, item.progress());

        float tx = Ui.px(928), tw = Ui.px(704);
        String where = ContinueWatching.where(episodeSeason(), episodeNumber()), name = episodeName();
        String facts = episodeFacts(), story = episode == null ? "" : episode.overview;
        // Its line alone (all the entry knows of it) sits in the middle of the panel
        float y1 = facts.isEmpty() && story.isEmpty() ? centred(epName, Ui.px(710)) : Ui.px(676);
        if (!where.isEmpty()) {
            c.drawText(where, tx, y1, epWhere);
            if (!name.isEmpty()) {
                float ww = epWhere.measureText(where);
                c.drawText(TextUtils.ellipsize("  ·  " + name, epName, tw - ww, TextUtils.TruncateAt.END).toString(),
                        tx + ww, y1, epName);
            }
        } else {
            c.drawText(TextUtils.ellipsize(name, epName, tw, TextUtils.TruncateAt.END).toString(), tx, y1, epName);
        }
        float storyTop = Ui.px(690);
        if (!facts.isEmpty()) {
            c.drawText(TextUtils.ellipsize(facts, epFacts, tw, TextUtils.TruncateAt.END).toString(), tx, Ui.px(708), epFacts);
            storyTop = Ui.px(722);
        }
        if (!story.isEmpty()) {
            if (!story.equals(storyText) || storyLayout == null || storyLayout.getWidth() != Math.round(tw)) {
                storyText = story;
                storyLayout = StaticLayout.Builder.obtain(story, 0, story.length(), epStory, Math.round(tw)).setMaxLines(2)
                        .setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0f, 1.2f)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
            }
            c.save();
            c.translate(tx, storyTop);
            storyLayout.draw(c);
            c.restore();
        }
        return true;
    }

    /** Resume (and Episodes), with the time left beside them, quietly. */
    private void drawButtons(Canvas c) {
        float bottom = Ui.px(BUTTONS_BOTTOM);
        float end = painter.drawButtons(c, Ui.px(TEXT_L), bottom, actions, button);
        String timeLeft = item.timeLeft();
        if (!timeLeft.isEmpty()) c.drawText(timeLeft, end + Ui.px(28), centred(left, bottom - Ui.px(32)), left);
    }

    // ---- Shared with the episodes window ----------------------------------------------------

    /** The baseline that centres {@code p}'s text on {@code cy}. */
    static float centred(TextPaint p, float cy) {
        Paint.FontMetrics fm = p.getFontMetrics();
        return cy - (fm.ascent + fm.descent) / 2f;
    }

    /** {@code color}, at {@code alpha} (0-255) of its own alpha. */
    static void setAlpha(Paint p, int color, int alpha) {
        p.setColor(color);
        p.setAlpha(Math.round(Color.alpha(color) * alpha / 255f));
    }

    /** "Oct 3, 2024" from TMDB's "2024-10-03"; a date in another form as it is, "" for none. */
    static String airDate(String date) {
        if (date == null || date.trim().isEmpty()) return "";
        try {
            return LocalDate.parse(date.trim()).format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US));
        } catch (RuntimeException e) {
            return date.trim();
        }
    }

    /** "Oct 3, 2024  ·  42 min": when it aired and how long it runs, what is known of them; "" for neither. */
    static String episodeFacts(ContinueWatching.Episode e) {
        List<String> parts = new ArrayList<>();
        String aired = airDate(e.airDate);
        if (!aired.isEmpty()) parts.add(aired);
        if (e.runtimeMin > 0) parts.add(Tmdb.duration(e.runtimeMin));
        return String.join("  ·  ", parts);
    }

    /**
     * Pictures in rounded frames (centre-cropped, as the cards show artwork), their graphite stand-ins, and the
     * progress bar the Continue Watching cards have. Main thread.
     */
    static final class Pictures {
        private final Paint picture = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix matrix = new Matrix();
        private final Map<Bitmap, BitmapShader> shaders = new WeakHashMap<>();
        // Unit gradients, placed on each frame by their matrix: nothing is made as a list scrolls
        private final LinearGradient graphite = new LinearGradient(0, 0, 1, 1, 0xFF363A42, 0xFF1E2025, Shader.TileMode.CLAMP);
        private final LinearGradient shade = new LinearGradient(0, 0.55f, 0, 1, 0x00000000, 0x8C000000, Shader.TileMode.CLAMP);
        private final LinearGradient blue = new LinearGradient(0, 0, 0, 1, ACCENT, ACCENT_ON_WHITE, Shader.TileMode.CLAMP);
        private final RectF bar = new RectF();

        /** {@code b} filling {@code rect}, centre-cropped, its corners rounded. */
        void image(Canvas c, Bitmap b, RectF rect, float radius) {
            BitmapShader s = shaders.get(b);
            if (s == null) {
                s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                if (shaders.size() > 48) shaders.clear();
                shaders.put(b, s);
            }
            float k = Math.max(rect.width() / b.getWidth(), rect.height() / b.getHeight());
            matrix.setScale(k, k);
            matrix.postTranslate(rect.centerX() - b.getWidth() * k / 2f, rect.centerY() - b.getHeight() * k / 2f);
            s.setLocalMatrix(matrix);
            picture.setShader(s);
            c.drawRoundRect(rect, radius, radius, picture);
            picture.setShader(null);
        }

        /** The graphite a picture stands in on while it loads, or when there is none. */
        void placeholder(Canvas c, RectF rect, float radius) {
            matrix.setScale(rect.width(), rect.height());
            matrix.postTranslate(rect.left, rect.top);
            graphite.setLocalMatrix(matrix);
            fill.setColor(0xFF000000);   // opaque: a shader takes the paint's alpha
            fill.setShader(graphite);
            c.drawRoundRect(rect, radius, radius, fill);
            fill.setShader(null);
        }

        /**
         * How far it has been watched (0 to 1; nothing for less than 0), as on the cards: a soft shade at the foot, a
         * faint track and Control Center's blue.
         */
        void progress(Canvas c, RectF rect, float radius, float progress) {
            if (progress < 0f) return;
            matrix.setScale(1f, rect.height());
            matrix.postTranslate(0, rect.top);
            shade.setLocalMatrix(matrix);
            fill.setColor(0xFF000000);
            fill.setShader(shade);
            c.drawRoundRect(rect, radius, radius, fill);
            fill.setShader(null);
            float side = Ui.px(12), h = Ui.px(5), bottom = rect.bottom - Ui.px(10);
            bar.set(rect.left + side, bottom - h, rect.right - side, bottom);
            fill.setColor(0x59FFFFFF);
            c.drawRoundRect(bar, h / 2f, h / 2f, fill);
            bar.right = bar.left + Math.max(h, (rect.width() - 2 * side) * Math.min(1f, progress));
            matrix.setScale(1f, h);
            matrix.postTranslate(0, bar.top);
            blue.setLocalMatrix(matrix);
            fill.setColor(0xFF000000);
            fill.setShader(blue);
            c.drawRoundRect(bar, h / 2f, h / 2f, fill);
            fill.setShader(null);
        }

        void release() {
            shaders.clear();
        }
    }
}
