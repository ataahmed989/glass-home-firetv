package app.glasshome.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Artwork from TMDB and ESPN. Views ask for an image on every draw: a hit returns it, a miss starts
 * one download-and-decode and calls back on the main thread when it is ready. Images are decoded
 * no larger than they are drawn, kept in memory (LRU by size) and on disk.
 */
final class ImageCache {
    private static final long RETRY_MS = 60_000;
    private static final long DISK_LIMIT = 160L * 1024 * 1024, DISK_TRIM_TO = 110L * 1024 * 1024;
    private static ImageCache instance;

    private final LruCache<String, Bitmap> memory;
    private final File dir;
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, List<Runnable>> pending = new HashMap<>();
    private final Map<String, Long> failedAt = new HashMap<>();

    private ImageCache(Context context) {
        // Kept modest: the Fire TV Stick has under 2 GB shared with Fire OS, and decoding again from disk is quick
        int budget = (int) Math.min(48L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 5);
        memory = new LruCache<String, Bitmap>(budget) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getAllocationByteCount();
            }
        };
        dir = new File(context.getCacheDir(), "img");
        dir.mkdirs();
        pool.execute(this::trimDisk);
    }

    static synchronized ImageCache get(Context context) {
        if (instance == null) instance = new ImageCache(context.getApplicationContext());
        return instance;
    }

    /** Lets go of the decoded artwork kept in memory (Clear Cache); what's on screen keeps its own. Main thread. */
    void clearMemory() {
        memory.evictAll();
        failedAt.clear();
    }

    /** Tests start each case with a fresh app sandbox, so the cache must not outlive it. */
    static synchronized void reset() {
        if (instance != null) instance.pool.shutdownNow();
        instance = null;
    }

    /**
     * The image at {@code url}, decoded about {@code width} px wide, or null while it loads (then
     * {@code onLoaded} runs once it is ready). {@code alpha}: keep transparency (logos).
     */
    Bitmap get(String url, int width, boolean alpha, Runnable onLoaded) {
        if (url == null || url.isEmpty()) return null;
        String key = url + "#" + width;
        Bitmap hit = memory.get(key);
        if (hit != null) return hit;
        Long failed = failedAt.get(key);
        if (failed != null && Clock.now() - failed < RETRY_MS) return null;
        List<Runnable> waiting = pending.get(key);
        if (waiting != null) {
            if (onLoaded != null && !waiting.contains(onLoaded)) waiting.add(onLoaded);
            return null;
        }
        waiting = new ArrayList<>();
        if (onLoaded != null) waiting.add(onLoaded);
        pending.put(key, waiting);
        pool.execute(() -> {
            Bitmap loaded = load(url, width, alpha);
            main.post(() -> {
                List<Runnable> callbacks = pending.remove(key);
                if (loaded != null) {
                    memory.put(key, loaded);
                    failedAt.remove(key);
                } else {
                    failedAt.put(key, Clock.now());
                }
                if (callbacks != null && loaded != null) for (Runnable r : callbacks) r.run();
            });
        });
        return null;
    }

    /**
     * Another app is in front: give most decoded images back (they come from disk again quickly),
     * keeping the most recently drawn quarter, Home's own, so nothing pops in on the way back.
     */
    void trimMemory() {
        memory.trimToSize(memory.maxSize() / 4);
    }

    /** Loads still in flight (tests wait for these before capturing a screen). */
    int pending() {
        return pending.size();
    }

    /** Already in memory, without starting a load. */
    Bitmap peek(String url, int width) {
        return url == null ? null : memory.get(url + "#" + width);
    }

    private Bitmap load(String url, int width, boolean alpha) {
        File file = new File(dir, Net.sha1(url));
        try {
            if (!file.isFile()) Net.write(file, Net.get(url, null));
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), o);
            if (o.outWidth <= 0) {
                file.delete();
                return null;
            }
            int sample = 1;
            while (o.outWidth / (sample * 2) >= width) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            o.inPreferredConfig = alpha ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
            Bitmap b = BitmapFactory.decodeFile(file.getPath(), o);
            if (b == null) {
                file.delete();
                return null;
            }
            if (b.getWidth() > width * 1.4f) {
                Bitmap scaled = Bitmap.createScaledBitmap(b, width, Math.max(1, Math.round(b.getHeight() * width / (float) b.getWidth())), true);
                if (scaled != b) b.recycle();
                b = scaled;
            }
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    /** Keeps the disk cache bounded: oldest files go first. */
    private void trimDisk() {
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= DISK_LIMIT) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total <= DISK_TRIM_TO) break;
            total -= f.length();
            f.delete();
        }
    }
}
