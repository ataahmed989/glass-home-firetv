package app.glasshome.launcher;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

/**
 * The tiles' art is kept small: a layered tile has no flat picture, and its layers are cut down (a
 * back of one colour kept as one pixel of it). None of it changes what a tile looks like.
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
}
