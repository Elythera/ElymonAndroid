package com.elythera.elymon.sync;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Entry point of the sync host tests, run by tools/elymon/test-sync.sh:
 * <pre>
 * java com.elythera.elymon.sync.AllSyncTests --server http://127.0.0.1:PORT/ --www DIR --log FILE
 *      --tmp DIR --policy FILE --strings FILE --dist FILE --version-json FILE [--live]
 * </pre>
 * Exit code 0 when every check passed.
 */
public final class AllSyncTests {
    private AllSyncTests() {}

    public static void main(String[] args) {
        Map<String, String> a = new HashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--live")) {
                a.put("live", "true");
            } else if (args[i].startsWith("--") && i + 1 < args.length) {
                a.put(args[i].substring(2), args[++i]);
            }
        }
        long start = System.currentTimeMillis();
        File tmp = new File(a.get("tmp"));
        try {
            SyncUnitTests.run(new File(a.get("policy")), new File(a.get("strings")));
        } catch (Throwable t) {
            TestSupport.fail("unit tests", t);
        }
        try {
            PlanRealDistributionTest.run(new File(a.get("dist")), new File(a.get("policy")),
                    new File(a.get("version-json")), tmp, a.containsKey("live"));
        } catch (Throwable t) {
            TestSupport.fail("planning tests", t);
        }
        try {
            EndToEndSyncTest.run(a.get("server"), new File(a.get("www")), new File(a.get("log")), tmp,
                    new File(a.get("policy")));
        } catch (Throwable t) {
            TestSupport.fail("end-to-end tests", t);
        }
        long seconds = (System.currentTimeMillis() - start) / 1000;
        System.out.println();
        if (TestSupport.failures.isEmpty()) {
            System.out.println("SYNC TESTS PASSED: " + TestSupport.passed + " checks in " + seconds + " s");
            System.exit(0);
        }
        System.out.println("SYNC TESTS FAILED: " + TestSupport.failures.size() + " of "
                + (TestSupport.passed + TestSupport.failures.size()) + " checks");
        for (String f : TestSupport.failures) {
            System.out.println(" - " + f);
        }
        System.exit(1);
    }
}
