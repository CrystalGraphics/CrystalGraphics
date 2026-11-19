package com.crystalgraphics.render.ui.svg;

import java.util.ArrayList;
import java.util.List;

/**
 * Every SVG shape element, reduced to polylines — the "shapes become paths" half of usvg's resolution.
 *
 * <p>Split out so that the one place a {@code rect} stops being a {@code rect} is a named seam rather than
 * a private corner of the document walk. Nothing here reads style, transforms or paint: a shape's geometry
 * is a function of its own attributes and nothing else, which is exactly why it is separable.</p>
 *
 * <h3>Curves are flattened here, once</h3>
 *
 * <p>Everything returns polylines, so no consumer ever meets a Bézier. That is a real decision and not
 * only a convenience: a fill has to flatten anyway to be scanline-decomposed, and flattening twice — once
 * for the fill and once for the stroke — is how the two end up disagreeing about where the outline is,
 * which shows as a stroke that drifts off its own fill on tight curves.</p>
 */
final class CgSvgGeometry {

    /**
     * Segments per full ellipse.
     *
     * <p>32 is chosen for icon sizes: at 16-32px a circle's error is well under a pixel, and the count is
     * fixed rather than adaptive because the mesh is built once and cached <b>scale-free</b> — there is no
     * draw size to adapt to at the moment it is computed. It is visibly faceted past a few hundred pixels,
     * which is the same ceiling the gradient cell cap documents.</p>
     */
    private static final int CIRCLE_STEPS = 32;

    private CgSvgGeometry() {
    }

    static List<CgSvgPath.Polyline> of(CgSvgScanner.Tag tag, int steps) {
        List<CgSvgPath.Polyline> out = new ArrayList<>();
        switch (tag.name()) {
            case "path" -> out.addAll(CgSvgPath.parse(tag.get("d"), steps));
            case "polyline" -> addPoints(out, tag.get("points"), false);
            case "polygon" -> addPoints(out, tag.get("points"), true);
            case "line" -> out.add(new CgSvgPath.Polyline(List.of(
                    new float[]{CgSvgResolver.number(tag.get("x1"), 0f), CgSvgResolver.number(tag.get("y1"), 0f)},
                    new float[]{CgSvgResolver.number(tag.get("x2"), 0f), CgSvgResolver.number(tag.get("y2"), 0f)}),
                    false));
            case "rect" -> addRect(out, tag, steps);
            case "circle" -> addEllipse(out, steps, CgSvgResolver.number(tag.get("cx"), 0f),
                    CgSvgResolver.number(tag.get("cy"), 0f),
                    CgSvgResolver.number(tag.get("r"), 0f), CgSvgResolver.number(tag.get("r"), 0f));
            case "ellipse" -> addEllipse(out, steps, CgSvgResolver.number(tag.get("cx"), 0f),
                    CgSvgResolver.number(tag.get("cy"), 0f),
                    CgSvgResolver.number(tag.get("rx"), 0f), CgSvgResolver.number(tag.get("ry"), 0f));
            default -> { }
        }
        return out;
    }

