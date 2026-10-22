package com.crystalgraphics.vfx.render;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxSlot;
import com.crystalgraphics.vfx.system.CgVfxSystemDefinition;
import com.crystalgraphics.vfx.system.CgVfxSystemInstance;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CgVfxSortGroupTest {

    private static final String PART = "crystalgraphics:shaders/vfx/sequence/part.shader";
    private static final CgVfxSlot BODY = CgVfxSlot.mesh("test:sortgroup/body");

    private static CgVfxSlotRenderer slot(String name, int hint) {
        return CgVfxSlotRenderer.of(name, BODY)
                .material(CgMaterialDefinition.of("test:sortgroup/" + name, PART).order(hint).build())
                .build();
    }

    @Test
    public void twoLooksRankTheirDrawsByHintTogether() {
        int surface = CgVfxLook.SURFACE;
        CgVfxSlotRenderer a1 = slot("a1", surface + 10), a2 = slot("a2", surface + 30), b1 = slot("b1", surface + 20),
                b2 = slot("b2", surface + 30), a0 = slot("a0", 50);
        CgVfxLook a = CgVfxLook.builder(CgVfxSystemDefinition.SCHEMA).slot(a2).slot(a1).slot(a0).build();
        CgVfxLook b = CgVfxLook.builder(CgVfxSystemDefinition.SCHEMA).slot(b2).slot(b1).build();

        CgVfxSortGroup group = new CgVfxSortGroup();
        CgVfxSystemInstance<?> first = new CgVfxSystemInstance<>(a, 0, 0, 0).sortGroup(group);
        CgVfxSystemInstance<?> second = new CgVfxSystemInstance<>(b, 10, 0, 0).sortGroup(group);
        assertEquals(2, group.members());

        // The smoke band first; then surfaces by hint across both looks, the earlier look first on a tie
        assertEquals(0, rank(group, a, a0));
        assertEquals(1, rank(group, a, a1));
        assertEquals(2, rank(group, b, b1));
        assertEquals(3, rank(group, a, a2));
        assertEquals(4, rank(group, b, b2));

        assertEquals("at its members' middle", 5.0, group.x(1), 0.0);
        group.at(2, 3, 4);
        assertEquals(2.0, group.x(2), 0.0);
        assertEquals(4.0, group.z(2), 0.0);

        second.sortGroup(null);
        assertEquals(1, group.members());
        assertEquals(group, first.sortGroup());
    }

    private static int rank(CgVfxSortGroup group, CgVfxLook look, CgVfxSlotRenderer slot) {
        for (CgVfxLook.Placed p : look.placed()) if (p.slotRenderer() == slot) return group.order(look, p);
        throw new AssertionError(slot + " is not in the look");
    }
}
