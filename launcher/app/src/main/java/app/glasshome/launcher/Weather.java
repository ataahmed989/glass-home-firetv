package app.glasshome.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Live Centerville, OH weather from Open-Meteo (no API key), in °F and America/New_York time. */
final class Weather {
    static final ZoneId ZONE = ZoneId.of("America/New_York");
    private static final String URL = "https://api.open-meteo.com/v1/forecast?latitude=39.6284&longitude=-84.1594"
            + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,wind_direction_10m,weather_code,is_day"
            + "&hourly=temperature_2m,weather_code,is_day,precipitation_probability"
            + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset"
            + "&temperature_unit=fahrenheit&wind_speed_unit=mph&timezone=America%2FNew_York&forecast_days=7";
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("h a", Locale.US);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US);
    private static final DateTimeFormatter WEEKDAY = DateTimeFormatter.ofPattern("EEE", Locale.US);

    static final class Hour {
        final String label;
        final int temp, code;
        final boolean day;
        final int precipitationChance;   // percent, or -1 when not reported

        Hour(String label, int temp, int code, boolean day, int precipitationChance) {
            this.label = label;
            this.temp = temp;
            this.code = code;
            this.day = day;
            this.precipitationChance = precipitationChance;
        }
    }

    static final class Day {
        final String label;
        final int code, high, low, precipitationChance;

        Day(String label, int code, int high, int low, int precipitationChance) {
            this.label = label;
            this.code = code;
            this.high = high;
            this.low = low;
            this.precipitationChance = precipitationChance;
        }
    }

    static final class Data {
        final int temp, code, high, low, feelsLike, humidity, windMph;
        final String windFrom, sunrise, sunset;
        final boolean day;
        final Hour[] hours;      // next 4 hours, for the card
        final Hour[] next12;     // next 12 hours, for the detailed forecast
        final Day[] days;        // 7 days starting today
        final long fetchedAtMillis;

        Data(int temp, int code, boolean day, int high, int low, int feelsLike, int humidity, int windMph, String windFrom,
             String sunrise, String sunset, Hour[] next12, Day[] days, long fetchedAtMillis) {
            this.temp = temp;
            this.code = code;
            this.day = day;
            this.high = high;
            this.low = low;
            this.feelsLike = feelsLike;
            this.humidity = humidity;
            this.windMph = windMph;
            this.windFrom = windFrom;
            this.sunrise = sunrise;
            this.sunset = sunset;
            this.next12 = next12;
            this.hours = java.util.Arrays.copyOf(next12, 4);
            this.days = days;
            this.fetchedAtMillis = fetchedAtMillis;
        }

        String condition() {
            return Weather.condition(code, day);
        }
    }

    private Weather() {}

    static Data fetch() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(URL).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        try (InputStream in = c.getInputStream()) {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            return parse(new JSONObject(read(in)), System.currentTimeMillis());
        } finally {
            c.disconnect();
        }
    }

    static Data parse(JSONObject json, long now) throws Exception {
        JSONObject current = json.getJSONObject("current");
        JSONObject daily = json.getJSONObject("daily");
        JSONObject hourly = json.getJSONObject("hourly");
        JSONArray times = hourly.getJSONArray("time");
        JSONArray temps = hourly.getJSONArray("temperature_2m");
        JSONArray codes = hourly.getJSONArray("weather_code");
        JSONArray isDay = hourly.getJSONArray("is_day");
        JSONArray chances = hourly.optJSONArray("precipitation_probability");

        // The next twelve whole hours after the current hour in Centerville
        LocalDateTime hourNow = LocalDateTime.now(ZONE).withMinute(0).withSecond(0).withNano(0);
        Hour[] next12 = new Hour[12];
        int filled = 0;
        for (int i = 0; i < times.length() && filled < next12.length; i++) {
            LocalDateTime t = LocalDateTime.parse(times.getString(i), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            if (!t.isAfter(hourNow)) continue;
            int chance = chances == null || chances.isNull(i) ? -1 : chances.getInt(i);
            next12[filled++] = new Hour(t.format(HOUR), round(temps, i), codes.getInt(i), isDay.getInt(i) == 1, chance);
        }
        if (filled < next12.length) throw new IOException("hourly forecast too short");

        JSONArray dates = daily.getJSONArray("time");
        JSONArray highs = daily.getJSONArray("temperature_2m_max");
        JSONArray lows = daily.getJSONArray("temperature_2m_min");
        JSONArray dayCodes = daily.getJSONArray("weather_code");
        JSONArray dayChances = daily.optJSONArray("precipitation_probability_max");
        Day[] days = new Day[dates.length()];
        for (int i = 0; i < days.length; i++) {
            String label = i == 0 ? "Today" : LocalDate.parse(dates.getString(i)).format(WEEKDAY);
            int chance = dayChances == null || dayChances.isNull(i) ? -1 : dayChances.getInt(i);
            days[i] = new Day(label, dayCodes.getInt(i), round(highs, i), round(lows, i), chance);
        }

        return new Data(round(current, "temperature_2m"), current.getInt("weather_code"), current.getInt("is_day") == 1,
                days[0].high, days[0].low, round(current, "apparent_temperature"), round(current, "relative_humidity_2m"),
                round(current, "wind_speed_10m"), compass(current.optDouble("wind_direction_10m", Double.NaN)),
                clock(daily.getJSONArray("sunrise").getString(0)), clock(daily.getJSONArray("sunset").getString(0)),
                next12, days, now);
    }

    private static int round(JSONArray a, int i) throws Exception {
        return (int) Math.round(a.getDouble(i));
    }

    private static int round(JSONObject o, String key) throws Exception {
        return (int) Math.round(o.getDouble(key));
    }

    private static String clock(String isoLocal) {
        return LocalDateTime.parse(isoLocal, DateTimeFormatter.ISO_LOCAL_DATE_TIME).format(CLOCK);
    }

    private static String compass(double degrees) {
        if (Double.isNaN(degrees)) return "";
        String[] points = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return points[(int) Math.round(((degrees % 360) + 360) % 360 / 45.0) % 8];
    }

    /** WMO weather interpretation codes, as used by Open-Meteo. */
    static String condition(int code, boolean day) {
        switch (code) {
            case 0: return day ? "Sunny" : "Clear";
            case 1: return day ? "Mostly Sunny" : "Mostly Clear";
            case 2: return "Partly Cloudy";
            case 3: return "Cloudy";
            case 45: case 48: return "Fog";
            case 51: case 53: case 55: return "Drizzle";
            case 56: case 57: return "Freezing Drizzle";
            case 61: return "Light Rain";
            case 63: return "Rain";
            case 65: return "Heavy Rain";
            case 66: case 67: return "Freezing Rain";
            case 71: return "Light Snow";
            case 73: return "Snow";
            case 75: return "Heavy Snow";
            case 77: return "Snow Grains";
            case 80: case 81: return "Showers";
            case 82: return "Heavy Showers";
            case 85: case 86: return "Snow Showers";
            case 95: return "Thunderstorms";
            case 96: case 99: return "Thunderstorms, Hail";
            default: return "—";
        }
    }

    static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
