package app.glasshome.launcher;

import static android.view.KeyEvent.KEYCODE_DPAD_CENTER;
import static android.view.KeyEvent.KEYCODE_DPAD_DOWN;
import static android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
import static android.view.KeyEvent.KEYCODE_DPAD_UP;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.provider.Settings;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

/** Control Center's shortcuts open real Fire TV screens, never Android's empty stand-ins. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, qualifiers = "w960dp-h540dp-land-xhdpi")
public class ControlShortcutsTest {
    private static final String LEANBACK_SETTINGS = "android.intent.category.LEANBACK_SETTINGS";

    /** Android's display action reaches only Fire OS's stand-in, which closes at once: it is never sent. */
    @Test
    public void displayAndSoundsNeverUsesAndroidsStandIn() {
        Intent[] tried = SystemControls.displaySettings();
        for (Intent i : tried) assertNotEquals(Settings.ACTION_DISPLAY_SETTINGS, i.getAction());
        assertTrue("Fire TV's own settings last", tried[tried.length - 1].hasCategory(LEANBACK_SETTINGS));
    }

    /** On a TV with none of Fire OS's display actions, the shortcut still lands in Fire TV's settings. */
    @Test
    public void displayAndSoundsFallsBackToFireTvSettings() throws Exception {
        TestHome h = new TestHome();
        h.pm.addResolveInfoForIntent(new Intent(Intent.ACTION_MAIN).addCategory(LEANBACK_SETTINGS),
                TestHome.resolve("com.amazon.tv.settings.v2", "Settings"));
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true);
        h.press(KEYCODE_DPAD_UP);
        h.press(KEYCODE_DPAD_UP);
        h.press(KEYCODE_DPAD_CENTER);   // Control Center
        ControlCenter cc = TestHome.find(h.activity.getWindow().getDecorView(), ControlCenter.class);
        assertTrue(cc.isOpen());
        h.press(KEYCODE_DPAD_DOWN);
        while (cc.focusedItem() != ControlCenter.Item.DISPLAY && cc.focusedItem() != ControlCenter.Item.HOME_SCREEN) {
            h.press(KEYCODE_DPAD_RIGHT);
        }
        assertEquals(ControlCenter.Item.DISPLAY, cc.focusedItem());
        h.press(KEYCODE_DPAD_CENTER);
        Intent opened = shadowOf(h.activity).getNextStartedActivity();
        assertNotNull(opened);
        assertTrue(opened.hasCategory(LEANBACK_SETTINGS));
    }
}
