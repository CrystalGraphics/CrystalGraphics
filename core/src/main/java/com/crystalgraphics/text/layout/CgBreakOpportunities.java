package com.crystalgraphics.text.layout;

import java.util.Arrays;

/**
 * Corrects the JDK line iterator's break opportunities to the ones UAX #14 actually specifies.
 *
 * <p>{@link java.text.BreakIterator#getLineInstance} implements a LEGACY line-breaking ruleset, not the
 * current annex, and on ASCII punctuation it is the opposite of both the spec and every browser. Measured
 * on {@code Locale.ROOT}:</p>
 *
 * <pre>{@code
 * a/b                          no break offered at all
 * a:b                          breaks after the colon
 * a.b                          breaks after the dot
 * https://example.com/one/two  breaks after `https:` and `example.`, never at a slash
 * }</pre>
 *
 * <p>So a path wrapped as {@code harness.scratch:} / {@code src/main/java/...} -- broken in the two
 * places a reader would not choose, and never at the separators they would. Two rules put it right:</p>
 *
 * <ul>
 *   <li><b>LB29, {@code IS x (AL | HL)}</b> -- no break between an infix separator ({@code . : , ;}) and
 *       a following letter or digit. {@code harness.scratch} and {@code scratch:src} are one run each.
 *       Prose is untouched: in {@code "ends. Next"} the separator is followed by a SPACE, and the break
 *       belongs to the space rather than to the dot.</li>
 *   <li><b>SY, permitted by LB31</b> -- a break may fall AFTER a solidus, and by <b>LB13</b> never
 *       before one. Both halves are needed and the order matters: the first offers a break between the
 *       two slashes of {@code https://}, and the second is what takes it away again. A wrapped path
 *       therefore keeps its slash on the upper line rather than starting the next with one.</li>
 * </ul>
 *
 * <p>This agrees with CrystalGUI's editor, whose {@code BreakOpportunities} is ported from VS Code and
 * lists {@code /} as break-after and no colon at all. The two could not be shared -- that one is in the
 * consumer and this is the backend -- so they are two statements of one rule, and the divergence they
 * produced was visible only as text wrapping differently in an editor and in a label.</p>
 *
 * <p>Deliberately NOT a UAX #14 implementation. The iterator is still the source of truth for spaces,
 * hyphens, CJK and everything else; this adjusts the two classes it is demonstrably wrong about.</p>
 */
final class CgBreakOpportunities {

    private CgBreakOpportunities() {
    }

    /**
     * @param text       the segment the boundaries were collected from
     * @param boundaries offsets to break after, ascending, excluding 0 and {@code text.length()}
     * @return the corrected offsets, in the same convention
     */
    static int[] correct(String text, int[] boundaries) {
        int length = text.length();
        if (length < 2) return boundaries;

        boolean[] allowed = new boolean[length];
        for (int boundary : boundaries) {
            if (boundary > 0 && boundary < length) allowed[boundary] = true;
        }

        int count = 0;
        for (int i = 1; i < length; i++) {
            char previous = text.charAt(i - 1);
            char next = text.charAt(i);
            if (allowed[i] && isInfixSeparator(previous) && Character.isLetterOrDigit(next)) {
                allowed[i] = false;
            }
            // A solidus offers a break after itself, which the iterator never reports. Not before a
            // space: the space is already a break and one there would be an empty opportunity.
            if (previous == '/' && !Character.isWhitespace(next)) allowed[i] = true;
            // LB13, and it has to come last: never break BEFORE a solidus. This is what keeps a wrapped
            // path's slash on the upper line, and what stops `https://` splitting between its two --
            // the rule above had just offered exactly that break.
            if (next == '/') allowed[i] = false;
            if (allowed[i]) count++;
        }

        int[] corrected = new int[count];
        int at = 0;
        for (int i = 1; i < length; i++) {
            if (allowed[i]) corrected[at++] = i;
        }
        return Arrays.equals(corrected, boundaries) ? boundaries : corrected;
    }

    /** UAX #14 class IS, restricted to the ASCII members a source path or a sentence actually uses. */
    private static boolean isInfixSeparator(char c) {
        return c == '.' || c == ':' || c == ',' || c == ';';
    }
}
