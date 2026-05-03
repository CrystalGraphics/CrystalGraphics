package io.github.somehussar.crystalgraphics.gl.render;

import io.github.somehussar.crystalgraphics.api.state.CgRenderState;
import io.github.somehussar.crystalgraphics.api.vertex.CgInstanceLayout;
import io.github.somehussar.crystalgraphics.api.vertex.CgVertexFormat;
import io.github.somehussar.crystalgraphics.gl.buffer.staging.CgInstanceWriter;
import io.github.somehussar.crystalgraphics.gl.buffer.staging.CgVertexWriter;
import org.joml.Matrix4f;

/**
 * Render layer for instanced geometry. Mirrors the lifecycle of {@link CgRenderLayer}
 * but delegates drawing to {@link CgInstancedBatchRenderer}.
 *
 * <p>On {@link #flush()}, the owned {@link CgRenderState} is applied, the instanced
 * batch renderer draws, and then the state is cleared. The state bracket is identical
 * to {@link CgRenderLayer}: {@code state.apply(projection)} → flush → {@code state.clear()}.</p>
 *
 * <p>Implements {@link CgLayer} so it can be registered in {@link CgBufferSource} via
 * an existing {@code CgLayer.Key<CgInstancedRenderLayer>}.</p>
 */
public final class CgInstancedRenderLayer implements CgLayer {

    private final String name;
    private final CgRenderState state;
    private final CgInstancedBatchRenderer renderer;
    private final CgInstancedDrawMode drawMode;
    private final Matrix4f projection = new Matrix4f();
    private boolean begun;

    public static CgInstancedRenderLayer create(String name, CgRenderState state,
                                                  CgVertexFormat baseFormat,
                                                  CgInstanceLayout instanceLayout,
                                                  CgInstancedDrawMode drawMode,
                                                  int initialMaxBaseQuads,
                                                  int initialMaxInstances) {
        CgInstancedBatchRenderer renderer = CgInstancedBatchRenderer.create(
                baseFormat, instanceLayout, initialMaxBaseQuads, initialMaxInstances);
        return new CgInstancedRenderLayer(name, state, renderer, drawMode);
    }

    private CgInstancedRenderLayer(String name, CgRenderState state,
                                    CgInstancedBatchRenderer renderer,
                                    CgInstancedDrawMode drawMode) {
        this.name = name;
        this.state = state;
        this.renderer = renderer;
        this.drawMode = drawMode;
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
        renderer.flush(drawMode);
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
    public CgInstanceWriter instance() { return renderer.instance(); }
}
