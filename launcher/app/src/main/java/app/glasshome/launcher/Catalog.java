package app.glasshome.launcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Builds the Movies and TV Shows pages: TMDB shelves around the apps installed here: Top 10, new
 * arrivals, popular on each service.
 */
final class Catalog {
    /** The subscription apps, in the order their shelves appear. */
    static final String[] SERVICES = {
            "com.netflix.ninja", "com.hbo.hbonow", "com.disney.disneyplus", "com.amazon.firebat", "com.apple.atve.amazon.appletv"};
    private static final int HERO_SIZE = 6;

    private Catalog() {}

    static String name(String appKey) {
        return Brand.of(appKey, appKey).name;
    }

    static PageModel page(Tmdb tmdb, boolean series, Set<String> installed) throws Exception {
        PageModel page = new PageModel();
        page.credit = Tmdb.CREDIT;
        String type = series ? "tv" : "movie";
        List<String> services = new ArrayList<>();
        for (String s : SERVICES) if (installed.contains(s)) services.add(s);
        if (services.isEmpty()) java.util.Collections.addAll(services, SERVICES);
        String all = providers(services);
        String base = "watch_region=US&with_watch_monetization_types=flatrate&include_adult=false&page=1";

        // Hero: what is popular right now on the apps installed here
        List<Tmdb.Title> popular = tmdb.list("discover/" + type, base + "&sort_by=popularity.desc&with_watch_providers=" + all, series);
        for (Tmdb.Title t : popular) {
            if (page.hero.size() >= HERO_SIZE) break;
            if (t.backdrop.isEmpty()) continue;
            try {
                page.hero.add(feature(tmdb.details(t), false));
            } catch (Exception ignored) {
                // A title whose details fail is left out of the hero
            }
        }

        String today = Clock.today().toString();
        tryShelf(page, () -> ranked(series ? "Top 10 Shows This Week" : "Top 10 Movies This Week",
                tmdb.list("trending/" + type + "/week", "", series)));
        if (series) {
            String weekAgo = Clock.today().minusDays(6).toString();
            tryShelf(page, () -> landscape("New Episodes This Week", tmdb.list("discover/tv", base
                    + "&sort_by=popularity.desc&air_date.gte=" + weekAgo + "&air_date.lte=" + today + "&with_watch_providers=" + all, true)));
        } else {
            tryShelf(page, () -> landscape("New on Your Apps", tmdb.list("discover/movie", base
                    + "&sort_by=primary_release_date.desc&primary_release_date.lte=" + today + "&vote_count.gte=5&with_watch_providers=" + all, false)));
        }
        for (String app : services) {
            tryShelf(page, () -> landscape("Popular on " + name(app), tmdb.list("discover/" + type,
                    base + "&sort_by=popularity.desc&with_watch_providers=" + providers(java.util.Collections.singletonList(app)), series)));
        }
        if (!series) {
            tryShelf(page, () -> landscape("In Theaters", tmdb.list("movie/now_playing", "region=US&page=1", false)));
            tryShelf(page, () -> {
                List<Tmdb.Title> upcoming = new ArrayList<>();
                for (Tmdb.Title t : tmdb.list("movie/upcoming", "region=US&page=1", false)) {
                    if (t.date.compareTo(today) > 0) upcoming.add(t);
                }
                return landscape("Coming Soon", upcoming);
            });
        }
        tryShelf(page, () -> landscape("Top Rated on Your Apps", tmdb.list("discover/" + type, base
                + "&sort_by=vote_average.desc&vote_count.gte=" + (series ? 1000 : 2000) + "&with_watch_providers=" + all, series)));
        if (page.hero.isEmpty() && page.shelves.size() <= 1) throw new java.io.IOException("TMDB returned nothing");
        return page;
    }

    private interface ShelfSource {
        PageModel.Shelf get() throws Exception;
    }

    /** One shelf failing to load leaves the rest of the page alone. */
    private static void tryShelf(PageModel page, ShelfSource source) {
        try {
            PageModel.Shelf s = source.get();
            if (s != null && !s.cards.isEmpty()) page.shelves.add(s);
        } catch (Exception ignored) {
            // skipped
        }
    }

    static String providers(List<String> apps) {
        List<String> ids = new ArrayList<>();
        for (String app : apps) {
            String p = Brand.of(app, app).tmdbProviders;
            if (p != null) ids.add(p);
        }
        return String.join("|", ids).replace("|", "%7C");
    }

    /** A hero slide or, with {@code detail}, the detail page for a title (its details already loaded). */
    static PageModel.Feature feature(Tmdb.Title t, boolean detail) {
        PageModel.Feature f = new PageModel.Feature();
        f.ref = t;
        String first = t.streamingApps.isEmpty() ? null : t.streamingApps.iterator().next();
        f.label = first != null ? (detail ? (t.series ? "Series" : "Movie") : "Popular on " + name(first)) : (t.series ? "Series" : "Movie");
        f.title = t.name;
        f.meta = t.meta();
        f.body = t.overview;
        f.imageUrl = t.backdropUrl("w1280");
        f.logoUrl = t.logo == null ? null : Tmdb.IMG + "w500" + t.logo;
        for (String app : t.streamingApps) {
            f.actions.add(PageModel.Action.open("Open " + name(app), app));
            if (!detail) break;   // the hero offers the first; the detail page lists them all
        }
        if (!detail) {
            f.actions.add(PageModel.Action.details());
            return f;
        }
        for (String app : t.storeApps) f.actions.add(PageModel.Action.open("Rent or Buy on " + name(app), app));
        if (!t.cast.isEmpty()) f.facts.add(new String[] {"Starring", t.cast});
        if (!t.crew.isEmpty()) f.facts.add(new String[] {t.series ? "Created by" : "Director", t.crew});
        if (!t.nextEpisode.isEmpty()) f.facts.add(new String[] {"Next episode", t.nextEpisode});
        if (t.streamingApps.isEmpty()) {
            List<String> elsewhere = new ArrayList<>(t.streamingNames);
            f.note = elsewhere.isEmpty() ? "Not streaming on a subscription service in the US"
                    : "Streaming on " + String.join(", ", elsewhere.subList(0, Math.min(3, elsewhere.size()))) + ", not on an app on this TV";
        }
        return f;
    }

    private static PageModel.Shelf landscape(String title, List<Tmdb.Title> titles) {
        PageModel.Shelf s = new PageModel.Shelf(title, PageModel.Style.LANDSCAPE);
        for (Tmdb.Title t : titles) {
            if (s.cards.size() >= 20) break;
            if (t.backdrop.isEmpty()) continue;
            PageModel.Card c = new PageModel.Card();
            c.title = t.name;
            c.subtitle = t.cardLine();
            c.imageUrl = t.backdropUrl("w780");
            c.ref = t;
            s.cards.add(c);
        }
        return s;
    }

    private static PageModel.Shelf ranked(String title, List<Tmdb.Title> titles) {
        PageModel.Shelf s = new PageModel.Shelf(title, PageModel.Style.RANKED);
        for (Tmdb.Title t : titles) {
            if (s.cards.size() >= 10) break;
            if (t.poster.isEmpty()) continue;
            PageModel.Card c = new PageModel.Card();
            c.title = t.name;
            c.subtitle = t.cardLine();
            c.imageUrl = t.posterUrl("w342");
            c.rank = s.cards.size() + 1;
            c.ref = t;
            s.cards.add(c);
        }
        return s;
    }
}
