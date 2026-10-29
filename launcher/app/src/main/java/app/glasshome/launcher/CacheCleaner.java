package app.glasshome.launcher;

import android.content.Context;

import java.io.File;
import java.util.Locale;

/**
 * Home Screen settings > Clear Cache: Glass Home's own cache (artwork, saved pages and scores, a
 * finished update download), as Fire OS's "Clear cache" for the app would. Folders stay, only
 * their files go, so whatever is writing to them carries on. Other apps' caches are Fire OS's to
 * clear: an app can't, without ADB.
 */
final class CacheCleaner {
    private CacheCleaner() {}

    /** Clears it and says how many bytes went; a download under way keeps its file. Blocks: off the main thread. */
    static long clear(Context c, boolean keepUpdates) {
        long freed = 0;
        for (File dir : new File[] {c.getCacheDir(), c.getExternalCacheDir(), c.getCodeCacheDir()}) {
            if (dir != null) freed += empty(dir, keepUpdates ? new File(c.getCacheDir(), "updates") : null);
        }
        return freed;
    }

    private static long empty(File dir, File keep) {
        long freed = 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        for (File f : files) {
            if (f.equals(keep)) continue;
            if (f.isDirectory()) {
                freed += empty(f, keep);
            } else {
                long size = f.length();
                if (f.delete()) freed += size;
            }
        }
        return freed;
    }

    /** "84 MB", "512 KB". */
    static String size(long bytes) {
        if (bytes >= 1024L * 1024) return String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024));
        return Math.max(1, Math.round(bytes / 1024.0)) + " KB";
    }
}
