package com.crystalgraphics.vfx.render;

import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshTopology;

/**
 * {@link #COUNT} ribbons, {@link #SEGMENTS} segments each, that a shader places itself: stateless GPU particles. The
 * shader hashes each ribbon's index into a path of its own (a streak falling in, an arc of lightning), evaluated from
 * the effect's age. Nothing is simulated and nothing is uploaded per frame.
 *
 * <p>The mesh is the shared {@link CgMesh#vertices vertex-only} triangle list: no vertex data and no indices
 * ({@code #type none}). {@code fx_common.glsl} reads where a vertex lies from {@code CG_VERTEX_ID}:</p>
 *
 * <pre>{@code
 * float index = FX_RIBBON_INDEX;   // its ribbon, 0 to COUNT - 1
 * float along = FX_RIBBON_ALONG;   // 0 at the ribbon's tail, 1 at its head
 * float side = FX_RIBBON_SIDE;     // -1 or 1
 * }</pre>
 *
 * <ul>
 *   <li>It has no bounds: every draw states the cube -1..1 its transform scales, and places every ribbon inside it.</li>
 *   <li>Drawn through {@code CgVfxFrame.ribbons}, {@code pathRibbons} and the {@code ARCS} particle renderer. A shader
 *       that wants fewer ribbons collapses the rest; a particle draw draws only its live ones, {@link #VERTICES} a
 *       ribbon.</li>
 *   <li>{@link #SEGMENTS} and {@link #VERTICES} are written into {@code fx_common.glsl}'s macros: change them
 *       together.</li>
 * </ul>
 */
public final class CgVfxRibbons {

    public static final int COUNT = 96, SEGMENTS = 32;
    /** Vertices a ribbon: two triangles a segment. */
    public static final int VERTICES = SEGMENTS * 6;

    private CgVfxRibbons() {
    }

    public static CgMesh mesh() {
        return CgMesh.vertices(COUNT * VERTICES, CgMeshTopology.TRIANGLES);
    }
}
