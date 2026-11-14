package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.material.CgMaterialProxy;
import com.crystalgraphics.material.CgMaterialTestSupport;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.draw.CgDrawCommands;
import com.crystalgraphics.render.draw.CgIndirect;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgMaterialBindings;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.draw.CgPrimitiveTable;
import com.crystalgraphics.render.shader.CgBindingModel;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * A raster pass of mesh draw commands, packed with no GL: neighbours differing only by their objects merge into one
 * instanced batch (Unreal's dynamic instancing), each instance's id the command's (a scene record's as it is, a frame
 * record's past its table's base), its material's slot beside it; a command of GPU ids binds them at {@code OBJECT_IDS}.
 */
public class CgDrawCommandsPackTest {

    private static final String SOURCE = """
            #domain global
            Material { Queue Geometry }
            void vertex(out v2f o) { }
            void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(1.0); }
            """;
    private static final int SCENE = 0x80000000;

    private final CgFrameBuilder builder = new CgFrameBuilder();
    private CgPipeline pipeline;
    private CgMaterialBindings bindings;
    private final CgMesh cube = CgMeshShapes.cube(), quad = CgMeshShapes.quad();

    @Before
    public void setUp() {
        CgMaterialTestSupport.resetShaderRegistry();
        CgMaterial material = CgMaterial.fromSource(SOURCE);
        pipeline = CgMaterialProxy.of(material).pipeline(CgInstanceKind.OBJECT);
        bindings = CgMaterialProxy.of(material).bindings();
    }

    private CgRasterPass pass(CgRecording rec) {
        // Requested: the frame keeps it, so the pass drawing it is not culled
        CgGraphTexture target = CgGraphTexture.requested("t", CgTextureDesc.of(64, 64, CgFormat.RGBA8_UNORM));
        return rec.raster("commands").color(target, CgLoad.CLEAR).constants(new CgPassConstants().resolution(64, 64));
    }

    private int id(CgFrame frame, int instance) {
        return Float.floatToRawIntBits(frame.instances[CgInstanceKind.OBJECT.ordinal()][instance * 2]);
    }

    @Test
    public void sceneRecordsOfOneMeshMergeIntoOneInstancedBatch() {
        CgRecording rec = new CgRecording();
        CgDrawCommands commands = new CgDrawCommands(rec.primitives());
        for (int i = 0; i < 3; i++) commands.add(pipeline, bindings, cube).sceneRecord(SCENE | (5 + i));
        commands.add(pipeline, bindings, quad).sceneRecord(SCENE | 9);
        CgRasterPass pass = pass(rec);
        pass.add(commands);
        pass.end();
        CgFrame frame = builder.build(new CgFrameGraph().add(rec.seal()));
        CgFrame.Raster packed = frame.rasters[0];

        assertEquals("three cubes, one quad", 2, packed.count);
        assertSame(cube, packed.mesh[0]);
        assertEquals(3, packed.instances[0]);
        assertEquals(1, packed.instances[1]);
        for (int i = 0; i < 3; i++) assertEquals(SCENE | (5 + i), id(frame, packed.first[0] + i));
        assertEquals(SCENE | 9, id(frame, packed.first[1]));
    }

    @Test
    public void aFrameRecordIsNamedPastItsTablesBase() {
        CgRecording rec = new CgRecording();
        rec.primitives().add();   // a record another builder wrote first
        int slot = CgPrimitiveTable.slot(rec.primitives().add());
        CgDrawCommands commands = new CgDrawCommands(rec.primitives());
        commands.add(pipeline, bindings, cube).frameRecord(slot);
        commands.add(pipeline, bindings, cube).sceneRecord(SCENE | 2);
        CgRasterPass pass = pass(rec);
        pass.add(commands);
        pass.end();
        CgFrame frame = builder.build(new CgFrameGraph().add(rec.seal()));
        CgFrame.Raster packed = frame.rasters[0];

        assertEquals("a frame record and a scene record never share a batch", 2, packed.count);
        assertEquals(slot, id(frame, packed.first[0]));
        assertEquals(SCENE | 2, id(frame, packed.first[1]));
    }

    @Test
    public void gpuIdsBindAtObjectIdsAndDrawAsTheirCountSays() {
        CgRecording rec = new CgRecording();
        CgGraphBuffer ids = CgGraphBuffer.transientBuffer("ids", CgBufferDesc.elements(64, 8, CgBufferUsage.STORAGE));
        CgGraphBuffer counts = CgGraphBuffer.transientBuffer("counts", CgBufferDesc.of(16, CgBufferUsage.STORAGE, CgBufferUsage.INDIRECT));
        CgDrawCommands commands = new CgDrawCommands(rec.primitives());
        commands.add(pipeline, bindings, cube).ids(ids, 32, 20).indirect(counts, 4, CgIndirect.INSTANCES, 1);
        CgRasterPass pass = pass(rec);
        pass.add(commands);
        pass.end();
        CgFrame frame = builder.build(new CgFrameGraph().add(rec.seal()));
        CgFrame.Raster packed = frame.rasters[0];

        assertEquals(1, packed.count);
        assertTrue(packed.ownIds[0]);
        assertSame(ids, packed.buffers[0]);
        assertSame(CgBindingModel.OBJECT_IDS, packed.bufferAt[0]);
        assertEquals(32, packed.first[0]);
        assertEquals(20, packed.instances[0]);
        assertSame(counts, packed.counts[0]);
        assertEquals(1, packed.indirects);
    }
}
