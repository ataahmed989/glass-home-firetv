package app.glasshome.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Artwork from TMDB and ESPN. Views ask for an image on every draw: a hit returns it, a miss starts
 * one download-and-decode and calls back on the main thread when it is ready. Images are decoded
 * no larger than they are drawn, kept in memory (LRU by size) and on disk.
 *
 * A load that fails (the TV's DNS through Tailscale drops out now and then) is tried again on its
 * own while a view is waiting for it, a few seconds later and then less and less often, so a cover
 * comes in once it can without anything on screen having to ask again. A picture that comes over the
 * network says the network is back: the others from there that failed are tried again at once.
 */
final class ImageCache {
    /** A failed load is tried again 2 s later, then twice as long each time, up to 2 minutes apart... */
    private static final long RETRY_FIRST_MS = 2_000, RETRY_MAX_MS = 120_000;
    /** ...for about half an hour; then it rests until it is drawn again, or Clear Cache... */
    private static final int MAX_TRIES = 20;
    /** ...but for want of the network, it is still tried this often while a view waits for it (a long outage). */
    private static final long RETRY_SLOW_MS = 10 * 60_000;
    /** Tried again at once when another from the same server comes in, so many at a time: each that comes brings on more. */
    private static final int BRING_ON = 6;
    private static final long DISK_LIMIT = 160L * 1024 * 1024, DISK_TRIM_TO = 110L * 1024 * 1024;
    /**
     * Who the loaders take first: covers being drawn, then those being drawn that failed before (they
     * may well fail again), then covers fetched ahead of a scroll, then tidying the disk.
     */
    private static final int NOW = 0, AGAIN = 1, AHEAD = 2, TIDY = 3;
    private static ImageCache instance;

    private final LruCache<String, Bitmap> memory;
    private final File dir;
    private final ThreadPoolExecutor pool = new ThreadPoolExecutor(3, 3, 0L, TimeUnit.MILLISECONDS, new PriorityBlockingQueue<>());
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Images loading, waiting to be tried again, or resting after failing; by url#width. Main thread. */
    private final Map<String, Load> loads = new HashMap<>();

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
        pool.execute(new Job(TIDY) {
            @Override
            public void run() {
                trimDisk();
            }
        });
    }

    static synchronized ImageCache get(Context context) {
        if (instance == null) instance = new ImageCache(context.getApplicationContext());
        return instance;
    }

    /**
     * Lets go of the decoded artwork kept in memory (Clear Cache); what's on screen keeps its own.
     * Clear Cache is also the way to say "try again": what failed is tried at once for the views
     * still waiting for it, and forgotten otherwise. Main thread.
     */
    void clearMemory() {
        memory.evictAll();
        for (Iterator<Load> it = loads.values().iterator(); it.hasNext(); ) {
            Load load = it.next();
            if (load.inPool) continue;
            main.removeCallbacks(load.retry);
            load.retryDue = false;
            load.tries = 0;
            load.notBefore = 0;
            for (Runnable r : load.waiters()) load.hold(r);
            if (load.held.isEmpty()) it.remove();
            else start(load);
        }
    }

    /** Tests start each case with a fresh app sandbox, so the cache must not outlive it. */
    static synchronized void reset() {
        if (instance != null) {
            instance.pool.shutdownNow();
            instance.main.removeCallbacksAndMessages(null);
        }
        instance = null;
    }

    /**
     * The image at {@code url}, decoded about {@code width} px wide, or null while it loads (then
     * {@code onLoaded} runs once it is ready, however many tries that takes). {@code alpha}: keep
     * transparency (logos). Without {@code onLoaded} it is fetched ahead: after anything being drawn,
     * and not tried again unless it is drawn.
     */
    Bitmap get(String url, int width, boolean alpha, Runnable onLoaded) {
        if (url == null || url.isEmpty()) return null;
        String key = url + "#" + width;
        Bitmap hit = memory.get(key);
        if (hit != null) return hit;
        Load load = loads.get(key);
        if (load == null) {
            load = new Load(key, url, width, alpha);
            loads.put(key, load);
        }
        if (onLoaded != null) load.waitFor(onLoaded);
        if (load.inPool) {
            if (onLoaded != null) promote(load);
        } else {
            long wait = load.notBefore - SystemClock.uptimeMillis();
            if (wait <= 0) {
                // First asked, or it failed long enough ago (it rests, or was only fetched ahead)
                main.removeCallbacks(load.retry);
                start(load);
            } else if (onLoaded != null && !load.retryDue) {
                tryAgainIn(load, wait);   // failed lately: when it may, not on every draw
            }
        }
        return null;
    }

    /**
     * Another app is in front: give most decoded images back (they come from disk again quickly),
     * keeping the most recently drawn quarter, Home's own, so nothing pops in on the way back.
     */
    void trimMemory() {
        memory.trimToSize(memory.maxSize() / 4);
    }

    /** Loads under way, not those waiting to be tried again (tests wait for these before capturing a screen). */
    int pending() {
        int n = 0;
        for (Load load : loads.values()) if (load.inPool) n++;
        return n;
    }

    /** Already in memory, without starting a load. */
    Bitmap peek(String url, int width) {
        return url == null ? null : memory.get(url + "#" + width);
    }

    // ---- Loading ----------------------------------------------------------------------------

    /** Work for the loaders, in order of rank and then oldest first. */
    private abstract static class Job implements Runnable, Comparable<Job> {
        private static final AtomicLong queued = new AtomicLong();
        // Changed only while out of the queue
        volatile int rank;
        volatile long order = queued.incrementAndGet();

        Job(int rank) {
            this.rank = rank;
        }

        @Override
        public int compareTo(Job o) {
            return rank != o.rank ? Integer.compare(rank, o.rank) : Long.compare(order, o.order);
        }
    }

    /** One image at one size, from first asked until it comes in. Main thread, but for {@link #run}. */
    private final class Load extends Job {
        final String key, url, host;
        final int width;
        final boolean alpha;
        /** Who is waiting for it, weakly, so that a view long gone isn't kept alive... */
        private final List<WeakReference<Runnable>> waiting = new ArrayList<>();
        /** ...and for sure while it loads and is tried again (about half an hour from its last fresh start), however the caller keeps it. */
        final List<Runnable> held = new ArrayList<>();
        final Runnable retry = () -> tryAgain(this);
        boolean inPool, retryDue;
        /** It last failed for want of the network (no answer at all), not for what the server sent. */
        boolean offline;
        int tries;
        long notBefore;
        /** This try downloaded it, rather than finding it on disk. Loader thread. */
        boolean fetched;

        Load(String key, String url, int width, boolean alpha) {
            super(AHEAD);
            this.key = key;
            this.url = url;
            this.width = width;
            this.alpha = alpha;
            host = String.valueOf(Uri.parse(url).getHost());
        }

        void waitFor(Runnable r) {
            hold(r);
            waiting.removeIf(w -> w.get() == null);
            for (WeakReference<Runnable> w : waiting) if (w.get() == r) return;
            waiting.add(new WeakReference<>(r));
        }

        void hold(Runnable r) {
            if (!held.contains(r)) held.add(r);
        }

        List<Runnable> waiters() {
            List<Runnable> live = new ArrayList<>();
            for (WeakReference<Runnable> w : waiting) {
                Runnable r = w.get();
                if (r != null) live.add(r);
            }
            return live;
        }

        @Override
        public void run() {
            Bitmap loaded = null;
            Throwable error = null;
            fetched = false;
            try {
                loaded = load(this);
            } catch (Throwable e) {
                // An OutOfMemoryError too: the load has to end either way, or it would stay "loading" for good
                error = e;
            }
            Bitmap result = loaded;
            Throwable failure = error;
            boolean overNetwork = fetched;
            main.post(() -> finished(this, result, failure, overNetwork));
        }
    }

    private void start(Load load) {
        load.retryDue = false;
        load.inPool = true;
        load.rank = load.held.isEmpty() ? AHEAD : load.tries == 0 ? NOW : AGAIN;
        load.order = Job.queued.incrementAndGet();
        try {
            pool.execute(load);
        } catch (RejectedExecutionException e) {
            load.inPool = false;   // closed (tests)
        }
    }

    /** Fetched ahead and now being drawn: it goes before the others fetched ahead. */
    private void promote(Load load) {
        if (load.rank != AHEAD) return;
        if (pool.getQueue().remove(load)) {
            load.rank = load.tries == 0 ? NOW : AGAIN;
            try {
                pool.execute(load);
            } catch (RejectedExecutionException e) {
                load.inPool = false;
            }
        }
    }

    private void tryAgainIn(Load load, long ms) {
        load.retryDue = true;
        main.postDelayed(load.retry, ms);
    }

    /** Its time to try again came, or the network came back. */
    private void tryAgain(Load load) {
        load.retryDue = false;
        if (load.held.isEmpty()) {
            // It rests: tried only for a view still waiting for it, and held for sure while it loads
            List<Runnable> live = load.waiters();
            if (live.isEmpty()) {
                loads.remove(load.key, load);
                return;
            }
            for (Runnable r : live) load.hold(r);
        }
        start(load);
    }

    private void finished(Load load, Bitmap loaded, Throwable error, boolean overNetwork) {
        load.inPool = false;
        if (loaded != null) {
            loads.remove(load.key);
            memory.put(load.key, loaded);
            for (Runnable r : load.waiters()) r.run();
            if (overNetwork) bringOn(load.host);
            return;
        }
        // Short of memory: give some back before it is tried again
        if (error instanceof OutOfMemoryError) memory.trimToSize(memory.maxSize() / 2);
        load.tries++;
        load.offline = error instanceof IOException && !(error instanceof Net.HttpError);
        // A picture the server says isn't there rests at once; anything else is tried again, less often each time
        boolean lasting = error instanceof Net.HttpError && ((Net.HttpError) error).lasting();
        long wait = lasting ? RETRY_MAX_MS : Math.min(RETRY_MAX_MS, RETRY_FIRST_MS << Math.min(load.tries - 1, 16));
        load.notBefore = SystemClock.uptimeMillis() + wait;
        if (!lasting && load.tries < MAX_TRIES && !load.held.isEmpty()) {
            tryAgainIn(load, wait);
            return;
        }
        // Rests: whoever is waiting is kept only weakly until it is drawn again (or Clear Cache)...
        load.held.clear();
        // ...but while the network is out, it is still tried now and then for a view still waiting for it:
        // a shelf held still through a long outage (nothing drawn again) gets its covers once it is back
        if (load.offline && !load.waiters().isEmpty()) tryAgainIn(load, RETRY_SLOW_MS);
    }

    /**
     * A picture just came over the network from {@code host}, so the network is back: those from
     * there that failed for want of it, and are still waited for, are tried again now and from the
     * start, rather than when their wait is up (up to 2 minutes, or 10 once they rest).
     */
    private void bringOn(String host) {
        List<Load> due = new ArrayList<>();
        for (Load other : loads.values()) {
            if (!other.retryDue || !other.offline || !other.host.equals(host)) continue;
            due.add(other);
            if (due.size() == BRING_ON) break;
        }
        for (Load other : due) {
            main.removeCallbacks(other.retry);
            other.tries = 0;
            tryAgain(other);
        }
    }

    /** Downloads (once) and decodes; null if what came is no picture. On a loader thread. */
    private Bitmap load(Load job) throws IOException {
        String url = job.url;
        int width = job.width;
        File file = new File(dir, Net.sha1(url));
        if (!file.isFile()) {
            Net.write(file, Net.picture(url));
            job.fetched = true;
        }
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
        o.inPreferredConfig = job.alpha ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
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
