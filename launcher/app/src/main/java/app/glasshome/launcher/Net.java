package app.glasshome.launcher;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.GZIPInputStream;

/**
 * HTTP GET for the browse pages (TMDB, ESPN) and their artwork. Gzip is decoded here rather than
 * left to the platform, because the servers send it even when the JVM used by the tests did not
 * ask. Tests replace {@link #fetcher} with recorded responses.
 */
final class Net {
    interface Fetcher {
        byte[] get(String url, String bearer) throws IOException;
    }

    static volatile Fetcher fetcher = Net::http;

    private Net() {}

    static byte[] get(String url, String bearer) throws IOException {
        return fetcher.get(url, bearer);
    }

    static byte[] http(String url, String bearer) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Accept-Encoding", "gzip");
        // ESPN turns away generic Java clients; name the app instead
        c.setRequestProperty("User-Agent", "GlassHome/1.0 (Linux; Android 11; Fire TV)");
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
        try {
            int code = c.getResponseCode();
            if (code / 100 != 2) throw new IOException("HTTP " + code + " from " + c.getURL().getHost());
            try (InputStream in = c.getInputStream()) {
                return gunzip(readAll(in));
            }
        } finally {
            c.disconnect();
        }
    }

    /**
     * A text response kept on disk for {@code maxAgeMs}. When the network fails, an older copy is
     * used rather than nothing.
     */
    static String cached(Context context, String url, String bearer, long maxAgeMs) throws IOException {
        File dir = new File(context.getCacheDir(), "net");
        dir.mkdirs();
        File file = new File(dir, sha1(url) + ".txt");
        if (file.isFile() && Clock.now() - file.lastModified() < maxAgeMs) return read(file);
        try {
            byte[] body = get(url, bearer);
            write(file, body);
            return new String(body, StandardCharsets.UTF_8);
        } catch (IOException e) {
            if (file.isFile()) return read(file);
            throw e;
        }
    }

    static void write(File file, byte[] body) throws IOException {
        File tmp = new File(file.getPath() + ".part");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(body);
        }
        if (!tmp.renameTo(file)) throw new IOException("cache write failed");
        file.setLastModified(Clock.now());
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }

    static byte[] gunzip(byte[] body) throws IOException {
        if (body.length < 2 || (body[0] & 0xFF) != 0x1F || (body[1] & 0xFF) != 0x8B) return body;
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
            return readAll(in);
        }
    }

    static String sha1(String text) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder();
            for (byte b : d) s.append(String.format("%02x", b));
            return s.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
