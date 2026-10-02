package com.crystalgraphics.vfx.render;

import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshWriter;
import com.crystalgraphics.api.vertex.CgVertexFormat;

/**
 * A mesh of {@link #COUNT} ribbons, {@link #SEGMENTS} segments each, that a shader places itself: stateless GPU
 * particles. The shader hashes each ribbon's index into a path of its own (a streak falling in, an arc of lightning),
 * evaluated from the effect's age. Nothing is simulated and nothing is uploaded per frame.
 *
 * <p>The mesh carries no vertex data ({@code #type none}), only the indices joining each ribbon's {@link #VERTICES}
 * vertices into a strip of quads. {@code fx_common.glsl} reads where a vertex lies from {@code CG_VERTEX_ID}:</p>
 *
 * <pre>{@code
 * float index = FX_RIBBON_INDEX;   // its ribbon, 0 to COUNT - 1
 * float along = FX_RIBBON_ALONG;   // 0 at the ribbon's tail, 1 at its head
 * float side = FX_RIBBON_SIDE;     // -1 or 1
 * }</pre>
 *
 * <ul>
 *   <li>Its bounds are the cube -1..1 that a draw's transform scales: place every ribbon inside it.</li>
 *   <li>Drawn through {@code CgVfxFrame.ribbons}, {@code pathRibbons} and the {@code ARCS} particle renderer. A shader
 *       that wants fewer ribbons collapses the rest; a particle draw draws only its live ones.</li>
 *   <li>{@link #SEGMENTS} and {@link #VERTICES} are written into {@code fx_common.glsl}'s macros: change all three
 *       together.</li>
 * </ul>
 */
public final class CgVfxRibbons {

    public static final int COUNT = 96, SEGMENTS = 32;
    /** Vertices a ribbon: two at each end of each segment. */
    public static final int VERTICES = (SEGMENTS + 1) * 2;
    /** Indices a ribbon: two triangles a segment. */
    public static final int INDICES = SEGMENTS * 6;

    private CgVfxRibbons() {
    }

    /** A new mesh, for {@code CgVfxSystem}, which releases it. */
    public static CgMesh mesh() {
        CgMesh mesh = CgMesh.build(CgVertexFormat.NONE, CgVfxRibbons::write);
        mesh.bounds(-1f, -1f, -1f, 1f, 1f, 1f);
        return mesh;
    }

    private static void write(CgMeshWriter m) {
        for (int i = 0; i < COUNT * VERTICES; i++) m.vertex().end();
        for (int ribbon = 0; ribbon < COUNT; ribbon++) {
            int base = ribbon * VERTICES;
            for (int s = 0; s < SEGMENTS; s++) {
                int a = base + s * 2, b = a + 1, c = a + 2, d = a + 3;
                m.triangle(a, b, c);
                m.triangle(b, d, c);
            }
        }
    }
}
