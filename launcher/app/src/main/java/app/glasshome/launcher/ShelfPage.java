package app.glasshome.launcher;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Color;
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

        /** The hero's artwork has come in after it was shown: the glass above it needs its backdrop again. */
        default void onBackdropChanged(ShelfPage page) {
        }
    }

    // Geometry in design px (1920 wide)
    private static final int LEFT = 106, RIGHT = 1814;
    private static final int HERO_BOTTOM = 800;         // bottom of the hero's button row
    private static final int FIRST_ROW_TOP = 880;       // the first shelf peeks below the hero
    private static final int FOCUS_ROW_TOP = 170;       // a focused shelf settles here
    /** How dark the background gets at the top edge once the page is down among the shelves. */
    private static final int TOP_DIM = 0x33000000;
    private static final int TITLE_H = 56, CAPTION_H = 78, ROW_GAP = 30;
    private static final int CARD_W = 372, CARD_H = 209, CARD_GAP = 32;
    private static final int POSTER_W = 222, POSTER_H = 333, RANK_W = 124, POSTER_GAP = 22;
    private static final int RADIUS = 14;
    /**
     * How much bigger the chosen card is. It grows from its left edge, which stays under the shelf's title, and the
     * cards after it move along to make room (as Prime Video's rows do).
     */
    private static final float CARD_SCALE = 1.14f;
    /** The white outline round the chosen card: its gap from the card, and its width. */
    private static final float RING_GAP = 5f, RING_W = 4f;
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
    /** Continue Watching, kept above the other shelves across refreshes (see {@link #setTopShelf}); or null. */
    private PageModel.Shelf top;
    // A card taken off the top shelf: it fades where it was while the cards after it close the gap
    private PageModel.Card gone;
    private int goneAt;
    private float goneFocus, gapT = 1f;
    private ValueAnimator gapAnim;
    // A card's menu is open (see HomeActivity): the rest of the page dims back, the card's caption makes way
    private float menuT;
    private ValueAnimator menuAnim;
    private static final float MENU_DIM = 0.5f;
    private String message = "";
    private long loadedAt;

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
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint shelfTitle = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint cardTitle = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint cardSub = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint rank = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint messagePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
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
    /** The hero's artwork come in: the page draws it, and the bars' glass is frosted from it (it was graphite). */
    private final Runnable heroArtIn = () -> {
        invalidateAll();
        if (listener != null && model != null) listener.onBackdropChanged(this);
    };

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
        FeaturePainter.style(credit, context, 400, 17, 0x73FFFFFF);
        FeaturePainter.style(abbr, context, 700, 22, 0xFFFFFFFF);
        abbr.setTextAlign(Paint.Align.CENTER);
        FeaturePainter.style(bigScore, context, 700, 44, 0xFFFFFFFF);
        bigScore.setTextAlign(Paint.Align.CENTER);
        FeaturePainter.style(chip, context, 700, 15, 0xFFFFFFFF);
        FeaturePainter.style(titleCard, context, 600, 30, 0xE6FFFFFF);
        titleCard.setTextAlign(Paint.Align.CENTER);
        chip.setLetterSpacing(0.06f);
        shadow.setColor(0xFF000000);
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(Math.max(1.5f, Ui.px(2f)));
        ring.setStyle(Paint.Style.STROKE);
        ring.setColor(Color.WHITE);
    }

    void setListener(Listener l) {
        listener = l;
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
        shelvesFrost = null;
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
        if (top != null && (m.shelves.isEmpty() || m.shelves.get(0) != top)) m.shelves.add(0, top);
        model = m;
        endGap();
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
                // The chosen card stays at the left (see setCol)
                rowX[i] = cols[i] * pitch(m.shelves.get(i));
            }
        }
        layoutRows();
        rebuildParts();
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
        prefetchNextHero();
    }

    /** A part per shelf, after the backdrop and the hero's text. */
    private void rebuildParts() {
        int n = model.shelves.size();
        if (getChildCount() > 2) removeViews(2, getChildCount() - 2);
        rows = new Layer[n];
        rowDrawn = new boolean[n];
        for (int r = 0; r < n; r++) {
            rows[r] = new Layer(r);
            addView(rows[r]);
        }
        applyMenu();
    }

    /**
     * Continue Watching, above the other shelves (null or empty: none), kept there when the page's
     * content is refreshed. Focus stays on the shelf and card it was on: a card taken away has the
     * cards after it close the gap, focus passing to the one that takes its place; the shelf taken
     * away whole has the shelves below move up, focus passing to the next.
     */
    void setTopShelf(PageModel.Shelf s) {
        if (s != null && s.cards.isEmpty()) s = null;
        PageModel.Shelf old = top;
        top = s;
        if (model == null) return;
        boolean had = old != null && !model.shelves.isEmpty() && model.shelves.get(0) == old;
        if (had && s != null) {
            replaceTop(old, s);
            return;
        }
        if (!had && s == null) return;
        endGap();
        float h = rowHeight(had ? old : s);
        int shift = had ? -1 : 1;
        if (had) model.shelves.remove(0);
        else model.shelves.add(0, s);
        int n = model.shelves.size();
        int[] oldCols = cols;
        float[] oldX = rowX;
        cols = new int[n];
        rowX = new float[n];
        for (int i = 0; i < n; i++) {
            int from = i - shift;
            if (from >= 0 && from < oldCols.length) {
                cols[i] = oldCols[from];
                rowX[i] = oldX[from];
            }
        }
        layoutRows();
        rebuildParts();
        // The shelves' indices moved: what focus last grew is worked out afresh
        prevRow = Integer.MIN_VALUE;
        prevT = 0f;
        if (row < 0) {
            focusRow = Integer.MIN_VALUE;
            markFocus(false);
            applyScroll();
            invalidateAll();
            return;
        }
        boolean sameShelf = !had || row > 0;
        if (!had) row++;
        else if (row > 0) row--;
        if (!focusable(row)) {
            // The shelf that went was the only one
            focusRow = Integer.MIN_VALUE;
            setRow(model.hero.isEmpty() ? next(-1, 1) : -1);
            invalidateAll();
            return;
        }
        // The page stays still on screen, then the shelf below glides up into the place of one taken away
        scrollY += shift * h;
        if (sameShelf) {
            focusRow = isFocused() ? row : Integer.MIN_VALUE;
            focusCol = cols[row];
        } else {
            focusRow = Integer.MIN_VALUE;
            markFocus(true);
        }
        animateScroll();
        applyScroll();
        invalidateAll();
    }

    /** The top shelf's cards changed; one taken away closes its gap. */
    private void replaceTop(PageModel.Shelf old, PageModel.Shelf s) {
        model.shelves.set(0, s);
        if (sameCards(old, s)) {
            // Only what they show changed (artwork come, FirePlayer's answer after a Remove): a gap
            // still closing, or the shelf sliding, carries on
            invalidateRow(0);
            return;
        }
        endGap();
        int k = removedIndex(old, s);
        int col = cols[0];
        String focusedId = col < old.cards.size() ? old.cards.get(col).id : null;
        if (k >= 0 && !Ui.reducedMotion) {
            gone = old.cards.get(k);
            goneAt = k;
            goneFocus = focusAmount(0, k);
            gapT = 0f;
            gapAnim = ValueAnimator.ofFloat(0f, 1f);
            gapAnim.setDuration(Ui.SCROLL_MS);
            gapAnim.setInterpolator(Ui.EASE);
            gapAnim.addUpdateListener(a -> {
                gapT = (float) a.getAnimatedValue();
                if (gapT >= 1f) gone = null;
                invalidateRow(0);
            });
            gapAnim.start();
        }
        if (k >= 0 && col > k) col--;
        else if (k < 0) {
            // Otherwise focus stays with its card, wherever that is now
            for (int i = 0; focusedId != null && i < s.cards.size(); i++) if (focusedId.equals(s.cards.get(i).id)) col = i;
        }
        cols[0] = Math.max(0, Math.min(col, s.cards.size() - 1));
        if (row == 0) {
            if (!java.util.Objects.equals(focusedId, s.cards.get(cols[0]).id)) {
                // The card in its place takes focus, growing in as the gap closes
                focusRow = Integer.MIN_VALUE;
                markFocus(true);
            } else {
                focusCol = cols[0];
            }
            setCol(cols[0]);
        } else {
            animateRow(0, cols[0] * pitch(model.shelves.get(0)));
        }
        invalidateRow(0);
    }

    private static boolean sameCards(PageModel.Shelf a, PageModel.Shelf b) {
        if (a.cards.size() != b.cards.size()) return false;
        for (int i = 0; i < a.cards.size(); i++) {
            if (!java.util.Objects.equals(a.cards.get(i).id, b.cards.get(i).id)) return false;
        }
        return true;
    }

    /** The index of the one card {@code s} lacks of {@code old}'s, the others the same in order; else -1. */
    private static int removedIndex(PageModel.Shelf old, PageModel.Shelf s) {
        if (old.cards.size() != s.cards.size() + 1) return -1;
        int k = 0;
        while (k < s.cards.size() && java.util.Objects.equals(old.cards.get(k).id, s.cards.get(k).id)) k++;
        for (int i = k; i < s.cards.size(); i++) {
            if (!java.util.Objects.equals(old.cards.get(i + 1).id, s.cards.get(i).id)) return -1;
        }
        return k;
    }

    private void endGap() {
        if (gapAnim != null) gapAnim.cancel();
        gone = null;
        gapT = 1f;
    }

    /** A card taken off the top shelf is still fading while the gap closes (tests). */
    boolean closingGap() {
        return gone != null;
    }

    /** The top shelf (Continue Watching), or null. */
    PageModel.Shelf topShelf() {
        return top;
    }

    /** The card focus is on, or null on the hero. */
    PageModel.Card focusedCard() {
        if (model == null || row < 0 || row >= model.shelves.size()) return null;
        PageModel.Shelf s = model.shelves.get(row);
        return cols[row] < s.cards.size() ? s.cards.get(cols[row]) : null;
    }

    /** Where the focused card is on screen, grown as focus grows it (for the menu held Select brings up); false on the hero. */
    boolean focusedCardBounds(RectF out) {
        if (focusedCard() == null) return false;
        PageModel.Shelf s = model.shelves.get(row);
        boolean poster = s.style == PageModel.Style.RANKED;
        float w = Ui.px(poster ? POSTER_W : CARD_W), h = Ui.px(poster ? POSTER_H : CARD_H);
        float x = Ui.px(LEFT) + cols[row] * pitch(s) - rowX[row] + (poster ? Ui.px(RANK_W) : 0);
        float y = rowTop[row] - targetScroll() + Ui.px(TITLE_H);
        float grow = (CARD_SCALE - 1f) * focusT;
        out.set(x, y - h * grow / 2f, x + w + w * grow, y + h + h * grow / 2f);
        return true;
    }

    /** A card's menu opened or closed over the page: the rest dims back while it is open. */
    void setMenuOpen(boolean open, boolean animate) {
        if (menuAnim != null) menuAnim.cancel();
        float to = open ? 1f : 0f;
        if (!animate || Ui.reducedMotion) {
            menuT = to;
            applyMenu();
            return;
        }
        menuAnim = ValueAnimator.ofFloat(menuT, to);
        menuAnim.setDuration(Ui.FOCUS_MS);
        menuAnim.setInterpolator(Ui.EASE);
        menuAnim.addUpdateListener(a -> {
            menuT = (float) a.getAnimatedValue();
            applyMenu();
        });
        menuAnim.start();
    }

    /** Shelves other than the focused one dim as views (no drawing again); the focused one dims its other cards itself. */
    private void applyMenu() {
        for (int r = 0; r < rows.length; r++) rows[r].setAlpha(r == focusRow ? 1f : 1f - MENU_DIM * menuT);
        invalidateRow(focusRow);
    }

    boolean menuOpen() {
        return menuT > 0f;
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
        int body = s.style == PageModel.Style.RANKED ? POSTER_H : CARD_H;
        return Ui.px(TITLE_H + body + CAPTION_H + ROW_GAP);
    }

    private float pitch(PageModel.Shelf s) {
        return s.style == PageModel.Style.RANKED ? Ui.px(RANK_W + POSTER_W + POSTER_GAP) : Ui.px(CARD_W + CARD_GAP);
    }

    private float itemWidth(PageModel.Shelf s) {
        return s.style == PageModel.Style.RANKED ? Ui.px(RANK_W + POSTER_W) : Ui.px(CARD_W);
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

    /** {@code fresh}: a new press, not the remote held down; only that goes from the last card back to the first. */
    void right(boolean fresh) {
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
        int last = model.shelves.get(row).cards.size() - 1;
        if (cols[row] < last) setCol(cols[row] + 1);
        else if (fresh && last > 0) setCol(0);
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
        // The chosen card stays at the left, under the shelf's title; the cards before it slide off that side
        animateRow(row, c * pitch(model.shelves.get(row)));
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
        boolean rowChanged = newRow != focusRow;
        prevRow = focusRow;
        prevCol = focusCol;
        prevT = focusT;
        focusRow = newRow;
        focusCol = newCol;
        if (rowChanged && menuT > 0f) applyMenu();
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
        prefetchNextHero();
    }

    /** The next hero's artwork, decoded ahead (it turns every 8 s), so it and the glass above it are right as it shows. */
    private void prefetchNextHero() {
        if (model == null || model.hero.size() < 2 || getWidth() <= 0) return;
        PageModel.Feature next = model.hero.get((hero + 1) % model.hero.size());
        if (next.game == null && next.imageUrl != null) images.get(next.imageUrl, getWidth(), false, null);
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

    /**
     * What the glass of the bars above the page shows: the hero's background while the hero is up, and once the page
     * has scrolled down to the shelves, what is behind them there (Home's own background, darkened), not the hero's
     * picture that has scrolled away.
     */
    Bitmap barBackdrop() {
        return intoShelves && model != null ? shelvesBackdrop() : backdrop();
    }

    /** The shelves' background, blurred small; made once for each background. */
    private Bitmap shelvesFrost;

    private Bitmap shelvesBackdrop() {
        if (shelvesFrost != null) return shelvesFrost;
        Bitmap small = Bitmap.createBitmap(Backdrop.W, Backdrop.H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(small);
        drawHome(c, Backdrop.W, Backdrop.H, SHELF_DIM);
        // Made for the small size: the screen's own is made again for the next frame
        homeShader = null;
        // The same darker top as on the page, under the bar
        if (getHeight() > 0) {
            float k = Backdrop.H / (float) getHeight();
            c.save();
            c.scale(Backdrop.W / (float) getWidth(), k);
            drawTopDim(c, getWidth(), Ui.px(FOCUS_ROW_TOP - 10), 1f);
            c.restore();
        }
        Backdrop.blur(small, Backdrop.RADIUS);
        // Graphite until Home's background has loaded: not kept, so the picture is used once it is there
        if (home != null) shelvesFrost = small;
        return small;
    }

    /** A small blurred copy of what is behind the shelves as they are now, for glass over them (a card's menu). */
    Bitmap sceneBackdrop() {
        Bitmap small = Bitmap.createBitmap(Backdrop.W, Backdrop.H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(small);
        if (getWidth() > 0 && getHeight() > 0) c.scale(Backdrop.W / (float) getWidth(), Backdrop.H / (float) getHeight());
        drawBackdrop(c);
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
        return f == null || f.imageUrl == null ? null : images.get(f.imageUrl, w, false, heroArtIn);
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
            // The shelf above the focused one fades into the background at the top edge instead of
            // being cut off: the background is drawn again over it there, clear toward the shelves
            float fadeH = Ui.px(FOCUS_ROW_TOP - 10);
            int layer = c.saveLayerAlpha(0, 0, w, fadeH, Math.round(255 * scrolled));
            c.clipRect(0, 0, w, fadeH);
            drawChild(c, backdropLayer, getDrawingTime());
            topFade.setShader(cached("topfade" + fadeH, () -> new LinearGradient(0, 0, 0, fadeH,
                    new int[] {0xFF000000, 0xF2000000, 0x00000000}, new float[] {0f, 0.6f, 1f}, Shader.TileMode.CLAMP)));
            c.drawRect(0, 0, w, fadeH, topFade);
            c.restoreToCount(layer);
            drawTopDim(c, w, fadeH, scrolled);
        }
    }

    /** Keeps the background where the gradient is, so it covers the shelf above with itself. */
    private final Paint topFade = new Paint();
    {
        topFade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }

    private final Paint topDim = new Paint();

    /**
     * The background a little darker toward the top edge, under the bar, but not black: the
     * picture still shows there. The bar's glass frosts the same ({@link #shelvesBackdrop}).
     */
    private void drawTopDim(Canvas c, int w, float fadeH, float alpha) {
        topDim.setShader(cached("topdim" + fadeH, () -> new LinearGradient(0, 0, 0, fadeH,
                TOP_DIM, 0x00000000, Shader.TileMode.CLAMP)));
        topDim.setAlpha(Math.round(255 * alpha));
        c.drawRect(0, 0, w, fadeH, topDim);
    }

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
        float pitch = pitch(s), focusedX = Float.NaN;
        boolean closing = r == 0 && s == this.top && gone != null;
        // While a card's menu is open, the shelf's other cards dim back
        boolean dim = r == focusRow && menuT > 0f;
        if (dim) c.saveLayerAlpha(0, 0, w, rows[r].getHeight(), Math.round(255 * (1f - MENU_DIM * menuT)));
        if (closing) drawGone(c, s, Ui.px(LEFT) + goneAt * pitch - rowX[r], cardTop);
        int first = Math.max(0, (int) Math.floor((rowX[r] - Ui.px(LEFT)) / pitch) - 1);
        for (int i = first; i < s.cards.size(); i++) {
            float x = Ui.px(LEFT) + i * pitch - rowX[r] + roomBefore(r, i, s);
            if (closing && i >= goneAt) x += pitch * (1f - gapT);
            if (x > w) break;
            float t = focusAmount(r, i);
            if (r == focusRow && i == focusCol && t > 0f) {
                focusedX = x;
                continue;   // drawn last, above its neighbours
            }
            drawCard(c, s, s.cards.get(i), x, cardTop, t, 1f);
        }
        if (dim) c.restore();
        if (!Float.isNaN(focusedX)) drawCard(c, s, s.cards.get(focusCol), focusedX, cardTop, focusT, 1f - menuT);
        // Ask for the next card's artwork before it scrolls in
        int ahead = (int) ((rowX[r] + w) / pitch) + 1;
        if (ahead < s.cards.size()) prefetch(s, s.cards.get(ahead));
    }

    /** The card taken away, fading and shrinking a little where it was while the gap closes. */
    private void drawGone(Canvas c, PageModel.Shelf s, float x, float top) {
        float fade = Math.max(0f, 1f - gapT * 1.6f);
        if (fade <= 0f) return;
        float w = Ui.px(CARD_W), h = Ui.px(CARD_H), k = 1f - 0.12f * gapT;
        int saved = c.saveLayerAlpha(x - w, top - h, x + 2 * w, top + 2 * h, Math.round(255 * fade));
        c.scale(k, k, x + w / 2f, top + h / 2f);
        drawCard(c, s, gone, x, top, goneFocus, 1f);
        c.restoreToCount(saved);
    }

    /** How far card {@code i} moves along for the grown cards before it (the chosen one, and the one settling back). */
    private float roomBefore(int r, int i, PageModel.Shelf s) {
        float grow = (CARD_SCALE - 1f) * Ui.px(s.style == PageModel.Style.RANKED ? POSTER_W : CARD_W);
        float room = 0f;
        if (r == focusRow && i > focusCol) room += grow * focusT;
        if (r == prevRow && i > prevCol && prevCol != focusCol) room += grow * prevT;
        return room;
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

    /** One card at {@code x}, grown by {@code focus}; {@code captions} is how much its title and line underneath show. */
    private void drawCard(Canvas c, PageModel.Shelf s, PageModel.Card card, float x, float top, float focus, float captions) {
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
        float cy = top + h / 2f;
        c.save();
        c.scale(scale, scale, ix, cy);
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
            case CONTINUE:
                Bitmap b = card.imageUrl == null ? null : images.get(card.imageUrl, cardImageWidth(s), false, invalidator);
                if (b != null) drawBitmap(c, b, rect, r);
                else if (card.imageUrl != null) drawImage(c, null, rect, r, 0);   // its artwork is on the way
                else drawTitleCard(c, card.title, w, h, r);
                drawProgress(c, card.progress, w, h, r);
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
        rim.setColor(0x1FFFFFFF);
        float in = rim.getStrokeWidth() / 2f;
        rect.inset(in, in);
        c.drawRoundRect(rect, r - in, r - in, rim);
        if (focus > 0f) {
            // A white outline just off the card, with a dark gap between, drawn at the same width however big the card
            float ringW = Ui.px(RING_W) / scale, out = (Ui.px(RING_GAP) + Ui.px(RING_W) / 2f) / scale;
            ring.setStrokeWidth(ringW);
            ring.setAlpha(Math.round(255 * focus));
            rect.set(-out, -out, w + out, h + out);
            c.drawRoundRect(rect, r + out, r + out, ring);
        }
        c.restore();
        if (captions <= 0f) return;
        if (captions < 1f) {
            cardTitle.setAlpha(Math.round(0xF2 * captions));
            cardSub.setAlpha(Math.round(0x99 * captions));
        }
        // Captions move down with the grown card instead of being covered by it
        float capTop = top + h + (scale - 1f) * h / 2f;
        c.drawText(TextUtils.ellipsize(card.title, cardTitle, w, TextUtils.TruncateAt.END).toString(), ix, capTop + Ui.px(34), cardTitle);
        if (!card.subtitle.isEmpty()) {
            c.drawText(TextUtils.ellipsize(card.subtitle, cardSub, w, TextUtils.TruncateAt.END).toString(), ix, capTop + Ui.px(62), cardSub);
        }
        cardTitle.setAlpha(0xF2);
        cardSub.setAlpha(0x99);
    }

    private final TextPaint titleCard = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** A card with no artwork (FirePlayer couldn't match it on TMDB): its title, centred on graphite. */
    private void drawTitleCard(Canvas c, String title, float w, float h, float radius) {
        fill.setColor(0xFF000000);   // opaque: a shader takes the paint's alpha
        fill.setShader(cached("titlecard" + h, () -> new LinearGradient(0, 0, w, h, 0xFF363A42, 0xFF1E2025, Shader.TileMode.CLAMP)));
        c.drawRoundRect(rect, radius, radius, fill);
        fill.setShader(null);
        String t = TextUtils.ellipsize(title, titleCard, w - Ui.px(48), TextUtils.TruncateAt.END).toString();
        c.drawText(t, w / 2f, h * 0.46f - (titleCard.ascent() + titleCard.descent()) / 2f, titleCard);
    }

    /**
     * How far it has been watched: a thin blue bar (Control Center's blue, lit a little brighter
     * at its top edge, as glass catches light) on a faint frosted track near the card's foot, over
     * a soft shade so it reads on bright artwork too.
     */
    private void drawProgress(Canvas c, float progress, float w, float h, float radius) {
        if (progress < 0f) return;
        progressPaint.setColor(0xFF000000);
        progressPaint.setShader(cached("progressshade" + h, () -> new LinearGradient(0, h * 0.55f, 0, h,
                0x00000000, 0x8C000000, Shader.TileMode.CLAMP)));
        c.drawRoundRect(rect, radius, radius, progressPaint);
        progressPaint.setShader(null);
        float side = Ui.px(18), bar = Ui.px(6), bottom = h - Ui.px(16), track = w - 2 * side;
        RectF r = chipBox;
        r.set(side, bottom - bar, side + track, bottom);
        progressPaint.setColor(0x59FFFFFF);
        c.drawRoundRect(r, bar / 2f, bar / 2f, progressPaint);
        r.right = side + Math.max(bar, track * progress);
        final float top = r.top, foot = r.bottom;
        progressPaint.setColor(0xFF000000);
        progressPaint.setShader(cached("progressblue" + h, () -> new LinearGradient(0, top, 0, foot,
                0xFF4DB0FF, 0xFF0088FF, Shader.TileMode.CLAMP)));
        c.drawRoundRect(r, bar / 2f, bar / 2f, progressPaint);
        progressPaint.setShader(null);
        rect.set(0, 0, w, h);
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
            // Each team's own colour, a little deeper towards the split down the middle
            int away = FeaturePainter.teamTone(g.away.color, FeaturePainter.CARD_TONE);
            int home = FeaturePainter.teamTone(g.home.color, FeaturePainter.CARD_TONE);
            teams = new LinearGradient(0, 0, w, h,
                    new int[] {away, FeaturePainter.shade(away, 0.88f), FeaturePainter.shade(home, 0.88f), home},
                    new float[] {0f, 0.48f, 0.52f, 1f}, Shader.TileMode.CLAMP);
        }

        private String chipText(String text) {
            return TextUtils.ellipsize(text, chip, Ui.px(150), TextUtils.TruncateAt.END).toString();
        }
    }

    private static final int SCORE_LOGO = 76;
    private final Map<Sports.Game, ScoreCard> scoreCards = new java.util.HashMap<>();
    private Shader scoreShade, logoHalo;
    private final Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF chipBox = new RectF(), logoBox = new RectF();

    /** A game: the teams' colours split down the card, both logos, the score or the start time, the channel. */
    private void drawScoreCard(Canvas c, Sports.Game g, float w, float h, float radius) {
        ScoreCard card = scoreCards.get(g);
        if (card == null) {
            card = new ScoreCard(g, w, h);   // not in the model's score shelves: worked out now, once
            scoreCards.put(g, card);
        }
        if (scoreShade == null) scoreShade = new LinearGradient(0, 0, 0, h, 0x14FFFFFF, 0x40000000, Shader.TileMode.CLAMP);
        rect.set(0, 0, w, h);
        fill.setColor(0xFF000000);   // opaque: the paint keeps the alpha of whatever it drew last, which would show through
        fill.setShader(card.teams);
        c.drawRoundRect(rect, radius, radius, fill);
        fill.setShader(scoreShade);
        c.drawRoundRect(rect, radius, radius, fill);
        fill.setShader(null);
        RectF r = rect;
        float logo = Ui.px(SCORE_LOGO), ly = h * 0.42f;
        float ax = w * 0.17f, hx = w - w * 0.17f, mid = w / 2f;
        // A soft shadow behind each logo, which is often in the very colour it stands on
        if (logoHalo == null) {
            logoHalo = new android.graphics.RadialGradient(0, 0, logo * 0.8f, 0x66000000, 0x00000000, Shader.TileMode.CLAMP);
        }
        halo.setShader(logoHalo);
        for (float x : new float[] {ax, hx}) {
            c.save();
            c.translate(x, ly);
            c.drawCircle(0, 0, logo * 0.8f, halo);
            c.restore();
        }
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
