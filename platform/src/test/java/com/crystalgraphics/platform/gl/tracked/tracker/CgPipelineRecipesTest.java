package com.crystalgraphics.platform.gl.tracked.tracker;

import com.crystalgraphics.platform.device.format.CgAttribFormat;
import com.crystalgraphics.platform.device.format.CgCompare;
import com.crystalgraphics.platform.device.format.CgFormat;
import com.crystalgraphics.platform.device.pipeline.CgPipelineDesc;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** A recorded pipeline reads back as the description it was written from, and a program's key is its sources'. */
public class CgPipelineRecipesTest {

    @Test
    public void aRecipeReadsBackAsWritten() {
        CgPipelineDesc.StencilFace face = new CgPipelineDesc.StencilFace(CgCompare.EQUAL, CgPipelineDesc.StencilOp.KEEP,
                CgPipelineDesc.StencilOp.ZERO, CgPipelineDesc.StencilOp.REPLACE);
        CgPipelineRecipes.Recipe full = new CgPipelineRecipes.Recipe(true,
                List.of(new CgPipelineDesc.VertexBuffer(0, 24, false, List.of(
                        new CgPipelineDesc.VertexAttrib(0, CgAttribFormat.FLOAT32X2, 0),
                        new CgPipelineDesc.VertexAttrib(1, CgAttribFormat.FLOAT32X4, 8)))),
                CgPipelineDesc.Topology.TRIANGLE_STRIP,
                new CgPipelineDesc.Raster(CgPipelineDesc.CullMode.BACK, CgPipelineDesc.FrontFace.CW, CgPipelineDesc.PolygonMode.FILL, true),
                new CgPipelineDesc.DepthStencil(true, false, CgCompare.GREATER, true, face, CgPipelineDesc.StencilFace.KEEP, 0x0F, 0xF0),
                List.of(new CgPipelineDesc.ColorTarget(CgFormat.values()[0], CgPipelineDesc.Blend.ALPHA, 0xF),
                        new CgPipelineDesc.ColorTarget(CgFormat.values()[1], null, 0x7)),
                CgFormat.values()[2], 4);
        assertEquals(full, CgPipelineRecipes.Recipe.decode(full.encode()));

        CgPipelineRecipes.Recipe bare = new CgPipelineRecipes.Recipe(false, List.of(), CgPipelineDesc.Topology.POINTS,
                CgPipelineDesc.Raster.DEFAULT, CgPipelineDesc.DepthStencil.OFF, List.of(), null, 1);
        assertEquals(bare, CgPipelineRecipes.Recipe.decode(bare.encode()));
    }

    @Test
    public void aProgramsKeyFollowsItsSourcesAlone() {
        long a = CgTrackedProgram.key("vs", "fs", Map.of("pos", 0, "uv", 1));
        assertEquals(a, CgTrackedProgram.key("vs", "fs", Map.of("uv", 1, "pos", 0)));
        assertNotEquals(a, CgTrackedProgram.key("vs", "fs2", Map.of("pos", 0, "uv", 1)));
        assertNotEquals(a, CgTrackedProgram.key("vsf", "s", Map.of("pos", 0, "uv", 1)));
    }
}
