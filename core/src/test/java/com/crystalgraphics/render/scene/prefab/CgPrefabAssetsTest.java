package com.crystalgraphics.render.scene.prefab;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.value.CgUnits;
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
}
