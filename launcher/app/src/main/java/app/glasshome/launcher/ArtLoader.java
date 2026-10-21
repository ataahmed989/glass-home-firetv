package app.glasshome.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * Tile artwork. Supplied 800x480 PNGs are used as-is (a sideloaded copy in the app's
 * external files dir wins over the bundled one); apps without one fall back to their banner.
 */
final class ArtLoader {
    private static final int ART_W = 800, ART_H = 480;

    private ArtLoader() {}

    /**
     * Layered art: <key>.l0.png (opaque back) plus .l1.png, .l2.png… (transparent, front-most last),
     * from the app's external files dir or the bundled assets. Null when the app has no layers.
     */
    static TileLayers loadLayers(Context context, AppEntry app, int targetWidth) {
        java.util.List<Bitmap> list = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String name = app.key + ".l" + i + ".png";
            File custom = new File(context.getExternalFilesDir("icons"), name);
            Bitmap b = custom.isFile() ? decodeFile(custom, targetWidth) : decodeAsset(context, "icons/" + name, targetWidth);
            if (b == null) break;
            list.add(b);
        }
        return list.size() >= 2 ? TileLayers.of(list) : null;
    }

    static Bitmap load(Context context, AppEntry app, int targetWidth) {
        File custom = new File(context.getExternalFilesDir("icons"), app.key + ".png");
        Bitmap art = custom.isFile() ? decodeFile(custom, targetWidth) : null;
        if (art == null) art = decodeAsset(context, "icons/" + app.key + ".png", targetWidth);
        if (art == null) art = fromApp(context, app);
        return art;
    }

    /** Halves the 800x480 source while it stays at least as wide as the focused tile, so it never upscales. */
    private static BitmapFactory.Options options(int sourceWidth, int targetWidth) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 1;
        while (sourceWidth / (o.inSampleSize * 2) >= targetWidth) o.inSampleSize *= 2;
        return o;
    }

    private static Bitmap decodeFile(File file, int targetWidth) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth <= 0) return null;
        return BitmapFactory.decodeFile(file.getPath(), options(bounds.outWidth, targetWidth));
    }

    private static Bitmap decodeAsset(Context context, String path, int targetWidth) {
        try (InputStream in = context.getAssets().open(path)) {
            return BitmapFactory.decodeStream(in, null, options(ART_W, targetWidth));
        } catch (IOException e) {
            return null;
        }
    }

    /** Renders the app's own banner (or icon) onto a 5:3 card for apps without supplied art. */
    private static Bitmap fromApp(Context context, AppEntry app) {
        PackageManager pm = context.getPackageManager();
        Drawable banner = null, icon = null;
        try {
            ComponentName component = app.launchIntent.getComponent();
            if (component != null) banner = pm.getActivityBanner(component);
            if (banner == null) banner = pm.getApplicationBanner(app.key);
            if (banner == null) icon = pm.getApplicationIcon(app.key);
        } catch (PackageManager.NameNotFoundException ignored) {
            // Settings and similar entries have no package of their own
        }
        int w = ART_W / 2, h = ART_H / 2;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(Color.rgb(36, 40, 50));
        if (banner != null) {
            // Banners are 16:9; scale to cover the 5:3 card
            float s = Math.max(w / (float) Math.max(1, banner.getIntrinsicWidth()), h / (float) Math.max(1, banner.getIntrinsicHeight()));
            int bw = Math.round(banner.getIntrinsicWidth() * s), bh = Math.round(banner.getIntrinsicHeight() * s);
            banner.setBounds((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2);
            banner.draw(canvas);
        } else {
            if (icon != null) {
                int size = h / 2;
                icon.setBounds((w - size) / 2, h / 6, (w + size) / 2, h / 6 + size);
                icon.draw(canvas);
            }
            Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
            text.setColor(Color.WHITE);
            text.setTextSize(h / 9f);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(Fonts.inter(context, 600));
            canvas.drawText(app.label, w / 2f, h * 0.86f, text);
        }
        return out;
    }
}
