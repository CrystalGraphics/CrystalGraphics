package com.crystalgraphics.presentation.stage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgStageKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.validate.CgValidation;
import com.crystalgraphics.object.value.CgUnits;
import com.crystalgraphics.render.post.settings.CgPostSettings;
import com.crystalgraphics.render.scene.prefab.CgPrefab;

/** A stage compiled to a prefab: its kit's units into the host's blocks round its anchor, its builder's file, and what it refuses at load. */
public class CgStageAssetsTest {

    /** Unreal's units; a material answers as compiled elsewhere, so none is made here. */
    private static final CgAssetCompiler.Dependencies UNREAL = new CgAssetCompiler.Dependencies() {
        @Override
        public Object definition(CgType kind, String id) {
            return id;
        }

        @Override
        public CgUnits units() {
            return CgUnits.UNREAL;
        }
    };

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    private static CgStageAssets.Builder arena() {
        return CgStageAssets.builder("test:arena").anchor(0, 0, 7849)
                .volume(new CgPostSettings().tonemap().vignette(0.5f), v -> v.unbound()
                        .material("test:post/overlay", Map.of("SunVector", List.of(0.8, -0.5, 0.3, 0.0))))
                .volume(new CgPostSettings().vignette(0.2f), v -> v.priority(2).at(100, 0, 7849, 1500, 500))
                .fog(f -> f.density(0.002).heightFalloff(0.1).height(15799).inscatteringColour(0.41, 0.89, 1)
                        .maxOpacity(0.65).startDistance(30000).cutoffDistance(4.0e6));
    }

    @Test
    public void itsFogAndVolumesAreInBlocksFromItsAnchor() {
        CgPrefab stage = arena().build(UNREAL);
        assertEquals(-1, stage.parent(stage.node("anchor")));
        int fogNode = stage.node("fog");
        CgPrefab.Fog fog = stage.fog(fogNode);
        assertEquals("FogDensity / 1000 a centimetre, a block", 2e-4f, fog.density(), 1e-9f);
        assertEquals(0.01f, fog.falloff(), 1e-7f);
        assertEquals("its height over the anchor's", 79.5f, stage.rest(fogNode, new float[CgPrefab.REST])[1], 1e-4f);
        assertEquals(300f, fog.start(), 1e-4f);
        assertEquals(40000f, fog.cutoff(), 1e-2f);
        assertEquals(0.65f, fog.maxOpacity(), 0f);

        int level = stage.node("volume0"), round = stage.node("volume1");
        CgPrefab.Post levelPost = stage.post(level), roundPost = stage.post(round);
        assertTrue(levelPost.unbound());
        assertEquals("overlay's material, named by its id", "test:post/overlay", levelPost.materials().get(0).name());
        assertNull("no material compiler here", levelPost.materials().get(0).material());
        assertEquals(0.5f, levelPost.settings().vignette(), 0f);
        assertFalse(roundPost.unbound());
        assertEquals(2, roundPost.priority());
        float[] at = stage.rest(round, new float[CgPrefab.REST]);
        assertEquals("game x is host x, a metre from the anchor", 1f, at[0], 1e-6f);
        assertEquals(0f, at[1], 1e-6f);
        assertEquals(15f, roundPost.radius(), 1e-5f);
        assertEquals(5f, roundPost.blend(), 1e-5f);
    }

    @Test
    public void itsBuilderWritesAFileThatValidatesAndReadsBack() {
        Map<String, Object> tree = arena().tree();
        String yaml = CgAssetYaml.write(tree, CgStageKinds.STAGE);
        Object again = CgAssetYaml.read(yaml);
        assertEquals(yaml, CgAssetYaml.write(again, CgStageKinds.STAGE));
        CgValidation v = CgAssetData.validate("test:arena", CgStageKinds.STAGE, (Map<?, ?>) again, CgValidation.Usecase.LOAD);
        assertTrue(v.issues().toString(), v.valid());
    }

    @Test
    public void fogItDoesNotDrawFailsValidationByName() {
        Map<String, Object> tree = Map.of("_v", 1, "fog", Map.of("density", 0.02, "directional_inscattering_exponent", 4));
        CgValidation v = CgAssetData.validate("test:hazy", CgStageKinds.STAGE, tree, CgValidation.Usecase.LOAD);
        assertFalse(v.valid());
        assertTrue(v.issues().toString(), v.issues().toString().contains("directional_inscattering_exponent"));
    }

    @Test
    public void aFractionalPriorityAndAScreenPointFailAtCompileByPath() {
        expectFailure(Map.of("_v", 1, "volumes", List.of(Map.of("post", Map.of("bloom", 1), "priority", 1.5))), "volumes[0].priority");
        Map<String, Object> followed = Map.of("material", "test:post/blur", "screen_point", Map.of("target", Map.of("locator", "k1")));
        expectFailure(Map.of("_v", 1, "volumes", List.of(Map.of("post", Map.of("materials", List.of(followed))))),
                "volumes[0].post.materials[0].screen_point");
    }

    private static void expectFailure(Map<String, Object> tree, String path) {
        try {
            CgStageAssets.compile(CgAssetData.of("test:bad", CgStageKinds.STAGE, tree, null), UNREAL);
            fail("compiled");
        } catch (CgAssetException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("test:bad: " + path));
        }
    }
}
