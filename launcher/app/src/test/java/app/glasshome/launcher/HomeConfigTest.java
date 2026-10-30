package app.glasshome.launcher;

import static org.junit.Assert.assertEquals;

import android.app.Application;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** A TV's own apps.json (written when it was set up) gains the apps added to Glass Home's order since, in place. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class HomeConfigTest {
    private File copy() {
        return new File(RuntimeEnvironment.getApplication().getFilesDir(), "apps.json");
    }

    @After
    public void remove() {
        copy().delete();
    }

    @Test
    public void newAppsTakeTheirPlaceInAnOlderCopy() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        // A TV's copy from before SmartTube: Glass Home's order then, rearranged by its owner (YouTube first)
        java.util.List<String> then = new java.util.ArrayList<>(HomeConfig.load(app).order);
        then.remove("org.smarttube.stable");
        then.remove("com.amazon.firetv.youtube");
        then.add(0, "com.amazon.firetv.youtube");
        org.json.JSONObject json = new org.json.JSONObject().put("tmdb_api_key", "x").put("order", new org.json.JSONArray(then));
        try (FileOutputStream out = new FileOutputStream(copy())) {
            out.write(json.toString().getBytes(StandardCharsets.UTF_8));
        }
        HomeConfig config = HomeConfig.load(app);
        java.util.List<String> expected = new java.util.ArrayList<>(then);
        expected.add(expected.indexOf("app.fireplayer") + 1, "org.smarttube.stable");
        assertEquals("the owner's order kept; SmartTube after FirePlayer, as in Glass Home's own", expected, config.order);
        assertEquals("x", config.tmdbApiKey);
    }
}
