package com.crystalgraphics.render.ui.svg;

import com.crystalgraphics.render.ui.path.CgPathTriangulator;

import javax.annotation.Nullable;

/**
 * Tessellated geometry with a colour attached to every cell — the output of {@link CgSvgTessellator}.
 *
 * <h3>The lyon seam</h3>
 *
 * <p>Ported in shape from <b>lyon</b> (MIT/Apache-2.0), whose tessellators consume a flattened path and a
 * fill rule and emit vertices plus per-vertex attributes through a builder, knowing nothing about the
 * format the path came from. <b>Nothing in this class mentions SVG</b>, and that is the point: it would
 * serve a font glyph or a hand-built polygon unchanged, and the SVG-shaped decisions all sit on the far
 * side of {@link CgSvgScene}.</p>
 *
 * <h3>Why a cell soup rather than vertices plus indices</h3>
 *
 * <p>lyon emits indexed geometry because its consumers upload a vertex buffer and an index buffer. This
 * engine's consumer is the cell primitive, which draws each cell as an <b>instance</b> carrying
 * its own four corners and computing exact-area coverage on whichever of its edges are on the outline —
 * so an index buffer would be dereferenced on the CPU and thrown away every frame. Eight floats per cell
 * is the format the draw path actually wants, and the per-edge antialiasing is what lyon pays a
 * separately tessellated fringe for.</p>
 *
 * @param cells   eight floats per cell — see {@link CgPathTriangulator.Fill#cells}
 * @param colour0 one ARGB per cell — the start of the ramp; null when the whole mesh is one colour,
 *                which the draw op already carries and which would otherwise be a full array of the
 *                same int per fill
 * @param colour1 the end of the ramp, or null when every cell is flat
 * @param axes    four floats per cell — {@code originX, originY, dirX, dirY}, with the reciprocal
 *                length folded into {@code dir} so the fragment stage needs a dot product and a clamp
 *                rather than a normalise. Null when every cell is flat
 * @param edges   per cell, which edges are on the outline — see {@link CgPathTriangulator.Fill#edges}
 * @param opaque  every colour is fully opaque
 */
record CgSvgMesh(float[] cells, @Nullable int[] colour0, @Nullable int[] colour1,
               @Nullable float[] axes, int[] edges, boolean opaque) {

    static final CgSvgMesh EMPTY = new CgSvgMesh(new float[0], null, null, null, new int[0], true);

    boolean isEmpty() {
        return cells.length == 0;
    }

    int cellCount() {
        return cells.length / 8;
    }

    /** Whether every colour is fully opaque — a ramp that fades out is not, even if it starts solid. */
    static boolean allOpaque(int[]... sets) {
        for (int[] set : sets) {
            if (set == null) continue;
            for (int argb : set) {
                if ((argb >>> 24) != 0xFF) return false;
            }
        }
        return true;
    }
}
