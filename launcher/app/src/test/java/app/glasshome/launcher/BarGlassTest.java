package app.glasshome.launcher;

import static android.view.KeyEvent.KEYCODE_DPAD_DOWN;
import static android.view.KeyEvent.KEYCODE_DPAD_UP;
import static org.junit.Assert.*;

import android.graphics.Bitmap;

import java.lang.reflect.Field;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

/**
 * The glass of the tab bar and the capsule over Movies or TV Shows: the hero's picture while the
 * hero is up, Home's own background once the page is down among the shelves (☰ brings the bar back
 * there), never the hero that has scrolled away.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, qualifiers = "w960dp-h540dp-land-xhdpi")
public class BarGlassTest {
    private static Bitmap barScene(TestHome h, String glass) throws Exception {
        Field f = HomeActivity.class.getDeclaredField(glass);
        f.setAccessible(true);
        return ((Glass) f.get(h.activity)).scene();
    }

    /** The two frosts are the same picture: the same colours across the image. */
    private static boolean samePicture(Bitmap a, Bitmap b) {
        if (a == b) return true;
        if (a == null || b == null || a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return false;
        for (int y = 0; y < a.getHeight(); y += Math.max(1, a.getHeight() / 8)) {
            for (int x = 0; x < a.getWidth(); x += Math.max(1, a.getWidth() / 8)) {
                if (a.getPixel(x, y) != b.getPixel(x, y)) return false;
            }
        }
        return true;
    }

    @Test
    public void barGlassShowsHomesBackgroundDownAmongTheShelves() throws Exception {
        TestHome h = TestHome.withTmdb();
        h.menu();
        h.press(android.view.KeyEvent.KEYCODE_DPAD_RIGHT);
        h.press(android.view.KeyEvent.KEYCODE_DPAD_RIGHT);
        h.waitForPage(HomeActivity.TV);
        ShelfPage tv = h.page(HomeActivity.TV);
        h.press(KEYCODE_DPAD_DOWN);
        TestHome.settleImages();
        assertEquals("the hero first", -1, tv.focusedRow());
        Bitmap hero = tv.backdrop();
        assertTrue("over the hero, the hero's picture", samePicture(hero, barScene(h, "tabGlass")));

        // Down to the shelves; ☰ brings the bar back over them
        h.press(KEYCODE_DPAD_DOWN);
        assertEquals(0, tv.focusedRow());
        h.menu();
        assertEquals(HomeActivity.Zone.TABS, h.zone());
        TestHome.settle();
        Bitmap shelves = tv.barBackdrop();
        assertFalse("the shelves' background is not the hero's", samePicture(hero, shelves));
        for (String glass : new String[] {"tabGlass", "capsuleGlass", "controlGlass", "sourceGlass"}) {
            assertTrue(glass + " shows Home's background behind the shelves", samePicture(shelves, barScene(h, glass)));
        }
        h.capture("tv-bar-over-shelves.png");

        // A shelf further down: the one above fades into the background, which is dimmed at the top, not black
        h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_DOWN);
        assertEquals(1, tv.focusedRow());
        TestHome.settle();
        TestHome.settleImages();
        h.capture("tv-shelves-second-row.png");

        // Back up to the hero: its picture again
        while (tv.focusedRow() >= 0) h.press(KEYCODE_DPAD_UP);
        TestHome.settle();
        assertTrue("over the hero again, the hero's picture", samePicture(tv.backdrop(), barScene(h, "tabGlass")));
    }
}
