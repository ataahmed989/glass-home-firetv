package app.glasshome.launcher;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

/** The Amazon apps turned off at setup, and telling when Fire OS turns them back on. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, qualifiers = "w960dp-h540dp-land-xhdpi")
public class FireOsCleanupTest {
    /** The list is setup's own (firetv/disabled-packages.txt), and the manifest lets Glass Home see each one. */
    @Test
    public void watchesWhatSetupTurnedOff() throws Exception {
        List<String> setup = new ArrayList<>();
        for (String line : Files.readAllLines(new File("../../firetv/disabled-packages.txt").toPath(), StandardCharsets.UTF_8)) {
            if (!line.trim().isEmpty() && !line.startsWith("#")) setup.add(line.trim());
        }
        assertEquals(setup, Arrays.asList(FireOsCleanup.PACKAGES));
        String manifest = new String(Files.readAllBytes(new File("src/main/AndroidManifest.xml").toPath()), StandardCharsets.UTF_8);
        for (String pkg : setup) assertTrue(pkg, manifest.contains("<package android:name=\"" + pkg + "\" />"));
    }

    private static void install(String pkg, String label, int state) {
        Application app = RuntimeEnvironment.getApplication();
        PackageInfo info = new PackageInfo();
        info.packageName = pkg;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = pkg;
        info.applicationInfo.nonLocalizedLabel = label;
        shadowOf(app.getPackageManager()).installPackage(info);
        app.getPackageManager().setApplicationEnabledSetting(pkg, state, 0);
    }

    @Test
    public void tellsOnceWhenFireOsTurnsThemBackOn() throws Exception {
        install("com.amazon.bueller.photos", "Amazon Photos", PackageManager.COMPONENT_ENABLED_STATE_DEFAULT);
        install("com.amazon.tv.ftvambient", "Ambient Experience", PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        install("com.amazon.dp.logger", "com.amazon.dp.logger", PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER);
        FireOsCleanup.Status status = FireOsCleanup.check(RuntimeEnvironment.getApplication());
        assertEquals(1, status.off);
        assertEquals(Arrays.asList("com.amazon.bueller.photos", "com.amazon.tv.ftvambient"), status.backOn);

        TestHome h = new TestHome();
        h.loadSampleWeather();
        UpdatePrompt prompt = TestHome.find(h.activity.getWindow().getDecorView(), UpdatePrompt.class);
        for (int i = 0; i < 80 && !prompt.isOpen(); i++) {
            TestHome.advance(250);
            Thread.sleep(10);
        }
        TestHome.settle();
        assertTrue(prompt.isOpen());
        assertEquals("Fire OS turned apps back on", prompt.heading());
        h.capture("cleanup-alert.png");
        h.press(android.view.KeyEvent.KEYCODE_DPAD_CENTER);
        assertFalse(prompt.isOpen());

        // Told once: not again on coming back, until another comes back on
        h.controller.pause();
        h.controller.resume();
        for (int i = 0; i < 60; i++) {
            TestHome.advance(250);
            Thread.sleep(10);
        }
        TestHome.settle();
        assertFalse(prompt.isOpen());
    }

    @Test
    public void quietWhileAllAreStillOff() throws Exception {
        install("com.amazon.dp.logger", "com.amazon.dp.logger", PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER);
        TestHome h = new TestHome();
        UpdatePrompt prompt = TestHome.find(h.activity.getWindow().getDecorView(), UpdatePrompt.class);
        for (int i = 0; i < 60; i++) {
            TestHome.advance(250);
            Thread.sleep(10);
        }
        TestHome.settle();
        assertFalse(prompt.isOpen());
    }
}
