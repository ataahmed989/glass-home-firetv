package app.glasshome.launcher;

import static android.view.KeyEvent.KEYCODE_DPAD_CENTER;
import static android.view.KeyEvent.KEYCODE_DPAD_DOWN;
import static android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
import static android.view.KeyEvent.KEYCODE_DPAD_UP;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

/** Renders the real HomeActivity at 1920x1080 in each focus state, for comparison with the mockup. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, qualifiers = "w960dp-h540dp-land-xhdpi")
public class HomeScreenshotTest {
    @Test
    public void captureFocusStates() throws Exception {
        TestHome h = new TestHome();
        h.loadSampleWeather();
        TestHome.settle();
        h.capture("1-dock-fireplayer.png");

        h.press(KEYCODE_DPAD_RIGHT);
        TestHome.advance(Ui.HERO_DWELL_MS + 50);
        TestHome.settle();
        h.capture("2-dock-netflix-branded.png");

        h.injectFeaturedSlide("com.netflix.ninja", "Netflix");
        TestHome.settle();
        h.capture("3-netflix-featured-test-slide.png");

        h.press(KEYCODE_DPAD_UP);
        h.capture("4-hero-focused.png");

        h.press(KEYCODE_DPAD_RIGHT);
        h.capture("5-weather-focused.png");

        h.press(KEYCODE_DPAD_CENTER);
        h.capture("6-forecast-overlay.png");

        h.back();
        h.back();
        h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_RIGHT);
        TestHome.advance(Ui.HERO_DWELL_MS + 50);
        TestHome.settle();
        h.capture("7-row2-spotify.png");

        h.press(KEYCODE_DPAD_DOWN);
        TestHome.advance(Ui.HERO_DWELL_MS + 50);
        TestHome.settle();
        h.capture("8-row3-settings.png");
    }

    /** Each background, chosen through Settings with the remote, behind the first row. */
    @Test
    public void captureEachBackground() throws Exception {
        TestHome h = new TestHome();
        h.loadSampleWeather();
        TestHome.settle();
        for (int i = 1; i <= 5; i++) {
            h.press(KEYCODE_DPAD_UP);
            h.press(KEYCODE_DPAD_UP);
            h.press(KEYCODE_DPAD_RIGHT);   // from Control Center to the avatar
            h.press(KEYCODE_DPAD_CENTER);
            while (h.panel.focusedIndex() < i) h.press(KEYCODE_DPAD_DOWN);
            while (h.panel.focusedIndex() > i) h.press(KEYCODE_DPAD_UP);
            h.press(KEYCODE_DPAD_CENTER);
            TestHome.settle();
            if (i == 5) h.capture("settings-panel.png");
            h.back();
            h.back();
            TestHome.settle();
            h.capture(String.format("background-%d.png", i));
        }
    }

    /** Every app, focused in turn, for an overview sheet. */
    @Test
    public void captureEveryApp() throws Exception {
        TestHome h = new TestHome();
        h.loadSampleWeather();
        h.press(KEYCODE_DPAD_UP);   // pass through weather and back, so focus starts cleanly on app 0
        h.press(KEYCODE_DPAD_DOWN);
        int count = h.grid.getAdapter().getItemCount();
        for (int i = 0; i < count; i++) {
            TestHome.advance(Ui.HERO_DWELL_MS + 50);
            TestHome.settle();
            h.capture(String.format("app-%02d.png", i));
            // Walk the grid like a remote: right along a row, then down and back to its start
            if (i % Ui.COLUMNS < Ui.COLUMNS - 1) {
                h.press(android.view.KeyEvent.KEYCODE_DPAD_RIGHT);
            } else {
                h.press(KEYCODE_DPAD_DOWN);
                for (int k = 0; k < Ui.COLUMNS - 1; k++) h.press(android.view.KeyEvent.KEYCODE_DPAD_LEFT);
            }
        }
    }
}
