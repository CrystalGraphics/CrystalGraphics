package com.crystalgraphics.api.mesh;

/** The primitive a mesh's indices make, or with none its vertices. Each backend maps it to its own mode. */
public enum CgMeshTopology {
    TRIANGLES,
    TRIANGLE_STRIP,
    LINES,
    LINE_STRIP,
    POINTS
}
