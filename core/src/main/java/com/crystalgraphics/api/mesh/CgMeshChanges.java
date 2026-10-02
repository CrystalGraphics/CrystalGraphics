package com.crystalgraphics.api.mesh;

/**
 * What changed in a {@link CgMesh} since a revision its reader last saw: filled by {@link CgMesh#changesSince}. A
 * holder the reader owns and reuses.
 *
 * <pre>{@code
 * CgMeshChanges changes = new CgMeshChanges();
 * if (mesh.changesSince(uploadedRevision, changes)) {
 *     if (changes.all) uploadEverything(mesh);
 *     else uploadVertices(mesh, changes.vertexFrom, changes.vertexTo);   // and the indices likewise
 *     uploadedRevision = changes.revision;
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Ranges are half-open, in vertices and in indices; an empty range has {@code from == to}.</li>
 *   <li>{@code all} means read everything: the contents were replaced, or more changes were made than the mesh
 *       remembers.</li>
 * </ul>
 */
public final class CgMeshChanges {

    /** The mesh's revision now. */
    public int revision;
    public boolean all;
    public int vertexFrom, vertexTo, indexFrom, indexTo;

    void clear(int revision) {
        this.revision = revision;
        all = false;
        vertexFrom = vertexTo = indexFrom = indexTo = 0;
    }
}
