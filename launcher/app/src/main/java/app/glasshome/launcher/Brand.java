package app.glasshome.launcher;

import java.util.HashMap;
import java.util.Map;

/** How the focused-app area presents each app, with its colours and (for utilities) a short description. */
final class Brand {
    enum Kind {
        FEATURED,   // streaming service: "Featured on …" slideshow when a catalogue key is set
        MEDIA,      // other video apps: branded background
        MUSIC,      // branded music background
        GAMES,      // green Xbox-themed background
        LIVE,       // refined blue live-TV background
        UTILITY     // centred icon, name and description
    }

    final Kind kind;
    final String name;
    final int start, end, glow;
    final String tmdbProviders;   // TMDB watch-provider ids, US region
    final String description;

    private Brand(Kind kind, String name, int start, int end, int glow, String tmdbProviders, String description) {
        this.kind = kind;
        this.name = name;
        this.start = start;
        this.end = end;
        this.glow = glow;
        this.tmdbProviders = tmdbProviders;
        this.description = description;
    }

    private static final Map<String, Brand> BRANDS = new HashMap<>();

    static {
        featured("com.netflix.ninja", "Netflix", 0xFF000000, 0xFF2A0306, 0xFFE50914, "8");
        featured("com.hbo.hbonow", "HBO Max", 0xFF05050A, 0xFF101A4A, 0xFF3E5BFF, "1899|384");
        featured("com.disney.disneyplus", "Disney+", 0xFF021B2C, 0xFF0A5966, 0xFF4CCDD3, "337");
        featured("com.amazon.firebat", "Prime Video", 0xFF0F171E, 0xFF0A3550, 0xFF1A98FF, "9");
        featured("com.apple.atve.amazon.appletv", "Apple TV", 0xFF000000, 0xFF26262A, 0xFF9A9AA2, "350");
        put("com.amazon.firetv.youtube", new Brand(Kind.MEDIA, "YouTube", 0xFF0F0F0F, 0xFF2A0A0A, 0xFFFF0033, null, null));
        put("app.fireplayer", new Brand(Kind.MEDIA, "FirePlayer", 0xFF02040C, 0xFF0A1E4A, 0xFF2F7BFF, null, null));
        put("com.spotify.tv.android", new Brand(Kind.MUSIC, "Spotify", 0xFF121212, 0xFF0B3D20, 0xFF1DB954, null, null));
        put("com.apple.android.music", new Brand(Kind.MUSIC, "Apple Music", 0xFF1A0610, 0xFF5A0F2A, 0xFFFA2D48, null, null));
        put("gamepass.fire.tv", new Brand(Kind.GAMES, "Xbox Game Pass", 0xFF041A04, 0xFF0E5A10, 0xFF3CD33C, null, null));
        put("ar.tvplayer.tv", new Brand(Kind.LIVE, "TiviMate", 0xFF04122E, 0xFF0B3B8C, 0xFF3D8BFF, null, null));
        put("com.amazon.tv.livetv", new Brand(Kind.LIVE, "Live TV", 0xFF04122E, 0xFF0B3B8C, 0xFF3D8BFF, null, null));
        put(AppEntry.SETTINGS, new Brand(Kind.UTILITY, "Settings", 0xFF16191F, 0xFF3A404C, 0xFF9AA4B5, null,
                "Network, display, sound, apps, and remotes."));
        put("com.amazon.venezia", new Brand(Kind.UTILITY, "Appstore", 0xFF041A3D, 0xFF0A5BD8, 0xFF4FB3FF, null,
                "Browse and install apps for your Fire TV."));
        put("com.esaba.downloader", new Brand(Kind.UTILITY, "Downloader", 0xFF2A1200, 0xFF8A3D00, 0xFFFF8A1F, null,
                "Download files and install apps from the web."));
        put("com.tailscale.ipn", new Brand(Kind.UTILITY, "Tailscale", 0xFF111111, 0xFF2E2C29, 0xFFE8E3DA, null,
                "Private access to your own devices from anywhere."));
        put("com.amazon.alexa", new Brand(Kind.UTILITY, "Alexa", 0xFF00243A, 0xFF006C9C, 0xFF00CAFF, null,
                "Voice, smart home, and Alexa preferences."));
    }

    private static void featured(String key, String name, int start, int end, int glow, String providers) {
        put(key, new Brand(Kind.FEATURED, name, start, end, glow, providers, null));
    }

    private static void put(String key, Brand brand) {
        BRANDS.put(key, brand);
    }

    /** Apps without an entry get a neutral graphite background with their own name. */
    static Brand of(String key, String label) {
        Brand b = BRANDS.get(key);
        return b != null ? b : new Brand(Kind.MEDIA, label, 0xFF14161B, 0xFF2C313B, 0xFF7D8696, null, null);
    }
}
