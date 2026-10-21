package app.glasshome.launcher;

import android.graphics.Bitmap;

/** One presentation of the focused app: a featured title, the user's chosen background, or the app's branded look. */
final class HeroSlide {
    final String appKey;
    final Brand brand;
    final Bitmap logo;          // the app's 5:3 tile art
    final Bitmap background;    // featured backdrop or the user's background; null for the branded look
    final String label, title, meta, body;

    private HeroSlide(String appKey, Brand brand, Bitmap logo, Bitmap background, String label, String title, String meta, String body) {
        this.appKey = appKey;
        this.brand = brand;
        this.logo = logo;
        this.background = background;
        this.label = label;
        this.title = title;
        this.meta = meta;
        this.body = body;
    }

    static HeroSlide branded(String appKey, Brand brand, Bitmap logo) {
        return new HeroSlide(appKey, brand, logo, null, null, null, null, null);
    }

    /** The user's chosen background behind the app's logo card (or centred icon for utilities). */
    static HeroSlide backdrop(String appKey, Brand brand, Bitmap logo, Bitmap background) {
        return new HeroSlide(appKey, brand, logo, background, null, null, null, null);
    }

    static HeroSlide featured(String appKey, Brand brand, Bitmap logo, Bitmap backdrop, FeaturedTitles.Title t) {
        return new HeroSlide(appKey, brand, logo, backdrop, "Featured on " + brand.name, t.name, t.meta(), t.overview);
    }

    boolean isFeatured() {
        return background != null && title != null;
    }
}
