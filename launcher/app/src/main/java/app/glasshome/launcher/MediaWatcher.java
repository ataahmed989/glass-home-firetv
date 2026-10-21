package app.glasshome.launcher;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Notices what the streaming apps play, for Continue Watching. Android only lets an app see other
 * apps' media sessions while it holds notification-listener access, which Fire TV has no screen
 * for; it is granted once over ADB:
 * {@code cmd notification allow_listener app.glasshome.launcher/app.glasshome.launcher.MediaWatcher}.
 * Notifications themselves are never read.
 */
public final class MediaWatcher extends NotificationListenerService {
    private static final long SAVE_EVERY_MS = 30_000;

    /** The playing app's own "back to what you were watching" intent, while its session lives. */
    static final Map<String, PendingIntent> RESUME = new ConcurrentHashMap<>();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService disk = Executors.newSingleThreadExecutor();
    private final Map<MediaSession.Token, Watched> watched = new HashMap<>();
    private MediaSessionManager sessions;
    private final MediaSessionManager.OnActiveSessionsChangedListener changed = this::onSessions;

    private final class Watched extends MediaController.Callback {
        final MediaController controller;
        long lastSaved;

        Watched(MediaController controller) {
            this.controller = controller;
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
            // Every pause or stop is kept; while playing, a snapshot every 30 s is plenty
            if (!playing || SystemClock.elapsedRealtime() - lastSaved > SAVE_EVERY_MS) save(this);
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            save(this);
        }

        @Override
        public void onSessionDestroyed() {
            save(this);
            detach(controller.getSessionToken());
        }
    }

    @Override
    public void onListenerConnected() {
        sessions = getSystemService(MediaSessionManager.class);
        ComponentName me = new ComponentName(this, MediaWatcher.class);
        try {
            sessions.addOnActiveSessionsChangedListener(changed, me, main);
            onSessions(sessions.getActiveSessions(me));
        } catch (SecurityException e) {
            sessions = null;   // access was withdrawn
        }
    }

    @Override
    public void onListenerDisconnected() {
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(changed);
        for (MediaSession.Token t : new ArrayList<>(watched.keySet())) detach(t);
        sessions = null;
    }

    private void onSessions(List<MediaController> controllers) {
        Set<MediaSession.Token> live = new HashSet<>();
        if (controllers != null) {
            for (MediaController c : controllers) {
                if (!WatchHistory.tracks(c.getPackageName())) continue;
                MediaSession.Token token = c.getSessionToken();
                live.add(token);
                if (watched.containsKey(token)) continue;
                Watched w = new Watched(c);
                watched.put(token, w);
                c.registerCallback(w, main);
                save(w);
            }
        }
        for (MediaSession.Token t : new ArrayList<>(watched.keySet())) {
            if (!live.contains(t)) {
                Watched w = watched.get(t);
                if (w != null) save(w);
                detach(t);
            }
        }
    }

    private void detach(MediaSession.Token token) {
        Watched w = watched.remove(token);
        if (w != null) w.controller.unregisterCallback(w);
    }

    private void save(Watched w) {
        MediaController c = w.controller;
        MediaMetadata m = c.getMetadata();
        PlaybackState state = c.getPlaybackState();
        if (m == null || state == null) return;
        long position = state.getPosition();
        if (state.getState() == PlaybackState.STATE_PLAYING && state.getLastPositionUpdateTime() > 0) {
            position += (long) ((SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime()) * state.getPlaybackSpeed());
        }
        WatchHistory.Snapshot s = new WatchHistory.Snapshot();
        s.pkg = c.getPackageName();
        s.title = text(m, MediaMetadata.METADATA_KEY_TITLE);
        s.displayTitle = text(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        s.subtitle = text(m, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        s.artist = text(m, MediaMetadata.METADATA_KEY_ARTIST);
        s.album = text(m, MediaMetadata.METADATA_KEY_ALBUM);
        s.description = text(m, MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION);
        s.durationMs = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        s.positionMs = Math.max(0, s.durationMs > 0 ? Math.min(position, s.durationMs) : position);
        s.at = System.currentTimeMillis();
        PendingIntent resume = c.getSessionActivity();
        if (resume != null) RESUME.put(s.pkg, resume);
        w.lastSaved = SystemClock.elapsedRealtime();
        disk.execute(() -> WatchHistory.record(this, s));
    }

    private static String text(MediaMetadata m, String key) {
        CharSequence t = m.getText(key);
        return t == null ? "" : t.toString();
    }

    @Override
    public void onDestroy() {
        disk.shutdown();
        super.onDestroy();
    }
}
