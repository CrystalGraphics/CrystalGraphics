package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.gpu.resource.CgTextureDesc;
import com.crystalgraphics.material.CgMaterialTestSupport;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.render.draw.CgDrawChunk;
import com.crystalgraphics.render.draw.CgPrimitiveTable;
import org.joml.Matrix4f;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/** {@code pass.draw(mesh, material)}: the records the engine writes, and where its draw lands among the pass's chunks. */
public class CgDrawTest {

    private CgMaterial material;
    private final CgMesh mesh = CgMesh.quads(1);

    @Before
    public void setUp() {
        CgMaterialTestSupport.resetShaderRegistry();
        material = CgMaterial.fromSource("""
                #domain global
                Material { Queue Geometry }
                void vertex(out v2f o) { }
                void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(1.0); }
                """);
    }

    private static CgRasterPass pass(CgRecording rec) {
        return rec.raster("draw").color(CgGraphTexture.requested("target", CgTextureDesc.of(8, 8, CgFormat.RGBA8_UNORM)),
                CgLoad.load());
    }

    @Test
    public void aDrawWithNothingSetIsOneIdentityInstance() {
        CgRecording rec = new CgRecording();
        CgRasterPass pass = pass(rec);
        pass.draw(mesh, material);
        pass.end();

        assertEquals(1, pass.chunks().size());
        CgDrawChunk chunk = pass.chunks().get(0);
        assertEquals(1, chunk.draws());
        assertEquals(1, chunk.instances(0));
        float[] record = rec.primitives().data();
        for (int i = 0; i < 4; i++) {
            assertEquals("model diagonal " + i, 1f, record[i * 5], 0f);
            assertEquals("normal diagonal " + i, 1f, record[16 + i * 5], 0f);
        }
        assertEquals("custom data zero", 0f, record[32], 0f);
    }

    @Test
    public void eachInstanceKeepsWhatWasSetOfIt() {
        CgRecording rec = new CgRecording();
        CgRasterPass pass = pass(rec);
        pass.draw(mesh, material).transform(new Matrix4f().translation(1f, 2f, 3f)).customData(0, 0.5f, 0f, 0f, 1f)
                .instance().transform(new Matrix4f().scaling(2f)).customData(1, 0f, 0.25f, 0f, 0f);
        pass.end();

        assertEquals(2, pass.chunks().get(0).instances(0));
        float[] r = rec.primitives().data();
        int second = CgPrimitiveTable.FLOATS;
        assertEquals(3f, r[14], 0f);
        assertEquals(0.5f, r[32], 0f);
        assertEquals(2f, r[second], 0f);
        assertEquals("a scale's normal matrix is its inverse transpose", 0.5f, r[second + 16], 1e-6f);
        assertEquals(0.25f, r[second + 37], 0f);
        assertEquals("the first instance's custom1 untouched", 0f, r[37], 0f);
    }

    @Test
    public void aChunkAddedAfterADrawLandsAfterIt() {
        CgRecording rec = new CgRecording();
        CgRasterPass pass = pass(rec);
        pass.draw(mesh, material);
        CgDrawChunk added = rec.chunks().begin().end();
        pass.add(added);
        pass.end();

        assertEquals(2, pass.chunks().size());
        assertSame("painter's order: the draw, then the chunk", added, pass.chunks().get(1));
    }
}
