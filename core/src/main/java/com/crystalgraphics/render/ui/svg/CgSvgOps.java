package com.crystalgraphics.render.ui.svg;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

/**
 * A resolved scene as draw operations, in document order: fills as cells, strokes as their contours and segments.
 * What a {@code CgSvg} tessellates once per curve tier and the canvas draws.
 *
 * <p>Fill before stroke, per node: SVG's painting order for one element, and why a stroked shape shows its whole
 * stroke. Document order is painter's order, so ops are never grouped by style.</p>
 */
public final class CgSvgOps {

    private CgSvgOps() {
    }

    /**
     * One batch of geometry sharing a colour and a mode.
     *
     * @param data         eight floats a cell for a fill (a convex quad, {@link CgSvgMesh}); four a segment for a stroke
     * @param colours      one ARGB a cell for a fill with colours of its own, parallel to {@code data}; null when the op
     *                     is one colour
     * @param coloursEnd   a cell's ramp end, with {@code gradients} its axis (origin and direction over length, four
     *                     floats a cell, in document units); null for flat cells
     * @param edges        per cell, which edges are on the outline; null for a stroke
     * @param currentColor the paint was {@code currentColor}: the draw binds it
     * @param contours     a stroke's centrelines, what the canvas outlines; empty for a fill
     * @param segmentCaps  a stroke's caps per segment, start in bits 0-1 and end in 2-3; null for a fill
     * @param shadow       the drop shadow of the filtered element this op is part of
     * @param bounds       {@code minX, minY, maxX, maxY} of its ink in document units, a stroke's reach past its
     *                     centreline included: what its raster is sized to
     */
    public record Op(boolean fill, float[] data, @Nullable int[] colours, @Nullable int[] coloursEnd,
                     @Nullable float[] gradients, @Nullable int[] edges, boolean opaque, int argb, boolean currentColor,
                     float halfWidth, int cap, List<CgSvgPath.Polyline> contours, @Nullable int[] segmentCaps,
                     @Nullable CgSvgScene.DropShadow shadow, float[] bounds) {

        /** A stroke reaches past its centreline by its half-width, a square cap's corner by √2 of it. */
        public static final float CAP_REACH = 1.4142135f;

        /** Whether the op's colours live in its cells rather than in one colour the draw applies. */
        public boolean coloured() {
            return fill && colours != null;
        }
    }

    /** {@code scene} tessellated. */
    public static List<Op> build(CgSvgScene scene) {
        List<Op> ops = new ArrayList<>();
        for (CgSvgScene.Node node : scene.nodes()) {
            CgSvgScene.Fill fill = node.fill();
            if (fill != null) {
                CgSvgMesh mesh = CgSvgTessellator.tessellate(node.contours(), fill.evenOdd(), fill.paint());
                if (!mesh.isEmpty()) {
                    CgSvgScene.Paint paint = fill.paint();
                    int argb = paint instanceof CgSvgScene.Gradient ramp ? ramp.argb() : ((CgSvgScene.Solid) paint).argb();
                    ops.add(new Op(true, mesh.cells(), mesh.colour0(), mesh.colour1(), mesh.axes(), mesh.edges(),
                            mesh.opaque(), argb, paint.currentColor(), 0f, 0, List.of(), null, node.shadow(),
                            boundsOf(mesh.cells(), 2, 0f)));
                }
            }
            CgSvgScene.Stroke stroke = node.stroke();
            if (stroke != null) {
                // Both ends: SVG has one linecap, not a start and end pair
                CgSvgGeometry.Segments segments = CgSvgGeometry.segmentsOf(node.contours(), stroke.cap(), stroke.cap());
                if (segments.data().length > 0) {
                    CgSvgScene.Solid paint = (CgSvgScene.Solid) stroke.paint();
                    ops.add(new Op(false, segments.data(), null, null, null, null, true, paint.argb(),
                            paint.currentColor(), stroke.halfWidth(), stroke.cap(), node.contours(), segments.caps(),
                            node.shadow(), boundsOf(segments.data(), 2, stroke.halfWidth() * Op.CAP_REACH)));
                }
            }
        }
        return ops;
    }

    /** The box of the (x, y) pairs in {@code xy}, grown by {@code pad}. */
    private static float[] boundsOf(float[] xy, int stride, float pad) {
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int i = 0; i + 1 < xy.length; i += stride) {
            minX = Math.min(minX, xy[i]);
            minY = Math.min(minY, xy[i + 1]);
            maxX = Math.max(maxX, xy[i]);
            maxY = Math.max(maxY, xy[i + 1]);
        }
        return new float[]{minX - pad, minY - pad, maxX + pad, maxY + pad};
    }
}
