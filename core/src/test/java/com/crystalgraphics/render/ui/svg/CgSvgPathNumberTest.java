package com.crystalgraphics.render.ui.svg;

import org.junit.Assume;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Holds {@code CgSvgPath}'s integer fast path to returning <b>exactly</b> what {@code Float.parseFloat}
 * returns.
 *
 * <h3>Why this test is the whole justification</h3>
 *
 * <p>Hand-rolled decimal parsing is usually a bad idea, and it is a bad idea for a specific reason: it is
 * easy to be right to within an ulp and impossible to notice. An ulp of drift in a control point is not a
 * visible bug — it is a coordinate that lands a hair off, which changes a band boundary, which changes a
 * seam, which shows up as a hairline at one zoom level six months later. The fast path is only defensible
 * because it <em>declines</em> every case it cannot prove (exponents, long mantissas, more than seven
 * decimals) and because this test exercises the boundary between accepting and declining.</p>
 *
 * <p>The parser is reached through {@link CgSvgPath#parse} rather than directly, since the cursor is private
 * — a {@code d} of {@code "M<number> 0L1 1"} puts the number straight into the first point. The trailing
 * lineto is load-bearing: {@code parse} drops any run of fewer than two points, so a bare moveto returns
 * no polyline at all and every assertion here would fail on the harness rather than on the parser.</p>
 */
public class CgSvgPathNumberTest {

    /** Everything the shipped corpus actually contains — the only inputs that have to be fast. */
    @Test
    public void everyNumberInTheShippedCorpusMatchesTheLibraryParser() throws IOException {
        List<String> tokens = new ArrayList<>();
        for (Path icon : shippedIcons()) {
            String source = Files.readString(icon, StandardCharsets.UTF_8);
            for (CgSvgScanner.Tag tag : CgSvgScanner.scan(source)) {
                String d = tag.get("d");
                if (d != null) collectNumberTokens(d, tokens);
            }
        }
        // Deliberately a low floor: the icon set is swappable, and this test is about the parser rather
        // than about how much artwork happens to ship.
        assertTrue("expected real path data, found " + tokens.size() + " numbers", tokens.size() > 200);
        for (String token : tokens) assertMatchesLibrary(token);
    }

    /** The cases the fast path is allowed to take, plus the ones just past each boundary. */
    @Test
    public void boundariesBetweenTheFastPathAndTheFallbackAgree() {
        String[] cases = {
                "0", "1", "-1", "+1", "0.5", "-0.5", ".5", "-.5", "1.", "-1.",
                "0.000", "0.500", "00.50", "123.456", "-123.456",
                "16777216", "16777217", "16777215.5",          // side to side of 2^24
                "0.1234567", "0.12345678", "0.123456789",       // side to side of seven decimals
                "1e3", "1E3", "1e-3", "1.5e2", "-1.5e-2", "1e0",
                "999999999999999999", "9999999999999999999",    // side to side of eighteen digits
                "0.0000001", "0.00000001",
                "3.4028235e38", "1.4e-45", "0.0000000000001",
        };
        for (String token : cases) assertMatchesLibrary(token);
    }

    /** Randomised decimals, weighted onto the shapes real path data uses. */
    @Test
    public void randomisedDecimalsAgree() {
        Random random = new Random(0x5F6E5EEDL);
        for (int i = 0; i < 20000; i++) {
            StringBuilder token = new StringBuilder();
            if (random.nextBoolean()) token.append('-');
            int whole = random.nextInt(9);
            for (int d = 0; d <= whole; d++) token.append((char) ('0' + random.nextInt(10)));
            if (random.nextInt(4) != 0) {
                token.append('.');
                int fraction = random.nextInt(10);
                for (int d = 0; d <= fraction; d++) token.append((char) ('0' + random.nextInt(10)));
            }
            if (random.nextInt(12) == 0) {
                token.append(random.nextBoolean() ? 'e' : 'E');
                if (random.nextBoolean()) token.append('-');
                token.append(random.nextInt(40));
            }
            assertMatchesLibrary(token.toString());
        }
    }

    /** Malformed input has to degrade the same way too, not merely not crash. */
    @Test
    public void malformedTokensDegradeIdentically() {
        for (String token : new String[]{"-", "+", ".", "-.", "e5", ".e5", "--1", "1e", "1e+"}) {
            float parsed = firstCoordinateOf("M" + token + " 0L1 1");
            assertTrue(token + " produced " + parsed, Float.isFinite(parsed));
        }
        // `2.128.194` is TWO numbers, and that has to survive the rewrite -- see Cursor#number.
        List<CgSvgPath.Polyline> split = CgSvgPath.parse("M2.128.194L0 0", 2);
        assertEquals(2.128f, split.get(0).points().get(0)[0], 0f);
        assertEquals(0.194f, split.get(0).points().get(0)[1], 0f);
    }

    private static void assertMatchesLibrary(String token) {
        float expected;
        try {
            expected = Float.parseFloat(token);
        } catch (NumberFormatException malformed) {
            expected = 0f;
        }
        float actual = firstCoordinateOf("M" + token + " 0L1 1");
        // Bits, not a delta: the claim is that these are the same float, not a close one. Compared as
        // raw bits so a -0.0f that should be 0.0f cannot pass on ==.
        assertEquals("token \"" + token + "\"",
                Float.floatToIntBits(expected), Float.floatToIntBits(actual));
    }

    private static float firstCoordinateOf(String d) {
        List<CgSvgPath.Polyline> lines = CgSvgPath.parse(d, 2);
        assertTrue("no polyline from \"" + d + "\"", !lines.isEmpty());
        return lines.get(0).points().get(0)[0];
    }

    /** Every number-shaped run in a {@code d} string, isolated so it can be parsed on its own. */
    private static void collectNumberTokens(String d, List<String> out) {
        int at = 0;
        while (at < d.length()) {
            char c = d.charAt(at);
            if (!(c == '-' || c == '+' || c == '.' || (c >= '0' && c <= '9'))) {
                at++;
                continue;
            }
            int start = at;
            if (c == '-' || c == '+') at++;
            boolean dot = false, exponent = false;
            while (at < d.length()) {
                char n = d.charAt(at);
                if (n >= '0' && n <= '9') at++;
                else if (n == '.' && !dot && !exponent) { dot = true; at++; }
                else if ((n == 'e' || n == 'E') && !exponent && at > start) {
                    exponent = true;
                    at++;
                    if (at < d.length() && (d.charAt(at) == '-' || d.charAt(at) == '+')) at++;
                } else break;
            }
            if (at == start) at++;
            else out.add(d.substring(start, at));
        }
    }

    /** CrystalGUI's shipped icons, the corpus: present when CrystalGraphics is checked out inside it, else skipped. */
    private static List<Path> shippedIcons() throws IOException {
        Path root = Path.of("../../core/src/main/resources/assets/crystalgui/ui/icons");
        Assume.assumeTrue("no icon corpus beside this checkout: " + root.toAbsolutePath(), Files.isDirectory(root));
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.getFileName().toString().endsWith(".svg")).sorted().toList();
        }
    }
}
