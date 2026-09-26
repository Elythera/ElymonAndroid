package com.elythera.elymon.ui;

import com.elythera.elymon.ElymonConfig;
import com.elythera.elymon.ElymonMemory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Host-side checks of the pure-Java helpers of the ui package (heap tiers and warning
 * threshold, pack version of the home screen). No JUnit, like ElymonCoreHostTest; run
 * from the repo root with a JDK:
 *
 *   S=app_pojavlauncher/src; O=$(mktemp -d)
 *   javac --release 8 -cp app_pojavlauncher/libs/gson-2.8.6.jar -d $O \
 *       $S/main/java/com/elythera/elymon/{ElymonConfig,ElymonMemory}.java \
 *       $S/main/java/com/elythera/elymon/ui/ElymonPackInfo.java \
 *       $S/test/java/com/elythera/elymon/ui/ElymonUiHostTest.java
 *   java -cp $O:app_pojavlauncher/libs/gson-2.8.6.jar com.elythera.elymon.ui.ElymonUiHostTest [distribution.json]
 *
 * The optional argument is a real distribution (e.g. elymon-android-notes/distribution-2026-09-26.json).
 */
public final class ElymonUiHostTest {
    private static int sChecks;
    private static int sFailures;

    public static void main(String[] args) throws IOException {
        memory();
        packInfo(args.length > 0 ? new File(args[0]) : null);
        System.out.println(sChecks + " checks, " + sFailures + " failure(s)");
        if (sFailures > 0) {
            System.exit(1);
        }
    }

    private static void memory() {
        // MemTotal as Android reports it, in MB
        check(ElymonMemory.defaultHeapMb(11059) == 4096, "Galaxy S24 Ultra (10.8 GiB) gets 4096");
        check(ElymonMemory.defaultHeapMb(11571) == 4096, "12 GB phone at 11.3 GiB");
        check(ElymonMemory.defaultHeapMb(10 * 1024) == 4096, "10 GiB boundary");
        check(ElymonMemory.defaultHeapMb(10 * 1024 - 1) == 3072, "just below 10 GiB");
        check(ElymonMemory.defaultHeapMb(7475) == 3072, "8 GB phone at 7.3 GiB");
        check(ElymonMemory.defaultHeapMb(7782) == 3072, "8 GB phone at 7.6 GiB");
        check(ElymonMemory.defaultHeapMb(5632) == 2560, "6 GB phone");
        check(ElymonMemory.defaultHeapMb(3789) == 2048, "4 GB phone");

        check(!ElymonMemory.exceedsSafeHeap(4096, 11059), "no warning at the default of a 12 GB phone");
        check(!ElymonMemory.exceedsSafeHeap(3072, 7475), "no warning at the default of an 8 GB phone");
        check(!ElymonMemory.exceedsSafeHeap(2560, 5632), "no warning at the default of a 6 GB phone");
        check(ElymonMemory.exceedsSafeHeap(6144, 7475), "6 GB heap on an 8 GB phone warns");
        check(!ElymonMemory.exceedsSafeHeap(5427, 7475) && ElymonMemory.exceedsSafeHeap(5428, 7475),
                "threshold is total minus 2 GiB");
        check(ElymonMemory.exceedsSafeHeap(2048, 3789), "a 4 GB phone warns at 2048");
        check(!ElymonMemory.exceedsSafeHeap(8192, 0), "unknown total never warns");
        check(ElymonMemory.maxSafeHeapMb(1000) == 0, "safe heap never negative");
    }

    private static void packInfo(File realDistribution) throws IOException {
        check(ElymonPackInfo.read(new File("/nonexistent/distribution.json"), ElymonConfig.SERVER_ID) == null, "missing file");
        check(ElymonPackInfo.read(null, ElymonConfig.SERVER_ID) == null, "null file");

        File file = File.createTempFile("elymon-dist", ".json");
        try {
            write(file, "{\"version\":\"1.0.0\",\"servers\":[{\"id\":\"other\",\"version\":\"9.9\",\"modules\":[{\"id\":\"a\"}]},"
                    + "{\"modules\":[{\"id\":\"x\",\"subModules\":[]}],\"minecraftVersion\":\"1.21.1\",\"version\":\" 2.4.6 \",\"id\":\"elymon-1.21.1\"}]}");
            ElymonPackInfo info = ElymonPackInfo.read(file, ElymonConfig.SERVER_ID);
            check(info != null && "2.4.6".equals(info.packVersion) && "1.21.1".equals(info.minecraftVersion),
                    "id after the modules, version trimmed");

            write(file, "{\"servers\":[{\"id\":\"elymon-1.21.1\",\"version\":\"\"}]}");
            check(ElymonPackInfo.read(file, ElymonConfig.SERVER_ID) == null, "blank version");

            write(file, "{\"servers\":[{\"id\":\"elymon-1.21.1\",\"version\":\"2.4.6\"}]}");
            info = ElymonPackInfo.read(file, ElymonConfig.SERVER_ID);
            check(info != null && info.minecraftVersion == null, "no minecraftVersion");

            write(file, "{\"servers\":[{\"id\":\"elymon-1.21.1\",\"version\":\"2.4");
            check(ElymonPackInfo.read(file, ElymonConfig.SERVER_ID) == null, "truncated file");

            write(file, "[1,2,3]");
            check(ElymonPackInfo.read(file, ElymonConfig.SERVER_ID) == null, "not an object");

            write(file, "");
            check(ElymonPackInfo.read(file, ElymonConfig.SERVER_ID) == null, "empty file");
        } finally {
            if (!file.delete()) {
                file.deleteOnExit();
            }
        }

        if (realDistribution != null) {
            long start = System.nanoTime();
            ElymonPackInfo info = ElymonPackInfo.read(realDistribution, ElymonConfig.SERVER_ID);
            long ms = (System.nanoTime() - start) / 1000000L;
            check(info != null && info.packVersion.length() > 0 && "1.21.1".equals(info.minecraftVersion),
                    "real distribution: " + (info == null ? "null" : info.packVersion + " / " + info.minecraftVersion)
                            + " in " + ms + " ms");
        }
    }

    private static void write(File file, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void check(boolean condition, String what) {
        sChecks++;
        if (condition) {
            System.out.println("ok   " + what);
        } else {
            sFailures++;
            System.out.println("FAIL " + what);
        }
    }
}
