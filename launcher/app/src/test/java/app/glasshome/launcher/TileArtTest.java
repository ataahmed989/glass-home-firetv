package app.glasshome.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.view.View;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * The tiles' art is kept small: a layered tile has no flat picture, its layers are cut down (a back
 * of one colour kept as one pixel of it), and art decoded once is used again while nothing about it
 * changed. None of it changes what a tile looks like.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, qualifiers = "w960dp-h540dp-land-xhdpi")
public class TileArtTest {
    private static final String PLAIN = "com.example.plain";

    @After
    public void restore() {
        TileLayers.compact = true;
    }

    /** Layered tiles decode no flat picture; an app with no art of its own still gets its banner card. */
    @Test
    public void layeredTilesHaveNoFlatPicture() throws Exception {
        shadowOf(RuntimeEnvironment.getApplication().getPackageManager())
                .addResolveInfoForIntent(TestHome.LEANBACK, TestHome.resolve(PLAIN, "Plain"));
        TestHome h = new TestHome();
        AppAdapter apps = (AppAdapter) h.grid.getAdapter();
        int layered = 0;
        boolean plainSeen = false;
        for (int i = 0; i < apps.getItemCount(); i++) {
            AppEntry app = apps.get(i);
            if (app.layers != null) {
                layered++;
                assertNull(app.key + " draws its layers only", app.art);
            }
            if (app.key.equals(PLAIN)) {
                plainSeen = true;
                assertNull(app.layers);
                assertNotNull("an app without art has its banner card", app.art);
            }
        }
        assertTrue(plainSeen);
        assertTrue(layered >= TestHome.APPS.length);
    }

    /** Every bundled tile, at rest and focused mid-sway, is the same kept small as kept whole. */
    @Test
    public void compactLayersDrawTheSame() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        Ui.init(1920);
        int artWidth = Math.round(Ui.tileW() * Ui.FOCUS_SCALE);
        int checked = 0, solid = 0;
        for (String name : app.getAssets().list("icons")) {
            if (!name.endsWith(".l0.png")) continue;
            String key = name.substring(0, name.length() - ".l0.png".length());
            AppEntry entry = new AppEntry(key, key, new Intent());
            TileLayers.compact = false;
            TileLayers whole = ArtLoader.loadLayers(app, entry, artWidth);
            TileLayers.compact = true;
            TileLayers small = ArtLoader.loadLayers(app, entry, artWidth);
            assertNotNull(key, whole);
            assertNotNull(key, small);
            if (small.solidBack) solid++;
            for (float[] pose : new float[][] {{0f, 0f, -0.25f}, {1f, 0.4f, -0.45f}, {1f, -0.5f, 0.05f}}) {
                Bitmap a = render(whole, pose), b = render(small, pose);
                assertTrue(key + " at focus " + pose[0] + ", tilt " + pose[1] + "/" + pose[2], a.sameAs(b));
            }
            checked++;
        }
        assertTrue(checked >= TestHome.APPS.length);
        assertTrue("some backs are of one colour", solid > 0);
    }

    private static Bitmap render(TileLayers layers, float[] pose) {
        TileView tile = new TileView(RuntimeEnvironment.getApplication());
        tile.setArt(null, layers);
        int w = Ui.tileW(), h = Ui.tileH();
        tile.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        tile.layout(0, 0, w, h);
        tile.pose(pose[0], pose[1], pose[2]);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        tile.draw(new Canvas(out));
        return out;
    }

    /**
     * A package broadcast decodes again only the app it names, and any app whose supplied art
     * changed; the others keep the very art they had. So too while Home is out of sight.
     */
    @Test
    public void aPackageBroadcastDecodesOnlyWhatChanged() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        // A sideloaded copy of one of Netflix's layers, so there is a file whose time can change
        File custom = new File(app.getExternalFilesDir("icons"), "com.netflix.ninja.l1.png");
        try (InputStream in = app.getAssets().open("icons/com.netflix.ninja.l1.png");
                FileOutputStream out = new FileOutputStream(custom)) {
            out.write(Net.readAll(in));
        }
        custom.setLastModified(1_700_000_000_000L);
        TestHome h = new TestHome();
        Map<String, TileLayers> before = layers(h);

        broadcast(h, "com.disney.disneyplus");
        Map<String, TileLayers> after = layers(h);
        assertEquals(before.keySet(), after.keySet());
        for (String key : before.keySet()) {
            if (key.equals("com.disney.disneyplus")) assertNotSame("the app named is decoded again", before.get(key), after.get(key));
            else assertSame(key + " is used as it was", before.get(key), after.get(key));
        }

        // Out of sight, with Netflix's sideloaded layer touched meanwhile
        h.controller.pause().stop();
        custom.setLastModified(1_700_000_100_000L);
        broadcast(h, "com.hbo.hbonow");
        Map<String, TileLayers> hidden = layers(h);
        for (String key : after.keySet()) {
            if (key.equals("com.hbo.hbonow") || key.equals("com.netflix.ninja")) {
                assertNotSame(key + " is decoded again", after.get(key), hidden.get(key));
            } else {
                assertSame(key + " is used as it was", after.get(key), hidden.get(key));
            }
        }
    }

    private static Map<String, TileLayers> layers(TestHome h) {
        AppAdapter apps = (AppAdapter) h.grid.getAdapter();
        Map<String, TileLayers> out = new HashMap<>();
        for (int i = 0; i < apps.getItemCount(); i++) {
            AppEntry e = apps.get(i);
            if (e.layers != null) out.put(e.key, e.layers);
        }
        return out;
    }

    /** Sends Fire OS's broadcast for {@code pkg} and waits for the apps it loads again. */
    private static void broadcast(TestHome h, String pkg) throws Exception {
        AppAdapter apps = (AppAdapter) h.grid.getAdapter();
        AppEntry first = apps.get(0);
        h.activity.sendBroadcast(new Intent(Intent.ACTION_PACKAGE_CHANGED, Uri.parse("package:" + pkg)));
        for (int i = 0; i < 400 && apps.get(0) == first; i++) {
            Thread.sleep(25);
            TestHome.idle();
        }
        assertFalse("the apps were loaded again", apps.get(0) == first);
        TestHome.settle();
    }
}
