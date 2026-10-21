package app.glasshome.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.view.View;

/**
 * Full-screen detail page for a title or a game: artwork, title, facts, and where to watch it on
 * this TV. It opens at once with what the shelf already knew and fills in when the details
 * arrive. Left/Right move between its buttons, Select opens one, Back closes it.
 */
final class DetailView extends View {
    private final FeaturePainter painter;
    private final ImageCache images;
    private final TextPaint credit = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Runnable invalidator = this::invalidate;
    private PageModel.Feature feature;
    private String creditText = "";
    private int button;

    DetailView(Context context) {
        super(context);
        painter = new FeaturePainter(context);
        images = ImageCache.get(context);
        FeaturePainter.style(credit, context, 400, 17, 0x8CFFFFFF);
        credit.setTextAlign(Paint.Align.RIGHT);
        setFocusable(true);
        setDefaultFocusHighlightEnabled(false);
        setVisibility(GONE);
    }

    /** Shows (or refreshes) the page; the focused button is kept when the same item is refreshed. */
    void show(PageModel.Feature f, String creditLine) {
        boolean same = feature != null && f != null && feature.ref == f.ref;
        feature = f;
        creditText = creditLine == null ? "" : creditLine;
        if (!same) button = 0;
        button = Math.min(button, Math.max(0, f.actions.size() - 1));
        invalidate();
    }

    boolean isOpen() {
        return getVisibility() == VISIBLE;
    }

    PageModel.Feature feature() {
        return feature;
    }

    int focusedButton() {
        return button;
    }

    void left() {
        if (button > 0) {
            button--;
            invalidate();
        }
    }

    void right() {
        if (feature != null && button < feature.actions.size() - 1) {
            button++;
            invalidate();
        }
    }

    PageModel.Action focusedAction() {
        return feature == null || feature.actions.isEmpty() ? null : feature.actions.get(button);
    }

    @Override
    protected void onDraw(Canvas c) {
        if (feature == null) return;
        int w = getWidth(), h = getHeight();
        Bitmap art = feature.imageUrl == null ? null : images.get(feature.imageUrl, w, false, invalidator);
        painter.drawBackground(c, w, h, feature, art, 0.2f, 1f);
        if (feature.game != null) {
            Bitmap away = images.get(Sports.logo(feature.game.away.logo, 300), Ui.px(220), true, invalidator);
            Bitmap home = images.get(Sports.logo(feature.game.home.logo, 300), Ui.px(220), true, invalidator);
            painter.drawGame(c, feature.game, away, home, Ui.px(400));
        }
        Bitmap logo = feature.logoUrl == null ? null : images.get(feature.logoUrl, Ui.px(600), true, invalidator);
        painter.drawText(c, Ui.px(106), Ui.px(930), feature, logo, isFocused() ? button : -1, 4,
                Ui.px(feature.game != null ? 820 : 940));
        if (!creditText.isEmpty()) c.drawText(creditText, Ui.px(1814), Ui.px(1040), credit);
    }
}
