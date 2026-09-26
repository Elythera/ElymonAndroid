package com.elythera.elymon;

/**
 * Tolerant comparison of dotted version strings ("1.2.0", "1.4.0-android.12", "2.0").
 *
 * Same rules as ElytheraMod's fr.elythera.badge.util.VersionOrder, so the app gates
 * on the distribution's "requires" exactly the way the mod and the desktop compare
 * launcher versions:
 * - only the leading run of numeric groups separated by '.' counts; everything from
 *   the first non numeric segment on is a qualifier and is ignored, so "1.2.0-beta",
 *   "1.2.0-android.7" and "1.2.0" are equal;
 * - missing trailing groups count as zero, so "1.2" equals "1.2.0";
 * - the parser never throws, whatever the input.
 *
 * Pure Java (no Android imports) for host-side tests.
 */
public final class ElymonVersions {
    /** Hard cap on parsed segments, as in VersionOrder. */
    private static final int MAX_SEGMENTS = 8;

    private ElymonVersions() {}

    /** Negative if left is older, zero if equivalent, positive if left is newer. */
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

    /**
     * Whether candidate is at least minimum. A blank minimum means "no minimum";
     * a blank candidate against a non-blank minimum is false.
     */
    public static boolean atLeast(String candidate, String minimum) {
        if (isBlank(minimum)) {
            return true;
        }
        if (isBlank(candidate)) {
            return false;
        }
        return compare(candidate, minimum) >= 0;
    }

    /** Whether at least one numeric group can be read out of version. */
    public static boolean isComparable(String version) {
        return parse(version).length > 0;
    }

    private static int[] parse(String version) {
        if (isBlank(version)) {
            return new int[0];
        }
        String[] parts = version.trim().split("\\.", MAX_SEGMENTS + 1);
        int[] out = new int[Math.min(parts.length, MAX_SEGMENTS)];
        int count = 0;
        for (int i = 0; i < out.length; i++) {
            String part = parts[i];
            int value = leadingNumber(part);
            if (value < 0) {
                // The segment does not even start with a digit: the qualifier starts here.
                break;
            }
            out[count++] = value;
            if (!isAllDigits(part)) {
                // "0-beta": keep the 0, then stop, or "1.2.0-beta.3" would outrank "1.2.0".
                break;
            }
        }
        int[] trimmed = new int[count];
        System.arraycopy(out, 0, trimmed, 0, count);
        return trimmed;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static boolean isAllDigits(String segment) {
        if (segment.isEmpty()) {
            return false;
        }
        for (int i = 0; i < segment.length(); i++) {
            if (!Character.isDigit(segment.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Leading digits of segment as an int, or -1 if it starts with a non digit. */
    private static int leadingNumber(String segment) {
        int end = 0;
        while (end < segment.length() && Character.isDigit(segment.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return -1;
        }
        try {
            return Integer.parseInt(segment.substring(0, end));
        } catch (NumberFormatException overflow) {
            // Absurdly long digit run: treat as "very new" rather than failing.
            return Integer.MAX_VALUE;
        }
    }
}
