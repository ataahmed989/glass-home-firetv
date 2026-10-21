package app.glasshome.launcher;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Two {@link HeroLayer}s; a new slide fades in over the old one, which is then hidden to save
 * overdraw. The stage itself is the hero's single focus target, so slideshow changes never move
 * focus: only the drawn content changes underneath it.
 */
final class HeroStage extends FrameLayout {
    private HeroLayer front, back;

    HeroStage(Context context) {
        super(context);
        back = new HeroLayer(context);
        front = new HeroLayer(context);
        back.setVisibility(INVISIBLE);
        addView(back, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addView(front, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setFocusable(true);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setDefaultFocusHighlightEnabled(false);
    }

    /** Only featured slides have an action ("Open …"); otherwise focus skips the hero. */
    boolean hasAction() {
        HeroSlide s = front.getSlide();
        return s != null && s.isFeatured();
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        front.setActionFocused(gainFocus);
    }

    HeroSlide current() {
        return front.getSlide();
    }

    void show(HeroSlide slide, long fadeMs) {
        if (front.getSlide() == slide) return;
        HeroLayer incoming = back;
        back = front;
        front = incoming;
        front.animate().cancel();
        front.setSlide(slide);
        front.setActionFocused(isFocused());
        back.setActionFocused(false);
        front.setVisibility(VISIBLE);
        front.bringToFront();
        if (fadeMs <= 0 || back.getSlide() == null) {
            front.setAlpha(1f);
            back.setVisibility(INVISIBLE);
            return;
        }
        front.setAlpha(0f);
        final View outgoing = back;
        // withLayer: the slide is rendered once into a GPU layer and only its alpha changes per frame
        front.animate().alpha(1f).setDuration(fadeMs).withLayer()
                .withEndAction(() -> { if (outgoing != front) outgoing.setVisibility(INVISIBLE); })
                .start();
    }
}
