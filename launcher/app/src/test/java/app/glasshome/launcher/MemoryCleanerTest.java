package app.glasshome.launcher;

import static android.view.KeyEvent.KEYCODE_DPAD_CENTER;
import static android.view.KeyEvent.KEYCODE_DPAD_DOWN;
import static android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
import static android.view.KeyEvent.KEYCODE_DPAD_UP;
import static org.junit.Assert.*;

import java.util.Set;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowToast;

/** Free Up Memory closes the apps left in the background, but never FirePlayer, TiviMate, Amazon's home or Tailscale. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, qualifiers = "w960dp-h540dp-land-xhdpi")
public class MemoryCleanerTest {
    @Test
    public void closesTheOthersButKeepsThePlayersAndTheHomeButton() throws Exception {
        new TestHome();   // installs the usual apps (FirePlayer, TiviMate, Netflix, Prime Video…)
        Set<String> closing = MemoryCleaner.candidates(RuntimeEnvironment.getApplication());
        assertTrue(closing.contains("com.netflix.ninja"));
        assertTrue(closing.contains("com.amazon.firebat"));
        assertTrue(closing.contains("com.spotify.tv.android"));
        for (String kept : new String[] {"app.fireplayer", "ar.tvplayer.tv", "com.amazon.tv.launcher", "com.tailscale.ipn",
                RuntimeEnvironment.getApplication().getPackageName()}) {
            assertFalse(kept + " stays", closing.contains(kept));
        }
    }

    @Test
    public void theButtonIsInStorageAndSaysWhatItDid() throws Exception {
        TestHome h = new TestHome();
        h.press(KEYCODE_DPAD_UP);
        h.press(KEYCODE_DPAD_UP);
        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_CENTER);
        int row = -1;
        for (int i = 0; row < 0; i++) if ("Free Up Memory".equals(h.panel.option(i).title)) row = i;
        assertEquals("STORAGE", h.panel.option(row).section);
        assertEquals("next to Clear Cache", "Clear Cache", h.panel.option(row + 1).title);
        while (h.panel.focusedIndex() < row) h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_CENTER);
        for (int i = 0; i < 40 && !String.valueOf(ShadowToast.getTextOfLatestToast()).matches("Freed .*|Nothing else.*"); i++) {
            Thread.sleep(100);
            TestHome.settle();
        }
        assertTrue(ShadowToast.getTextOfLatestToast(), ShadowToast.getTextOfLatestToast().matches("Freed .*|Nothing else.*"));
        assertEquals("settings stay open", HomeActivity.Zone.SETTINGS, h.zone());
    }
}
