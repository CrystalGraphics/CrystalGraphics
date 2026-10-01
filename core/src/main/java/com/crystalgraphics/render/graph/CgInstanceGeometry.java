package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.mesh.CgMesh;
import com.crystalgraphics.gl.mesh.CgMeshBuilder;
import com.crystalgraphics.gl.mesh.CgMeshRegistry;

/**
 * The geometry every {@code QUAD} and {@code CURVE} instance expands: one unit quad, {@code [0,0]-[1,1]}, shared
 * with {@code CgQuadRenderer} and {@code CgVectorRenderer}. Render thread; registry-owned, so context teardown frees it.
 */
public final class CgInstanceGeometry {

    private static final CgVertexFormat FORMAT = CgVertexFormat.POS2_UV2_COL4UB;

    private CgInstanceGeometry() {}

    /** The unit quad. */
    public static CgMesh unitQuad() {
        return CgMeshRegistry.get().getOrCreate("crystalgraphics:builtin/quad/" + FORMAT,
                () -> CgMesh.upload(CgMeshBuilder.quad2D(FORMAT, 0f, 0f, 1f, 1f)));
    }
}