    /**
     * A {@code points} list, scanned rather than split.
     *
     * <h3>Why this does not use {@code String.split}</h3>
     *
     * <p>It used to, and the cost was not the regex — that was hoisted to a {@code Pattern} and it barely
     * moved. The cost is <b>allocation per coordinate</b>: {@code split} produces a {@code String} for
     * every number, and {@code CgSvgResolver.number} then calls {@code trim()} on it before
     * {@code Float.parseFloat}, so a polygon of forty points allocated a hundred and twenty objects to
     * read eighty floats.</p>
     *
     * <p>It showed. Measured over the shipped set, {@code polyline}/{@code polygon} elements cost
     * <b>7.2 µs each against 5.4 µs for actual {@code path} elements</b> — the shapes with no curves in
     * them were dearer than the ones made of nothing but curves. Reusing {@link CgSvgPath.Cursor} reads the
     * attribute in place with no intermediate strings at all, and its number parser is the same exact
     * integer fast path that {@code d} data already goes through.</p>
     *
     * <h3>One deliberate behaviour change</h3>
     *
     * <p>{@code split} treated {@code "1.5.2"} as a single malformed token and yielded {@code 0}; the
     * cursor reads it as {@code 1.5} then {@code .2}, because a second decimal point starts a new number.
     * <b>That is the SVG number grammar</b> — the same rule {@code d} data has always followed here — and
     * it is what minifiers rely on. No shipped icon exercises it, so the corpus is unchanged; where the
     * two differ, this is the correct one.</p>
     */
    private static void addPoints(List<CgSvgPath.Polyline> out, String raw, boolean closed) {
        if (raw.isBlank()) return;
        CgSvgPath.Cursor cursor = new CgSvgPath.Cursor(raw);
        List<float[]> points = new ArrayList<>();
        while (cursor.hasNumber()) {
            int beforeX = cursor.position();
            float x = cursor.number();
            // A token that looks like a number and is not -- a lone "." -- consumes nothing. Stepping over
            // it is what stops this spinning on the same character forever.
            if (cursor.position() == beforeX) {
                cursor.skip();
                continue;
            }
            // An odd trailing coordinate is dropped rather than paired with a zero, which is what the
            // split-based version did by walking pairs and stopping one short.
            if (!cursor.hasNumber()) break;
            int beforeY = cursor.position();
            float y = cursor.number();
            if (cursor.position() == beforeY) {
                cursor.skip();
                continue;
            }
            points.add(new float[]{x, y});
        }
        if (points.size() > 1) out.add(new CgSvgPath.Polyline(points, closed));
    }

    /** A rect, with {@code rx}/{@code ry} corners when it has them. */
    private static void addRect(List<CgSvgPath.Polyline> out, CgSvgScanner.Tag tag, int steps) {
        float x = CgSvgResolver.number(tag.get("x"), 0f), y = CgSvgResolver.number(tag.get("y"), 0f);
        float w = CgSvgResolver.number(tag.get("width"), 0f);
        float h = CgSvgResolver.number(tag.get("height"), 0f);
        float rx = CgSvgResolver.number(tag.get("rx"), 0f), ry = CgSvgResolver.number(tag.get("ry"), 0f);
        if (w <= 0f || h <= 0f) return;
        if (ry == 0f) ry = rx;
        if (rx == 0f) rx = ry;
        rx = Math.min(rx, w / 2f);
        ry = Math.min(ry, h / 2f);

        if (rx <= 0f || ry <= 0f) {
            out.add(new CgSvgPath.Polyline(new ArrayList<>(List.of(
                    new float[]{x, y}, new float[]{x + w, y},
                    new float[]{x + w, y + h}, new float[]{x, y + h})), true));
            return;
        }
        // Still built from the one arc implementation, but without composing a `d` string to hand straight
        // back to the parser -- see CgSvgPath#roundedRect for what that cost.
        out.addAll(CgSvgPath.roundedRect(x, y, w, h, rx, ry, steps));
    }

    /** {@code steps} is per quarter-turn, so a full ellipse is four times it — see {@link CgSvgPath#parse(String, int)}. */
    private static void addEllipse(List<CgSvgPath.Polyline> out, int steps, float cx, float cy,
                                   float rx, float ry) {
        int segments = Math.max(8, steps * 4);
        if (rx <= 0f || ry <= 0f) return;
        List<float[]> points = new ArrayList<>();
        for (int i = 0; i < segments; i++) {
            double t = 2 * Math.PI * i / segments;
            points.add(new float[]{(float) (cx + rx * Math.cos(t)), (float) (cy + ry * Math.sin(t))});
        }
        out.add(new CgSvgPath.Polyline(points, true));
    }

    /**
     * A stroke's segments, each with its own pair of caps.
     *
     * @param data four floats per segment: {@code x0,y0, x1,y1}
     * @param caps one packed cap pair per segment — start in bits 0-1, end in bits 2-3, matching
     *             {@code CgCurveSplitter.packCaps}
     */
    record Segments(float[] data, int[] caps) {
    }

