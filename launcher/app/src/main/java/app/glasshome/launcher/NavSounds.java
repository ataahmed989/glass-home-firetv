package app.glasshome.launcher;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.provider.Settings;
import android.view.View;

import java.io.File;

/**
 * The faint ticks Fire TV's own home makes as focus moves, for moves on Glass Home's Home page.
 * They are Fire OS's files (one per direction, 125 ms, quiet); the standard Android navigation
 * sound is a different, generic tick. Silent when Fire TV's navigation sounds are switched off,
 * and on a TV without the files.
 */
final class NavSounds {
    private static final String DIR = "/system/media/audio/ui/";
    /** About the level Amazon's home plays them at: the files are already quiet. */
    private static final float VOLUME = 0.8f;

    private final Context context;
    private final SoundPool pool;
    private final int left, right, up, down;
    /** Ticks asked for (whether or not the TV has the sounds), for tests. */
    int requests;

    NavSounds(Context context) {
        this.context = context.getApplicationContext();
        pool = new SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        left = load("Left_Tick.ogg");
        right = load("Right_Tick.ogg");
        up = load("Up_Tick.ogg");
        down = load("Down_Tick.ogg");
    }

    /** Decoded on SoundPool's own thread; 0 when the TV has no such file. */
    private int load(String name) {
        File f = new File(DIR + name);
        return f.canRead() ? pool.load(f.getPath(), 1) : 0;
    }

    /** The tick for a move in {@code direction} (one of View.FOCUS_LEFT, RIGHT, UP, DOWN). */
    void play(int direction) {
        requests++;
        int id = direction == View.FOCUS_LEFT ? left : direction == View.FOCUS_RIGHT ? right
                : direction == View.FOCUS_UP ? up : down;
        if (id == 0 || Settings.System.getInt(context.getContentResolver(), Settings.System.SOUND_EFFECTS_ENABLED, 1) == 0) return;
        pool.play(id, VOLUME, VOLUME, 0, 0, 1f);
    }

    void release() {
        pool.release();
    }
}
