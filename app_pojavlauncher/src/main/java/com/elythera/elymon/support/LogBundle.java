package com.elythera.elymon.support;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes the support zip: text files copied line by line through {@link LogRedactor},
 * each one cut to its last maxBytes, plus an infos.txt.
 *
 * Pure Java (no Android imports) for host-side tests.
 */
public final class LogBundle {
    /** A line longer than this is split: a binary file must not load megabytes into one String. */
    static final int MAX_LINE_CHARS = 64 * 1024;

    private LogBundle() {}

    /** One file of the zip. */
    public static final class Entry {
        public final String name;
        public final File source;
        public final long maxBytes;

        /**
         * @param name     path inside the zip
         * @param source   file on the device; a missing file is listed in infos.txt as absent
         * @param maxBytes only the last maxBytes of the file are kept (whole lines)
         */
        public Entry(String name, File source, long maxBytes) {
            this.name = name;
            this.source = source;
            this.maxBytes = maxBytes;
        }
    }

    /**
     * Writes the zip, replacing target. Returns the names of the entries whose source
     * was missing or unreadable (they are not in the zip). The infos text is redacted too
     * and gets the list of missing files appended.
     */
    public static List<String> write(File target, List<Entry> entries, String infos) throws IOException {
        List<String> missing = new ArrayList<>();
        File partial = new File(target.getPath() + ".part");
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(partial)))) {
            for (Entry entry : entries) {
                if (entry.source == null || !entry.source.isFile() || !entry.source.canRead()) {
                    missing.add(entry.name);
                    continue;
                }
                zip.putNextEntry(new ZipEntry(entry.name));
                try {
                    copyRedacted(entry.source, entry.maxBytes, zip);
                } catch (IOException e) {
                    // Keep what was written and say why the rest is missing.
                    writeText(zip, "\n[Elymon] lecture interrompue : " + e.getClass().getSimpleName() + "\n");
                }
                zip.closeEntry();
            }
            StringBuilder text = new StringBuilder(infos == null ? "" : infos);
            if (!missing.isEmpty()) {
                text.append("\nFichiers absents :\n");
                for (String name : missing) {
                    text.append("  ").append(name).append('\n');
                }
            }
            zip.putNextEntry(new ZipEntry("infos.txt"));
            copyRedacted(new java.io.ByteArrayInputStream(text.toString().getBytes(StandardCharsets.UTF_8)), zip);
            zip.closeEntry();
        } catch (IOException | RuntimeException e) {
            deleteQuietly(partial);
            throw e;
        }
        deleteQuietly(target);
        if (!partial.renameTo(target)) {
            deleteQuietly(partial);
            throw new IOException("cannot rename " + partial.getName());
        }
        return missing;
    }

    /**
     * Copies the last maxBytes of source (maxBytes &lt;= 0: all of it) to out, redacted.
     * When the start is cut, the first partial line is dropped and a note says how much.
     */
    public static void copyRedacted(File source, long maxBytes, OutputStream out) throws IOException {
        long length = source.length();
        try (InputStream in = new FileInputStream(source)) {
            if (maxBytes > 0 && length > maxBytes) {
                long skip = length - maxBytes;
                skipFully(in, skip);
                // Drop the rest of the line the cut fell into.
                int b;
                long dropped = 0;
                while ((b = in.read()) != -1 && b != '\n') {
                    dropped++;
                }
                writeText(out, String.format(Locale.ROOT,
                        "[Elymon] début du fichier omis : %d octets (seuls les %d derniers sont gardés)%n",
                        skip + dropped + (b == -1 ? 0 : 1), maxBytes));
            }
            copyRedacted(in, out);
        }
    }

    /** Copies a whole stream to out, line by line, redacted. Does not close either stream. */
    public static void copyRedacted(InputStream in, OutputStream out) throws IOException {
        // Malformed UTF-8 (binary files, cut multibyte characters) is replaced, never fatal.
        Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
        Writer writer = new OutputStreamWriter(new NonClosingStream(out), StandardCharsets.UTF_8);
        StringBuilder line = new StringBuilder(256);
        char[] buffer = new char[8192];
        int read;
        while ((read = reader.read(buffer)) != -1) {
            for (int i = 0; i < read; i++) {
                char c = buffer[i];
                if (c == '\n') {
                    writer.write(LogRedactor.redactLine(stripCarriageReturn(line)));
                    writer.write('\n');
                    line.setLength(0);
                } else {
                    line.append(c);
                    if (line.length() >= MAX_LINE_CHARS) {
                        writer.write(LogRedactor.redactLine(line.toString()));
                        writer.write('\n');
                        line.setLength(0);
                    }
                }
            }
        }
        if (line.length() > 0) {
            writer.write(LogRedactor.redactLine(stripCarriageReturn(line)));
            writer.write('\n');
        }
        writer.flush();
    }

    /** The newest file of dir whose name starts with prefix and ends with suffix, or null. */
    public static File newest(File dir, String prefix, String suffix) {
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) {
            return null;
        }
        File best = null;
        for (File file : files) {
            String name = file.getName();
            if (!file.isFile() || !name.startsWith(prefix) || !name.endsWith(suffix)) {
                continue;
            }
            if (best == null || file.lastModified() > best.lastModified()) {
                best = file;
            }
        }
        return best;
    }

    private static String stripCarriageReturn(StringBuilder line) {
        int end = line.length();
        if (end > 0 && line.charAt(end - 1) == '\r') {
            end--;
        }
        return line.substring(0, end);
    }

    private static void writeText(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
    }

    private static void skipFully(InputStream in, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() == -1) {
                    return;
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private static void deleteQuietly(File file) {
        if (file.exists() && !file.delete()) {
            file.deleteOnExit();
        }
    }

    /** Lets a Writer be flushed without closing the zip stream under it. */
    private static final class NonClosingStream extends OutputStream {
        private final OutputStream mOut;

        NonClosingStream(OutputStream out) {
            mOut = out;
        }

        @Override
        public void write(int b) throws IOException {
            mOut.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            mOut.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            mOut.flush();
        }

        @Override
        public void close() throws IOException {
            flush();
        }
    }
}
