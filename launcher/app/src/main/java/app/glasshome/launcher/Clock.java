package app.glasshome.launcher;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Current time and zone for the browse pages; tests pin both so recorded data reads the same every run. */
final class Clock {
    static volatile Long fixedMillis;
    static volatile ZoneId fixedZone;

    private Clock() {}

    static long now() {
        Long fixed = fixedMillis;
        return fixed != null ? fixed : System.currentTimeMillis();
    }

    static ZoneId zone() {
        ZoneId fixed = fixedZone;
        return fixed != null ? fixed : ZoneId.systemDefault();
    }

    static ZonedDateTime at(long millis) {
        return Instant.ofEpochMilli(millis).atZone(zone());
    }

    static LocalDate today() {
        return at(now()).toLocalDate();
    }
}
