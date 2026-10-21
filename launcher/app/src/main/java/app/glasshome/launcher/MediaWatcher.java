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
 *
 * Some apps say that they are playing, and where, but not what (Netflix); what plays there just
 * after Glass Home opened a title's page in that app is taken to be that title, until playback
 * stops or jumps back to the start of something else. Apps that publish no session at all
 * (FirePlayer) cannot be seen.
 */
public final class MediaWatcher extends NotificationListenerService {
    private static final long SAVE_EVERY_MS = 30_000;
    /** Playback this far back from where it was is something else starting (longer than a trailer). */
    private static final long JUMP_BACK_MS = 10 * 60_000;

    /** The playing app's own "back to what you were watching" intent, while its session lives. */
    static final Map<String, PendingIntent> RESUME = new ConcurrentHashMap<>();

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService disk = Executors.newSingleThreadExecutor();
    private final Map<MediaSession.Token, Watched> watched = new HashMap<>();
    private MediaSessionManager sessions;
    private final MediaSessionManager.OnActiveSessionsChangedListener changed = this::onSessions;

    /** Apps may say nothing while they play on, so what is playing is saved every 30 s as well. */
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            boolean playing = false;
            for (Watched w : new ArrayList<>(watched.values())) {
                if (w.playingSince < 0) continue;
                playing = true;
                save(w);
            }
            if (playing) main.postDelayed(this, SAVE_EVERY_MS);
        }
    };

    private final class Watched extends MediaController.Callback {
        final MediaController controller;
        long lastSaved;
        long playingSince = -1;     // when it last started playing; -1 while it is not
        long playedMs;              // seen playing before that
        long lastPosition = -1;
        WatchHistory.Opened opened; // what Glass Home opened in this app, while this is still it playing
        boolean ending;             // the session is going: it takes no new opening

        Watched(MediaController controller) {
            this.controller = controller;
        }

        long played() {
            return playedMs + (playingSince < 0 ? 0 : SystemClock.elapsedRealtime() - playingSince);
        }

        void playing(boolean now) {
            if (now == (playingSince >= 0)) return;
            if (now) {
                playingSince = SystemClock.elapsedRealtime();
                main.removeCallbacks(tick);
                main.postDelayed(tick, SAVE_EVERY_MS);
            } else {
                playedMs = played();
                playingSince = -1;
            }
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
            // Every pause or stop is kept; while playing, a snapshot every 30 s is plenty
            if (!playing || SystemClock.elapsedRealtime() - lastSaved > SAVE_EVERY_MS) save(this);
            playing(playing);
            if (state == null || state.getState() == PlaybackState.STATE_STOPPED || state.getState() == PlaybackState.STATE_NONE
                    || state.getState() == PlaybackState.STATE_ERROR) {
                // Whatever plays next is not what Glass Home opened
                opened = null;
                playedMs = 0;
                lastPosition = -1;
            }
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            // Something else is playing: its time is counted from here
            playedMs = 0;
            if (playingSince >= 0) playingSince = SystemClock.elapsedRealtime();
            save(this);
        }

        @Override
        public void onSessionDestroyed() {
            ending = true;
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
        main.removeCallbacks(tick);
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
                PlaybackState state = c.getPlaybackState();
                w.playing(state != null && state.getState() == PlaybackState.STATE_PLAYING);
                save(w);
            }
        }
        for (MediaSession.Token t : new ArrayList<>(watched.keySet())) {
            if (!live.contains(t)) {
                Watched w = watched.get(t);
                if (w != null) {
                    w.ending = true;
                    save(w);
                }
                detach(t);
            }
        }
    }

    private void detach(MediaSession.Token token) {
        Watched w = watched.remove(token);
        if (w == null) return;
        w.controller.unregisterCallback(w);
        // The app's own way back is kept only while it has a session: past that it may lead anywhere
        String pkg = w.controller.getPackageName();
        for (Watched other : watched.values()) if (other.controller.getPackageName().equals(pkg)) return;
        RESUME.remove(pkg);
    }

    private void save(Watched w) {
        MediaController c = w.controller;
        MediaMetadata m = c.getMetadata();
        PlaybackState state = c.getPlaybackState();
        if (state == null) return;
        long position = state.getPosition();
        if (position >= 0 && state.getState() == PlaybackState.STATE_PLAYING && state.getLastPositionUpdateTime() > 0) {
            position += (long) ((SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime()) * state.getPlaybackSpeed());
        }
        WatchHistory.Snapshot s = new WatchHistory.Snapshot();
        s.pkg = c.getPackageName();
        if (m != null) {
            s.title = text(m, MediaMetadata.METADATA_KEY_TITLE);
            s.displayTitle = text(m, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            s.subtitle = text(m, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            s.artist = text(m, MediaMetadata.METADATA_KEY_ARTIST);
            s.album = text(m, MediaMetadata.METADATA_KEY_ALBUM);
            s.description = text(m, MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION);
            s.durationMs = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        }
        if (s.title.trim().isEmpty() && s.displayTitle.trim().isEmpty()) {
            // The app does not say what it plays: it is what Glass Home just opened there, if anything
            if (w.lastPosition >= 0 && position >= 0 && position < w.lastPosition - JUMP_BACK_MS) {
                w.opened = null;   // something else has started
                w.playedMs = 0;
            }
            if (w.opened == null && !w.ending && state.getState() == PlaybackState.STATE_PLAYING) w.opened = WatchHistory.openedIn(s.pkg);
            if (w.opened == null) return;
            s.opened = w.opened;
        }
        if (position >= 0) w.lastPosition = position;
        s.positionMs = Math.max(0, s.durationMs > 0 ? Math.min(position, s.durationMs) : position);
        if (position < 0) s.playedMs = w.played();   // no position: how long it has been seen playing
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
