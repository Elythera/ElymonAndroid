package com.elythera.elymon.sync;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Hashing, atomic writes and moves. Java 8 and Android API 29 only. */
final class FileOps {
    private FileOps() {}

    static final String EMPTY_MD5 = "d41d8cd98f00b204e9800998ecf8427e";
    static final int BUFFER = 64 * 1024;

    /** How many files were hashed from disk since the process started (read by the tests). */
    static final AtomicLong FILES_HASHED = new AtomicLong();

    static MessageDigest newMd5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] digest) {
        char[] out = new char[digest.length * 2];
        final char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < digest.length; i++) {
            out[i * 2] = digits[(digest[i] >> 4) & 0xf];
            out[i * 2 + 1] = digits[digest[i] & 0xf];
        }
        return new String(out);
    }

    static String md5(byte[] data) {
        MessageDigest md = newMd5();
        md.update(data);
        return hex(md.digest());
    }

    /** MD5 of a file, or null when it cannot be read. */
    static String md5(File file, AtomicBoolean cancel) throws CancelledIO {
        FILES_HASHED.incrementAndGet();
        MessageDigest md = newMd5();
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancel != null && cancel.get()) {
                    throw new CancelledIO();
                }
                md.update(buf, 0, n);
            }
            return hex(md.digest());
        } catch (CancelledIO e) {
            throw e;
        } catch (IOException e) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    /** Feeds a whole file into a digest (resume of a partial download). */
    static void update(MessageDigest md, File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        } finally {
            closeQuietly(in);
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[BUFFER];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    static byte[] readFile(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            return readAll(in);
        } finally {
            closeQuietly(in);
        }
    }

    static void ensureParent(File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("cannot create directory " + parent);
        }
    }

    /**
     * Writes data next to the target, flushes it to the disk, then renames it
     * over the target: a reader sees the old file or the new one, never half.
     */
    static void writeAtomic(File target, byte[] data) throws IOException {
        ensureParent(target);
        File tmp = new File(target.getPath() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(data);
            out.flush();
            out.getFD().sync();
        } finally {
            closeQuietly(out);
        }
        move(tmp, target);
    }

    /** Rename, replacing the target; falls back to copy then delete. */
    static void move(File from, File to) throws IOException {
        ensureParent(to);
        if (from.renameTo(to)) {
            return;
        }
        if (to.exists() && !to.isDirectory()) {
            to.delete();
            if (from.renameTo(to)) {
                return;
            }
        }
        copy(from, to, null);
        if (!from.delete()) {
            from.deleteOnExit();
        }
    }

    /** Copies a file and returns the MD5 of the bytes written. */
    static String copy(File from, File to, AtomicBoolean cancel) throws IOException {
        ensureParent(to);
        MessageDigest md = newMd5();
        InputStream in = new FileInputStream(from);
        OutputStream out = null;
        try {
            out = new FileOutputStream(to);
            byte[] buf = new byte[BUFFER];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancel != null && cancel.get()) {
                    throw new CancelledIO();
                }
                out.write(buf, 0, n);
                md.update(buf, 0, n);
            }
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
        return hex(md.digest());
    }

    static boolean isSymlink(File file) {
        try {
            return Files.isSymbolicLink(file.toPath());
        } catch (RuntimeException e) {
            return true;
        }
    }

    static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // Nothing more to do.
            }
        }
    }

    /** Thrown from inside I/O loops when the player cancelled. */
    static final class CancelledIO extends IOException {
        CancelledIO() {
            super("cancelled");
        }
    }
}
