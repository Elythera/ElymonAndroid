package com.elythera.elymon.sync;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;

/**
 * HTTP for the sync engine, on HttpURLConnection: a document fetch with ETag
 * and gzip, and resumable file downloads (Range on a .part file, MD5 check).
 * No logging: nothing here ever sees a secret, but nothing is printed either.
 */
final class Http {
    private Http() {}

    // Tunables; the host tests shorten the delays and allow file: URLs.
    static volatile int connectTimeoutMs = 15000;
    static volatile int readTimeoutMs = 30000;
    static volatile int attempts = 3;
    static volatile long retryDelayMs = 1000;
    static volatile boolean allowNonHttpUrls = false;

    /** Non-2xx answer. */
    static final class StatusException extends IOException {
        final int code;

        StatusException(int code, String url) {
            super("HTTP " + code + " for " + url);
            this.code = code;
        }

        /** 4xx other than timeout and rate limit will not change on retry. */
        boolean retryable() {
            return code < 400 || code >= 500 || code == 408 || code == 429;
        }
    }

    /** The bytes downloaded are complete but are not the published ones. */
    static final class Md5MismatchException extends IOException {
        Md5MismatchException(String url) {
            super("MD5 mismatch for " + url);
        }
    }

    /** A document answer: 304 has a null body. */
    static final class Document {
        int code;
        byte[] body;
        String etag;
    }

