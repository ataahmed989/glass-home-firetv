package app.glasshome.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LightingColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One tvOS-style browse page (Movies, TV Shows, Sports): a full-screen hero carousel with the first
 * shelf peeking below it, then shelves of cards. Moving down scrolls the hero away and settles the
 * focused shelf near the top, while the hero's picture gives way to Home's own background, darkened;
 * moving along a shelf slides it. The whole page is one focus target that keeps its own position
 * (a hero button, or a shelf and card), moved by HomeActivity's remote handling.
 *
 * The page draws itself in parts, each a child view ({@link Layer}): the background, the hero's
 * text, and one per shelf. The GPU redraws only the parts that changed, so sliding a shelf redraws
 * that shelf's band of the screen rather than all of it, and scrolling moves the shelves without
 * drawing them again. Cards are drawn by hand rather than as a view each.
 */
final class ShelfPage extends ViewGroup {
    interface Listener {
        void onAction(ShelfPage page, PageModel.Feature feature, PageModel.Action action);

        void onCard(ShelfPage page, PageModel.Shelf shelf, PageModel.Card card);

        /** The hero's artwork changed, so the glass above it needs a new backdrop. */
        void onHeroShown(ShelfPage page);

        /** Focus moved into the shelves (the top bar steps aside) or back to the hero. */
        void onScrolled(ShelfPage page, boolean intoShelves);
    }

    // Geometry in design px (1920 wide)
    private static final int LEFT = 106, RIGHT = 1814;
    private static final int HERO_BOTTOM = 800;         // bottom of the hero's button row
    private static final int FIRST_ROW_TOP = 880;       // the first shelf peeks below the hero
    private static final int FOCUS_ROW_TOP = 170;       // a focused shelf settles here
    private static final int TITLE_H = 56, CAPTION_H = 78, ROW_GAP = 30;
    private static final int CARD_W = 372, CARD_H = 209, CARD_GAP = 32;
    private static final int POSTER_W = 222, POSTER_H = 333, RANK_W = 124, POSTER_GAP = 22;
    private static final int EMPTY_H = 150;
    private static final int RADIUS = 14;
    private static final float CARD_SCALE = 1.08f;
    private static final long HERO_EVERY_MS = 8000, HERO_FADE_MS = 650;
    /** Room around a shelf's own band for its focused card to grow into and cast its shadow. */
    private static final int ROW_MARGIN = 40;
    /** How far Home's background is darkened under the shelves. */
    private static final float SHELF_DIM = 0.6f;
    /** The parts of the page that are not shelves (shelves are numbered from 0). */
    private static final int BACKDROP = -2, HERO = -1;
    /** On Sports, Home's background shows through the dark side of a game's colours. */
    static boolean seeThroughGames = true;

    final String name;
    private final FeaturePainter painter;
    private final ImageCache images;
    private Listener listener;
    private PageModel model;
    private String message = "";
    private long loadedAt;
    private Map<String, Bitmap> appArt = Collections.emptyMap();

