package com.crystalgraphics.gl.material;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.gl.texture.CgTextureMutable;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.render.draw.CgBindingTable;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPipeline;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.*;

/**
 * render-graph G0: what a recorder asks of a material — a pipeline key and a binding snapshot — answered with no GL
 * context at all, since a recorder will run on a document's thread. Any GL call here would throw.
 */
public class CgMaterialRecordingTest {

    private static final String SOURCE = """
            #type spatial
            #pragma cg_feature WITH_MASK
            Properties {
                _Color    ("Color", color)    = (1, 1, 1, 1)
                _Alpha    ("Alpha", float)    = 1
                _MainTex  ("Main", sampler2D)  = "white"
                _SharpTex ("Sharp", sampler2D) = "white"
            }
            Pass {
                void vertex(out v2f o) { }
                void fragment(in v2f i, out vec4 fragColor) { fragColor = _Color * _Alpha; }
            }
            """;

    @Before
    public void resetRegistry() {
        CgMaterialShaderRegistry.resetForTest();
    }

    @Test
    public void aPipelineIsAKeyAndCompilesNothing() {
        CgMaterial a = CgMaterial.fromSource(SOURCE);
        CgMaterial b = CgMaterial.fromSource(SOURCE);

        CgPipeline quad = a.pipeline(CgInstanceKind.QUAD);
        assertSame("one asset, one state, one kind: one pipeline", quad, b.pipeline(CgInstanceKind.QUAD));
        assertSame(quad, CgPipeline.byId(quad.id()));
        assertNotSame(quad, a.pipeline(CgInstanceKind.CURVE));

        a.enableKeyword("WITH_MASK");
        CgPipeline masked = a.pipeline(CgInstanceKind.QUAD);
        assertNotSame(quad, masked);
        a.disableKeyword("WITH_MASK");
        assertSame(quad, a.pipeline(CgInstanceKind.QUAD));

        assertEquals("nothing compiled", 0, quad.shader().getRevisionNumber());
    }

    @Test
    public void pipelinesInternAcrossThreads() throws Exception {
        CgMaterial material = CgMaterial.fromSource(SOURCE);
        CgPipeline expected = material.pipeline(CgInstanceKind.QUAD);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<CgPipeline>> answers = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                Callable<CgPipeline> ask = () -> CgPipeline.of(expected.shader(), expected.pass(), expected.keywords(),
                        expected.state(), CgInstanceKind.OBJECT);
                answers.add(pool.submit(ask));
            }
            CgPipeline first = answers.get(0).get();
            for (Future<CgPipeline> answer : answers) assertSame(first, answer.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    public void equalSnapshotsShareAnIdAndKeepTheirValues() {
        CgMaterial material = CgMaterial.fromSource(SOURCE);
        CgBindingTable table = new CgBindingTable();

        material.applyProperties(p -> p.set1f("_Alpha", 0.25f));
        int quarter = material.captureBindings(table);
        assertEquals(quarter, material.captureBindings(table));

        material.applyProperties(p -> p.set1f("_Alpha", 0.75f));
        int threeQuarters = material.captureBindings(table);
        assertNotEquals(quarter, threeQuarters);
        assertEquals(2, table.size());

        material.applyProperties(p -> p.set1f("_Alpha", 0.25f));
        assertEquals("the first snapshot still holds its own value", quarter, material.captureBindings(table));
        assertTrue(contains(table.blockFloats(quarter, 0), 0.25f));
        assertTrue(contains(table.blockFloats(threeQuarters, 0), 0.75f));
    }

    @Test
    public void texturesAreHandlesAtTheirDeclaredUnits() {
        CgMaterial material = CgMaterial.fromSource(SOURCE);
        CgBindingTable table = new CgBindingTable();
        CgTexture atlas = new CgTextureMutable(7, CgGL.GL_TEXTURE_2D);
        CgTexture other = new CgTextureMutable(8, CgGL.GL_TEXTURE_2D);

        material.applyProperties(p -> p.sampler("_SharpTex", 5, atlas));
        int id = material.captureBindings(table);
        assertEquals(1, table.textures(id));
        assertEquals("the unit is the declaration index, whatever the caller passed", 1, table.textureUnit(id, 0));
        assertEquals(7, table.texture(id, 0).getId());

        material.applyProperties(p -> p.sampler("_SharpTex", 1, other));
        assertNotEquals(id, material.captureBindings(table));
    }

    @Test
    public void aRepointableViewIsKeptAsWhatItPointsAtNow() {
        CgMaterial material = CgMaterial.fromSource(SOURCE);
        CgBindingTable table = new CgBindingTable();
        CgTextureMutable atlas = new CgTextureMutable(7, CgGL.GL_TEXTURE_2D);
        material.applyProperties(p -> p.sampler("_SharpTex", 0, atlas));

        int seven = material.captureBindings(table);
        atlas.setId(8);
        int eight = material.captureBindings(table);
        assertEquals("a draw recorded before the view moved binds what it saw", 7, table.texture(seven, 0).getId());
        assertEquals(8, table.texture(eight, 0).getId());

        atlas.setId(7);
        assertEquals("one id, one snapshot", seven, material.captureBindings(table));
    }

    private static boolean contains(float[] values, float value) {
        for (float v : values) if (v == value) return true;
        return false;
    }
}
