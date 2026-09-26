package com.elythera.elymon.update;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Network side of the self-update: fetching latest.json and downloading the APK with its
 * size and SHA-256 checked. Nothing here logs.
 *
 * Pure Java (no Android imports) for host-side tests.
 */
public final class UpdateHttp {
    static final int MAX_MANIFEST_BYTES = 64 * 1024;

    private UpdateHttp() {}

    /** Outcome of a feed request. */
    public static final class FeedResult {
        public enum Kind {
            /** latest.json was read and is valid. */
            FOUND,
            /** 404 or 410: nothing published yet. */
            NOT_FOUND,
            /** Network error, server error or invalid content. */
            ERROR
        }

        public final Kind kind;
        public final UpdateManifest manifest;
        /** The raw JSON when FOUND, to cache it. */
        public final String json;
        /** English, secret-free, for logs; null unless ERROR. */
        public final String error;

        FeedResult(Kind kind, UpdateManifest manifest, String json, String error) {
            this.kind = kind;
            this.manifest = manifest;
            this.json = json;
            this.error = error;
        }
    }

    /** Why a download failed. */
    public static final class DownloadException extends IOException {
        private static final long serialVersionUID = 1L;

        public enum Reason { NETWORK, SIZE, HASH, CANCELLED }

        public final Reason reason;

        DownloadException(Reason reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }
    }

    /** Download progress and cancellation. */
    public interface Progress {
        void onProgress(long doneBytes, long totalBytes);

        boolean isCancelled();
    }

    /** Fetches and validates latest.json. Never throws. */
    public static FeedResult fetchFeed(String url, String userAgent, int timeoutMs) {
        HttpURLConnection connection = null;
        try {
            connection = open(url, userAgent, timeoutMs);
            connection.setRequestProperty("Accept", "application/json");
            // latest.json must never be served stale from a cache between the CDN and the phone.
            connection.setRequestProperty("Cache-Control", "no-cache");
            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_FOUND || code == HttpURLConnection.HTTP_GONE) {
                return new FeedResult(FeedResult.Kind.NOT_FOUND, null, null, null);
            }
            if (code != HttpURLConnection.HTTP_OK) {
                return new FeedResult(FeedResult.Kind.ERROR, null, null, "HTTP " + code);
            }
            String json;
            try (InputStream in = connection.getInputStream()) {
                json = readLimited(in, MAX_MANIFEST_BYTES);
            }
            try {
                return new FeedResult(FeedResult.Kind.FOUND, UpdateManifest.parse(json), json, null);
            } catch (IllegalArgumentException e) {
                return new FeedResult(FeedResult.Kind.ERROR, null, null, e.getMessage());
            }
        } catch (IOException | RuntimeException e) {
            return new FeedResult(FeedResult.Kind.ERROR, null, null, e.getClass().getSimpleName());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Downloads url to target through target.part, then checks the exact size and the
     * SHA-256. On any failure nothing is left at target or target.part.
     */
    public static void download(String url, File target, long expectedSize, String expectedSha256,
                                String userAgent, int timeoutMs, Progress progress) throws DownloadException {
        File partial = new File(target.getPath() + ".part");
        delete(partial);
        delete(target);
        MessageDigest digest = sha256();
        long done = 0;
        HttpURLConnection connection = null;
        try {
            connection = open(url, userAgent, timeoutMs);
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new DownloadException(DownloadException.Reason.NETWORK, "HTTP " + code, null);
            }
            long announced = connection.getContentLength();
            if (announced >= 0 && announced != expectedSize) {
                throw new DownloadException(DownloadException.Reason.SIZE,
                        "server announced " + announced + " bytes, expected " + expectedSize, null);
            }
            byte[] buffer = new byte[64 * 1024];
            try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(partial)) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (progress != null && progress.isCancelled()) {
                        throw new DownloadException(DownloadException.Reason.CANCELLED, "cancelled", null);
                    }
                    done += read;
                    if (done > expectedSize) {
                        throw new DownloadException(DownloadException.Reason.SIZE, "more bytes than announced", null);
                    }
                    out.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    if (progress != null) {
                        progress.onProgress(done, expectedSize);
                    }
                }
            }
            if (done != expectedSize) {
                throw new DownloadException(DownloadException.Reason.SIZE,
                        "got " + done + " bytes, expected " + expectedSize, null);
            }
            String actual = hex(digest.digest());
            if (!actual.equalsIgnoreCase(expectedSha256)) {
                throw new DownloadException(DownloadException.Reason.HASH, "SHA-256 mismatch", null);
            }
            if (!partial.renameTo(target)) {
                throw new DownloadException(DownloadException.Reason.NETWORK, "cannot rename the download", null);
            }
        } catch (DownloadException e) {
            delete(partial);
            throw e;
        } catch (IOException | RuntimeException e) {
            delete(partial);
            throw new DownloadException(DownloadException.Reason.NETWORK, e.getClass().getSimpleName(), e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** Whether file exists with exactly this size and SHA-256 (a download kept from an earlier try). */
    public static boolean matches(File file, long size, String sha256) {
        if (file == null || !file.isFile() || file.length() != size) {
            return false;
        }
        try {
            return sha256Hex(file).equalsIgnoreCase(sha256);
        } catch (IOException e) {
            return false;
        }
    }

    public static String sha256Hex(File file) throws IOException {
        MessageDigest digest = sha256();
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = new FileInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    public static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }
        return out.toString();
    }

    private static HttpURLConnection open(String url, String userAgent, int timeoutMs) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(timeoutMs);
        connection.setReadTimeout(timeoutMs);
        connection.setInstanceFollowRedirects(true);
        connection.setUseCaches(false);
        if (userAgent != null) {
            connection.setRequestProperty("User-Agent", userAgent);
        }
        return connection;
    }

    private static String readLimited(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            if (out.size() + read > limit) {
                throw new IOException("latest.json is larger than " + limit + " bytes");
            }
            out.write(buffer, 0, read);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void delete(File file) {
        if (file.exists() && !file.delete()) {
            file.deleteOnExit();
        }
    }
}
