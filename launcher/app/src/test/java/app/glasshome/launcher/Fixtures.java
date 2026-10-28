package app.glasshome.launcher;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Recorded TMDB and ESPN responses (and artwork), so the browse pages render real data offline
 * and the same way every run. Run once with -PnetRecord=true (and -PtmdbTokenFile=…) to record
 * whatever is missing; every other run only replays.
 */
final class Fixtures {
    static final File DIR = new File("src/test/fixtures/net");
    /** The moment the fixtures were recorded: Tuesday 20 October 2026, 7:30 PM in Ohio. */
    static final long RECORDED_AT = Instant.parse("2026-10-20T23:30:00Z").toEpochMilli();

    private Fixtures() {}

    static boolean recording() {
        return Boolean.parseBoolean(System.getProperty("net.record", "false"));
    }

    static void install() {
        Clock.fixedMillis = RECORDED_AT;
        Clock.fixedZone = ZoneId.of("America/New_York");
        // Links into the streaming apps are not recorded: "Open …" falls back to the app itself
        AppLinks.reset();
        AppLinks.poster = (url, json) -> {
            throw new IOException("not recorded: " + url);
        };
        Net.fetcher = (url, bearer) -> {
            File f = new File(DIR, Net.sha1(url));
            // Glass Home's own releases change with every push: never kept, so no test sees one by accident
            if (url.equals(Updater.LATEST_URL)) throw new IOException("not recorded: " + url);
            if (f.isFile()) return Files.readAllBytes(f.toPath());
            if (!recording()) throw new IOException("not recorded: " + url);
            byte[] body = Net.http(url, bearer);
            synchronized (Fixtures.class) {
                DIR.mkdirs();
                Files.write(f.toPath(), body);
                Files.write(new File(DIR, "index.txt").toPath(), (f.getName() + " " + url + "\n").getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            return body;
        };
    }

    /** The real token while recording; otherwise any token of the same kind (the URLs never contain it). */
    static String tmdbToken() throws IOException {
        String path = System.getProperty("tmdb.token.file", "");
        if (recording() && !path.isEmpty()) return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8).trim();
        return "replay-placeholder-token-long-enough-to-be-sent-as-a-bearer-header-0000";
    }
}
