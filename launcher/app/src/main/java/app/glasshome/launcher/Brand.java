package app.glasshome.launcher;

import java.util.HashMap;
import java.util.Map;

/** Each app's display name and colours (behind artwork that has not loaded), and whether it has featured titles. */
final class Brand {
    enum Kind {
        FEATURED,   // streaming service: "Featured on …" slideshow when a catalogue key is set
        OTHER
    }

    final Kind kind;
    final String name;
    final int start, end;
    final String tmdbProviders;   // TMDB watch-provider ids, US region

    private Brand(Kind kind, String name, int start, int end, String tmdbProviders) {
        this.kind = kind;
        this.name = name;
        this.start = start;
        this.end = end;
        this.tmdbProviders = tmdbProviders;
    }

    private static final Map<String, Brand> BRANDS = new HashMap<>();

    static {
        featured("com.netflix.ninja", "Netflix", 0xFF000000, 0xFF2A0306, "8");
        featured("com.hbo.hbonow", "HBO Max", 0xFF05050A, 0xFF101A4A, "1899|384");
        featured("com.disney.disneyplus", "Disney+", 0xFF021B2C, 0xFF0A5966, "337");
        featured("com.amazon.firebat", "Prime Video", 0xFF0F171E, 0xFF0A3550, "9");
        featured("com.apple.atve.amazon.appletv", "Apple TV", 0xFF000000, 0xFF26262A, "350");
        other("com.amazon.firetv.youtube", "YouTube", 0xFF0F0F0F, 0xFF2A0A0A);
        other("app.fireplayer", "FirePlayer", 0xFF02040C, 0xFF0A1E4A);
        other("com.spotify.tv.android", "Spotify", 0xFF121212, 0xFF0B3D20);
        other("com.apple.android.music", "Apple Music", 0xFF1A0610, 0xFF5A0F2A);
        other("gamepass.fire.tv", "Xbox Game Pass", 0xFF041A04, 0xFF0E5A10);
        other("ar.tvplayer.tv", "TiviMate", 0xFF04122E, 0xFF0B3B8C);
        other("com.amazon.tv.livetv", "Live TV", 0xFF04122E, 0xFF0B3B8C);
        other(AppEntry.SETTINGS, "Settings", 0xFF16191F, 0xFF3A404C);
        other("com.amazon.venezia", "Appstore", 0xFF041A3D, 0xFF0A5BD8);
        other("com.esaba.downloader", "Downloader", 0xFF2A1200, 0xFF8A3D00);
        other("com.tailscale.ipn", "Tailscale", 0xFF111111, 0xFF2E2C29);
        other("com.amazon.alexa", "Alexa", 0xFF00243A, 0xFF006C9C);
    }

    private static void featured(String key, String name, int start, int end, String providers) {
        BRANDS.put(key, new Brand(Kind.FEATURED, name, start, end, providers));
    }

    private static void other(String key, String name, int start, int end) {
        BRANDS.put(key, new Brand(Kind.OTHER, name, start, end, null));
    }

    /** Apps without an entry get neutral graphite with their own name. */
    static Brand of(String key, String label) {
        Brand b = BRANDS.get(key);
        return b != null ? b : new Brand(Kind.OTHER, label, 0xFF14161B, 0xFF2C313B, null);
    }
}
