package app.glasshome.launcher;

import static android.view.KeyEvent.KEYCODE_DPAD_CENTER;
import static android.view.KeyEvent.KEYCODE_DPAD_DOWN;
import static android.view.KeyEvent.KEYCODE_DPAD_LEFT;
import static android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
import static android.view.KeyEvent.KEYCODE_DPAD_UP;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Intent;
import android.os.Looper;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowActivity;

import app.glasshome.launcher.HomeActivity.Zone;

/**
 * The tab bar and the Movies, TV Shows and Sports pages, driven with the remote. Page content
 * comes from TMDB and ESPN responses recorded on 20 October 2026 (see {@link Fixtures}).
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 30, qualifiers = "w960dp-h540dp-land-xhdpi")
public class PagesTest {
    private static final int HOME = HomeActivity.HOME, MOVIES = HomeActivity.MOVIES, TV = HomeActivity.TV, SPORTS = HomeActivity.SPORTS;

    @Test
    public void menuButtonGoesToTheTabsAndBack() throws Exception {
        TestHome h = new TestHome();
        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_RIGHT);
        h.menu();
        assertEquals(Zone.TABS, h.zone());
        assertEquals("Home is the selected tab", 0, h.tabs.selected());
        h.capture("home-tabs-focused.png");
        h.menu();
        assertEquals("pressed again, back down", Zone.GRID, h.zone());
        assertEquals(2, h.focused());

        // From an open overlay, ☰ closes it on the way to the tabs
        h.press(KEYCODE_DPAD_UP);
        h.press(KEYCODE_DPAD_CENTER);
        assertEquals(Zone.OVERLAY, h.zone());
        h.menu();
        assertEquals(Zone.TABS, h.zone());
        assertFalse(h.detail.isOpen());
    }

    @Test
    public void pressingUpEndsOnTheTabs() throws Exception {
        TestHome h = new TestHome();
        h.press(KEYCODE_DPAD_UP);
        assertEquals(Zone.WEATHER, h.zone());
        h.press(KEYCODE_DPAD_UP);
        assertEquals(Zone.CAPSULE, h.zone());
        h.press(KEYCODE_DPAD_UP);
        assertEquals(Zone.TABS, h.zone());
        h.press(KEYCODE_DPAD_UP);
        assertEquals("nothing above the tabs", Zone.TABS, h.zone());
        h.press(KEYCODE_DPAD_DOWN);
        assertEquals("back down to where it was on Home", Zone.WEATHER, h.zone());

        // With an "Open …" action, the hero sits between the apps and the tabs
        h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_RIGHT);
        TestHome.advance(Ui.HERO_DWELL_MS + 50);
        h.injectFeaturedSlide("com.netflix.ninja", "Netflix");
        TestHome.settle();
        h.press(KEYCODE_DPAD_UP);
        assertEquals(Zone.HERO, h.zone());
        h.press(KEYCODE_DPAD_UP);
        assertEquals(Zone.TABS, h.zone());
        h.press(KEYCODE_DPAD_DOWN);
        assertEquals(Zone.HERO, h.zone());
    }

    @Test
    public void tabsChangePagesOnlyOnceTheSelectionRests() throws Exception {
        TestHome h = TestHome.withTmdb();
        h.menu();
        h.tap(KEYCODE_DPAD_RIGHT);
        h.tap(KEYCODE_DPAD_RIGHT);
        assertEquals("TV Shows selected", 2, h.tabs.selected());
        assertEquals("the page waits for the selection to rest", HOME, h.activity.page());
        TestHome.advance(Ui.TAB_DWELL_MS + 20);
        assertEquals(TV, h.activity.page());
        assertFalse("Movies was passed over and never loaded", h.page(MOVIES).hasModel());
        h.waitForPage(TV);

        h.press(KEYCODE_DPAD_RIGHT);
        assertEquals(SPORTS, h.activity.page());
        h.press(KEYCODE_DPAD_RIGHT);
        assertEquals("past the last tab: the capsule", Zone.CAPSULE, h.zone());
        h.press(KEYCODE_DPAD_LEFT);
        assertEquals(Zone.TABS, h.zone());
        assertEquals(3, h.tabs.selected());

        // Back unwinds: page → tabs → Home's tab → Home's apps
        h.press(KEYCODE_DPAD_DOWN);
        assertEquals(Zone.PAGE, h.zone());
        h.back();
        assertEquals(Zone.TABS, h.zone());
        h.back();
        assertEquals(HOME, h.activity.page());
        assertEquals(Zone.TABS, h.zone());
        h.back();
        assertEquals(Zone.GRID, h.zone());
        assertEquals(0, h.focused());
    }

    /**
     * A page change never dims towards black: the page on top fades (the new one in over the old,
     * or the old one out over the new) while the one beneath stays whole until it is covered. Seen
     * as the change starts and once it is over (Robolectric runs a fade to its end on its first frame).
     */
    @Test
    public void aPageChangeKeepsAWholePageBeneath() throws Exception {
        TestHome h = TestHome.withTmdb();
        h.menu();
        h.tap(KEYCODE_DPAD_RIGHT);
        untilPage(h, MOVIES);
        assertEquals(View.VISIBLE, h.page(MOVIES).getVisibility());
        assertTrue("Movies fades in over Home", h.page(MOVIES).getAlpha() < 1f);
        assertEquals(View.VISIBLE, h.stage.getVisibility());
        assertEquals("Home stays whole beneath it", 1f, h.stage.getAlpha(), 0.001f);
        TestHome.advance(Ui.PAGE_FADE_MS + 50);
        assertEquals(1f, h.page(MOVIES).getAlpha(), 0.001f);
        assertEquals("Home goes once covered", View.GONE, h.stage.getVisibility());
        assertEquals("without having faded", 1f, h.stage.getAlpha(), 0.001f);

        h.tap(KEYCODE_DPAD_LEFT);
        untilPage(h, HOME);
        assertEquals(View.VISIBLE, h.stage.getVisibility());
        assertEquals("Home is whole at once, beneath Movies", 1f, h.stage.getAlpha(), 0.001f);
        assertEquals(View.VISIBLE, h.page(MOVIES).getVisibility());
        assertEquals("Movies fades out over it", 1f, h.page(MOVIES).getAlpha(), 0.001f);
        TestHome.advance(Ui.PAGE_FADE_MS + 50);
        assertEquals(View.GONE, h.page(MOVIES).getVisibility());
        assertEquals("having faded out", 0f, h.page(MOVIES).getAlpha(), 0.001f);
        assertEquals(1f, h.stage.getAlpha(), 0.001f);
    }

    /** Fire TV's navigation tick on Home when focus moves, never at an edge, and not on the browse pages. */
    @Test
    public void ticksOnlyForMovesOnHome() throws Exception {
        TestHome h = TestHome.withTmdb();
        h.press(KEYCODE_DPAD_RIGHT);
        assertEquals("a move on Home", 1, h.activity.navTicks());
        h.press(KEYCODE_DPAD_LEFT);
        h.press(KEYCODE_DPAD_LEFT);
        assertEquals("the second Left is at the edge: nothing moved", 2, h.activity.navTicks());

        h.menu();
        int onTabs = h.activity.navTicks();
        h.press(KEYCODE_DPAD_RIGHT);   // to Movies: leaving Home
        assertEquals(MOVIES, h.activity.page());
        h.waitForPage(MOVIES);
        h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_DOWN);
        h.press(KEYCODE_DPAD_RIGHT);
        assertEquals("nothing on the browse pages", onTabs, h.activity.navTicks());
    }

    /** Runs the main thread's tasks one at a time until page {@code p} is the one shown, and no further. */
    private static void untilPage(TestHome h, int p) {
        for (int i = 0; i < 1000 && h.activity.page() != p; i++) shadowOf(Looper.getMainLooper()).runOneTask();
        assertEquals(p, h.activity.page());
    }

    @Test
    public void moviesHeroShelvesAndDetails() throws Exception {
        TestHome h = TestHome.withTmdb();
        h.menu();
        h.press(KEYCODE_DPAD_RIGHT);
        h.waitForPage(MOVIES);
        ShelfPage movies = h.page(MOVIES);
        PageModel m = movies.model();
        assertTrue("a hero carousel", m.hero.size() >= 2);
        assertEquals("Top 10 Movies This Week", m.shelves.get(0).title);
        assertEquals(10, m.shelves.get(0).cards.size());

        h.press(KEYCODE_DPAD_DOWN);
        assertEquals(Zone.PAGE, h.zone());
        assertEquals("the hero first", -1, movies.focusedRow());
        TestHome.settleImages();
        h.capture("movies-1-hero.png");

        // Along the hero's buttons, then on to the next slide
        PageModel.Feature first = movies.currentHero();
        int buttons = first.actions.size();
        for (int i = 1; i < buttons; i++) h.press(KEYCODE_DPAD_RIGHT);
        assertEquals(buttons - 1, movies.focusedColumn());
        h.press(KEYCODE_DPAD_RIGHT);
        assertTrue("next hero slide", movies.currentHero() != first);
        assertEquals(0, movies.focusedColumn());
        h.press(KEYCODE_DPAD_LEFT);
        assertTrue("and back", movies.currentHero() == first);

        // Down to the Top 10, and the top bar steps aside
        h.press(KEYCODE_DPAD_DOWN);
        assertEquals(0, movies.focusedRow());
        assertTrue(h.tabs.getAlpha() < 0.5f);
        h.capture("movies-2a-top10.png");
        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_RIGHT);
        assertEquals(2, movies.focusedColumn());
        h.press(KEYCODE_DPAD_DOWN);
        assertEquals(1, movies.focusedRow());
        TestHome.settleImages();
        h.capture("movies-2-shelves.png");

        h.press(KEYCODE_DPAD_CENTER);
        assertEquals(Zone.PAGE_DETAIL, h.zone());
        TestHome.settleImages();
        assertNotNull(h.titleDetail.feature());
        h.capture("movies-3-detail.png");

        h.back();
        assertEquals(Zone.PAGE, h.zone());
        assertEquals(1, movies.focusedRow());
        h.back();
        assertEquals("Back returns to the hero", -1, movies.focusedRow());
        assertEquals(1f, h.tabs.getAlpha(), 0.01f);
        h.back();
        assertEquals(Zone.TABS, h.zone());
    }

    @Test
    public void heroOpenButtonLaunchesTheApp() throws Exception {
        TestHome h = TestHome.withTmdb();
        h.menu();
        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_RIGHT);   // TV Shows
        h.waitForPage(TV);
        h.press(KEYCODE_DPAD_DOWN);
        PageModel.Feature hero = h.page(TV).currentHero();
        PageModel.Action action = hero.actions.get(0);
        h.press(KEYCODE_DPAD_CENTER);
        ShadowActivity sa = shadowOf(h.activity);
        Intent started = sa.getNextStartedActivity();
        if (action.appKey != null) {
            assertNotNull(started);
            assertEquals(action.appKey, started.getComponent().getPackageName());
        } else {
            assertNull(started);
            assertEquals(Zone.PAGE_DETAIL, h.zone());
        }
    }

    @Test
    public void sportsShowsYourTeamsAndWhereToWatch() throws Exception {
        TestHome h = new TestHome();
        h.menu();
        h.tap(KEYCODE_DPAD_RIGHT);
        h.tap(KEYCODE_DPAD_RIGHT);
        h.tap(KEYCODE_DPAD_RIGHT);
        TestHome.advance(Ui.TAB_DWELL_MS + 20);
        assertEquals(SPORTS, h.activity.page());
        h.waitForPage(SPORTS);
        PageModel m = h.page(SPORTS).model();
        assertFalse(m.hero.isEmpty());
        assertTrue("your teams lead the hero", m.hero.get(0).game.favourite);
        boolean mine = false;
        for (PageModel.Shelf s : m.shelves) mine |= s.title.equals("My Teams");
        assertTrue(mine);

        h.press(KEYCODE_DPAD_DOWN);
        TestHome.settleImages();
        h.capture("sports-1-hero.png");
        h.press(KEYCODE_DPAD_DOWN);
        TestHome.settleImages();
        h.capture("sports-2-shelves.png");
        h.press(KEYCODE_DPAD_CENTER);
        assertEquals(Zone.PAGE_DETAIL, h.zone());
        TestHome.settleImages();
        h.capture("sports-3-detail.png");
        h.back();
        assertEquals(Zone.PAGE, h.zone());
    }

    @Test
    public void tvShowsHero() throws Exception {
        TestHome h = TestHome.withTmdb();
        h.menu();
        h.press(KEYCODE_DPAD_RIGHT);
        h.press(KEYCODE_DPAD_RIGHT);
        h.waitForPage(TV);
        h.press(KEYCODE_DPAD_DOWN);
        TestHome.settleImages();
        h.capture("tv-1-hero.png");
        assertEquals("Top 10 Shows This Week", h.page(TV).model().shelves.get(0).title);
    }
}
