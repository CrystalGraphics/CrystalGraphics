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
    /** A row's flags: drawn at half size; after distortion; casting shadows. */
    int HALF_RESOLUTION = 1, AFTER_DISTORTION = 2, CASTS_SHADOW = 4;

    boolean skinned();

    /** Its row's flags: {@link #HALF_RESOLUTION}, {@link #AFTER_DISTORTION}, {@link #CASTS_SHADOW}. */
    int flags();

    /** Whether its layer puts it in the separate translucency's target: {@code CgSortLayer.AFTER_DOF}. */
    boolean separate();
}
