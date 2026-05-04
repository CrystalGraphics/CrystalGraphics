package io.github.somehussar.crystalgraphics.gl.render;

import io.github.somehussar.crystalgraphics.api.vertex.CgInstanceFormat;
import io.github.somehussar.crystalgraphics.gl.buffer.CgStreamBuffer;
import io.github.somehussar.crystalgraphics.gl.buffer.staging.CgInstanceWriter;
import io.github.somehussar.crystalgraphics.gl.buffer.staging.CgStagingBuffer;
import io.github.somehussar.crystalgraphics.gl.mesh.CgMesh;
import io.github.somehussar.crystalgraphics.gl.vertex.CgInstanceVertexBuffer;
import io.github.somehussar.crystalgraphics.gl.vertex.CgInstanceVertexArrayBinding;
import io.github.somehussar.crystalgraphics.gl.vertex.CgVertexArray;
import io.github.somehussar.crystalgraphics.gl.vertex.CgVertexArrayRegistry;
import io.github.somehussar.crystalgraphics.gl.vertex.CgVertexBufferRegistry;
import org.lwjgl.opengl.ARBDrawInstanced;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GLContext;

/**
 * Instanced renderer for a single static mesh.
 *
 * <p>One {@code CgInstanceRenderer} is bound to one {@link CgMesh} at construction
 * time. It manages a CPU-side instance staging buffer and flushes per-instance data
 * to the GPU each frame via the shared instance stream VBO.</p>
 *
 * <p>GPU resources (mesh VBO/IBO and the shared instance stream) are borrowed from
 * {@link CgVertexArrayRegistry} and {@link CgVertexBufferRegistry} — never owned or
 * deleted by this renderer.</p>
 *
 * <p>Wrap in a {@link CgRenderLayer} for automatic shader/texture/blend/depth state
 * management around the flush.</p>
 *
 * <h3>Per-frame lifecycle</h3>
 * <pre>{@code
 * renderer.begin();
 * CgInstanceWriter w = renderer.instance();
 * w.putFloat(...);          // write all per-instance fields
 * w.endInstance();
 * renderer.flush();
 * renderer.end();
 * }</pre>
 */
public final class CgInstanceRenderer extends CgAbstractRenderer {

    /** The static mesh drawn instanced each frame. Its VBO and IBO are borrowed, not owned. */
    private final CgMesh mesh;
    /** Per-instance attribute layout describing the data written by each {@link CgInstanceWriter#endInstance()} call. */
    private final CgInstanceFormat instanceFormat;

    /** CPU-side staging buffer holding per-instance float data before GPU upload. */
    private final CgStagingBuffer instanceStaging;
    /** Format-aware writer used by callers to fill per-instance attribute fields. */
    private final CgInstanceWriter instanceWriter;

    /**
     * Creates a new {@code CgInstanceRenderer} for the given static mesh and instance layout.
     *
     * @param mesh                the static mesh to draw instanced (VBO/IBO are borrowed)
     * @param layout              the per-instance attribute layout
     * @param initialMaxInstances initial staging capacity (in instances); grows automatically
     * @return a new renderer ready for use
     */
    public static CgInstanceRenderer create(CgMesh mesh, CgInstanceFormat layout, int initialMaxInstances) {
        CgStagingBuffer staging = new CgStagingBuffer(layout.getFloatsPerInstance(), initialMaxInstances);
        return new CgInstanceRenderer(mesh, layout, staging, new CgInstanceWriter(staging, layout));
    }

    private CgInstanceRenderer(CgMesh mesh, CgInstanceFormat instanceFormat,
                               CgStagingBuffer instanceStaging, CgInstanceWriter instanceWriter) {
        this.mesh = mesh;
        this.instanceFormat = instanceFormat;
        this.instanceStaging = instanceStaging;
        this.instanceWriter = instanceWriter;
    }

    @Override
    protected void onBegin() {
        instanceStaging.reset();
    }

    @Override
    protected boolean hasPendingWork() {
        return !instanceStaging.isEmpty();
    }

