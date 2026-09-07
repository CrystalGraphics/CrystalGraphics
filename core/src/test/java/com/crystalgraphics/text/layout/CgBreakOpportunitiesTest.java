package com.crystalgraphics.text.layout;

import org.junit.Test;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.junit.Assert.assertEquals;

/**
 * Where a line may break, against what UAX #14 and every browser actually do.
 *
 * <p>The JDK's line iterator is a legacy ruleset wearing the annex's name, and on ASCII punctuation it
 * is the opposite of it -- so these cases are written as the text a reader sees, with {@code |} where a
 * break may fall, and each one failed before {@link CgBreakOpportunities} existed.</p>
 */
public class CgBreakOpportunitiesTest {

    /** The text with a bar at every offset a line may break after. */
    private static String marked(String text) {
        BreakIterator iterator = BreakIterator.getLineInstance(Locale.ROOT);
        iterator.setText(text);
        List<Integer> raw = new ArrayList<>();
        for (int b = iterator.first(); b != BreakIterator.DONE; b = iterator.next()) {
            if (b > 0 && b < text.length()) raw.add(b);
        }
        int[] boundaries = new int[raw.size()];
        for (int i = 0; i < boundaries.length; i++) boundaries[i] = raw.get(i);

        int[] corrected = CgBreakOpportunities.correct(text, boundaries);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (Arrays.binarySearch(corrected, i) >= 0) out.append('|');
            out.append(text.charAt(i));
        }
        return out.toString();
    }

    /** SY: a break may fall after a solidus, and LB13 forbids one before it. */
    @Test
    public void aPathBreaksAfterItsSlashes() {
        assertEquals("a/|b", marked("a/b"));
        assertEquals("src/|main/|java", marked("src/main/java"));
    }

    /** LB29, IS x AL: an infix separator does not break from the letter after it. */
    @Test
    public void anInfixSeparatorDoesNotBreakFromWhatFollowsIt() {
        assertEquals("a.b", marked("a.b"));
        assertEquals("a:b", marked("a:b"));
        assertEquals("harness.scratch", marked("harness.scratch"));
    }

    /** ...and prose keeps its break, because there the separator is followed by a SPACE. */
    @Test
    public void aSentenceStillBreaksAfterItsFullStop() {
        // The spaces are break opportunities in their own right -- what matters is that the dot
        // does not add one of its own, as it does in `a.b`.
        assertEquals("It |ends. |Next |one", marked("It ends. Next one"));
    }

    /** The string that started this: broken where a reader would choose, and nowhere else. */
    @Test
    public void theNotificationsOwnPath() {
        assertEquals("harness.scratch:src/|main/|java/|com/|example/|util/",
                marked("harness.scratch:src/main/java/com/example/util/"));
    }

    @Test
    public void aUrlBreaksAtItsSlashesAndNotAtItsScheme() {
        assertEquals("https://|example.com/|one/|two", marked("https://example.com/one/two"));
    }
}
