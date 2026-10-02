package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.api.vertex.CgVertexFormat;

/**
 * The geometry every {@code QUAD} and {@code CURVE} instance expands: one unit quad, {@code [0,0]-[1,1]}, shared
 * with {@code CgQuadRenderer} and {@code CgVectorRenderer}. Data only: the mesh store places it.
 */
public final class CgInstanceGeometry {

    private static final CgVertexFormat FORMAT = CgVertexFormat.POS2_UV2_COL4UB;
    private static final CgMesh UNIT_QUAD = CgMesh.build(FORMAT, m -> CgMeshShapes.quad(m, 0f, 0f, 1f, 1f));

    private CgInstanceGeometry() {}

    /** The unit quad. */
    public static CgMesh unitQuad() {
        return UNIT_QUAD;
    }
}
