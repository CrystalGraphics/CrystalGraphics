package com.crystalgraphics.vfx.particle.renderer;

import com.crystalgraphics.api.material.CgBlendMode;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.material.CgMaterialUsage;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.kind.CgParticleKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.object.validate.CgValidation;
import com.crystalgraphics.settings.CgQuality;
import com.crystalgraphics.vfx.look.CgVfxFlipbook;
import com.crystalgraphics.vfx.particle.CgVfxSubUvAnimation;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterAssets;
import org.junit.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** An emitter's renderers: compiled from their entries by kind, built in Java to the same tree, round-tripped. */
public class CgVfxRenderersTest {

    private static final String SPRITE = "crystalgraphics:shaders/vfx/particle/sprite.shader";

    @Test
    public void everyBuiltKindCompilesFromItsEntry() {
        List<CgVfxRendererProperties> all = CgVfxRenderers.compileAll("sparks", List.of(
                Map.of("id", "r1", "kind", "sprite", "material", "test:materials/spark", "alignment", "velocity",
                        "facing", "camera_plane", "sort", "view_depth", "quality", "medium"),
                Map.of("id", "r2", "kind", "mesh", "meshes", List.of(Map.of("mesh", "test:models/rock.glb"))),
                Map.of("id", "r3", "kind", "ribbon", "shape", "plane"),
                Map.of("id", "r4", "kind", "arc"),
                Map.of("id", "r5", "kind", "sequence", "sequence", "test:sequences/blades", "play_rate", 2.0)));
        CgVfxSpriteRendererProperties sprite = (CgVfxSpriteRendererProperties) all.get(0);
        assertEquals(CgVfxSpriteRendererProperties.Alignment.VELOCITY, sprite.alignment());
        assertEquals(CgVfxSpriteRendererProperties.Facing.CAMERA_PLANE, sprite.facing());
        assertEquals(CgQuality.MEDIUM, sprite.common().quality());
        assertArrayEquals("Unreal's pivot", new float[] {0.5f, 0.5f}, sprite.pivot(), 0f);
        assertEquals(1, ((CgVfxMeshRendererProperties) all.get(1)).meshes().size());
        assertEquals(CgVfxRibbonRendererProperties.Shape.PLANE, ((CgVfxRibbonRendererProperties) all.get(2)).shape());
        assertEquals(0, ((CgVfxArcRendererProperties) all.get(3)).subdivisions());
        assertEquals(2f, ((CgVfxSequenceRendererProperties) all.get(4)).playRate(), 0f);
    }

    @Test
    public void aSubImageIsItsFlipbook() {
        CgVfxSpriteRendererProperties sprite = (CgVfxSpriteRendererProperties) CgVfxRenderers.sprite("r1")
                .subImage(Map.of("size", List.of(4, 2), "blend", true)).build();
        CgVfxFlipbook f = sprite.subImage();
        assertEquals(8, f.frames());
        // The renderer holds the frames, shown at frame 0; its emitter's sub-UV animation plays them.
        assertEquals(CgVfxFlipbook.Mode.DIRECT, f.mode());
        assertEquals(CgVfxFlipbook.Mode.LOOP, CgVfxSubUvAnimation.loop(30f).play(f).mode());
    }

