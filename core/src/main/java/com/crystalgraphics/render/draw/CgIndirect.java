package com.crystalgraphics.render.draw;

/**
 * What the count an indirect draw reads stands for. The GPU writes one {@code uint}; the engine multiplies it by the
 * draw's factor and composes the command from it and the mesh's placement.
 *
 * <pre>{@code
 * world.draw(CgMesh.quads(capacity), sparks).indirect(live, 0, CgIndirect.INDICES, 6).at(x, y, z).submit();   // a quad each
 * world.draw(billow, smoke).indirect(live, 0, CgIndirect.INSTANCES, 1).at(x, y, z).submit();                   // a mesh each
 * world.draw(CgMesh.vertices(capacity * 3, CgMeshTopology.TRIANGLES), shards)
 *      .indirect(live, 0, CgIndirect.VERTICES, 3).at(x, y, z).submit();                                       // a triangle each
 * }</pre>
 */
public enum CgIndirect {

    /**
     * The draw's range, drawn count x factor times. Every instance reads the draw's one record
     * ({@code CG_OBJECT_DATA}), and {@code CG_DRAW_INSTANCE} is which element it is.
     */
    INSTANCES,
    /** Count x factor of the range's indices, at most the range's own. The mesh is indexed. */
    INDICES,
    /** Count x factor of the range's vertices, at most the range's own. The mesh has no indices. */
    VERTICES
}