    /**
     * Flattens contours into drawable segments, deciding each end's cap.
     *
     * <h3>Interior joints get ONE round cap, not two</h3>
     *
     * <p>Every segment is submitted as its own instance, so a joint between two of them is where the
     * stroke has to be made continuous. Giving both sides a round cap does that — and stacks two identical
     * half-discs on one point, so the antialiased rim composites twice ({@code 1-(1-a)²} rather than
     * {@code a}) and the joint reads as a bump on the edge. {@code stroke.glsl} documents that failure for
     * the engine's own curve splitter, which butts its interior ends for exactly this reason.</p>
     *
     * <p>Butting BOTH sides is not the answer either: with no join geometry, the outer side of every bend
     * loses a wedge, and a flattened curve is fifteen bends. <b>One round cap per joint is enough</b> — a
     * half-disc past an endpoint spans the full stroke width, so it fills the notch on its own for any
     * bend, and there is nothing to composite it against. So: butt the start of every segment that has a
     * predecessor, round the end of every segment that has a successor.</p>
     *
     * <p>That also retires a workaround. The draw used to force {@code CAP_ROUND} whenever the file asked
     * for {@code butt}, because per-segment butt caps notched every joint — which meant a stroke authored
     * with flat ends could not have them. The op's own cap now survives to the two ends that are really
     * ends.</p>
     *
     * <h3>Degenerate segments are dropped here</h3>
     *
     * <p>A round cap on a segment of no length paints a filled dot, so a path whose {@code z} closes onto
     * the point it already ended at would leave a speck at its start — and filtering per frame would pay
     * for that check on every draw of every icon forever. It is a property of the geometry, so it belongs
     * with it. Caps are assigned <b>after</b> the drop, so the first and last surviving segment of a
     * contour are the ones that get its real ends.</p>
     */
    static Segments segmentsOf(List<CgSvgPath.Polyline> contours, int openStartCap, int openEndCap) {
        List<float[]> found = new ArrayList<>();
        List<Integer> caps = new ArrayList<>();
        for (CgSvgPath.Polyline contour : contours) {
            List<float[]> points = contour.points();
            int before = found.size();
            for (int i = 0; i + 1 < points.size(); i++) {
                addSegment(found, points.get(i), points.get(i + 1));
            }
            if (contour.closed() && points.size() > 1) {
                addSegment(found, points.get(points.size() - 1), points.get(0));
            }
            int count = found.size() - before;
            for (int i = 0; i < count; i++) {
                boolean firstEnd = i == 0 && !contour.closed();
                boolean lastEnd = i == count - 1 && !contour.closed();
                int start = firstEnd ? openStartCap : CAP_BUTT;
                int end = lastEnd ? openEndCap : CAP_ROUND;
                caps.add((start & 3) | ((end & 3) << 2));
            }
        }
        float[] packed = new float[found.size() * 4];
        for (int i = 0; i < found.size(); i++) {
            System.arraycopy(found.get(i), 0, packed, i * 4, 4);
        }
        int[] packedCaps = new int[caps.size()];
        for (int i = 0; i < caps.size(); i++) packedCaps[i] = caps.get(i);
        return new Segments(packed, packedCaps);
    }

    /** Equal to {@link CgSvgStyle#CAP_BUTT} and {@link CgSvgStyle#CAP_ROUND}; see {@link Segments#caps()}. */
    private static final int CAP_BUTT = 0;
    private static final int CAP_ROUND = 1;

    private static void addSegment(List<float[]> out, float[] a, float[] b) {
        if (Math.abs(a[0] - b[0]) < 0.001f && Math.abs(a[1] - b[1]) < 0.001f) return;
        out.add(new float[]{a[0], a[1], b[0], b[1]});
    }

    /** {@code minX, minY, width, height} — what an {@code objectBoundingBox} gradient resolves against. */
    static float[] boundsOf(List<CgSvgPath.Polyline> contours) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (CgSvgPath.Polyline contour : contours) {
            for (float[] point : contour.points()) {
                minX = Math.min(minX, point[0]);
                minY = Math.min(minY, point[1]);
                maxX = Math.max(maxX, point[0]);
                maxY = Math.max(maxY, point[1]);
            }
        }
        if (minX > maxX) return new float[]{0f, 0f, 1f, 1f};
        return new float[]{minX, minY, Math.max(1e-6f, maxX - minX), Math.max(1e-6f, maxY - minY)};
    }

    /** The rings a tessellator wants: points only, closure implied. */
    static List<List<float[]>> ringsOf(List<CgSvgPath.Polyline> contours) {
        List<List<float[]>> rings = new ArrayList<>(contours.size());
        for (CgSvgPath.Polyline contour : contours) rings.add(contour.points());
        return rings;
    }
}