    /**
     * Returns the instance writer for one instance. The caller must write all
     * per-instance fields and then call {@link CgInstanceWriter#endInstance()}.
     *
     * @return the shared instance writer for this renderer
     * @throws IllegalStateException if {@link #begin()} has not been called
     */
    public CgInstanceWriter instance() {
        if (!begun) throw new IllegalStateException("CgInstanceRenderer not begun");
        instanceWriter.beginInstance();
        return instanceWriter;
    }

    /**
     * Uploads the staged instance data, draws the mesh instanced, and resets staging.
     *
     * <p>If there are no staged instances, this is a no-op (resets staging but issues
     * no GL draw call).</p>
     *
     * <p>State-blind: this method does NOT touch shader, texture, blend, depth, or cull
     * state. That is the responsibility of the enclosing {@link CgRenderLayer}.</p>
     */
    @Override
    public void flush() {
        if (!begun || instanceStaging.isEmpty()) {
            instanceStaging.reset();
            return;
        }
        int instanceCount = instanceStaging.vertexCount();

        // Obtain (or lazily create) the instanced VAO for this mesh + instance layout.
        CgInstanceVertexArrayBinding binding = CgVertexArrayRegistry.get().getOrCreateMeshInstanced(mesh, instanceFormat);

        // Fetch and upload instance data to the shared instance stream VBO.
        CgInstanceVertexBuffer instanceVbo = CgVertexBufferRegistry.get().getOrCreateInstanced(instanceFormat);
        CgStreamBuffer instanceStream = instanceVbo.getStreamBuffer();
        int instanceOffset = instanceStream.uploadFloats(instanceStaging.rawData(), instanceStaging.rawCursor());

        // Bind the VAO, then lazily rebind instance attribute pointers if the ring-buffer
        // slot changed (sync-ring path returns a new offset each frame).
        CgVertexArray.bind(binding.getGlVao());
        binding.rebindInstancePointersIfNeeded(instanceOffset);

        // Issue the instanced draw call: indexed (if mesh has an IBO) or array.
        if (mesh.getGlIndexBuffer() != 0) {
            drawElementsInstanced(
                    mesh.getTopology().getGlMode(),
                    mesh.getIndexCount(),
                    mesh.getIndexType(),
                    0L,
                    instanceCount);
        } else {
            drawArraysInstanced(
                    mesh.getTopology().getGlMode(),
                    0,
                    mesh.getVertexCount(),
                    instanceCount);
        }

        instanceStream.afterSubmit();
        CgVertexArray.bind(0);
        instanceStaging.reset();
    }

    // ── Instancing draw-call waterfall (GL31 core vs ARB_draw_instanced) ──────

    /**
     * Cached GL31 core availability for instanced draw calls.
     * {@code null} = not yet detected; {@code true/false} = cached result.
     * Reset via {@link #resetCoreCache()} on context recreation.
     */
    private static Boolean useGL31 = null;

    /**
     * Resets the cached GL31 core dispatch flag.
     * Called automatically by {@code CgGraphicsLifecycle.destroyContext()}.
     */
    public static void resetCoreCache() {
        useGL31 = null;
    }

    private static void drawArraysInstanced(int mode, int first, int count, int instanceCount) {
        if (useGL31 == null) useGL31 = GLContext.getCapabilities().OpenGL31;
        if (useGL31) {
            GL31.glDrawArraysInstanced(mode, first, count, instanceCount);
        } else {
            ARBDrawInstanced.glDrawArraysInstancedARB(mode, first, count, instanceCount);
        }
    }

    private static void drawElementsInstanced(int mode, int count, int type, long offset, int instanceCount) {
        if (useGL31 == null) useGL31 = GLContext.getCapabilities().OpenGL31;
        if (useGL31) {
            GL31.glDrawElementsInstanced(mode, count, type, offset, instanceCount);
        } else {
            ARBDrawInstanced.glDrawElementsInstancedARB(mode, count, type, offset, instanceCount);
        }
    }
}
