package com.crystalgraphics.render.meshpass;

import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.mesh.CgMesh;

/**
 * One mesh with one material that a primitive draws, as a processor sees it: Unreal's {@code FMeshBatch}. A row of a
 * view's visibility; which objects it draws (a GPU scene record, instances, a frame record) the scene names on each
 * command the processor adds.
 *
 * <pre>{@code
 * if ((batch.relevance() & CgPrimitiveRelevance.TRANSLUCENT) == 0) return;   // solid: not this pass's
 * out.draw(batch, batch.material());
 * }</pre>
 */
public interface CgMeshBatch {

    CgMesh mesh();

    CgMaterialInterface material();

    /** The submesh it draws, or -1 for the whole mesh. */
    int submesh();

    /** Its material's passes: {@link CgPrimitiveRelevance}'s bits. */
    int relevance();

    /** Whether its mesh is skinned: every pipeline it draws through is then its skinned form. */
    boolean skinned();
}
