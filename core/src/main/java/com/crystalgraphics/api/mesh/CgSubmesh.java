package com.crystalgraphics.api.mesh;

/**
 * One part of a {@link CgMesh} drawn on its own: a run of indices over a run of vertices. Its indices count from
 * {@code firstVertex}, so a draw of it adds that as its base vertex. With no indices, the vertices are drawn in order.
 *
 * <pre>{@code
 * CgSubmesh part = mesh.submesh(1);
 * part.indexCount();     // what a draw of the whole part names
 * }</pre>
 */
public record CgSubmesh(int firstIndex, int indexCount, int firstVertex, int vertexCount) {
}
