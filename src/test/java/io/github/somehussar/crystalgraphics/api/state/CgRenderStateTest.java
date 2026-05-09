package io.github.somehussar.crystalgraphics.api.state;

import org.junit.Test;

import static org.junit.Assert.*;

public class CgRenderStateTest {

    @Test
    public void defaultBuilder_hasExpectedBlend() {
        CgRenderState state = CgRenderState.builder().build();
        assertSame(CgBlendState.DISABLED, state.getBlend());
    }

    @Test
    public void defaultBuilder_hasExpectedDepth() {
        CgRenderState state = CgRenderState.builder().build();
        assertSame(CgDepthState.TEST_WRITE, state.getDepth());
    }

    @Test
    public void defaultBuilder_hasExpectedCull() {
        CgRenderState state = CgRenderState.builder().build();
        assertSame(CgCullState.BACK, state.getCull());
    }

    @Test
    public void defaultBuilder_hasExpectedStencil() {
        CgRenderState state = CgRenderState.builder().build();
        assertSame(CgStencilState.DISABLED, state.getStencil());
    }

    @Test
    public void DEFAULT_constant_isNotNull() {
        assertNotNull(CgRenderState.DEFAULT);
    }

    @Test
    public void DEFAULT_matchesBuilderDefaults() {
        CgRenderState fromBuilder = CgRenderState.builder().build();
        assertSame(CgRenderState.DEFAULT.getBlend(),   fromBuilder.getBlend());
        assertSame(CgRenderState.DEFAULT.getDepth(),   fromBuilder.getDepth());
        assertSame(CgRenderState.DEFAULT.getCull(),    fromBuilder.getCull());
        assertSame(CgRenderState.DEFAULT.getStencil(), fromBuilder.getStencil());
    }

    @Test
    public void builder_storesCustomBlend() {
        CgRenderState state = CgRenderState.builder().blend(CgBlendState.ALPHA).build();
        assertSame(CgBlendState.ALPHA, state.getBlend());
    }

    @Test
    public void builder_storesCustomDepth() {
        CgRenderState state = CgRenderState.builder().depth(CgDepthState.NONE).build();
        assertSame(CgDepthState.NONE, state.getDepth());
    }

    @Test
    public void builder_storesCustomCull() {
        CgRenderState state = CgRenderState.builder().cull(CgCullState.NONE).build();
        assertSame(CgCullState.NONE, state.getCull());
    }

    @Test
    public void builder_storesCustomStencil() {
        CgRenderState state = CgRenderState.builder().stencil(CgStencilState.DISABLED).build();
        assertSame(CgStencilState.DISABLED, state.getStencil());
    }
}
