package com.crystalgraphics.vfx.prefab;

import com.crystalgraphics.vfx.look.CgVfxLayer;
import org.joml.Vector3f;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public class CgVfxPrefabTest {

    private static final float HALF_TURN_Y = (float) Math.sqrt(0.5);

    private static final CgVfxLayer.Builder SKIN = CgVfxLayer.of("test:skin.shader"), GLOW = CgVfxLayer.of("test:glow.shader");

    private static CgVfxPrefab chain() {
        return CgVfxPrefab.builder("test:chain")
                .node("root").at(1f, 0f, 0f)
                .node("arm").parent("root").at(0f, 2f, 0f).rotation(0f, HALF_TURN_Y, 0f, HALF_TURN_Y).scale(2f).opacity(0.5f)
                .part(SKIN)
                .node("hand").parent("arm").at(1f, 0f, 0f).opacity(0.5f).part(SKIN).part(GLOW)
                .build();
    }

    @Test
    public void childrenComposeWithTheirParentsAndOpacityMultipliesDown() {
        CgVfxPrefab prefab = chain();
        CgVfxPrefabInstance instance = new CgVfxPrefabInstance(prefab);
        instance.evaluate();
        int hand = prefab.node("hand");

        // hand's origin: arm turned a quarter about +y and doubled, so its +x of 1 lands at -z 2, from arm at (1, 2, 0)
        Vector3f at = instance.world(hand).getTranslation(new Vector3f());
        assertEquals(1f, at.x, 1e-5f);
        assertEquals(2f, at.y, 1e-5f);
        assertEquals(-2f, at.z, 1e-5f);
        assertEquals(0.25f, instance.presence(hand), 1e-6f);

        instance.opacity(prefab.node("root"), 0f).evaluate();
        assertEquals("a parent's opacity fades its subtree", 0f, instance.presence(hand), 0f);
        instance.reset();
        instance.evaluate();
        assertEquals(0.25f, instance.presence(hand), 1e-6f);
    }

    @Test
    public void aMaterialIsOneLayerHoweverManyPartsDrawWithIt() {
        CgVfxPrefab prefab = chain();
        int arm = prefab.node("arm"), hand = prefab.node("hand");
        assertEquals(0, prefab.firstPart(arm) - prefab.firstPart(prefab.node("root")));
        assertEquals(2, prefab.firstPart(hand + 1) - prefab.firstPart(hand));
        assertEquals(2, prefab.kit().layers().size());
        assertSame(prefab.partSlot(prefab.firstPart(arm)), prefab.partSlot(prefab.firstPart(hand)));
        assertSame(prefab.partSlot(prefab.firstPart(hand) + 1), prefab.kit().layers().get(1).slots().get(0));
        assertNull(prefab.partMesh(prefab.firstPart(arm)));
    }

    @Test
    public void parentsComeFirstAndNamesAreUnique() {
        assertThrows(IllegalArgumentException.class,
                () -> CgVfxPrefab.builder("test:bad").node("a").parent("b").build());
        assertThrows(IllegalArgumentException.class,
                () -> CgVfxPrefab.builder("test:bad").node("a").node("a").build());
        assertThrows(IllegalStateException.class, () -> CgVfxPrefab.builder("test:bad").at(0f, 0f, 0f));
    }
}