    // Focus: row -1 is the hero (button = its focused button); otherwise a shelf and its column
    private int row = -1, button;
    private int[] cols = new int[0];
    private float[] rowX = new float[0];
    private float[] rowTop = new float[0];   // screen px, before scrolling
    private float scrollY;
    private ValueAnimator scrollAnim, rowAnim, focusAnim, heroAnim;
    private int focusRow = Integer.MIN_VALUE, focusCol, prevRow = Integer.MIN_VALUE, prevCol;
    private float focusT, prevT;
    private int hero, heroFrom = -1;
    private float heroFade = 1f;
    private boolean intoShelves;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint art = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint shelfTitle = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint cardTitle = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint cardSub = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint rank = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint messagePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint emptyPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint credit = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint abbr = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint bigScore = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint chip = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Matrix matrix = new Matrix();
    /** Card pictures' shaders, for the cards drawn lately (a shader holds its picture, so the cache is kept small). */
    private static final int MAX_SHADERS = 48;
    private final Map<Bitmap, BitmapShader> shaders = new LinkedHashMap<Bitmap, BitmapShader>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Bitmap, BitmapShader> eldest) {
            return size() > MAX_SHADERS;
        }
    };
    private final Runnable invalidator = this::invalidateAll;

    // The parts, and what each was last drawn with, so that only parts that change are redrawn
    private final Layer backdropLayer, heroLayer;
    private Layer[] rows = new Layer[0];
    private boolean[] rowDrawn = new boolean[0];   // false: too far off screen to have been drawn
    private float drawnShown = -1f, drawnTextAlpha = -1f;

    // Home's own background, which the page settles on under the shelves
    private Bitmap home;
    private BitmapShader homeShader;
    private int homeShaderW, homeShaderH;
    private final Paint homePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint colourPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final ColorFilter[] dimFilters = new ColorFilter[256];
    private final Map<String, Bitmap> teamColours = new LinkedHashMap<>();
    private final Runnable advance = new Runnable() {
        @Override
        public void run() {
            if (model != null && model.hero.size() > 1 && !(isFocused() && row == -1)) showHero((hero + 1) % model.hero.size());
            schedule();
        }
    };

    ShelfPage(Context context, String name) {
        super(context);
        this.name = name;
        painter = new FeaturePainter(context);
        images = ImageCache.get(context);
        setFocusable(true);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setDefaultFocusHighlightEnabled(false);
        setContentDescription(name);
        setChildrenDrawingOrderEnabled(true);
        backdropLayer = new Layer(BACKDROP);
        heroLayer = new Layer(HERO);
        addView(backdropLayer);
        addView(heroLayer);
        FeaturePainter.style(shelfTitle, context, 600, 28, 0xF2FFFFFF);
        FeaturePainter.style(cardTitle, context, 500, 22, 0xF2FFFFFF);
        FeaturePainter.style(cardSub, context, 400, 19, 0x99FFFFFF);
        FeaturePainter.style(rank, context, 800, 250, 0xFFFFFFFF);
        rank.setTextAlign(Paint.Align.RIGHT);
        rank.setLetterSpacing(-0.06f);
        FeaturePainter.style(messagePaint, context, 500, 30, 0xB3FFFFFF);
        FeaturePainter.style(emptyPaint, context, 500, 23, 0xB3FFFFFF);
        FeaturePainter.style(credit, context, 400, 17, 0x73FFFFFF);
        FeaturePainter.style(abbr, context, 700, 22, 0xFFFFFFFF);
        abbr.setTextAlign(Paint.Align.CENTER);
        FeaturePainter.style(bigScore, context, 700, 44, 0xFFFFFFFF);
        bigScore.setTextAlign(Paint.Align.CENTER);
        FeaturePainter.style(chip, context, 700, 15, 0xFFFFFFFF);
        chip.setLetterSpacing(0.06f);
        shadow.setColor(0xFF000000);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1.5f, Ui.px(2f)));
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setAppArt(Map<String, Bitmap> art) {
        appArt = art;
        invalidateAll();
    }

    /** Another app is in front: gives back the pictures that are made again when next drawn. */
    void trimMemory() {
        painter.release();
        teamColours.clear();
        shaders.clear();
    }

    /** Home's own background, which the page settles on once the hero has scrolled away. */
    void setHomeBackground(Bitmap b) {
        if (b == home) return;
        home = b;
        homeShader = null;
        teamColours.clear();   // a game's colours are clear where this shows through
        backdropLayer.invalidate();
        if (listener != null && model != null) listener.onHeroShown(this);
    }

    // ---- Parts ------------------------------------------------------------------------------

    /** One part of the page, drawn by the page: the backdrop, the hero's text, or shelf {@code part} (0 and up). */
    private final class Layer extends View {
        final int part;

        Layer(int part) {
            super(ShelfPage.this.getContext());
            this.part = part;
        }

        @Override
        protected void onDraw(Canvas c) {
            if (part == BACKDROP) drawBackdrop(c);
            else if (part == HERO) drawHero(c);
            else drawRow(c, part);
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec), h = MeasureSpec.getSize(heightSpec);
        setMeasuredDimension(w, h);
        for (int i = 0; i < getChildCount(); i++) {
            Layer l = (Layer) getChildAt(i);
            int lh = l.part < 0 ? h : Math.round(rowHeight(model.shelves.get(l.part))) + 2 * Ui.px(ROW_MARGIN);
            l.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(lh, MeasureSpec.EXACTLY));
        }
    }

    /** The backdrop and the hero's text cover the page; each shelf sits in its band, moved up by the scroll. */
    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        for (int i = 0; i < getChildCount(); i++) {
            Layer l = (Layer) getChildAt(i);
            int y = l.part < 0 ? 0 : Math.round(rowTop[l.part]) - Ui.px(ROW_MARGIN);
            l.layout(0, y, l.getMeasuredWidth(), y + l.getMeasuredHeight());
        }
    }

    /** The shelf with the focused card draws last, so the card's growth and shadow lie over its neighbours. */
    @Override
    protected int getChildDrawingOrder(int count, int i) {
        int focused = focusRow >= 0 && focusRow < rows.length ? 2 + focusRow : -1;
        if (focused < 0 || i < focused) return i;
        return i == count - 1 ? focused : i + 1;
    }

    private void invalidateAll() {
        backdropLayer.invalidate();
        heroLayer.invalidate();
        for (Layer l : rows) l.invalidate();
        invalidate();
    }

    private void invalidateRow(int r) {
        if (r >= 0 && r < rows.length) rows[r].invalidate();
    }

    /**
     * After a scroll: the shelves move with it without being drawn again, a shelf coming near the
     * screen is drawn for the first time, and the backdrop and hero text redraw while they change.
     */
    private void applyScroll() {
        for (int r = 0; r < rows.length; r++) {
            rows[r].setTranslationY(-scrollY);
            if (!rowDrawn[r] && near(r)) rows[r].invalidate();
        }
        float shown = shelvesShown();
        if (shown != drawnShown) backdropLayer.invalidate();
        float textAlpha = heroTextAlpha();
        if (textAlpha > 0f || drawnTextAlpha != 0f) heroLayer.invalidate();
        invalidate();   // the top fade and the credit line
    }

    /** Within a screen of the visible area: drawn, and asking for its artwork. */
    private boolean near(int r) {
        float top = rowTop[r] - scrollY, h = getHeight() > 0 ? getHeight() : Ui.px(1080);
        return top < 2 * h && top + rowHeight(model.shelves.get(r)) > -h;
    }

    /** 0 while the hero is on screen, 1 once its banner has scrolled away. */
    private float shelvesShown() {
        return Math.min(1f, scrollY / Ui.px(700));
    }

    private float heroTextAlpha() {
        return 1f - Math.min(1f, scrollY / Ui.px(420));
    }

    // ---- Content ----------------------------------------------------------------------------

    /** New content; on a refresh the focused shelf and card stay where they were. */
    void setModel(PageModel m) {
        boolean first = model == null;
        model = m;
        message = "";
        loadedAt = Clock.now();
        // What the score cards show, worked out once per game here rather than on every frame
        scoreCards.clear();
        for (PageModel.Shelf s : m.shelves) {
            if (s.style != PageModel.Style.SCORE) continue;
            for (PageModel.Card card : s.cards) {
                if (card.game != null && !scoreCards.containsKey(card.game)) {
                    scoreCards.put(card.game, new ScoreCard(card.game, Ui.px(CARD_W), Ui.px(CARD_H)));
                }
            }
        }
        int n = m.shelves.size();
        int[] oldCols = cols;
        float[] oldX = rowX;
        cols = new int[n];
        rowX = new float[n];
        for (int i = 0; i < n; i++) {
            if (i < oldCols.length) {
                cols[i] = Math.min(oldCols[i], Math.max(0, m.shelves.get(i).cards.size() - 1));
                rowX[i] = Math.min(oldX[i], maxRowX(i));
            }
        }
        layoutRows();
        // A part per shelf, after the backdrop and the hero's text
        if (getChildCount() > 2) removeViews(2, getChildCount() - 2);
        rows = new Layer[n];
        rowDrawn = new boolean[n];
        for (int r = 0; r < n; r++) {
            rows[r] = new Layer(r);
            addView(rows[r]);
        }
        if (hero >= m.hero.size()) hero = 0;
        heroFrom = -1;
        heroFade = 1f;
        if (row >= n || (row >= 0 && !focusable(row))) row = -1;
        if (row == -1 && m.hero.isEmpty()) row = next(-1, 1);
        if (row == -1) button = Math.min(button, Math.max(0, heroActions() - 1));
        scrollY = targetScroll();
        if (first) focusT = 1f;
        markFocus(false);
        schedule();
        applyScroll();
        invalidateAll();
        if (listener != null) listener.onHeroShown(this);
    }

    void setMessage(String text) {
        message = text;
        if (model == null) backdropLayer.invalidate();
    }

    boolean hasModel() {
        return model != null;
    }

    long age() {
        return model == null ? Long.MAX_VALUE : Clock.now() - loadedAt;
    }

    PageModel model() {
        return model;
    }

    PageModel.Feature currentHero() {
        return model == null || model.hero.isEmpty() ? null : model.hero.get(hero);
    }

    int focusedRow() {
        return row;
    }

    int focusedColumn() {
        return row < 0 ? button : cols[row];
    }

    boolean scrolledIntoShelves() {
        return intoShelves;
    }

    private void layoutRows() {
        int n = model.shelves.size();
        rowTop = new float[n];
        float y = Ui.px(FIRST_ROW_TOP);
        for (int i = 0; i < n; i++) {
            rowTop[i] = y;
            y += rowHeight(model.shelves.get(i));
        }
    }

    private float rowHeight(PageModel.Shelf s) {
        if (s.cards.isEmpty()) return Ui.px(TITLE_H + EMPTY_H + ROW_GAP + 20);
        int body = s.style == PageModel.Style.RANKED ? POSTER_H : CARD_H;
        return Ui.px(TITLE_H + body + CAPTION_H + ROW_GAP);
    }

    private float pitch(PageModel.Shelf s) {
        return s.style == PageModel.Style.RANKED ? Ui.px(RANK_W + POSTER_W + POSTER_GAP) : Ui.px(CARD_W + CARD_GAP);
    }

    private float itemWidth(PageModel.Shelf s) {
        return s.style == PageModel.Style.RANKED ? Ui.px(RANK_W + POSTER_W) : Ui.px(CARD_W);
    }

    private float maxRowX(int r) {
        PageModel.Shelf s = model.shelves.get(r);
        float content = (s.cards.size() - 1) * pitch(s) + itemWidth(s);
        return Math.max(0f, content - (Ui.px(RIGHT) - Ui.px(LEFT)));
    }

    private boolean focusable(int r) {
        return r >= 0 && r < model.shelves.size() && !model.shelves.get(r).cards.isEmpty();
    }

    /** The next shelf with cards in {@code dir}, or -1 for the hero when going up past the first. */
    private int next(int from, int dir) {
        for (int r = from + dir; r >= 0 && r < model.shelves.size(); r += dir) if (focusable(r)) return r;
        return dir < 0 ? -1 : from;
    }

    private int heroActions() {
        PageModel.Feature f = currentHero();
        return f == null ? 0 : f.actions.size();
    }

    // ---- Navigation (called by HomeActivity) -------------------------------------------------

    /** False when already at the top, so the caller moves focus on to the tab bar. */
    boolean up() {
        if (model == null || row == -1) return false;
        int target = next(row, -1);
        if (target == -1 && model.hero.isEmpty()) return false;
        setRow(target);
        return true;
    }

    void down() {
        if (model == null) return;
        int target = next(row, 1);
        if (target != row) setRow(target);
    }

    void left() {
        if (model == null) return;
        if (row == -1) {
            if (button > 0) {
                button--;
                heroLayer.invalidate();
            } else if (hero > 0) {
                showHero(hero - 1);
            }
            return;
        }
        if (cols[row] > 0) setCol(cols[row] - 1);
    }

    void right() {
        if (model == null) return;
        if (row == -1) {
            if (button < heroActions() - 1) {
                button++;
                heroLayer.invalidate();
            } else if (hero < model.hero.size() - 1) {
                showHero(hero + 1);
            }
            return;
        }
        if (cols[row] < model.shelves.get(row).cards.size() - 1) setCol(cols[row] + 1);
    }

    void select() {
        if (model == null || listener == null) return;
        if (row == -1) {
            PageModel.Feature f = currentHero();
            if (f != null && button < f.actions.size()) listener.onAction(this, f, f.actions.get(button));
        } else {
            PageModel.Shelf s = model.shelves.get(row);
            listener.onCard(this, s, s.cards.get(cols[row]));
        }
    }

    /** Back from the shelves returns to the hero first; false when there is nothing left to unwind. */
    boolean back() {
        if (model == null || row == -1 || model.hero.isEmpty()) return false;
        setRow(-1);
        return true;
    }

    /** Back to the hero's first button, scrolled to the top (when the tab is chosen again). */
    void reset() {
        if (model == null) return;
        if (!model.hero.isEmpty()) {
            button = 0;
            setRow(-1);
        }
    }

    private void setRow(int r) {
        row = r;
        if (r == -1) button = 0;
        markFocus(true);
        animateScroll();
        boolean into = r >= 0;
        if (into != intoShelves) {
            intoShelves = into;
            if (listener != null) listener.onScrolled(this, into);
        }
        schedule();
    }

    private void setCol(int c) {
        cols[row] = c;
        markFocus(true);
        // Keep the focused card fully on screen, sliding the shelf as little as possible
        PageModel.Shelf s = model.shelves.get(row);
        float x = c * pitch(s), w = itemWidth(s), span = Ui.px(RIGHT) - Ui.px(LEFT);
        float target = rowX[row];
        if (x - target < 0) target = x;
        else if (x + w - target > span) target = x + w - span;
        target = Math.max(0, Math.min(target, maxRowX(row)));
        animateRow(row, target);
    }

    private float targetScroll() {
        if (row < 0 || model == null) return 0f;
        return rowTop[row] - Ui.px(FOCUS_ROW_TOP);
    }

    private void animateScroll() {
        float target = targetScroll();
        if (scrollAnim != null) scrollAnim.cancel();
        if (Ui.reducedMotion) {
            scrollY = target;
            applyScroll();
            return;
        }
        scrollAnim = ValueAnimator.ofFloat(scrollY, target);
        scrollAnim.setDuration(Ui.SCROLL_MS);
        scrollAnim.setInterpolator(Ui.EASE);
        scrollAnim.addUpdateListener(a -> {
            scrollY = (float) a.getAnimatedValue();
            applyScroll();
        });
        scrollAnim.start();
    }

    /** Slides shelf {@code r}; only that shelf is drawn again. */
    private void animateRow(int r, float target) {
        if (rowAnim != null) rowAnim.end();
        if (Ui.reducedMotion || rowX[r] == target) {
            rowX[r] = target;
            invalidateRow(r);
            return;
        }
        rowAnim = ValueAnimator.ofFloat(rowX[r], target);
        rowAnim.setDuration(Ui.SCROLL_MS);
        rowAnim.setInterpolator(Ui.EASE);
        rowAnim.addUpdateListener(a -> {
            rowX[r] = (float) a.getAnimatedValue();
            invalidateRow(r);
        });
        rowAnim.start();
    }

    /** The focused card grows in while the previous one settles back; only their shelves are drawn again. */
    private void markFocus(boolean animate) {
        int newRow = isFocused() ? row : Integer.MIN_VALUE;
        int newCol = newRow >= 0 ? cols[newRow] : 0;
        if (newRow == focusRow && newCol == focusCol) return;
        if (newRow != focusRow) invalidate();   // the focused shelf draws last
        prevRow = focusRow;
        prevCol = focusCol;
        prevT = focusT;
        focusRow = newRow;
        focusCol = newCol;
        if (focusAnim != null) focusAnim.cancel();
        if (!animate || Ui.reducedMotion) {
            focusT = 1f;
            prevT = 0f;
            invalidateRow(prevRow);
            invalidateRow(focusRow);
            return;
        }
        focusT = 0f;
        final float prevStart = prevT;
        focusAnim = ValueAnimator.ofFloat(0f, 1f);
        focusAnim.setDuration(Ui.FOCUS_MS);
        focusAnim.setInterpolator(Ui.EASE);
        focusAnim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            focusT = t;
            prevT = prevStart * (1f - t);
            invalidateRow(prevRow);
            invalidateRow(focusRow);
        });
        focusAnim.start();
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        markFocus(true);
        schedule();
        invalidateAll();
    }

    // ---- Hero carousel ----------------------------------------------------------------------

    private void showHero(int index) {
        if (model == null || index == hero || index < 0 || index >= model.hero.size()) return;
        heroFrom = hero;
        hero = index;
        button = 0;
        if (heroAnim != null) heroAnim.cancel();
        if (Ui.reducedMotion) {
            heroFade = 1f;
            heroFrom = -1;
        } else {
            heroFade = 0f;
            heroAnim = ValueAnimator.ofFloat(0f, 1f);
            heroAnim.setDuration(HERO_FADE_MS);
            heroAnim.setInterpolator(Ui.EASE);
            heroAnim.addUpdateListener(a -> {
                heroFade = (float) a.getAnimatedValue();
                if (heroFade >= 1f) heroFrom = -1;
                backdropLayer.invalidate();
                heroLayer.invalidate();
            });
            heroAnim.start();
        }
        backdropLayer.invalidate();
        heroLayer.invalidate();
        if (listener != null) listener.onHeroShown(this);
    }

    /**
     * The hero turns over every 8 s while it is not being looked at closely, and holds still under
     * the shelves, where it is too dim to follow and a new picture would only slow the browsing. It
     * holds still too while another app is in front: back, the page shows the hero it was left on,
     * whose picture is kept, and not one whose picture was given back meanwhile.
     */
    private void schedule() {
        removeCallbacks(advance);
        if (model != null && model.hero.size() > 1 && !intoShelves && !Ui.reducedMotion && isAttachedToWindow() && getVisibility() == VISIBLE
                && getWindowVisibility() == VISIBLE) {
            postDelayed(advance, HERO_EVERY_MS);
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        schedule();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        schedule();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(advance);
        super.onDetachedFromWindow();
    }

    /** A small blurred copy of the hero's background, for the glass of the bars above it. */
    Bitmap backdrop() {
        Bitmap small = Bitmap.createBitmap(Backdrop.W, Backdrop.H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(small);
        PageModel.Feature f = currentHero();
        if (f != null && f.game != null) {
            drawHeroPicture(c, Backdrop.W, Backdrop.H, f, 0f, 1f);   // a game's colours need no artwork
        } else {
            painter.drawBackground(c, Backdrop.W, Backdrop.H, f, f == null ? null : images.peek(f.imageUrl, getWidth()));
        }
        Backdrop.blur(small, Backdrop.RADIUS);
        return small;
    }

    // ---- Drawing ----------------------------------------------------------------------------

    /**
     * The backdrop: the hero's background, which gives way to Home's own background, darkened, as the
     * banner scrolls away. Browsing the shelves then draws only that one picture behind them.
     */
    private void drawBackdrop(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (model == null) {
            painter.drawBackground(c, w, h, null, null);
            if (!message.isEmpty()) c.drawText(message, Ui.px(LEFT), Ui.px(HERO_BOTTOM) - Ui.px(20), messagePaint);
            drawSkeleton(c);
            return;
        }
        float shown = shelvesShown();
        drawnShown = shown;
        if (shown > 0f) drawHome(c, w, h, SHELF_DIM);
        if (shown < 1f) drawHeroBackground(c, w, h, SHELF_DIM * shown, 1f - shown);
    }

    /** The hero's background, darkened by {@code dim}, at {@code alpha}; a new one fades in over the old. */
    private void drawHeroBackground(Canvas c, int w, int h, float dim, float alpha) {
        PageModel.Feature cur = currentHero();
        if (heroFrom >= 0 && heroFrom < model.hero.size() && heroFade < 1f) {
            drawHeroPicture(c, w, h, model.hero.get(heroFrom), dim, alpha);
            drawHeroPicture(c, w, h, cur, dim, alpha * heroFade);
        } else {
            drawHeroPicture(c, w, h, cur, dim, alpha);
        }
    }

    /**
     * One hero's background under its scrims: its artwork, or for a game the teams' colours. Over
     * Home's background, a game's colours are clear on their dark side, so that background shows
     * through it (see {@link #seeThroughGames}).
     */
    private void drawHeroPicture(Canvas c, int w, int h, PageModel.Feature f, float dim, float alpha) {
        if (f == null || f.game == null) {
            painter.drawBackground(c, w, h, f, heroArt(f, w), dim, alpha);
            return;
        }
        boolean clear = seeThroughGames && home != null;
        if (clear) painter.drawBackground(c, w, h, null, home, dim, alpha);
        colourPaint.setColorFilter(dimFilter(dim));
        colourPaint.setAlpha(Math.round(255 * alpha));
        rect.set(0, 0, w, h);
        c.drawBitmap(teamColours(f.game, clear), null, rect, colourPaint);
    }

    private Bitmap heroArt(PageModel.Feature f, int w) {
        return f == null || f.imageUrl == null ? null : images.get(f.imageUrl, w, false, invalidator);
    }

    /**
     * A game's colours with the hero's scrims multiplied in, drawn once into a small image: they are
     * soft gradients, so a quarter of the screen's size loses nothing when drawn full size, and the
     * GPU then draws one image instead of working out five gradients for every pixel on every frame.
     * The scrims darken the colours but not their coverage, so where they are clear the picture
     * under them takes the same scrims from its own pass.
     */
    private Bitmap teamColours(Sports.Game g, boolean clear) {
        String key = g.id + (clear ? "/clear" : "/dark");
        Bitmap b = teamColours.get(key);
        if (b != null) return b;
        int w = Backdrop.W, h = Backdrop.H;
        b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint();
        for (Shader s : FeaturePainter.teamShaders(w, h, g, clear)) {
            p.setShader(s);
            c.drawRect(0, 0, w, h, p);
        }
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.MULTIPLY));
        for (Shader s : FeaturePainter.scrims(w, h)) {
            p.setShader(s);
            c.drawRect(0, 0, w, h, p);
        }
        if (teamColours.size() >= 4) teamColours.remove(teamColours.keySet().iterator().next());
        teamColours.put(key, b);
        return b;
    }

    /** Home's own background, centre-cropped and darkened by {@code dim}; graphite until it has loaded. */
    private void drawHome(Canvas c, int w, int h, float dim) {
        if (home == null) {
            painter.drawBackground(c, w, h, null, null, dim, 1f);
            return;
        }
        if (homeShader == null || homeShaderW != w || homeShaderH != h) {
            homeShader = new BitmapShader(home, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            float k = Math.max(w / (float) home.getWidth(), h / (float) home.getHeight());
            Matrix m = new Matrix();
            m.setScale(k, k);
            m.postTranslate((w - home.getWidth() * k) / 2f, (h - home.getHeight() * k) / 2f);
            homeShader.setLocalMatrix(m);
            homeShaderW = w;
            homeShaderH = h;
        }
        homePaint.setShader(homeShader);
        homePaint.setColorFilter(dimFilter(dim));
        c.drawRect(0, 0, w, h, homePaint);
    }

    /** Multiplies colour by 1 - {@code dim}, leaving alpha alone; one filter per step, made once. */
    private ColorFilter dimFilter(float dim) {
        int keep = Math.round(255 * (1f - Math.max(0f, Math.min(1f, dim))));
        if (keep >= 255) return null;
        if (dimFilters[keep] == null) dimFilters[keep] = new LightingColorFilter(0xFF000000 | keep * 0x010101, 0);
        return dimFilters[keep];
    }

    /** The hero's text, which scrolls up with it and fades on the way. */
    private void drawHero(Canvas c) {
        float alpha = heroTextAlpha();
        drawnTextAlpha = alpha;
        if (model != null && alpha > 0f) drawHeroText(c, alpha);
    }

    /** Above the shelves: the credit line after the last one, and the fade at the top of the screen. */
    @Override
    protected void dispatchDraw(Canvas c) {
        super.dispatchDraw(c);
        if (model == null) return;
        int w = getWidth(), h = getHeight();
        if (!model.credit.isEmpty() && rowTop.length > 0) {
            float end = rowTop[rowTop.length - 1] + rowHeight(model.shelves.get(rowTop.length - 1)) - scrollY;
            if (end < h) c.drawText(model.credit, Ui.px(LEFT), end + Ui.px(10), credit);
        }
        float scrolled = shelvesShown();
        if (scrolled > 0f) {
            // The shelf above the focused one fades into the top edge instead of being cut off
            float fadeH = Ui.px(FOCUS_ROW_TOP - 10);
            topFade.setShader(cached("topfade" + fadeH, () -> new LinearGradient(0, 0, 0, fadeH,
                    new int[] {0xFF000000, 0xF2000000, 0x00000000}, new float[] {0f, 0.6f, 1f}, Shader.TileMode.CLAMP)));
            topFade.setAlpha(Math.round(255 * scrolled));
            c.drawRect(0, 0, w, fadeH, topFade);
        }
    }

    private final Paint topFade = new Paint();

    private void drawHeroText(Canvas c, float alpha) {
        if (model.hero.isEmpty()) return;
        float shift = -scrollY;
        int focusedButton = isFocused() && row == -1 ? button : -1;
        if (heroFrom >= 0 && heroFade < 1f) {
            drawHeroBlock(c, model.hero.get(heroFrom), shift, alpha * (1f - heroFade), -1);
            drawHeroBlock(c, currentHero(), shift, alpha * heroFade, focusedButton);
        } else {
            drawHeroBlock(c, currentHero(), shift, alpha, focusedButton);
        }
        if (model.hero.size() > 1) drawDots(c, shift, alpha);
    }

    private void drawHeroBlock(Canvas c, PageModel.Feature f, float shift, float alpha, int focusedButton) {
        if (f == null || alpha <= 0.01f) return;
        painter.setFade(alpha < 0.99f ? alpha : 1f);
        c.save();
        c.translate(0, shift);
        if (f.game != null) {
            Bitmap away = images.get(Sports.logo(f.game.away.logo, 300), Ui.px(220), true, invalidator);
            Bitmap home = images.get(Sports.logo(f.game.home.logo, 300), Ui.px(220), true, invalidator);
            painter.drawGame(c, f.game, away, home, Ui.px(400));
        }
        Bitmap logo = f.logoUrl == null ? null : images.get(f.logoUrl, Ui.px(600), true, invalidator);
        painter.drawText(c, Ui.px(LEFT), Ui.px(HERO_BOTTOM), f, logo, focusedButton, 2, Ui.px(f.game != null ? 820 : 880));
        c.restore();
        painter.setFade(1f);
    }

    private void drawDots(Canvas c, float shift, float alpha) {
        int n = model.hero.size();
        float d = Ui.px(10), gap = Ui.px(12), current = Ui.px(28);
        float total = (n - 1) * (d + gap) + current;
        float x = Ui.px(RIGHT) - total, cy = Ui.px(HERO_BOTTOM) - Ui.px(32) + shift;
        for (int i = 0; i < n; i++) {
            float w = i == hero ? current : d;
            fill.setColor(i == hero ? 0xF2FFFFFF : 0x66FFFFFF);
            fill.setAlpha(Math.round(fill.getAlpha() * alpha));
            rect.set(x, cy - d / 2f, x + w, cy + d / 2f);
            c.drawRoundRect(rect, d / 2f, d / 2f, fill);
            x += w + gap;
        }
    }

    /**
     * Shelf {@code r} in its own part, whose top is ROW_MARGIN above the shelf: its title, then its
     * cards, the focused one last, above its neighbours. A shelf far off screen is left blank until
     * it comes near (see applyScroll), so it does not ask for its artwork early.
     */
    private void drawRow(Canvas c, int r) {
        if (model == null || r >= model.shelves.size()) return;
        rowDrawn[r] = near(r);
        if (!rowDrawn[r]) return;
        int w = getWidth();
        PageModel.Shelf s = model.shelves.get(r);
        float top = Ui.px(ROW_MARGIN);
        c.drawText(s.title, Ui.px(LEFT), top + Ui.px(36), shelfTitle);
        float cardTop = top + Ui.px(TITLE_H);
        if (s.cards.isEmpty()) {
            drawEmpty(c, s, cardTop);
            return;
        }
        float pitch = pitch(s), focusedX = Float.NaN;
        int first = Math.max(0, (int) Math.floor((rowX[r] - Ui.px(LEFT)) / pitch) - 1);
        for (int i = first; i < s.cards.size(); i++) {
            float x = Ui.px(LEFT) + i * pitch - rowX[r];
            if (x > w) break;
            float t = focusAmount(r, i);
            if (r == focusRow && i == focusCol && t > 0f) {
                focusedX = x;
                continue;   // drawn last, above its neighbours
            }
            drawCard(c, s, s.cards.get(i), x, cardTop, t);
        }
        if (!Float.isNaN(focusedX)) drawCard(c, s, s.cards.get(focusCol), focusedX, cardTop, focusT);
        // Ask for the next card's artwork before it scrolls in
        int ahead = (int) ((rowX[r] + w) / pitch) + 1;
        if (ahead < s.cards.size()) prefetch(s, s.cards.get(ahead));
    }

    private float focusAmount(int r, int i) {
        if (r == focusRow && i == focusCol) return focusT;
        if (r == prevRow && i == prevCol) return prevT;
        return 0f;
    }

    private void prefetch(PageModel.Shelf s, PageModel.Card card) {
        if (card.imageUrl != null) images.get(card.imageUrl, cardImageWidth(s), false, null);
    }

    private int cardImageWidth(PageModel.Shelf s) {
        return Math.round((s.style == PageModel.Style.RANKED ? Ui.px(POSTER_W) : Ui.px(CARD_W)) * CARD_SCALE);
    }

    private void drawCard(Canvas c, PageModel.Shelf s, PageModel.Card card, float x, float top, float focus) {
        float w, h;
        float ix = x;
        if (s.style == PageModel.Style.RANKED) {
            w = Ui.px(POSTER_W);
            h = Ui.px(POSTER_H);
            ix = x + Ui.px(RANK_W);
            // The rank stands just left of the poster, tucked a little behind it; "10" is narrowed to fit
            String n = String.valueOf(card.rank);
            float tuck = Ui.px(10), room = Ui.px(RANK_W + 6) + tuck;
            rank.setTextSize(Ui.px(230));
            float tw = rank.measureText(n);
            if (tw > room) rank.setTextSize(Ui.px(230) * room / tw);
            c.drawText(n, ix + tuck, top + h - Ui.px(2), rank);
        } else {
            w = Ui.px(CARD_W);
            h = Ui.px(CARD_H);
        }
        float scale = 1f + (CARD_SCALE - 1f) * focus;
        float cx = ix + w / 2f, cy = top + h / 2f;
        c.save();
        c.scale(scale, scale, cx, cy);
        // Cards draw in their own coordinates, so their gradients can be built once and reused
        c.translate(ix, top);
        rect.set(0, 0, w, h);
        float r = Ui.px(RADIUS);
        if (focus > 0f) {
            // The shadow, blurred once per card size, then the card's black base
            Bitmap shade = shadowFor(s.style == PageModel.Style.RANKED, w, h, r);
            float pad = (shade.getWidth() - w) / 2f;
            shadowPaint.setAlpha(Math.round(150 * focus));
            c.drawBitmap(shade, -pad, Ui.px(SHADOW_DROP) - pad, shadowPaint);
            c.drawRoundRect(rect, r, r, shadow);
        }
        switch (s.style) {
            case SCORE:
                drawScoreCard(c, card.game, w, h, r);
                break;
            case PROGRESS:
                drawProgressCard(c, card, w, h, r, cardImageWidth(s));
                break;
            default:
                drawImage(c, card.imageUrl, rect, r, cardImageWidth(s));
                break;
        }
        rect.set(0, 0, w, h);
        if (focus > 0f) {
            sheen.setShader(cached("sheen" + h, () -> new LinearGradient(0, 0, 0, h * 0.55f, 0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)));
            sheen.setAlpha(Math.round(255 * focus));
            c.drawRoundRect(rect, r, r, sheen);
        }
        rim.setColor(focus > 0f ? Math.round(0xD9 * focus) << 24 | 0xFFFFFF : 0x1FFFFFFF);
        float in = rim.getStrokeWidth() / 2f;
        rect.inset(in, in);
        c.drawRoundRect(rect, r - in, r - in, rim);
        c.restore();
        // Captions move down with the grown card instead of being covered by it
        float capTop = top + h + (scale - 1f) * h / 2f;
        c.drawText(TextUtils.ellipsize(card.title, cardTitle, w, TextUtils.TruncateAt.END).toString(), ix, capTop + Ui.px(34), cardTitle);
        if (!card.subtitle.isEmpty()) {
            c.drawText(TextUtils.ellipsize(card.subtitle, cardSub, w, TextUtils.TruncateAt.END).toString(), ix, capTop + Ui.px(62), cardSub);
        }
    }

    private static final int SHADOW_BLUR = 26, SHADOW_DROP = 14;
    private static Bitmap cardShadow, posterShadow;   // shared by the pages; main thread only
    private final Paint shadowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    /**
     * A focused card's shadow: its shape in black, blurred once in software and kept as an alpha
     * mask with room for the blur to spread. Drawn as a bitmap, it costs the GPU a texture where a
     * shadow layer would blur it again on every frame of the focus animation.
     */
    private static Bitmap shadowFor(boolean poster, float w, float h, float r) {
        Bitmap b = poster ? posterShadow : cardShadow;
        if (b != null) return b;
        int pad = 2 * Ui.px(SHADOW_BLUR);
        b = Bitmap.createBitmap(Math.round(w) + 2 * pad, Math.round(h) + 2 * pad, Bitmap.Config.ALPHA_8);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        // The same blur a shadow layer of this radius uses
        p.setMaskFilter(new BlurMaskFilter(Ui.px(SHADOW_BLUR), BlurMaskFilter.Blur.NORMAL));
        new Canvas(b).drawRoundRect(new RectF(pad, pad, pad + w, pad + h), r, r, p);
        if (poster) posterShadow = b;
        else cardShadow = b;
        return b;
    }

    private interface ShaderMaker {
        Shader make();
    }

    private final Map<String, Shader> gradientCache = new java.util.HashMap<>();

    private Shader cached(String key, ShaderMaker maker) {
        Shader s = gradientCache.get(key);
        if (s == null) {
            if (gradientCache.size() > 300) gradientCache.clear();
            s = maker.make();
            gradientCache.put(key, s);
        }
        return s;
    }

    private void drawImage(Canvas c, String url, RectF r, float radius, int decodeWidth) {
        Bitmap b = url == null ? null : images.get(url, decodeWidth, false, invalidator);
        if (b == null) {
            fill.setColor(0x1AFFFFFF);
            c.drawRoundRect(r, radius, radius, fill);
            return;
        }
        drawBitmap(c, b, r, radius);
    }

    /** Centre-crops {@code b} into the rounded rect. */
    private void drawBitmap(Canvas c, Bitmap b, RectF r, float radius) {
        BitmapShader shader = shaders.get(b);
        if (shader == null) {
            shader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            shaders.put(b, shader);
        }
        float s = Math.max(r.width() / b.getWidth(), r.height() / b.getHeight());
        matrix.setScale(s, s);
        matrix.postTranslate(r.left + (r.width() - b.getWidth() * s) / 2f, r.top + (r.height() - b.getHeight() * s) / 2f);
        shader.setLocalMatrix(matrix);
        art.setShader(shader);
        c.drawRoundRect(r, radius, radius, art);
        art.setShader(null);
    }

    /** Continue Watching: the artwork (or the app's own colours), the app's tile in the corner and a progress bar. */
    private void drawProgressCard(Canvas c, PageModel.Card card, float w, float h, float radius, int decodeWidth) {
        Bitmap b = card.imageUrl == null ? null : images.get(card.imageUrl, decodeWidth, false, invalidator);
        Bitmap tile = card.appKey == null ? null : appArt.get(card.appKey);
        rect.set(0, 0, w, h);
        if (b != null) {
            drawBitmap(c, b, rect, radius);
        } else {
            Brand brand = Brand.of(card.appKey == null ? "" : card.appKey, "");
            fill.setShader(cached("brand" + brand.start + "/" + brand.end + "/" + w,
                    () -> new LinearGradient(0, 0, w, h, brand.start, brand.end, Shader.TileMode.CLAMP)));
            c.drawRoundRect(rect, radius, radius, fill);
            fill.setShader(null);
            if (tile != null) {
                float tw = w * 0.46f, th = tw * 0.6f;
                rect.set(w / 2f - tw / 2f, h / 2f - th / 2f - Ui.px(8), w / 2f + tw / 2f, h / 2f + th / 2f - Ui.px(8));
                drawBitmap(c, tile, rect, Ui.px(10));
            }
        }
        // A dark fade at the bottom keeps the bar visible on bright artwork
        rect.set(0, 0, w, h);
        fill.setShader(cached("fade" + h, () -> new LinearGradient(0, h * 0.55f, 0, h, 0x00000000, 0x99000000, Shader.TileMode.CLAMP)));
        c.drawRoundRect(rect, radius, radius, fill);
        fill.setShader(null);
        if (b != null && tile != null) {
            float tw = Ui.px(72), th = tw * 0.6f;
            rect.set(Ui.px(12), Ui.px(12), Ui.px(12) + tw, Ui.px(12) + th);
            drawBitmap(c, tile, rect, Ui.px(7));
        }
        if (card.progress >= 0f) {
            float pad = Ui.px(16), bh = Ui.px(6), y = h - Ui.px(16) - bh;
            rect.set(pad, y, w - pad, y + bh);
            fill.setColor(0x59FFFFFF);
            c.drawRoundRect(rect, bh / 2f, bh / 2f, fill);
            rect.right = rect.left + Math.max(bh, (w - 2 * pad) * card.progress);
            fill.setColor(0xFFFFFFFF);
            c.drawRoundRect(rect, bh / 2f, bh / 2f, fill);
        }
    }

    /**
     * What a score card shows that only changes with its game, worked out once when the game
     * arrives (see {@link #setModel}) instead of on every frame: the status line and chips fitted
     * to the card, the size the score fits at, the logos' addresses and the card's colours.
     */
    private final class ScoreCard {
        final String status, leftChip, rightChip, awayLogo, homeLogo;
        final float leftChipW, rightChipW, scoreSize;
        final int leftChipColor;
        final Shader teams;

        ScoreCard(Sports.Game g, float w, float h) {
            // Status along the bottom: the clock while live, "Final", or when it starts
            bigScore.setTextSize(Ui.px(19));
            status = TextUtils.ellipsize(g.live() || g.over() ? g.status : Sports.startText(g.start), bigScore,
                    w - Ui.px(40), TextUtils.TruncateAt.END).toString();
            // Scores shrink to fit between the logos (basketball reaches three digits)
            float logo = Ui.px(SCORE_LOGO), ax = w * 0.17f, room = w / 2f - Ui.px(16) - (ax + logo / 2f + Ui.px(8));
            bigScore.setTextSize(Ui.px(44));
            float widest = Math.max(bigScore.measureText(g.away.score), bigScore.measureText(g.home.score));
            scoreSize = widest > room ? Ui.px(44) * room / widest : Ui.px(44);
            leftChip = chipText(g.live() ? "LIVE" : g.league.name.length() <= 4 ? g.league.name : "");
            leftChipColor = g.live() ? 0xF2E5262B : 0x40000000;
            rightChip = chipText(g.network().toUpperCase());
            leftChipW = chip.measureText(leftChip) + Ui.px(18);
            rightChipW = chip.measureText(rightChip) + Ui.px(18);
            awayLogo = Sports.logo(g.away.logo, 160);
            homeLogo = Sports.logo(g.home.logo, 160);
            teams = new LinearGradient(0, 0, w, h,
                    new int[] {FeaturePainter.shade(g.away.color, 0.55f), FeaturePainter.shade(g.away.color, 0.40f),
                            FeaturePainter.shade(g.home.color, 0.40f), FeaturePainter.shade(g.home.color, 0.55f)},
                    new float[] {0f, 0.48f, 0.52f, 1f}, Shader.TileMode.CLAMP);
        }

        private String chipText(String text) {
            return TextUtils.ellipsize(text, chip, Ui.px(150), TextUtils.TruncateAt.END).toString();
        }
    }

    private static final int SCORE_LOGO = 76;
    private final Map<Sports.Game, ScoreCard> scoreCards = new java.util.HashMap<>();
    private Shader scoreShade;
    private final RectF chipBox = new RectF(), logoBox = new RectF();

    /** A game: the teams' colours split down the card, both logos, the score or the start time, the channel. */
    private void drawScoreCard(Canvas c, Sports.Game g, float w, float h, float radius) {
        ScoreCard card = scoreCards.get(g);
        if (card == null) {
            card = new ScoreCard(g, w, h);   // not in the model's score shelves: worked out now, once
            scoreCards.put(g, card);
        }
        if (scoreShade == null) scoreShade = new LinearGradient(0, 0, 0, h, 0x14FFFFFF, 0x59000000, Shader.TileMode.CLAMP);
        rect.set(0, 0, w, h);
        fill.setShader(card.teams);
        c.drawRoundRect(rect, radius, radius, fill);
        fill.setShader(scoreShade);
        c.drawRoundRect(rect, radius, radius, fill);
        fill.setShader(null);
        RectF r = rect;
        float logo = Ui.px(SCORE_LOGO), ly = h * 0.42f;
        float ax = w * 0.17f, hx = w - w * 0.17f, mid = w / 2f;
        drawLogo(c, card.awayLogo, ax, ly, logo);
        drawLogo(c, card.homeLogo, hx, ly, logo);
        c.drawText(g.away.abbr, ax, ly + logo / 2f + Ui.px(30), abbr);
        c.drawText(g.home.abbr, hx, ly + logo / 2f + Ui.px(30), abbr);
        bigScore.setTextSize(g.live() || g.over() ? card.scoreSize : Ui.px(44));
        bigScore.getFontMetrics(scoreMetrics);
        float base = ly - (scoreMetrics.ascent + scoreMetrics.descent) / 2f;
        if (g.live() || g.over()) {
            bigScore.setTextAlign(Paint.Align.RIGHT);
            bigScore.setAlpha(g.over() && g.home.winner ? 150 : 255);
            c.drawText(g.away.score, mid - Ui.px(16), base, bigScore);
            bigScore.setTextAlign(Paint.Align.LEFT);
            bigScore.setAlpha(g.over() && g.away.winner ? 150 : 255);
            c.drawText(g.home.score, mid + Ui.px(16), base, bigScore);
            bigScore.setAlpha(255);
            bigScore.setTextAlign(Paint.Align.CENTER);
        } else {
            bigScore.setTextSize(Ui.px(26));
            bigScore.setAlpha(200);
            c.drawText("@", mid, base, bigScore);
            bigScore.setAlpha(255);
        }
        bigScore.setTextSize(Ui.px(19));
        c.drawText(card.status, mid, r.bottom - Ui.px(18), bigScore);
        bigScore.setTextSize(Ui.px(44));
        float chipY = r.top + Ui.px(14);
        drawChip(c, card.leftChip, card.leftChipW, r.left + Ui.px(14), chipY, card.leftChipColor, true);
        drawChip(c, card.rightChip, card.rightChipW, r.right - Ui.px(14), chipY, 0x4D000000, false);
    }

    private final Paint.FontMetrics scoreMetrics = new Paint.FontMetrics(), chipMetrics = new Paint.FontMetrics();

    /** A small label in a rounded box, {@code w} wide, from {@code x} rightwards or leftwards. */
    private void drawChip(Canvas c, String text, float w, float x, float top, int color, boolean fromLeft) {
        if (text.isEmpty()) return;
        float h = Ui.px(26), left = fromLeft ? x : x - w;
        chipBox.set(left, top, left + w, top + h);
        fill.setColor(color);
        c.drawRoundRect(chipBox, Ui.px(7), Ui.px(7), fill);
        chip.getFontMetrics(chipMetrics);
        c.drawText(text, left + Ui.px(9), top + h / 2f - (chipMetrics.ascent + chipMetrics.descent) / 2f, chip);
    }

    /** A team's logo, at the address worked out with its card. */
    private void drawLogo(Canvas c, String logoUrl, float cx, float cy, float size) {
        Bitmap b = images.get(logoUrl, Math.round(size), true, invalidator);
        if (b == null) return;
        float s = size / Math.max(b.getWidth(), b.getHeight());
        logoBox.set(cx - b.getWidth() * s / 2f, cy - b.getHeight() * s / 2f, cx + b.getWidth() * s / 2f, cy + b.getHeight() * s / 2f);
        c.drawBitmap(b, null, logoBox, art);
    }

    /** An empty shelf (Continue Watching before anything has played) says what will appear there. */
    private void drawEmpty(Canvas c, PageModel.Shelf s, float top) {
        float w = Ui.px(CARD_W * 2 + CARD_GAP), h = Ui.px(EMPTY_H);
        rect.set(Ui.px(LEFT), top, Ui.px(LEFT) + w, top + h);
        fill.setColor(0x1FFFFFFF);
        c.drawRoundRect(rect, Ui.px(RADIUS), Ui.px(RADIUS), fill);
        rim.setColor(0x26FFFFFF);
        c.drawRoundRect(rect, Ui.px(RADIUS), Ui.px(RADIUS), rim);
        // A play symbol in a ring, then the text
        float cx = rect.left + Ui.px(64), cy = rect.centerY(), ring = Ui.px(26);
        rim.setColor(0x99FFFFFF);
        c.drawCircle(cx, cy, ring, rim);
        if (emptyPlay.isEmpty()) {
            emptyPlay.moveTo(-Ui.px(7), -Ui.px(11));
            emptyPlay.lineTo(Ui.px(12), 0);
            emptyPlay.lineTo(-Ui.px(7), Ui.px(11));
            emptyPlay.close();
        }
        fill.setColor(0xB3FFFFFF);
        c.save();
        c.translate(cx, cy);
        c.drawPath(emptyPlay, fill);
        c.restore();
        // Laid out once, and again only if the text or its width changes
        int textW = Math.round(w - Ui.px(140));
        if (emptyLayout == null || !s.empty.equals(emptyText) || textW != emptyWidth) {
            emptyText = s.empty;
            emptyWidth = textW;
            emptyLayout = android.text.StaticLayout.Builder.obtain(s.empty, 0, s.empty.length(), emptyPaint, textW)
                    .setMaxLines(3).setLineSpacing(0f, 1.15f).build();
        }
        c.save();
        c.translate(rect.left + Ui.px(112), cy - emptyLayout.getHeight() / 2f);
        emptyLayout.draw(c);
        c.restore();
    }

    private final android.graphics.Path emptyPlay = new android.graphics.Path();
    private android.text.StaticLayout emptyLayout;
    private String emptyText;
    private int emptyWidth;

    /** Before content arrives: quiet placeholders where the first shelf will be. */
    private void drawSkeleton(Canvas c) {
        float top = Ui.px(FIRST_ROW_TOP + TITLE_H);
        fill.setColor(0x14FFFFFF);
        rect.set(Ui.px(LEFT), Ui.px(FIRST_ROW_TOP + 14), Ui.px(LEFT + 260), Ui.px(FIRST_ROW_TOP + 40));
        c.drawRoundRect(rect, Ui.px(8), Ui.px(8), fill);
        for (int i = 0; i < 5; i++) {
            float x = Ui.px(LEFT) + i * Ui.px(CARD_W + CARD_GAP);
            rect.set(x, top, x + Ui.px(CARD_W), top + Ui.px(CARD_H));
            c.drawRoundRect(rect, Ui.px(RADIUS), Ui.px(RADIUS), fill);
        }
    }
}
