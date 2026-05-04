package io.github.somehussar.crystalgraphics.gl.render;

import io.github.somehussar.crystalgraphics.api.vertex.CgInstanceFormat;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;
import io.github.somehussar.crystalgraphics.gl.buffer.staging.CgInstanceWriter;
import io.github.somehussar.crystalgraphics.gl.mesh.CgMesh;
import io.github.somehussar.crystalgraphics.gl.mesh.CgMeshBuilder;
import io.github.somehussar.crystalgraphics.gl.mesh.CgMeshRegistry;

/**
 * Instanced renderer for quads (sprites, particles, UI elements).
 *
 * <p>Internally creates and caches a shared quad {@link CgMesh} in
 * {@link CgMeshRegistry} under the key
 * {@code "crystalgraphics:builtin/quad/<quadFormat.toString()>"}. The quad mesh is
 * uploaded only once per unique vertex format and reused across all renderers
 * that share that format. The quad occupies [0,0]→[1,1] in XY at Z=0.</p>
 *
 * <p>The cache key uses {@link CgVertexFormat#toString()} rather than
 * {@link Object#hashCode()} to avoid hash-code collisions between distinct formats
 * that happen to produce the same 32-bit hash.</p>
 *
 * <p>This class is a thin convenience wrapper around {@link CgInstanceRenderer}.
 * All heavy logic (staging, upload, VAO management) lives in the delegate — this
 * class only handles quad mesh lookup and lifecycle delegation.</p>
 *
 * <h3>Per-frame lifecycle</h3>
 * <pre>{@code
 * renderer.begin();
 * CgInstanceWriter w = renderer.instance();
 * w.putFloat(...);          // write all per-instance fields
 * w.endInstance();
 * // optional mid-frame flush (e.g. to change shader state):
 * renderer.flush();
 * // can continue writing instances after flush:
 * w = renderer.instance();
 * w.putFloat(...);
 * w.endInstance();
 * renderer.flush();
 * renderer.end();
 * }</pre>
 *
 * <p>Wrap in a {@link CgRenderLayer} for automatic shader/texture/blend/depth
 * state management around the flush.</p>
 */
public final class CgQuadInstanceRenderer extends CgAbstractRenderer {

    private final CgInstanceRenderer delegate;

    /**
     * Tracks whether {@link #flush()} re-began the delegate mid-cycle.
     * When true, {@link #onBegin()} must end the delegate before beginning it fresh
     * to avoid an "already begun" exception on the next cycle.
     */
    private boolean delegateReBegun = false;

    /**
     * Creates a new {@code CgQuadInstanceRenderer} for the given vertex format,
     * instance layout, and initial instance capacity.
     *
     * <p>The unit quad mesh ({@code [0,0]→[1,1]}) is looked up or created in
     * {@link CgMeshRegistry} under the key
     * {@code "crystalgraphics:builtin/quad/<quadFormat.toString()>"}.</p>
     *
     * @param quadFormat          vertex format of the quad mesh
     * @param layout              per-instance attribute layout
     * @param initialMaxInstances initial staging capacity; grows automatically
     * @return a new quad instance renderer
     */
    public static CgQuadInstanceRenderer create(CgVertexFormat quadFormat, CgInstanceFormat layout,
                                                 int initialMaxInstances) {
        // Use toString() as the registry key to avoid hashCode collisions between
        // distinct formats that happen to produce the same 32-bit integer hash.
        final CgVertexFormat fmt = quadFormat;
        CgMesh quad = CgMeshRegistry.get().getOrCreate("crystalgraphics:builtin/quad/" + quadFormat.toString(),
                () -> CgMesh.upload(CgMeshBuilder.quad2D(fmt, 0f, 0f, 1f, 1f)));
        
        return new CgQuadInstanceRenderer(CgInstanceRenderer.create(quad, layout, initialMaxInstances));
    }

    private CgQuadInstanceRenderer(CgInstanceRenderer delegate) {
        this.delegate = delegate;
    }

    public CgInstanceWriter instance() {
        if (!begun) throw new IllegalStateException("CgQuadInstanceRenderer not begun");
        return delegate.instance();
    }

    @Override
    protected void onBegin() {
        // If a previous cycle's flush() re-began the delegate (delegateReBegun == true),
        // end it before beginning fresh. This cleans up the dangling begun-state that
        // flush() leaves behind when the outer end() is called without another flush.
        if (delegateReBegun) {
            delegate.end();
            delegateReBegun = false;
        }
        delegate.begin();
    }

    @Override
    protected boolean hasPendingWork() {
        return delegate.isDirty();
    }

    /**
     * Flushes staged instances to the GPU and re-begins the delegate so that
     * additional instances can be written in the same outer begin/end cycle.
     *
     * <p>This preserves correct lifecycle: after {@code flush()}, the caller can
     * continue calling {@link #instance()} without ending and re-beginning the outer
     * renderer. The re-begun delegate state is cleaned up in {@link #onBegin()} at
     * the start of the next cycle.</p>
     */
    @Override
    public void flush() {
        delegate.flush();
        delegate.end();
        // Re-begin the delegate so additional instances can be written after this flush
        // within the same outer begin/end cycle.
        delegate.begin();
        delegateReBegun = true;
    }

    @Override
    public void delete() {
        delegate.delete();
    }
}
