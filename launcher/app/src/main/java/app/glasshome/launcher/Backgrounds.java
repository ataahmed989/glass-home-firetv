package app.glasshome.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The user's background images: bundled in assets/backgrounds (numbered to keep the user's
 * order), plus any copied to the app's external files dir under backgrounds/ (which win on a name
 * clash). One is chosen in Settings and stays behind every app.
 */
final class Backgrounds {
    static final class Entry {
        final String name;     // file name, the stable id saved in settings
        final String title;    // shown in Settings
        final File file;       // null when bundled

        Entry(String name, String title, File file) {
            this.name = name;
            this.title = title;
            this.file = file;
        }
    }

    /** Kept lowercase inside a title: "Lake at Dusk". */
    private static final java.util.Set<String> SMALL_WORDS = new java.util.HashSet<>(
            java.util.Arrays.asList("a", "an", "and", "at", "by", "for", "in", "of", "on", "or", "the", "to"));

    private Backgrounds() {}

    static List<Entry> list(Context context) {
        List<Entry> out = new ArrayList<>();
        List<String> names = new ArrayList<>();
        File dir = context.getExternalFilesDir("backgrounds");
        File[] files = dir == null ? null : dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (isImage(f.getName())) {
                    out.add(entry(f.getName(), f));
                    names.add(f.getName());
                }
            }
        }
        try {
            String[] bundled = context.getAssets().list("backgrounds");
            if (bundled != null) {
                for (String n : bundled) if (isImage(n) && !names.contains(n)) out.add(entry(n, null));
            }
        } catch (IOException ignored) {
            // no bundled backgrounds
        }
        Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    static Entry find(List<Entry> list, String name) {
        for (Entry e : list) if (e.name.equals(name)) return e;
        return list.isEmpty() ? null : list.get(0);
    }

    /** Small preview for Settings. */
    static Bitmap thumbnail(Context context, Entry e) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = 8;
        return decode(context, e, o);
    }

    /** Decoded once per choice; RGB_565 halves memory for an opaque full-screen image. */
    static Bitmap decode(Context context, Entry e, int screenWidth) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        decode(context, e, bounds);
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        o.inSampleSize = 1;
        while (bounds.outWidth / (o.inSampleSize * 2) >= screenWidth) o.inSampleSize *= 2;
        return decode(context, e, o);
    }

    private static Bitmap decode(Context context, Entry e, BitmapFactory.Options o) {
        if (e.file != null) return BitmapFactory.decodeFile(e.file.getPath(), o);
        try (InputStream in = context.getAssets().open("backgrounds/" + e.name)) {
            return BitmapFactory.decodeStream(in, null, o);
        } catch (IOException ex) {
            return null;
        }
    }

    private static boolean isImage(String name) {
        String n = name.toLowerCase(Locale.US);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp");
    }

    /** "1-night-dunes.jpg" → "Night Dunes" (a leading number only sets the order) */
    private static Entry entry(String name, File file) {
        String base = name.substring(0, name.lastIndexOf('.')).replaceFirst("^\\d+[-_ ]*", "").replaceAll("[-_]+", " ").trim();
        StringBuilder t = new StringBuilder();
        for (String w : base.split(" ")) {
            if (w.isEmpty()) continue;
            boolean small = t.length() > 0 && SMALL_WORDS.contains(w.toLowerCase(Locale.US));
            if (t.length() > 0) t.append(' ');
            t.append(small ? w.toLowerCase(Locale.US) : Character.toUpperCase(w.charAt(0)) + w.substring(1));
        }
        Entry e = new Entry(name, t.toString(), file);
        return e;
    }
}
