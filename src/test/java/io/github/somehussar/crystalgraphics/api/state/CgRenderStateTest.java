package io.github.somehussar.crystalgraphics.api.state;

import com.crystalgraphics.api.state.*;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link CgRenderState} builder and {@link CgRenderState#DEFAULT}.
 *
 * <p>Design contract: {@code builder().build()} produces a state with <em>all slots null</em>
 * (meaning the slot is inherited from the global GL state, not managed by this material).
 * Only {@link CgRenderState#DEFAULT} is guaranteed to have every slot populated.</p>
 */
public class CgRenderStateTest {

    @Test
    public void defaultBuilder_allSlotsNull() {
        CgRenderState state = CgRenderState.builder().build();
        assertNull("blend should be null when not set", state.getBlend());
        assertNull("depth should be null when not set", state.getDepth());
        assertNull("cull should be null when not set",  state.getCull());
        assertNull("stencil should be null when not set", state.getStencil());
    }

    @Test
    public void DEFAULT_constant_isNotNull() {
        assertNotNull(CgRenderState.DEFAULT);
    }

    @Test
    public void DEFAULT_hasExpectedBlend() {
        assertSame(CgBlendState.DISABLED, CgRenderState.DEFAULT.getBlend());
    }

    @Test
    public void DEFAULT_hasExpectedDepth() {
        assertSame(CgDepthState.TEST_WRITE, CgRenderState.DEFAULT.getDepth());
    }

    @Test
    public void DEFAULT_hasExpectedCull() {
        assertSame(CgCullState.BACK, CgRenderState.DEFAULT.getCull());
    }

    @Test
    public void DEFAULT_hasExpectedStencil() {
        assertSame(CgStencilState.DISABLED, CgRenderState.DEFAULT.getStencil());
    }

    @Test
    public void DEFAULT_matchesBuilderDefaults() {
        CgRenderState fromBuilder = CgRenderState.builder()
                .blend(CgBlendState.DISABLED)
                .depth(CgDepthState.TEST_WRITE)
                .cull(CgCullState.BACK)
                .stencil(CgStencilState.DISABLED)
                .build();
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