    /**
     * Encodes what is not allowed in a URL (spaces, non-ASCII, quotes...) and
     * keeps what is: existing %XX escapes are left alone, so an URL already
     * percent-encoded by Nebula is not encoded twice.
     */
    static String normalizeUrl(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 16);
        int i = 0;
        while (i < raw.length()) {
            int cp = raw.codePointAt(i);
            int len = Character.charCount(cp);
            if (cp == '%') {
                if (i + 2 < raw.length() && isHex(raw.charAt(i + 1)) && isHex(raw.charAt(i + 2))) {
                    out.append('%');
                } else {
                    out.append("%25");
                }
            } else if (cp > 0x20 && cp < 0x7f && "\"<>\\^`{|}".indexOf(cp) < 0) {
                out.append((char) cp);
            } else {
                byte[] bytes = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8);
                for (byte b : bytes) {
                    out.append('%').append(String.format(Locale.ROOT, "%02X", b & 0xff));
                }
            }
            i += len;
        }
        return out.toString();
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    static URLConnection open(String url, String userAgent, long rangeStart, String etag, boolean gzip)
            throws IOException {
        URL u = new URL(normalizeUrl(url));
        String protocol = u.getProtocol().toLowerCase(Locale.ROOT);
        if (!protocol.equals("https") && !protocol.equals("http") && !allowNonHttpUrls) {
            throw new IOException("unsupported URL scheme " + protocol);
        }
        URLConnection c = u.openConnection();
        c.setConnectTimeout(connectTimeoutMs);
        c.setReadTimeout(readTimeoutMs);
        c.setUseCaches(false);
        if (c instanceof HttpURLConnection) {
            HttpURLConnection h = (HttpURLConnection) c;
            h.setInstanceFollowRedirects(true);
            h.setRequestProperty("User-Agent", userAgent == null ? "ElymonAndroid" : userAgent);
            // Explicit, so that neither the JDK nor Android's OkHttp decompresses
            // behind our back: Range offsets and Content-Length stay about raw bytes.
            h.setRequestProperty("Accept-Encoding", gzip ? "gzip" : "identity");
            if (rangeStart > 0) {
                h.setRequestProperty("Range", "bytes=" + rangeStart + "-");
            }
            if (etag != null) {
                h.setRequestProperty("If-None-Match", etag);
            }
        }
        return c;
    }

    /** One GET of a small document (the distribution). No retry here. */
    static Document fetchDocument(String url, String userAgent, String etag) throws IOException {
        URLConnection c = open(url, userAgent, -1, etag, true);
        Document d = new Document();
        InputStream in = null;
        try {
            if (c instanceof HttpURLConnection) {
                HttpURLConnection h = (HttpURLConnection) c;
                d.code = h.getResponseCode();
                if (d.code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                    d.etag = h.getHeaderField("ETag");
                    return d;
                }
                if (d.code != HttpURLConnection.HTTP_OK) {
                    throw new StatusException(d.code, url);
                }
                d.etag = h.getHeaderField("ETag");
            } else {
                d.code = 200;
            }
            in = c.getInputStream();
            if ("gzip".equalsIgnoreCase(c.getContentEncoding())) {
                in = new GZIPInputStream(in);
            }
            d.body = FileOps.readAll(in);
            return d;
        } finally {
            FileOps.closeQuietly(in);
            if (c instanceof HttpURLConnection) {
                ((HttpURLConnection) c).disconnect();
            }
        }
    }

    /**
     * Downloads url into part, resuming from what part already holds, and
     * checks the whole file against md5 (when not null) and size (when ≥ 0).
     *
     * On an interrupted transfer the part is kept for the next attempt; on an
     * MD5 mismatch it is deleted. Returns the bytes read from the network.
     */
    static long downloadToPart(String url, String userAgent, File part, String md5, long size,
                               AtomicBoolean cancel, AtomicLong progress) throws IOException {
        FileOps.ensureParent(part);
        long existing = part.isFile() ? part.length() : 0;
        if (size >= 0 && existing > size) {
            part.delete();
            existing = 0;
        }
        if (existing > 0 && size >= 0 && existing == size && md5 != null) {
            // Complete from an earlier attempt that died before the rename.
            if (md5.equals(FileOps.md5(part, cancel))) {
                return 0;
            }
            part.delete();
            existing = 0;
        }

        URLConnection c = open(url, userAgent, existing, null, false);
        MessageDigest digest = FileOps.newMd5();
        boolean append = false;
        long announced = -1;
        InputStream in = null;
        OutputStream out = null;
        long received = 0;
        try {
            if (c instanceof HttpURLConnection) {
                HttpURLConnection h = (HttpURLConnection) c;
                int code = h.getResponseCode();
                if (code == HttpURLConnection.HTTP_PARTIAL && existing > 0) {
                    long start = contentRangeStart(h.getHeaderField("Content-Range"));
                    if (start != existing) {
                        part.delete();
                        throw new IOException("unexpected Content-Range for " + url);
                    }
                    append = true;
                } else if (code == 416) {
                    // The part does not fit this file any more: start over next time.
                    part.delete();
                    throw new IOException("range not satisfiable for " + url);
                } else if (code != HttpURLConnection.HTTP_OK) {
                    throw new StatusException(code, url);
                }
                announced = contentLength(h);
            }
            if (append) {
                FileOps.update(digest, part);
            }
            in = c.getInputStream();
            out = new FileOutputStream(part, append);
            byte[] buf = new byte[FileOps.BUFFER];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancel.get()) {
                    throw new FileOps.CancelledIO();
                }
                out.write(buf, 0, n);
                digest.update(buf, 0, n);
                received += n;
                progress.addAndGet(n);
            }
            out.flush();
        } finally {
            FileOps.closeQuietly(out);
            FileOps.closeQuietly(in);
            if (c instanceof HttpURLConnection) {
                ((HttpURLConnection) c).disconnect();
            }
        }
        if (announced >= 0 && received < announced) {
            throw new IOException("connection closed early for " + url);
        }
        long total = part.length();
        if (size >= 0 && total < size) {
            throw new IOException("incomplete download for " + url);
        }
        if ((size >= 0 && total > size) || (md5 != null && !md5.equals(FileOps.hex(digest.digest())))) {
            part.delete();
            throw new Md5MismatchException(url);
        }
        return received;
    }

    private static long contentLength(HttpURLConnection h) {
        String value = h.getHeaderField("Content-Length");
        if (value == null) {
            return -1;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** "bytes 100-199/200" gives 100; anything unreadable gives -1. */
    static long contentRangeStart(String header) {
        if (header == null) {
            return -1;
        }
        String h = header.trim();
        if (!h.toLowerCase(Locale.ROOT).startsWith("bytes")) {
            return -1;
        }
        h = h.substring(5).trim();
        int dash = h.indexOf('-');
        if (dash <= 0) {
            return -1;
        }
        try {
            return Long.parseLong(h.substring(0, dash).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Sleeps before retry number attempt (1-based), waking early on cancel. */
    static void backoff(int attempt, AtomicBoolean cancel) throws FileOps.CancelledIO {
        long delay = retryDelayMs * (1L << Math.max(0, attempt - 1));
        long end = System.currentTimeMillis() + delay;
        while (System.currentTimeMillis() < end) {
            if (cancel != null && cancel.get()) {
                throw new FileOps.CancelledIO();
            }
            try {
                Thread.sleep(Math.min(50, Math.max(1, end - System.currentTimeMillis())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FileOps.CancelledIO();
            }
        }
    }
}
