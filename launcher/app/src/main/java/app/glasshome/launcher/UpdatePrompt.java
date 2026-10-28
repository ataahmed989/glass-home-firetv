package app.glasshome.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.View;

/**
 * A glass alert in the middle of the screen, as tvOS draws them: a title, a message, and its
 * buttons stacked below, the focused one white. It asks about Glass Home's updates (see
 * {@link Updater}), and shows a download's progress in place of the buttons. Up and Down move,
 * Select chooses, Back does what the alert says Back does (Later, as a rule). It is the only focus
 * target while open.
 */
final class UpdatePrompt extends View {
    private static final float CARD_W = 760, PAD = 52, BUTTON_H = 72, BUTTON_GAP = 14, RADIUS = 44;

    private final Glass glass;
    private final Paint scrim = new Paint();
    private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint title, body, label;
    private final RectF r = new RectF();
    private String heading = "", message = "";
    private String[] buttons = new String[0];
    private Runnable[] actions = new Runnable[0];
    private Runnable back;
    private int focused;
    /** Download progress in percent, -1 while its size isn't known; below that, no progress shown. */
    private int progress = -2;

    UpdatePrompt(Context context, Glass glass) {
        super(context);
        this.glass = glass;
        setFocusable(true);
        setDefaultFocusHighlightEnabled(false);
        setVisibility(GONE);
        scrim.setColor(0x80000000);
        title = text(context, 600, 34, 0xFFFFFFFF);
        body = text(context, 400, 23, 0xB3FFFFFF);
        label = text(context, 500, 25, 0xFFFFFFFF);
        title.setTextAlign(Paint.Align.CENTER);
        label.setTextAlign(Paint.Align.CENTER);
    }

    private static TextPaint text(Context c, int weight, int size, int color) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(Fonts.inter(c, weight));
        p.setTextSize(Ui.px(size));
        p.setColor(color);
        return p;
    }

    boolean isOpen() {
        return getVisibility() == VISIBLE;
    }

    /** Asks {@code heading}/{@code message}, with a button per label doing the matching action; {@code onBack} for Back. */
    void ask(String heading, String message, String[] labels, Runnable[] onChosen, Runnable onBack) {
        this.heading = heading;
        this.message = message;
        buttons = labels;
        actions = onChosen;
        back = onBack;
        focused = 0;
        progress = -2;
        invalidate();
    }

    /** A download under way: its progress instead of buttons; Back hides the alert, the download goes on. */
    void showProgress(String heading, int percent) {
        this.heading = heading;
        message = percent >= 0 ? percent + "%" : "";
        buttons = new String[0];
        actions = new Runnable[0];
        back = null;
        progress = percent;
        invalidate();
    }

    void move(int delta) {
        int to = focused + delta;
        if (to >= 0 && to < buttons.length) {
            focused = to;
            invalidate();
        }
    }

    int focusedIndex() {
        return focused;
    }

    String heading() {
        return heading;
    }

    String[] buttons() {
        return buttons;
    }

    void choose() {
        if (focused < actions.length && actions[focused] != null) actions[focused].run();
    }

    /** What Back does here; null when Back just hides the alert. */
    Runnable backAction() {
        return back;
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawRect(0, 0, getWidth(), getHeight(), scrim);
        float w = Ui.px(CARD_W), pad = Ui.px(PAD), inner = w - 2 * pad;
        StaticLayout text = StaticLayout.Builder.obtain(message, 0, message.length(), body, Math.round(inner))
                .setAlignment(Layout.Alignment.ALIGN_CENTER).setLineSpacing(Ui.px(4), 1f).build();
        float titleH = Ui.px(44), gap = Ui.px(14);
        float progressH = progress >= -1 ? Ui.px(34) : 0;
        float buttonsH = buttons.length == 0 ? 0 : Ui.px(30) + buttons.length * Ui.px(BUTTON_H) + (buttons.length - 1) * Ui.px(BUTTON_GAP);
        float h = pad + titleH + gap + progressH + (message.isEmpty() ? 0 : text.getHeight()) + buttonsH + pad;
        float left = (getWidth() - w) / 2f, top = (getHeight() - h) / 2f;
        r.set(left, top, left + w, top + h);
        glass.draw(c, r, Ui.px(RADIUS), getLeft(), getTop(), 0.35f);

        float cx = getWidth() / 2f, y = top + pad;
        c.drawText(heading, cx, y + Ui.px(34), title);
        y += titleH + gap;
        if (progress >= -1) {
            // A thin bar, filled as far as the download has got (a third, sliding, while the size isn't known)
            float barW = inner * 0.8f, bx = cx - barW / 2f, bh = Ui.px(8);
            r.set(bx, y + Ui.px(10), bx + barW, y + Ui.px(10) + bh);
            track.setColor(0x33FFFFFF);
            c.drawRoundRect(r, bh / 2f, bh / 2f, track);
            float done = progress >= 0 ? progress / 100f : 0.33f;
            r.right = r.left + Math.max(bh, barW * done);
            track.setColor(0xFFFFFFFF);
            c.drawRoundRect(r, bh / 2f, bh / 2f, track);
            y += progressH;
        }
        if (!message.isEmpty()) {
            c.save();
            c.translate(left + pad, y);
            text.draw(c);
            c.restore();
            y += text.getHeight();
        }
        if (buttons.length == 0) return;
        y += Ui.px(30);
        for (int i = 0; i < buttons.length; i++) {
            boolean f = i == focused && isFocused();
            r.set(left + pad, y, left + w - pad, y + Ui.px(BUTTON_H));
            pill.setColor(f ? 0xF7FFFFFF : 0x1FFFFFFF);
            c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, pill);
            label.setColor(f ? 0xFF111317 : 0xFFFFFFFF);
            Paint.FontMetrics fm = label.getFontMetrics();
            c.drawText(buttons[i], cx, r.centerY() - (fm.ascent + fm.descent) / 2f, label);
            y += Ui.px(BUTTON_H) + Ui.px(BUTTON_GAP);
        }
    }
}
