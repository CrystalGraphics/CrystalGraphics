package io.github.somehussar.crystalgraphics.gl.render;

import io.github.somehussar.crystalgraphics.api.state.CgRenderState;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;

import io.github.somehussar.crystalgraphics.gl.buffer.staging.CgStagingBuffer;
import io.github.somehussar.crystalgraphics.gl.buffer.staging.CgVertexWriter;
import org.joml.Matrix4f;

/**
 * Dynamic-texture render layer that auto-flushes when the active texture changes.
 *
 * <p>Unlike {@link CgRenderLayer}, this layer's texture ID is set per-draw via
 * {@link #setTexture(int)}. When the texture changes, all previously staged geometry
 * is flushed under the old texture before the new one is recorded. This enables
 * correct batching for text rendering (where atlas pages switch mid-frame) without
 * manual flush management by the caller.</p>
 *
 * <p>The render state can also be swapped mid-frame via {@link #setRenderState},
 * which likewise triggers a flush before applying the new state. This supports
 * cases where different text blocks require different shader configurations.</p>
 *
 * @see CgRenderLayer for fixed-texture layers
 */
public final class CgDynamicTextureRenderLayer implements CgLayer {

    private final String name;
    private CgRenderState state;
    private final CgBatchRenderer renderer;
    private final Matrix4f projection = new Matrix4f();
    private int activeTextureId = -1;
    private boolean begun;

    public static CgDynamicTextureRenderLayer create(String name,
                                                     CgRenderState state,
                                                     CgVertexFormat format,
                                                     int initialMaxQuads) {
        return new CgDynamicTextureRenderLayer(name, state, CgBatchRenderer.create(format, initialMaxQuads));
    }

    private CgDynamicTextureRenderLayer(String name, CgRenderState state, CgBatchRenderer renderer) {
        this.name = name;
        this.state = state;
        this.renderer = renderer;
    }

    public void setRenderState(CgRenderState state) {
        if (state == null) throw new IllegalArgumentException("state must not be null");
        if (this.state != state) {
            flush();
            this.state = state;
        }
    }

    public CgRenderState getRenderState() {
        return state;
    }

    public void setTexture(int textureId) {
        if (textureId != activeTextureId) {
            flush();
            activeTextureId = textureId;
        }
    }

    @Override
    public void begin(Matrix4f projection) {
        if (begun) throw new IllegalStateException("Layer already begun: " + name);
        this.projection.set(projection);
        this.activeTextureId = -1;
        this.begun = true;
        renderer.begin();
    }

    @Override
    public void flush() {
        if (!begun || !renderer.isDirty()) return;
        state.apply(projection, activeTextureId);
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

    public CgVertexWriter vertex() { return renderer.vertex(); }
    public CgStagingBuffer staging() { return renderer.staging(); }
}
