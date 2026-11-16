package com.crystalgraphics.render.scene.prefab;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.value.CgUnits;
import com.crystalgraphics.render.post.settings.CgPostSettings;
import org.joml.Quaternionf;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** A prefab's file and its Java builder are one model. */
public class CgPrefabAssetsTest {

    private static final String PART = "crystalgraphics:shaders/vfx/sequence/part.shader";
    private static final CgMaterialDefinition SKIN = CgMaterialDefinition.of("test:skin", PART).build(),
            GLOW = CgMaterialDefinition.of("test:glow", PART).value("_U0_ScaleX", 2f).build();
    private static final CgAssetCompiler.Dependencies DEPS = (kind, id) -> id.equals("test:skin") ? SKIN : id.equals("test:glow") ? GLOW : null;

    @Test
    public void aPrefabBuiltInJavaWritesTheFileItLoadsFrom() {
        CgPrefab built = CgPrefab.builder("test:core")
                .node("root").elementId("n1").part(SKIN, "test:models/root.glb")
                .node("ring").elementId("n2").parent("root").at(0f, 1f, 0f).rotation(0f, 0.7071068f, 0f, 0.7071068f)
                .scale(2f, 1f, 1f).opacity(0.5f).value("_V0_LocY", 0.25f).part(GLOW, "test:models/ring.glb")
                .node("body").elementId("n3").part(List.of(SKIN, GLOW), "test:models/body.glb", Map.of("cast_shadow", true))
                .build();
        Map<String, Object> written = CgPrefabAssets.write(built);
        CgPrefab loaded = CgPrefabAssets.compile("test:core", written, DEPS);
        assertEquals(written, CgPrefabAssets.write(loaded));
        assertEquals(loaded.node("root"), loaded.parent(loaded.node("ring")));
        assertEquals(0.25f, loaded.values(loaded.node("ring")).get("_V0_LocY")[0], 0f);
        assertEquals(List.of(SKIN, GLOW), loaded.partMaterials(loaded.firstPart(loaded.node("body"))));
    }

    @Test
    public void aSkeletalNode_aSocketParent_andAnEffect_writeTheFileTheyLoadFrom() {
        CgPrefab built = CgPrefab.builder("test:wyvern")
                .node("body").elementId("n1").skeletal("test:geo/wyvern.glb", List.of(SKIN)).play("idle")
                .node("membranes").elementId("n2").skeletal("test:geo/membranes.glb", List.of(GLOW, SKIN)).followPose("body")
                .node("flame").elementId("n3").parent("body", "jaw").effect("test:vfx/breath", false)
                .node("aura").elementId("n4").effect("test:vfx/aura", true)
                .build();
        Map<String, Object> written = CgPrefabAssets.write(built);
        CgPrefab loaded = CgPrefabAssets.compile("test:wyvern", written, DEPS);
        assertEquals(written, CgPrefabAssets.write(loaded));
        int flame = loaded.node("flame");
        assertEquals("jaw", loaded.parentSocket(flame));
        assertEquals(new CgPrefab.Effect("test:vfx/breath", false), loaded.effect(flame));
        assertEquals("body", loaded.skeletal(loaded.node("membranes")).followPose());
        assertEquals(List.of(GLOW, SKIN), loaded.skeletal(loaded.node("membranes")).materials());
        assertTrue(loaded.effect(loaded.node("aura")).auto());
    }

    @Test
    public void aPostNode_bound_andUnbound_writesTheFileItLoadsFrom() {
        CgPrefab built = CgPrefab.builder("test:breath")
                .node("body").elementId("n1").skeletal("test:geo/wyvern.glb", List.of(SKIN))
                .node("breath_look").elementId("n2").parent("body", "jaw")
                .post(new CgPostSettings().bloom(2f).vignette(0.3f), 20f, 12f, 0f, 3)
                .node("mood").elementId("n3").post(new CgPostSettings().chromatic(1f), Float.POSITIVE_INFINITY, 0f, 1f, 0)
                .build();
        Map<String, Object> written = CgPrefabAssets.write(built);
        CgPrefab loaded = CgPrefabAssets.compile("test:breath", written, DEPS);
        assertEquals(written, CgPrefabAssets.write(loaded));
        CgPrefab.Post look = loaded.post(loaded.node("breath_look"));
        assertEquals(20f, look.radius(), 0f);
        assertEquals(12f, look.blend(), 0f);
        assertEquals(0f, look.weight(), 0f);
        assertEquals(3, look.priority());
        assertEquals(2f, look.settings().bloom(), 0f);
        assertTrue(loaded.post(loaded.node("mood")).unbound());
        assertTrue(loaded.hasSceneNodes());
    }

    @Test
    public void anUnrealRotatorByKeyTurnsTheNodeAndAShapeItCannotReadFailsNamingIt() {
        CgAssetCompiler.Dependencies unreal = new CgAssetCompiler.Dependencies() {
            @Override public Object definition(CgType kind, String id) {
                return DEPS.definition(kind, id);
            }
            @Override public CgUnits units() {
                return CgUnits.UNREAL;
            }
        };
        Map<String, Object> node = Map.of("id", "n1", "name", "body", "rotation", Map.of("pitch", 0.0, "yaw", -90.0, "roll", 0.0));
        CgPrefab goku = CgPrefabAssets.compile("test:goku", Map.of("nodes", List.of(node)), unreal);
        float[] rest = goku.rest(0, new float[CgPrefab.REST]);
        Quaternionf expected = CgUnits.UNREAL.rotationToHost(0f, 0f, (float) Math.toRadians(-90), new Quaternionf());
        assertEquals(expected.y, rest[4], 1e-6f);
        assertEquals(expected.w, rest[6], 1e-6f);
        CgAssetException e = assertThrows(CgAssetException.class, () -> CgPrefabAssets.compile("test:bad",
                Map.of("nodes", List.of(Map.of("id", "n1", "name", "a", "location", 2.0))), DEPS));
        assertTrue(e.getMessage(), e.getMessage().contains("nodes.n1.location"));
    }

    @Test
    public void fogAndLightNodes_writeTheFileTheyLoadFrom() {
        CgPrefab built = CgPrefab.builder("test:arena")
                .node("anchor").elementId("n1")
                .node("haze").elementId("n2").parent("anchor").at(0f, 80f, 0f)
                .fog(new CgPrefab.Fog(2e-4f, 0.01f, 0.41f, 0.89f, 1f, 0.65f, 300f, 0f))
                .node("lamp").elementId("n3").parent("anchor").at(1f, 2f, 3f)
                .light(new CgPrefab.Light(CgPrefab.Light.Kind.SPOT, 0xFFFF8040, 8f, 12f, 0f, 0.5f, false, 1f, 10f, 30f, 0f))
                .build();
        Map<String, Object> written = CgPrefabAssets.write(built);
        CgPrefab loaded = CgPrefabAssets.compile("test:arena", written, DEPS);
        assertEquals(written, CgPrefabAssets.write(loaded));
        assertEquals(built.fog(built.node("haze")), loaded.fog(loaded.node("haze")));
        assertEquals(built.light(built.node("lamp")), loaded.light(loaded.node("lamp")));
        assertTrue(loaded.hasSceneNodes());
        CgAssetException e = assertThrows(CgAssetException.class, () -> CgPrefabAssets.compile("test:bad",
                Map.of("nodes", List.of(Map.of("id", "n1", "name", "a", "light", Map.of("kind", "rect")))), DEPS));
        assertTrue(e.getMessage(), e.getMessage().contains("light.kind"));
    }
}
