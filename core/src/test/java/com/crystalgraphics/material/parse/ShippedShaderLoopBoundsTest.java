package com.crystalgraphics.material.parse;

import org.junit.Test;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Every loop in a shipped shader, library or kernel runs a number of times a constant bounds: a literal, a constant or
 * macro, or {@code min(data, CONSTANT)}. A loop bounded some other way (a binary search, a work group's stride, a
 * compare-and-swap retry) says why on its line, {@code // LOOP-BOUND: <why>}.
 *
 * <p>A loop whose count came from data walked every light of a light-grid cell a pixel: a 4x4 warm-frame grid put
 * thousands in a cell, the GPU ran past the driver's timeout and every monitor reset. {@code lights.glsl}'s
 * {@code CG_LIGHT_CANDIDATES} is the rule for a light walk.</p>
 */
public class ShippedShaderLoopBoundsTest {

    private static final Pattern FOR = Pattern.compile("\\bfor\\s*\\((.*)");
    private static final Pattern WHILE = Pattern.compile("\\bwhile\\s*\\(");
    private static final Pattern ATOM = Pattern.compile(
            "\\d+(\\.\\d+)?[uUfF]?|[A-Z][A-Z0-9_]*|u?int\\(\\s*([A-Z][A-Z0-9_]*|\\d+)\\s*\\)");
    private static final Pattern COMPARE = Pattern.compile("^\\s*\\w+\\s*(<=|<)\\s*(.+?)\\s*$");
    private static final Pattern CONST_DECL = Pattern.compile("const\\s+(?:int|uint|float)\\s+(\\w+)\\s*=");
    private static final Pattern DEFINE = Pattern.compile("#define\\s+(\\w+)\\s+[\\d(]");
    private static final Pattern CAPPED_LOCAL = Pattern.compile("\\b(\\w+)\\s*=\\s*(min\\([^;]*\\))");
    private static final String NOTE = "LOOP-BOUND:";

    @Test
    public void shippedShaders_everyLoopHasAConstantBound() throws Exception {
        URL dir = getClass().getResource("/assets/crystalgraphics/shaders/");
        assertTrue("the shaders are not on the test classpath", dir != null && "file".equals(dir.getProtocol()));
        Path root = Paths.get(dir.toURI());
        List<Path> sources;
        try (Stream<Path> walk = Files.walk(root)) {
            sources = walk.filter(f -> f.toString().matches(".*\\.(shader|glsl|compute)$")).sorted().collect(Collectors.toList());
        }
        assertFalse("no shaders found: this test would pass vacuously", sources.isEmpty());
        List<String> found = new ArrayList<>();
        for (Path f : sources) {
            String src = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
            for (String loop : unboundedLoops(src)) found.add(root.relativize(f) + ":" + loop);
        }
        assertEquals("Loops whose count data sets with no constant bound: cap with min(data, CONSTANT), or say why on the"
                + " line with // LOOP-BOUND: <why> (CrystalGraphics/docs/SHADERS.md, Loops)\n" + String.join("\n", found),
                0, found.size());
    }

    // ── the detector detects ─────────────────────────────────────────────────

    @Test
    public void flags_aLoopOverACount() {
        assertEquals(1, unboundedLoops("for (int k = span.x; k < span.y; k++) {}").size());
        assertEquals(1, unboundedLoops("for (int k = 0; k < _Entities; k++) {}").size());
        assertEquals(1, unboundedLoops("while (t < end) t += step;").size());
    }

    @Test
    public void passes_constantAndCappedBounds() {
        assertEquals(0, unboundedLoops("for (int k = 0; k < 4; k++) {}").size());
        assertEquals(0, unboundedLoops("for (int k = 0; k < MAX_AIR_SAMPLES; k++) {}").size());
        assertEquals(0, unboundedLoops("for (int k = 0; k < min(_Entities, 64); k++) {}").size());
        assertEquals(0, unboundedLoops("for (int k = 0; k < 512 && t < far; k++) {}").size());
        assertEquals(0, unboundedLoops("int walked = min(count, CG_LIGHT_CANDIDATES);\nfor (int c = 0; c < walked; c++) {}").size());
        assertEquals(0, unboundedLoops("const int N = 8;\nfor (int k = 0; k < N; k++) {}").size());
    }

    @Test
    public void passes_aNotedLoop_andIgnoresComments() {
        assertEquals(0, unboundedLoops("while (lo < hi) {   // LOOP-BOUND: a binary search").size());
        assertEquals(0, unboundedLoops("// for (int k = 0; k < n; k++) in prose").size());
    }

    // ── machinery ────────────────────────────────────────────────────────────

    /** Each loop of {@code source} with no visible constant bound, as {@code "<line>: <text>"}. */
    static List<String> unboundedLoops(String source) {
        String[] lines = source.split("\n", -1);
        Set<String> consts = new HashSet<>();
        collect(CONST_DECL, source, consts);
        collect(DEFINE, source, consts);
        for (String line : lines) {
            Matcher m = CAPPED_LOCAL.matcher(line);
            while (m.find()) if (bounded(m.group(2), consts)) consts.add(m.group(1));
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i], t = line.trim();
            if (t.startsWith("//") || t.startsWith("*") || line.contains(NOTE)) continue;
            Matcher f = FOR.matcher(line);
            if (f.find()) {
                List<String> parts = splitTop(f.group(1), ';');
                if (parts.size() < 2 || conditionBounded(parts.get(1), consts)) continue;
                out.add((i + 1) + ": " + t);
            } else if (WHILE.matcher(line).find()) {
                out.add((i + 1) + ": " + t);
            }
        }
        return out;
    }

    private static void collect(Pattern p, String source, Set<String> into) {
        Matcher m = p.matcher(source);
        while (m.find()) into.add(m.group(1));
    }

    /** Whether one {@code &&} clause compares the counter against a constant bound, or a constant-wide window. */
    private static boolean conditionBounded(String cond, Set<String> consts) {
        for (String clause : cond.split("&&")) {
            Matcher m = COMPARE.matcher(clause);
            if (!m.matches()) continue;
            if (bounded(m.group(2), consts)) return true;
            List<String> sum = splitTop(m.group(2), '+');
            if (sum.size() == 2 && bounded(sum.get(1), consts)) return true;
        }
        return false;
    }

    private static boolean bounded(String expr, Set<String> consts) {
        String e = expr.trim();
        while (e.startsWith("(") && e.endsWith(")") && splitTop(e.substring(1, e.length() - 1), ',').size() == 1) {
            e = e.substring(1, e.length() - 1).trim();
        }
        if (ATOM.matcher(e).matches() || consts.contains(e)) return true;
        if (e.startsWith("min(") && e.endsWith(")")) {
            for (String a : splitTop(e.substring(4, e.length() - 1), ',')) if (bounded(a, consts)) return true;
            return false;
        }
        for (char op : new char[] {'+', '-', '*', '/'}) {
            List<String> parts = splitTop(e, op);
            if (parts.size() < 2) continue;
            boolean all = true;
            for (String p : parts) if (!p.trim().isEmpty() && !bounded(p, consts)) all = false;
            if (all) return true;
        }
        return false;
    }

    private static List<String> splitTop(String s, char sep) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '(' || c == '[') depth++;
            else if (c == ')' || c == ']') depth--;
            if (c == sep && depth == 0) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }
}
