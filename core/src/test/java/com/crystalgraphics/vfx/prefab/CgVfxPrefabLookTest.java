package com.crystalgraphics.vfx.prefab;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.render.scene.prefab.CgPrefab;
import com.crystalgraphics.vfx.look.CgVfxParam;
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

/** How a VFX look draws a prefab: a slot a material, keyed values starting at their rest. */
public class CgVfxPrefabLookTest {

    private static final String PART = "crystalgraphics:shaders/vfx/sequence/part.shader";
    private static final CgMaterialDefinition SKIN = CgMaterialDefinition.of("test:skin", PART).build(),
            GLOW = CgMaterialDefinition.of("test:glow", PART).value("_U0_ScaleX", 2f).build();

    private static CgPrefab.Builder core() {
        return CgPrefab.builder("test:core")
                .node("root").part(SKIN, "test:models/root.glb")
                .node("arm").parent("root").part(SKIN, "test:models/arm.glb").part(GLOW, "test:models/arm_glow.glb")
                .node("ring").parent("root").value("_V0_LocY", 0.25f).part(GLOW, "test:models/ring.glb");
    }

    @Test
    public void aMaterialIsOneSlotHoweverManyPartsDrawWithIt_aNodeWithValuesItsOwn() {
        CgPrefab prefab = core().build();
        CgVfxPrefabLook look = CgVfxPrefabLook.of(prefab);
        assertSame("one look a prefab", look, CgVfxPrefabLook.of(prefab));
        int root = prefab.firstPart(prefab.node("root")), arm = prefab.firstPart(prefab.node("arm"));
        int ring = prefab.firstPart(prefab.node("ring"));
        assertEquals(3, look.kit().slots().size());
        assertSame(look.partSlot(root), look.partSlot(arm));
        assertSame(look.partSlot(arm + 1), look.kit().slots().get(1).slot());
        assertNotSame(look.partSlot(arm + 1), look.partSlot(ring));
    }

    @Test
    public void aKeyedValueStartsAtItsRest_theNodesTheMaterialsOrTheShaders() {
        CgPrefab prefab = core().build();
        int ring = prefab.node("ring");
        CgVfxPrefabLook own = CgVfxPrefabLook.of(prefab);
        assertNull(own.param(ring, "_V0_LocY"));
        CgVfxPrefabLook keyed = own.keyed(Map.of("ring", List.of("_V0_LocY", "_U0_ScaleX", "_U0_LocX", "_Color")));
        assertEquals(0.25f, ((CgVfxParam.Scalar) keyed.param(ring, "_V0_LocY")).defaultValue(), 0f);    // the node's own
        assertEquals(2f, ((CgVfxParam.Scalar) keyed.param(ring, "_U0_ScaleX")).defaultValue(), 0f);     // its material's
        assertEquals(0f, ((CgVfxParam.Scalar) keyed.param(ring, "_U0_LocX")).defaultValue(), 0f);       // its shader's
        // A vector keys through a colour's four floats, its rest its shader's (1, 1, 1, 1).
        assertTrue(keyed.param(ring, "_Color") instanceof CgVfxParam.Color);
        assertArrayEquals(new float[] {1f, 1f, 1f, 1f}, keyed.keyedRest(ring, "_Color"), 0f);
        assertEquals(List.of("_V0_LocY", "_U0_ScaleX", "_U0_LocX", "_Color"), keyed.keyedNames(ring));
        assertSame(own, own.keyed(Map.of()));
        assertThrows(CgAssetException.class, () -> own.keyed(Map.of("ring", List.of("_NoSuch"))));
    }

    @Test
    public void aPartALookCannotDrawIsRefusedWhereItWouldDraw_namingIt() {
        CgPrefab body = core().node("body").part(List.of(SKIN, GLOW), "test:models/body.glb", Map.of()).build();
        CgAssetException e = assertThrows(CgAssetException.class, () -> CgVfxPrefabLook.of(body).requireDrawable("test:seq: spawnables.s1"));
        assertTrue(e.getMessage(), e.getMessage().contains("body") && e.getMessage().contains("2 materials"));
        CgPrefab shadowed = CgPrefab.builder("test:shadowed")
                .node("a").part(List.of(SKIN), "test:models/a.glb", Map.of("cast_shadow", true)).build();
        e = assertThrows(CgAssetException.class, () -> CgVfxPrefabLook.of(shadowed).requireDrawable("here"));
        assertTrue(e.getMessage(), e.getMessage().contains("cast_shadow"));
        CgVfxPrefabLook.of(CgPrefab.builder("test:plain").node("a")
                .part(List.of(SKIN), "test:models/a.glb", Map.of("cast_shadow", false)).build()).requireDrawable("here");
    }
}
