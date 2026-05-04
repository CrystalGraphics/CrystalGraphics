package io.github.somehussar.crystalgraphics.gl.lifecycle;

import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.gl.buffer.CgQuadIndexBuffer;
import io.github.somehussar.crystalgraphics.gl.mesh.CgMeshRegistry;
import io.github.somehussar.crystalgraphics.gl.render.CgInstanceRenderer;
import io.github.somehussar.crystalgraphics.gl.vertex.CgInstanceVertexArrayBinding;
import io.github.somehussar.crystalgraphics.gl.vertex.CgVertexArray;
import io.github.somehussar.crystalgraphics.gl.vertex.CgVertexArrayRegistry;
import io.github.somehussar.crystalgraphics.gl.vertex.CgVertexBufferRegistry;

/**
 * Coordinates teardown of all CrystalGraphics GL resources in the correct order.
 *
 * <p>Call {@link #destroyContext()} exactly once when the OpenGL context is being
 * destroyed (e.g. game shutdown, render context reset). All VAOs must be deleted
 * before their referenced VBOs to avoid stale GPU state.</p>
 *
 * <h3>Canonical 4-step teardown order</h3>
 * <ol>
 *   <li>{@link CgVertexArrayRegistry#deleteAll()} — ALL VAOs (instanced first, then non-instanced inside {@code deleteAll})</li>
 *   <li>{@link CgMeshRegistry#deleteAll()} — static mesh VBOs + IBOs + per-mesh VAOs</li>
 *   <li>{@link CgVertexBufferRegistry#deleteAll()} — ALL stream VBOs (base + instance)</li>
 *   <li>{@link CgQuadIndexBuffer#freeAll()} — shared quad IBO</li>
 * </ol>
 *
 * <p>After all GL objects are freed, backend-capability caches are reset so that
 * context recreation will re-probe the new context's capabilities.</p>
 */
public final class CgGraphicsLifecycle {

    private CgGraphicsLifecycle() {}

    /**
     * Destroys all CrystalGraphics GL resources in canonical dependency order,
     * then resets all backend-capability caches.
     *
     * <p><strong>Must be called on the GL thread.</strong></p>
     *
     * <p>After this call, all VAOs, VBOs, IBOs, and cached GL capability flags are
     * cleared. A new GL context can be initialised immediately afterwards.</p>
     */
    public static void destroyContext() {
        // Step 1: ALL VAOs — CgVertexArrayRegistry.deleteAll() deletes instanced VAOs first,
        //   then non-instanced VAOs, ensuring no VBO referenced by a VAO is deleted first.
        CgVertexArrayRegistry.get().deleteAll();

        // Step 2: Static mesh VBOs + IBOs + per-mesh VAOs.
        //   Mesh VAOs reference mesh VBOs, so meshes must be deleted after streaming VAOs
        //   (handled in step 1) but before streaming VBOs (step 3).
        CgMeshRegistry.get().deleteAll();

        // Step 3: ALL stream VBOs (base + instance streams).
        CgVertexBufferRegistry.get().deleteAll();

        // Step 4: Shared quad IBO.
        CgQuadIndexBuffer.freeAll();

        // Reset all backend-capability caches so context recreation re-probes correctly.
        CgCapabilities.clearCache();
        CgVertexArray.resetCoreCache();
        CgInstanceVertexArrayBinding.resetCoreCache();
        CgInstanceRenderer.resetCoreCache();
    }
}
