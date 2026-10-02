package com.crystalgraphics.api.mesh;

/**
 * Anything the graph and the world renderer draw as a mesh: a {@link CgMesh}, or the GL mesh in {@code gl.mesh}
 * that holds one. Goes when that class does (mesh rewrite M5).
 *
 * <pre>{@code
 * world.draw(sphere, material).at(x, y, z).submit();   // either kind of mesh
 * }</pre>
 */
public interface CgMeshSource {

    /** The mesh drawn. */
    CgMesh mesh();
}
