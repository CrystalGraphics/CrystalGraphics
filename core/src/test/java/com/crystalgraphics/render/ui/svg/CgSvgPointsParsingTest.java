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
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;

/**
 * Holds the scanned {@code points} reader to what the {@code String.split} version produced.
 *
 * <p>Polygons are stacks of opaque shapes in this artwork — the IntelliJ mark is exactly that — so a
 * coordinate read one ULP differently, or a point dropped, does not fail loudly. It reorders a silhouette
 * by a hair, which is invisible until it is not.</p>
 *
 * <p>The one intended divergence is {@code "1.5.2"}: {@code split} saw one malformed token and yielded
 * {@code 0}, the cursor reads two numbers per the SVG number grammar. That case is asserted directly
 * rather than left to the corpus comparison, which no shipped icon exercises.</p>
 */
public class CgSvgPointsParsingTest {

    /** Every polygon and polyline in the shipped set, against the old reader. */
    @Test
    public void everyShippedPointsListReadsIdentically() throws IOException {
        int compared = 0;
        for (Path icon : shippedIcons()) {
            String source = Files.readString(icon, StandardCharsets.UTF_8);
            for (CgSvgScanner.Tag tag : CgSvgScanner.scan(source)) {
                if (!tag.name().equals("polygon") && !tag.name().equals("polyline")) continue;
                String raw = tag.get("points");
                if (raw == null || raw.isBlank()) continue;
                assertSameAsSplit(icon.getFileName() + ": " + raw, raw);
                compared++;
            }
        }
        // Not asserted as a minimum count: a shipped set may legitimately contain no polygons, and a
        // corpus that changes underneath this should not turn into a red build.
        System.out.println("compared " + compared + " shipped points lists");
    }

    /** Well-formed lists in every separator style the format allows. */
    @Test
    public void separatorStylesReadIdentically() {
        String[] cases = {
                "0,0 1,1 2,2",
                "0 0 1 1 2 2",
                "0,0,1,1,2,2",
                "  0 , 0   1 , 1  ",
                "0,0\n1,1\t2,2",
                "-1,-2 -3,-4",
                "1.5,2.5 3.5,4.5",
                ".5,.5 1.5,1.5",
                "1e1,2e1 3e1,4e1",
                "0,0 1,1 2",            // odd trailing coordinate -- dropped by both
                "0,0",                  // a single point yields no polyline
                "",
                "   ",
        };
        for (String raw : cases) assertSameAsSplit(raw, raw);
    }

    /** Randomised well-formed lists, which is what the corpus is made of. */
    @Test
    public void randomisedPointsListsReadIdentically() {
        Random random = new Random(0x9012FEEDL);
        for (int trial = 0; trial < 3000; trial++) {
            StringBuilder raw = new StringBuilder();
            int count = random.nextInt(12);
            for (int i = 0; i < count; i++) {
                if (i > 0) raw.append(random.nextBoolean() ? " " : ", ");
                raw.append(number(random)).append(random.nextBoolean() ? "," : " ").append(number(random));
            }
            assertSameAsSplit(raw.toString(), raw.toString());
        }
    }

    /** The one place the two deliberately disagree, and the direction of the disagreement. */
    @Test
    public void anImpliedSeparatorIsReadAsTwoNumbers() {
        List<CgSvgPath.Polyline> lines = parse("1.5.2 3,4");
        assertEquals("minified pair should read as three coordinates then one", 1, lines.size());
        List<float[]> points = lines.get(0).points();
        assertEquals(2, points.size());
        assertEquals(1.5f, points.get(0)[0], 0f);
        assertEquals(0.2f, points.get(0)[1], 0f);
        assertEquals(3f, points.get(1)[0], 0f);
        assertEquals(4f, points.get(1)[1], 0f);
    }

    private static String number(Random random) {
        int whole = random.nextInt(1000);
        String sign = random.nextBoolean() ? "-" : "";
        if (random.nextBoolean()) return sign + whole;
        return sign + whole + "." + random.nextInt(1000);
    }

    private static void assertSameAsSplit(String what, String raw) {
        List<float[]> expected = splitReference(raw);
        List<CgSvgPath.Polyline> actual = parse(raw);
        List<float[]> got = actual.isEmpty() ? List.of() : actual.get(0).points();

        assertEquals(what + ": point count", expected.size(), got.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(what + ": x[" + i + "]",
                    Float.floatToIntBits(expected.get(i)[0]), Float.floatToIntBits(got.get(i)[0]));
            assertEquals(what + ": y[" + i + "]",
                    Float.floatToIntBits(expected.get(i)[1]), Float.floatToIntBits(got.get(i)[1]));
        }
    }

    private static List<CgSvgPath.Polyline> parse(String raw) {
        CgSvgScanner.Tag tag = CgSvgScanner.scan(
                "<svg><polygon points=\"" + raw + "\"/></svg>").stream()
                .filter(t -> t.name().equals("polygon")).findFirst().orElseThrow();
        return CgSvgGeometry.of(tag, 16);
    }

    /** The reader as it stood before the cursor replaced it, frozen. */
    private static final Pattern SEPARATORS = Pattern.compile("[\\s,]+");

    private static List<float[]> splitReference(String raw) {
        if (raw.isBlank()) return List.of();
        String[] numbers = SEPARATORS.split(raw.trim());
        List<float[]> points = new ArrayList<>();
        for (int i = 0; i + 1 < numbers.length; i += 2) {
            points.add(new float[]{CgSvgResolver.number(numbers[i], 0f),
                    CgSvgResolver.number(numbers[i + 1], 0f)});
        }
        return points.size() > 1 ? points : List.of();
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
