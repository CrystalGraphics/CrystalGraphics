package com.crystalgraphics.mc.shared;

/**
 * A Maven version range, parsed far enough to answer "is this version in it".
 *
 * <pre>
 * VersionRange.parse("[1.20.1,1.21)").contains("1.20.4")   // true
 * VersionRange.parse("[1.7.10]").contains("1.7.10")        // true -- a bare version is exact
 * VersionRange.parse("[1.20.1,)").contains("1.21.4")       // true -- open above
 * </pre>
 *
 * <p>The build's own {@code McRange} is the twin of this and the two must agree: that one decides
 * which ranges may ship together, this one decides which variant runs. This is deliberately the
 * smaller half — no hull, no overlap test — because it runs on every boot of every loader and the
 * declaration it reads was already checked when the jar was built.</p>
 *
 * <p>Easy to get wrong: versions compare segment by segment as integers, so {@code 1.9} is
 * <i>below</i> {@code 1.20}. A segment that is not a number compares as <b>zero</b>, so a
 * pre-release sorts below its release — {@code 1.20.1-pre1} is not in {@code [1.20.1,1.21)}, and a
 * snapshot like {@code 23w31a} is in nothing at all. Both are deliberate: neither is a version any
 * variant was built against, and {@link Variants#select} refuses them by name rather than running a
 * variant that was never tested there.</p>
 */
public final class VersionRange {

    private final String low;
    private final boolean lowInclusive;
    private final String high;
    private final boolean highInclusive;

    private VersionRange(String low, boolean lowInclusive, String high, boolean highInclusive) {
        this.low = low;
        this.lowInclusive = lowInclusive;
        this.high = high;
        this.highInclusive = highInclusive;
    }

    public static VersionRange parse(String text) {
        String t = text.trim();
        if (t.length() < 3) {
            throw new IllegalArgumentException("not a version range: " + text);
        }
        char open = t.charAt(0);
        char close = t.charAt(t.length() - 1);
        if (open != '[' && open != '(') {
            throw new IllegalArgumentException("a version range opens with [ or (: " + text);
        }
        if (close != ']' && close != ')') {
            throw new IllegalArgumentException("a version range closes with ] or ): " + text);
        }
        String inner = t.substring(1, t.length() - 1);
        int comma = inner.indexOf(',');
        if (comma < 0) {
            String exact = inner.trim();
            if (exact.isEmpty()) {
                throw new IllegalArgumentException("a version range with no version in it: " + text);
            }
            return new VersionRange(exact, true, exact, true);
        }
        String lo = inner.substring(0, comma).trim();
        String hi = inner.substring(comma + 1).trim();
        return new VersionRange(
                lo.isEmpty() ? null : lo, open == '[' && !lo.isEmpty(),
                hi.isEmpty() ? null : hi, close == ']' && !hi.isEmpty());
    }

    public boolean contains(String version) {
        if (low != null) {
            int c = compare(version, low);
            if (c < 0 || (c == 0 && !lowInclusive)) {
                return false;
            }
        }
        if (high != null) {
            int c = compare(version, high);
            if (c > 0 || (c == 0 && !highInclusive)) {
                return false;
            }
        }
        return true;
    }

    static int compare(String a, String b) {
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        int n = Math.max(left.length, right.length);
        for (int i = 0; i < n; i++) {
            int l = segment(left, i);
            int r = segment(right, i);
            if (l != r) {
                return l - r;
            }
        }
        return 0;
    }

    private static int segment(String[] parts, int i) {
        if (i >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[i].trim());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    @Override
    public String toString() {
        if (low != null && low.equals(high) && lowInclusive && highInclusive) {
            return "[" + low + "]";
        }
        return (lowInclusive ? "[" : "(")
                + (low == null ? "" : low) + "," + (high == null ? "" : high)
                + (highInclusive ? "]" : ")");
    }
}
