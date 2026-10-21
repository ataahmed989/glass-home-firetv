package app.glasshome.launcher;

import android.graphics.Bitmap;

/** What fills the screen behind Home: the user's chosen background, or a featured title on the focused streaming app. */
final class HeroSlide {
    final String appKey;        // the app a featured title is on; null for the background
    final Brand brand;
    final Bitmap background;    // featured backdrop or the user's background; null until one has loaded
    final String label, title, meta, body;

    private HeroSlide(String appKey, Brand brand, Bitmap background, String label, String title, String meta, String body) {
        this.appKey = appKey;
        this.brand = brand;
        this.background = background;
        this.label = label;
        this.title = title;
        this.meta = meta;
        this.body = body;
    }

    /** The user's chosen background on its own: Home shows nothing about the focused app itself. */
    static HeroSlide backdrop(Bitmap background) {
        return new HeroSlide(null, null, background, null, null, null, null);
    }

    static HeroSlide featured(String appKey, Brand brand, Bitmap backdrop, FeaturedTitles.Title t) {
        return new HeroSlide(appKey, brand, backdrop, "Featured on " + brand.name, t.name, t.meta(), t.overview);
    }

    boolean isFeatured() {
        return background != null && title != null;
    }
}
