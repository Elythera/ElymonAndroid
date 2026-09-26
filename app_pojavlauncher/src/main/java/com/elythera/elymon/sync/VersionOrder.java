package com.elythera.elymon.sync;

/**
 * Version comparator shared with the Elythera launcher (app/assets/js/versionorder.js)
 * and ElytheraMod (badge/util/VersionOrder.java): only the leading numeric
 * groups count, so "1.4.0-android.12" equals "1.4.0", a missing group is 0,
 * and a version with no readable number is "not comparable".
 */
public final class VersionOrder {
    private VersionOrder() {}

    private static final int MAX_SEGMENTS = 8;

    static int[] parse(String version) {
        if (version == null || version.trim().isEmpty()) {
            return new int[0];
        }
        String[] parts = version.trim().split("\\.", MAX_SEGMENTS + 1);
        int limit = Math.min(parts.length, MAX_SEGMENTS);
        int[] out = new int[limit];
        int count = 0;
        for (int i = 0; i < limit; i++) {
            String part = parts[i];
            int end = 0;
            long value = 0;
            while (end < part.length() && Character.isDigit(part.charAt(end))) {
                value = value * 10 + Character.digit(part.charAt(end), 10);
                if (value > Integer.MAX_VALUE) {
                    value = Integer.MAX_VALUE;
                }
                end++;
            }
            if (end == 0) {
                break;
            }
            out[count++] = (int) value;
            if (end != part.length()) {
                break;
            }
        }
        int[] trimmed = new int[count];
        System.arraycopy(out, 0, trimmed, 0, count);
        return trimmed;
    }

    /** Negative when left is older, zero when equivalent, positive when newer. */
    public static int compare(String left, String right) {
        int[] a = parse(left);
        int[] b = parse(right);
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return x < y ? -1 : 1;
            }
        }
        return 0;
    }

    /** Whether at least one numeric group can be read. */
    public static boolean isComparable(String version) {
        return parse(version).length > 0;
    }

    /**
     * Whether candidate satisfies the floor. Fails open: a blank or unreadable
     * floor, or an unreadable candidate, never blocks.
     */
    public static boolean atLeast(String candidate, String minimum) {
        if (!isComparable(minimum) || !isComparable(candidate)) {
            return true;
        }
        return compare(candidate, minimum) >= 0;
    }
}
