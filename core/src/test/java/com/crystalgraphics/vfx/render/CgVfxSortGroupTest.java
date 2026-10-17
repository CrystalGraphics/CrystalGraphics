package com.crystalgraphics.vfx.render;

import com.crystalgraphics.vfx.effect.CgVfxBurst;
import com.crystalgraphics.vfx.look.CgVfxLayer;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxSlot;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CgVfxSortGroupTest {

    private static final CgVfxSlot BODY = CgVfxSlot.mesh("test:sortgroup/body");

    private static CgVfxLayer layer(String name, int hint) {
        return CgVfxLayer.of("test:" + name + ".shader").on(BODY).order(hint).build();
    }

    @Test
    public void twoLooksRankTheirLayersByRoleThenHintTogether() {
        CgVfxLayer a1 = layer("a1", 10), a2 = layer("a2", 30), b1 = layer("b1", 20), b2 = layer("b2", 30);
        CgVfxLayer a0 = CgVfxLayer.of("test:a0.shader").on(BODY).order(50).role(CgVfxLayer.Role.SMOKE).build();
        CgVfxLook a = CgVfxLook.builder(CgVfxBurst.SCHEMA).layer(a2).layer(a1).layer(a0).build();
        CgVfxLook b = CgVfxLook.builder(CgVfxBurst.SCHEMA).layer(b2).layer(b1).build();

        CgVfxSortGroup group = new CgVfxSortGroup();
        CgVfxBurst first = new CgVfxBurst(a, 0, 0, 0).sortGroup(group);
        CgVfxBurst second = new CgVfxBurst(b, 10, 0, 0).sortGroup(group);
        assertEquals(2, group.members());

        // Smoke first by role; then surfaces by hint across both looks, the earlier look first on a tie
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

    private static int rank(CgVfxSortGroup group, CgVfxLook look, CgVfxLayer layer) {
        for (CgVfxLook.Placed p : look.placed()) if (p.layer() == layer) return group.order(look, p);
        throw new AssertionError(layer + " is not in the look");
    }
}