    @Test
    public void aFieldNotBuiltYetFailsAtLoadNamingIt() {
        for (Map<String, Object> entry : List.<Map<String, Object>>of(
                Map.of("id", "r1", "kind", "mesh", "meshes", Map.of("prefab", "test:prefabs/rock")),
                Map.of("id", "r1", "kind", "ribbon", "shape", "custom"),
                Map.of("id", "r1", "kind", "sprite", "facing", "camera_distance_blend"),
                Map.of("id", "r1", "kind", "sprite", "sort", "spawn_order"))) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> CgVfxRenderers.compile("sparks", entry));
            assertTrue(e.getMessage(), e.getMessage().contains("not built yet"));
        }
    }

    @Test
    public void anUnbuiltKindFailsNamingWhatItNeeds() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CgVfxRenderers.compile("sparks", Map.of("id", "r1", "kind", "system")));
        assertTrue(e.getMessage(), e.getMessage().contains("not built yet"));
    }

    @Test
    public void anAnimTrailNeedsItsEdgeAsUserParameters() {
        IllegalArgumentException none = assertThrows(IllegalArgumentException.class,
                () -> CgVfxRenderers.compile("sparks", Map.of("id", "r1", "kind", "anim_trail")));
        assertTrue(none.getMessage(), none.getMessage().contains("edge"));
        CgVfxRibbonRendererProperties trail = (CgVfxRibbonRendererProperties) CgVfxRenderers.animTrail("r1", "hilt", "tip").build();
        assertEquals("hilt", trail.anim().first());
        assertEquals("tip", trail.anim().second());
        assertEquals(null, trail.trail());
    }

    @Test
    public void itsMaterialIsTheDefinitionItsAssetCompiledFirst() {
        CgAssetKinds.register();
        CgMaterialDefinition spark = CgMaterialDefinition.of("test:materials/spark", SPRITE).usage(CgMaterialUsage.SPRITES)
                                                         .blend(CgBlendMode.ADDITIVE).build();
        CgVfxRendererProperties fromFile = CgVfxRenderers.compile("sparks",
                Map.of("id", "r1", "kind", "sprite", "material", "test:materials/spark"),
                (kind, id) -> kind == CgLookKinds.MATERIAL && id.equals(spark.id()) ? spark : null);
        assertSame(spark, fromFile.common().definition());
        CgVfxRendererProperties inJava = CgVfxRenderers.sprite("r1").material(spark).build();
        assertSame(spark, inJava.common().definition());
        assertEquals(fromFile.tree(), inJava.tree());
        IllegalArgumentException wrong = assertThrows(IllegalArgumentException.class, () -> CgVfxRenderers.compile(
                "sparks", Map.of("id", "r1", "kind", "sprite", "material", "test:materials/raw"), (kind, id) -> "a tree"));
        assertTrue(wrong.getMessage(), wrong.getMessage().contains("test:materials/raw"));
    }

    @Test
    public void aMaterialIsDrawnOnlyAsItsUsageSays() {
        CgAssetKinds.register();
        CgMaterialDefinition spark = CgMaterialDefinition.of("test:materials/spark", SPRITE)
                .usage(CgMaterialUsage.SPRITES, CgMaterialUsage.RIBBONS).build();
        assertEquals(EnumSet.of(CgMaterialUsage.SPRITES, CgMaterialUsage.RIBBONS), spark.usage());
        assertEquals(List.of("sprites", "ribbons"), spark.resolved().get("usage"));
        CgVfxRenderers.ribbon("r1").material(spark).build();
        RuntimeException outside = assertThrows(RuntimeException.class,
                () -> CgVfxRenderers.mesh("r1").material(spark).build());
        assertTrue(outside.getMessage(), outside.getMessage().contains("as mesh_particles"));
        RuntimeException unserved = assertThrows(RuntimeException.class,
                () -> CgMaterialDefinition.of("test:materials/raw", SPRITE).build());
        assertTrue("a particle program draws no static mesh: " + unserved.getMessage(),
                unserved.getMessage().contains("static_mesh"));
    }

    @Test
    public void aBindReadsAUserParameterAndNothingElse() {
        assertEquals(Map.of("_Color", "user.ember"), CgVfxRenderers.sprite("r1").bind("_Color", "user.ember").build()
                .common().bind());
        IllegalArgumentException particle = assertThrows(IllegalArgumentException.class,
                () -> CgVfxRenderers.sprite("r1").bind("_Glow", "particles.dynamic_0").build());
        assertTrue(particle.getMessage(), particle.getMessage().contains("cg_dynamic_parameter"));
        IllegalArgumentException fromUser = assertThrows(IllegalArgumentException.class, () -> CgVfxRenderers.compile(
                "sparks", Map.of("id", "r1", "kind", "sprite", "material", Map.of("bind", "user.look"))));
        assertTrue(fromUser.getMessage(), fromUser.getMessage().contains("not built yet"));
    }

    @Test
    public void aRendererBuiltInJavaIsTheOneItsFileLoads() {
        CgVfxRendererProperties sprite = CgVfxRenderers.sprite("r1").material("test:materials/spark")
                .bind("_Color", "user.ember").value("_Soft", 0.5).keyword("SOFT").alignment("velocity").build();
        CgVfxRendererProperties ribbon = CgVfxRenderers.ribbon("r2").material("test:materials/streaks")
                .field("trail", Map.of("max_particles", 12, "tiling_distance", 1.0)).build();
        CgVfxEmitter built = CgVfxEmitter.builder("sparks").renderer(sprite).renderer(ribbon).build();
        Map<String, Object> file = CgVfxEmitterAssets.write(built);
        CgVfxEmitter loaded = CgVfxEmitterAssets.compile("sparks", file, null);
        assertEquals(2, loaded.renderers().size());
        for (int i = 0; i < 2; i++) assertEquals(built.renderers().get(i).tree(), loaded.renderers().get(i).tree());
        CgVfxRendererProperties.Common c = loaded.renderers().get(0).common();
        assertEquals(Map.of("_Color", "user.ember"), c.bind());
        assertEquals(List.of("SOFT"), c.keywords());
        assertEquals(List.of("id", "kind", "material", "bind", "values", "keywords", "alignment"),
                List.copyOf(sprite.tree().keySet()));
        CgAssetKinds.register();
        CgValidation v = CgAssetData.of("test:vfx/emitters/sparks", CgParticleKinds.EMITTER, file, null)
                .validate(CgValidation.Usecase.LOAD);
        assertTrue("the renderers the builder wrote are the declaration's: " + v, v.valid());
    }
}
