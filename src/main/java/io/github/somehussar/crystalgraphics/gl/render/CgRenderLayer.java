package io.github.somehussar.crystalgraphics.gl.render;

import io.github.somehussar.crystalgraphics.api.state.CgRenderState;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;

import io.github.somehussar.crystalgraphics.gl.buffer.staging.CgStagingBuffer;
import io.github.somehussar.crystalgraphics.gl.buffer.staging.CgVertexWriter;
import org.joml.Matrix4f;

/**
 * Fixed-texture (or no-texture) render layer.
 *
 * <p>On each {@link #flush()}, the owned {@link CgRenderState} is applied (shader,
 * texture, blend, depth, cull), the batch renderer uploads and draws, and then the
 * state is cleared. The texture binding is fixed at construction time — it does not
 * change between flushes.</p>
 *
 * <p>Suitable for: solid colour fills, static atlas panels, SDF shapes — any layer
 * where the bound texture (if any) is constant across the entire frame.</p>
 *
 * @see CgDynamicTextureRenderLayer for layers where the texture changes mid-frame
 */
public final class CgRenderLayer implements CgLayer {

    private final String name;
    private final CgRenderState state;
    private final CgAbstractRenderer renderer;
    private final Matrix4f projection = new Matrix4f();
    private boolean begun;

    public static CgRenderLayer create(String name, CgRenderState state, CgVertexFormat format, int initialMaxQuads) {
        return new CgRenderLayer(name, state, CgBatchRenderer.create(format, initialMaxQuads));
    }

    /** Creates a layer wrapping any {@link CgAbstractRenderer} implementation. */
    public CgRenderLayer(String name, CgRenderState state, CgAbstractRenderer renderer) {
        this.name = name;
        this.state = state;
        this.renderer = renderer;
    }

    @Override
    public void begin(Matrix4f projection) {
        if (begun) throw new IllegalStateException("Layer already begun: " + name);
        this.projection.set(projection);
        begun = true;
        renderer.begin();
    }

    @Override
    public void flush() {
        if (!begun || !renderer.isDirty()) return;
        state.apply(projection);
        renderer.flush();
        state.clear();
    }

    @Override
    public void end() {
        flush();
        renderer.end();
        begun = false;
    }

    @Override public boolean isDirty() { return renderer.isDirty(); }
    @Override public String getName() { return name; }
    @Override public void delete() { renderer.delete(); }

    // These convenience methods only work when the renderer is a CgBatchRenderer.
    public CgVertexWriter vertex() {
        if (!(renderer instanceof CgBatchRenderer)) {
            throw new IllegalStateException("vertex() requires a CgBatchRenderer renderer");
        }
        return ((CgBatchRenderer) renderer).vertex();
    }

    public CgStagingBuffer staging() {
        if (!(renderer instanceof CgBatchRenderer)) {
            throw new IllegalStateException("staging() requires a CgBatchRenderer renderer");
        }
        return ((CgBatchRenderer) renderer).staging();
    }
}
