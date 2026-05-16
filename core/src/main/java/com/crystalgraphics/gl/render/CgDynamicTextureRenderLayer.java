package com.crystalgraphics.gl.render;


import com.crystalgraphics.api.shader.CgShader;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.buffer.staging.CgStagingBuffer;
import com.crystalgraphics.gl.buffer.staging.CgVertexWriter;
import com.crystalgraphics.platform.gl.CgGL;
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
 * <p>An optional {@link CgShader} can be attached via {@link #setShader}. When set,
 * the shader is bound before each flush and unbound after — restoring shader binding
 * that was previously carried by {@code CgRenderState} before T8.</p>
 *
 * @see CgRenderLayer for fixed-texture layers
 */
public final class CgDynamicTextureRenderLayer implements CgLayer {

    private final String name;
    private CgRenderState state;
    private CgShader shader;
    private final CgBatchRenderer renderer;
    private final Matrix4f projection = new Matrix4f();
    private int activeTextureId = -1;
    private boolean begun;

    /** Creates a layer without a bound shader (for non-text layers). */
    public static CgDynamicTextureRenderLayer create(String name,
                                                     CgRenderState state,
                                                     CgVertexFormat format,
                                                     int initialMaxQuads) {
        return new CgDynamicTextureRenderLayer(name, null, state, CgBatchRenderer.create(format, initialMaxQuads));
    }

    /** Creates a layer with an initial shader that is bound/unbound around each flush. */
    public static CgDynamicTextureRenderLayer create(String name,
                                                     CgShader shader,
                                                     CgRenderState state,
                                                     CgVertexFormat format,
                                                     int initialMaxQuads) {
        return new CgDynamicTextureRenderLayer(name, shader, state, CgBatchRenderer.create(format, initialMaxQuads));
    }

    private CgDynamicTextureRenderLayer(String name, CgShader shader, CgRenderState state, CgBatchRenderer renderer) {
        this.name = name;
        this.shader = shader;
        this.state = state;
        this.renderer = renderer;
    }

    /**
     * Swaps the active shader, flushing any pending geometry first so it renders
     * under the previous shader. Accepts {@code null} to clear the shader.
     */
    public void setShader(CgShader shader) {
        if (this.shader != shader) {
            flush();
            this.shader = shader;
        }
    }

    public CgShader getShader() {
        return shader;
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
        if (shader != null) shader.bind();
        if (activeTextureId >= 0) {
            CgGL.glActiveTexture(CgGL.GL_TEXTURE0);
            CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, activeTextureId);
        }
        state.apply();
        renderer.flush();
        state.clear();
        if (shader != null) shader.unbind();
        if (activeTextureId >= 0) {
            CgGL.glActiveTexture(CgGL.GL_TEXTURE0);
            CgGL.glBindTexture(CgGL.GL_TEXTURE_2D, 0);
        }
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
