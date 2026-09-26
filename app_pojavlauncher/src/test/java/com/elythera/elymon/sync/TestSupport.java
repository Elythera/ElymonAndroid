package com.elythera.elymon.sync;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A tiny assertion harness for the host tests: no JUnit, plain Java 8, so that
 * tools/elymon/test-sync.sh can compile and run them with the JDK alone.
 */
final class TestSupport {
    private TestSupport() {}

    static int passed;
    static final List<String> failures = new ArrayList<String>();
    private static String section = "";

    static void section(String name) {
        section = name;
        System.out.println("== " + name);
    }

    static void check(boolean condition, String what) {
        if (condition) {
            passed++;
        } else {
            failures.add(section + ": " + what);
            System.out.println("   FAIL " + what);
        }
    }

    static void equal(Object expected, Object actual, String what) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (same) {
            passed++;
        } else {
            failures.add(section + ": " + what + " (expected <" + expected + ">, got <" + actual + ">)");
            System.out.println("   FAIL " + what + " (expected <" + expected + ">, got <" + actual + ">)");
        }
    }

    static void fail(String what, Throwable t) {
        failures.add(section + ": " + what + " threw " + t);
        System.out.println("   FAIL " + what + " threw " + t);
        t.printStackTrace(System.out);
    }

    static void note(String text) {
        System.out.println("   " + text);
    }

    static File mkdirs(File dir) {
        dir.mkdirs();
        return dir;
    }

    static void write(File file, byte[] data) throws IOException {
        file.getParentFile().mkdirs();
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(data);
        } finally {
            out.close();
        }
    }

    static void write(File file, String text) throws IOException {
        write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] read(File file) throws IOException {
        return FileOps.readFile(file);
    }

    static String readText(File file) throws IOException {
        return new String(read(file), StandardCharsets.UTF_8);
    }

    static String md5(byte[] data) {
        return FileOps.md5(data);
    }

    static String md5(File file) throws IOException {
        return FileOps.md5(read(file));
    }

    static void deleteTree(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory() && !FileOps.isSymlink(file)) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteTree(child);
                }
            }
        }
        file.delete();
    }

    /** Deterministic pseudo-random bytes. */
    static byte[] bytes(int size, int seed) {
        byte[] out = new byte[size];
        long x = seed * 2654435761L + 1;
        for (int i = 0; i < size; i++) {
            x = x * 6364136223846793005L + 1442695040888963407L;
            out[i] = (byte) (x >>> 56);
        }
        return out;
    }

    /** A listener that records what the engine tells it. */
    static class Recorder implements SyncListener {
        final List<String> stages = new ArrayList<String>();
        final List<Long> confirmations = new ArrayList<Long>();
        boolean answer = true;
        boolean cancelAtFirstProgress;
        /** Cancel once bytes have started to arrive during the download stage. */
        boolean cancelWhileDownloading;
        boolean cancelNow;
        /** Every progress call had done <= total. */
        boolean progressInRange = true;
        int progressCalls;
        long lastDone = -1;
        long lastTotal = -1;
        Thread callbackThread;
        boolean wrongThread;

        private void thread() {
            if (callbackThread == null) {
                callbackThread = Thread.currentThread();
            } else if (callbackThread != Thread.currentThread()) {
                wrongThread = true;
            }
        }

        @Override
        public void onStage(String label) {
            thread();
            stages.add(label);
        }

        @Override
        public void onProgress(long doneBytes, long totalBytes, String currentPath) {
            thread();
            progressCalls++;
            lastDone = doneBytes;
            lastTotal = totalBytes;
            if (doneBytes < 0 || doneBytes > totalBytes) {
                progressInRange = false;
            }
            if (cancelAtFirstProgress) {
                cancelNow = true;
            }
            if (cancelWhileDownloading && doneBytes > 0 && !stages.isEmpty()
                    && stages.get(stages.size() - 1).startsWith("Téléchargement")) {
                cancelNow = true;
            }
        }

        @Override
        public boolean confirmDownload(long bytesToDownload) {
            thread();
            confirmations.add(bytesToDownload);
            return answer;
        }

        @Override
        public boolean isCancelled() {
            thread();
            return cancelNow;
        }

        boolean sawStageStartingWith(String prefix) {
            for (String s : stages) {
                if (s.startsWith(prefix)) {
                    return true;
                }
            }
            return false;
        }
    }
}
