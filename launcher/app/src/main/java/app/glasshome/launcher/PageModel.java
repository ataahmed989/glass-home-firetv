package app.glasshome.launcher;

import java.util.ArrayList;
import java.util.List;

/** What a browse page (Movies, TV Shows, Sports) shows: a hero carousel and shelves of cards. */
final class PageModel {
    enum Style {
        LANDSCAPE,  // 16:9 artwork, title and a detail line underneath
        RANKED,     // 2:3 poster beside a large rank number (Top 10)
        PROGRESS,   // 16:9 artwork with a progress bar (Continue Watching)
        SCORE       // a game: team colours, logos, score or start time
    }

    /** A button on the hero or on a detail page. */
    static final class Action {
        final String label;
        final String appKey;     // the app it opens, or null
        final boolean details;   // opens the detail page instead

        Action(String label, String appKey, boolean details) {
            this.label = label;
            this.appKey = appKey;
            this.details = details;
        }

        static Action open(String label, String appKey) {
            return new Action(label, appKey, false);
        }

        static Action details() {
            return new Action("Details", null, true);
        }
    }

    /** One hero slide, or the content of a detail page. */
    static final class Feature {
        String label = "", title = "", meta = "", body = "";
        String imageUrl, logoUrl;
        Sports.Game game;
        String note;                                   // e.g. "Not streaming on your apps"
        final List<String[]> facts = new ArrayList<>();  // detail page: {"Starring", "…"}
        final List<Action> actions = new ArrayList<>();
        Object ref;                                    // the Tmdb.Title or Sports.Game behind it
    }

    static final class Card {
        String title = "", subtitle = "";
        String imageUrl;          // backdrop (16:9) or poster (2:3)
        String appKey;            // Continue Watching: the app it plays in
        float progress = -1f;     // 0..1, or -1 when unknown
        int rank;
        Sports.Game game;
        Object ref;
    }

    static final class Shelf {
        final String title;
        final Style style;
        final List<Card> cards = new ArrayList<>();
        String empty;             // shown in place of cards when there are none (the row is then skipped by focus)

        Shelf(String title, Style style) {
            this.title = title;
            this.style = style;
        }
    }

    final List<Feature> hero = new ArrayList<>();
    final List<Shelf> shelves = new ArrayList<>();
    String credit = "";
}
