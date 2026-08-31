package com.kholodilin.repogrowth.event.detect;

public final class ReadmeSignificance {

    static final double SHARE_THRESHOLD = 0.15;
    static final int ABSOLUTE_THRESHOLD = 400;

    private ReadmeSignificance() {
    }

    public static boolean significant(String previous, String current) {
        String left = normalize(previous);
        String right = normalize(current);
        if (left.equals(right)) {
            return false;
        }
        if (left.isEmpty() || right.isEmpty()) {
            return Math.max(left.length(), right.length()) >= ABSOLUTE_THRESHOLD;
        }
        int prefix = commonPrefix(left, right);
        int maxSuffix = Math.min(left.length() - prefix, right.length() - prefix);
        int suffix = commonSuffix(left, right, maxSuffix);
        int changed = Math.max(left.length(), right.length()) - prefix - suffix;
        int max = Math.max(left.length(), right.length());
        return changed >= ABSOLUTE_THRESHOLD || (double) changed / max >= SHARE_THRESHOLD;
    }

    public static String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.trim().replaceAll("\\s+", " ");
    }

    private static int commonPrefix(String left, String right) {
        int limit = Math.min(left.length(), right.length());
        int index = 0;
        while (index < limit && left.charAt(index) == right.charAt(index)) {
            index++;
        }
        return index;
    }

    private static int commonSuffix(String left, String right, int max) {
        int index = 0;
        while (index < max
                && left.charAt(left.length() - 1 - index) == right.charAt(right.length() - 1 - index)) {
            index++;
        }
        return index;
    }
}
