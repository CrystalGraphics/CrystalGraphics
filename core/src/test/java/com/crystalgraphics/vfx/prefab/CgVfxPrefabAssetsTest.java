package com.crystalgraphics.vfx.prefab;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.value.CgUnits;
import com.crystalgraphics.vfx.look.CgVfxParam;
import org.joml.Quaternionf;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** A prefab's file and its Java builder are one model; keyed material values start at their rest. */
public class CgVfxPrefabAssetsTest {

    private static final String PART = "crystalgraphics:shaders/vfx/sequence/part.shader";
    private static final CgMaterialDefinition SKIN = CgMaterialDefinition.of("test:skin", PART).build(),
            GLOW = CgMaterialDefinition.of("test:glow", PART).value("_U0_ScaleX", 2f).build();
    private static final CgAssetCompiler.Dependencies DEPS = (kind, id) -> id.equals("test:skin") ? SKIN : id.equals("test:glow") ? GLOW : null;

    private static CgVfxPrefab.Builder core() {
        return CgVfxPrefab.builder("test:core")
                .node("root").elementId("n1").part(SKIN, "test:models/root.glb")
                .node("ring").elementId("n2").parent("root").at(0f, 1f, 0f).rotation(0f, 0.7071068f, 0f, 0.7071068f)
                .scale(2f, 1f, 1f).opacity(0.5f).value("_V0_LocY", 0.25f).part(GLOW, "test:models/ring.glb")
                .node("body").elementId("n3").part(List.of(SKIN, GLOW), "test:models/body.glb", Map.of("cast_shadow", true));
    }

    @Test
    public void aPrefabBuiltInJavaWritesTheFileItLoadsFrom() {
        Map<String, Object> written = CgVfxPrefabAssets.write(core().build());
        CgVfxPrefab loaded = CgVfxPrefabAssets.compile("test:core", written, DEPS);
        assertEquals(written, CgVfxPrefabAssets.write(loaded));
        assertEquals(loaded.node("root"), loaded.parent(loaded.node("ring")));
        assertEquals(0.25f, loaded.values(loaded.node("ring")).get("_V0_LocY")[0], 0f);
        assertEquals(List.of(SKIN, GLOW), loaded.partMaterials(loaded.firstPart(loaded.node("body"))));
    }

    @Test
    public void aNodeWithValuesDrawsItsOwnMaterialAndAKeyedValueStartsAtItsRest() {
        CgVfxPrefab prefab = core().build();
        int root = prefab.node("root"), ring = prefab.node("ring");
        assertNotSame(prefab.partSlot(prefab.firstPart(root)), prefab.partSlot(prefab.firstPart(ring)));
        assertNull(prefab.param(ring, "_V0_LocY"));
        CgVfxPrefab keyed = prefab.keyed(Map.of("ring", List.of("_V0_LocY", "_U0_ScaleX", "_U0_LocX", "_Color")));
        assertEquals(0.25f, ((CgVfxParam.Scalar) keyed.param(ring, "_V0_LocY")).defaultValue(), 0f);    // the node's own
        assertEquals(2f, ((CgVfxParam.Scalar) keyed.param(ring, "_U0_ScaleX")).defaultValue(), 0f);     // its material's
        assertEquals(0f, ((CgVfxParam.Scalar) keyed.param(ring, "_U0_LocX")).defaultValue(), 0f);       // its shader's
        // A vector keys through a colour's four floats, its rest its shader's (1, 1, 1, 1).
        assertTrue(keyed.param(ring, "_Color") instanceof CgVfxParam.Color);
        assertArrayEquals(new float[] {1f, 1f, 1f, 1f}, keyed.keyedRest(ring, "_Color"), 0f);
        assertEquals(List.of("_V0_LocY", "_U0_ScaleX", "_U0_LocX", "_Color"), keyed.keyedNames(ring));
        assertSame(prefab, prefab.keyed(Map.of()));
        assertThrows(CgAssetException.class, () -> prefab.keyed(Map.of("ring", List.of("_NoSuch"))));
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
        CgVfxPrefab goku = CgVfxPrefabAssets.compile("test:goku", Map.of("nodes", List.of(node)), unreal);
        float[] rest = goku.rest(0, new float[10]);
        Quaternionf expected = CgUnits.UNREAL.rotationToHost(0f, 0f, (float) Math.toRadians(-90), new Quaternionf());
        assertEquals(expected.y, rest[4], 1e-6f);
        assertEquals(expected.w, rest[6], 1e-6f);
        CgAssetException e = assertThrows(CgAssetException.class, () -> CgVfxPrefabAssets.compile("test:bad",
                Map.of("nodes", List.of(Map.of("id", "n1", "name", "a", "location", 2.0))), DEPS));
        assertTrue(e.getMessage(), e.getMessage().contains("nodes.n1.location"));
    }

    @Test
    public void aPartItCannotDrawIsRefusedWhereItWouldDrawNamingIt() {
        CgAssetException e = assertThrows(CgAssetException.class, () -> core().build().requireDrawable("test:seq: spawnables.s1"));
        assertTrue(e.getMessage(), e.getMessage().contains("body") && e.getMessage().contains("2 materials"));
        CgVfxPrefab shadowed = CgVfxPrefab.builder("test:shadowed")
                .node("a").part(List.of(SKIN), "test:models/a.glb", Map.of("cast_shadow", true)).build();
        e = assertThrows(CgAssetException.class, () -> shadowed.requireDrawable("here"));
        assertTrue(e.getMessage(), e.getMessage().contains("cast_shadow"));
        CgVfxPrefab.builder("test:plain").node("a").part(List.of(SKIN), "test:models/a.glb", Map.of("cast_shadow", false))
                .build().requireDrawable("here");
    }
}
