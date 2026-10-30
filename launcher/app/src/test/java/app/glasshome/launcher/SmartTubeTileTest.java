package app.glasshome.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.robolectric.Shadows.shadowOf;

import android.view.KeyEvent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

/** SmartTube, when installed, has its own tile (YouTube's, on black, its button blue), between FirePlayer and YouTube. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, qualifiers = "w960dp-h540dp-land-xhdpi")
public class SmartTubeTileTest {
    @Test
    public void smartTubeTileComesBetweenFirePlayerAndYouTube() throws Exception {
        shadowOf(RuntimeEnvironment.getApplication().getPackageManager())
                .addResolveInfoForIntent(TestHome.LEANBACK, TestHome.resolve("org.smarttube.stable", "SmartTube"));
        TestHome h = new TestHome();
        h.loadSampleWeather();
        // Down to the second row, then along to FirePlayer and the tiles after it
        h.press(KeyEvent.KEYCODE_DPAD_DOWN);
        while (!"app.fireplayer".equals(h.focusedKey())) h.press(KeyEvent.KEYCODE_DPAD_RIGHT);
        h.press(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertEquals("org.smarttube.stable", h.focusedKey());
        h.press(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertEquals("com.amazon.firetv.youtube", h.focusedKey());
        h.press(KeyEvent.KEYCODE_DPAD_LEFT);
        TestHome.advance(Ui.HERO_DWELL_MS + 50);
        TestHome.settle();
        h.capture("smarttube-tile.png");
    }

    /** Not installed, no tile: it is in Glass Home's order, but the order only places apps that are there. */
    @Test
    public void noTileWithoutSmartTube() throws Exception {
        TestHome h = new TestHome();
        AppAdapter apps = (AppAdapter) h.grid.getAdapter();
        for (int i = 0; i < apps.getItemCount(); i++) {
            assertFalse("org.smarttube.stable".equals(apps.get(i).key));
        }
    }
}
