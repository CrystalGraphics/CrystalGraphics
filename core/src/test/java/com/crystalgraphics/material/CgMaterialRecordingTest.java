package com.crystalgraphics.material;

import com.crystalgraphics.render.shader.CgBindingModel;
import com.crystalgraphics.platform.gl.driver.GLC;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.gpu.resource.CgTextureAbstract;
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
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.render.draw.CgMaterialBindings;

/**
 * render-graph G0: what a recorder asks of a material — a pipeline key and its bindings — answered with no GL
 * context at all, since a recorder will run on a document's thread. Any GL call here would throw.
 */
public class CgMaterialRecordingTest {

    private static final String SOURCE = """
            #domain ui
            Keywords { WITH_MASK ("With mask") }
            Properties {
                _Color    ("Color", color)    = (1, 1, 1, 1)
                _Alpha    ("Alpha", float)    = 1
                _MainTex  ("Main", sampler2D)  = "white"
                _SharpTex ("Sharp", sampler2D) = "white"
            }
            void surface(in CgUiInput i, inout CgUiSurface s) { s.color = _Color * _Alpha; }
            """;

    @Before
    public void resetRegistry() {
        CgMaterialShaderRegistry.resetForTest();
    }

    @Test
    public void aPipelineIsAKeyAndCompilesNothing() {
        CgMaterial a = CgMaterial.fromSource(SOURCE);
        CgMaterial b = CgMaterial.fromSource(SOURCE);

        CgPipeline quad = CgMaterialProxy.of(a).pipeline(CgInstanceKind.QUAD);
        assertSame("one asset, one state, one kind: one pipeline", quad, CgMaterialProxy.of(b).pipeline(CgInstanceKind.QUAD));
        assertSame(quad, CgPipeline.byId(quad.id()));
        assertNotSame(quad, CgMaterialProxy.of(a).pipeline(CgInstanceKind.CURVE));

        CgMaterial masked = a.keyword("WITH_MASK", true);
        assertNotSame(quad, CgMaterialProxy.of(masked).pipeline(CgInstanceKind.QUAD));
        assertSame(quad, CgMaterialProxy.of(masked.keyword("WITH_MASK", false)).pipeline(CgInstanceKind.QUAD));

        assertEquals("nothing compiled", 0, quad.shader().getRevisionNumber());
    }

    @Test
    public void pipelinesInternAcrossThreads() throws Exception {
        CgMaterial material = CgMaterial.fromSource(SOURCE);
        CgPipeline expected = CgMaterialProxy.of(material).pipeline(CgInstanceKind.QUAD);
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
    public void aBlockIsANewVersionOnlyWhenAValueMoves() {
        CgMaterialInstance material = CgMaterial.fromSource(SOURCE).instance();

        material.set1f("_Alpha", 0.25f);
        CgMaterialBindings quarter = CgMaterialProxy.of(material).bindings();
        for (int frame = 0; frame < 3; frame++) assertSame("unchanged: one version", quarter, CgMaterialProxy.of(material).bindings());
        material.set1f("_Alpha", 0.25f);
        assertSame("the same value written: still one version", quarter, CgMaterialProxy.of(material).bindings());

        material.set1f("_Alpha", 0.75f);
        CgMaterialBindings threeQuarters = CgMaterialProxy.of(material).bindings();
        assertNotSame(quarter, threeQuarters);

        material.set1f("_Alpha", 0.25f);
        assertNotSame("a value moved back is a new version", threeQuarters, CgMaterialProxy.of(material).bindings());
        assertTrue("the first version still holds its own value", contains(quarter.block(0).floats(), 0.25f));
        assertTrue(contains(threeQuarters.block(0).floats(), 0.75f));
    }

    @Test
    public void texturesAreHandlesAtTheirDeclaredUnits() {
        CgMaterialInstance material = CgMaterial.fromSource(SOURCE).instance();
        CgTexture atlas = new Handle(7);
        CgTexture other = new Handle(8);

        material.sampler("_SharpTex", 5, atlas);
        CgMaterialBindings bindings = CgMaterialProxy.of(material).bindings();
        assertEquals(1, bindings.textures());
        assertEquals("the slot is the declaration index's, whatever the caller passed", CgBindingModel.packedTexture(1),
                bindings.textureSlot(0));
        assertEquals(7, ((CgTextureAbstract) bindings.texture(0)).getId());

        material.sampler("_SharpTex", 1, other);
        assertNotSame(bindings, CgMaterialProxy.of(material).bindings());
    }

    /** A texture by id alone, no GL. */
    private static final class Handle extends CgTextureAbstract {
        Handle(int id) {
            super(id, 0, 0, null);
        }

        @Override
        public int getTarget() {
            return GLC.GL_TEXTURE_2D;
        }

        @Override
        public void delete() {}
    }

    private static boolean contains(float[] values, float value) {
        for (float v : values) if (v == value) return true;
        return false;
    }
}
