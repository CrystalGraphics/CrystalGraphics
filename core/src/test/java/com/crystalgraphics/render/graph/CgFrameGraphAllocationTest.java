package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.gl.material.CgMaterialTestSupport;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.trace.CgAllocationProof;
import org.junit.Before;
import org.junit.Test;

/**
 * T5: a stage firing's recording and the frame builder allocate nothing per frame once warm: chunks of draws in two
 * materials, a sorted raster pass and a scratch target, sealed, built and recycled as {@code CgStageFrame.execute}
 * does, without the executor (no device here).
 */
public class CgFrameGraphAllocationTest {

    private static final String SOURCE = """
            #type none
            Properties {
                _Alpha ("Alpha", float) = 1
            }
            Pass {
                void vertex(out v2f o) { }
                void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(_Alpha); }
            }
            """;
    private static final String GLOW = """
            #type none
            Tags { "RenderType" = "Transparent" }
            Properties {
                _Gain ("Gain", float) = 2
            }
            Pass {
                void vertex(out v2f o) { }
                void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(_Gain); }
            }
            """;
    private static final CgTextureDesc DESC = new CgTextureDesc(256, 256, CgTextureDesc.RGBA8);

    private final CgPassConstants constants = new CgPassConstants().resolution(256, 256);
    private final CgFrameBuilder builder = new CgFrameBuilder();
    private final CgFrameGraph graph = new CgFrameGraph();
    private final CgRecording rec = new CgRecording();
    private CgMaterial opaque, glow;
    private CgPipeline opaqueQuads, glowQuads;
    private CgGraphTexture target;

    @Before
    public void setUp() {
        CgMaterialTestSupport.resetShaderRegistry();
        opaque = CgMaterial.fromSource(SOURCE);
        glow = CgMaterial.fromSource(GLOW);
        opaqueQuads = opaque.pipeline(CgInstanceKind.QUAD);
        glowQuads = glow.pipeline(CgInstanceKind.QUAD);
        target = CgGraphTexture.requested("alloc-scene", DESC);
    }

    private void frame() {
        CgRasterPass pass = rec.raster(target, CgLoad.load(), constants, null, CgOrder.SORTED);
        for (int c = 0; c < 8; c++) {
            boolean glows = (c & 1) == 1;
            CgChunkBuilder chunk = rec.chunks().begin();
            chunk.draw(glows ? glowQuads : opaqueQuads, (glows ? glow : opaque).captureBindings(rec.bindings()))
                    .sortKey(c);
            for (int i = 0; i < 32; i++) {
                int at = chunk.instance();
                chunk.data()[at] = i;
                chunk.bounds(i * 8, c * 30, i * 8 + 8, c * 30 + 8);
            }
            pass.add(chunk.end());
        }
        pass.end();
        graph.add(rec.seal());
        CgFrame built = builder.build(graph);
        builder.recycle(built);
        graph.clear();
        rec.reset();
    }

    @Test
    public void recordingAndBuildingAFrameAllocatesNothing() {
        CgAllocationProof.assertZero("record + CgFrameBuilder.build", 2_000, 500, this::frame);
    }
}
