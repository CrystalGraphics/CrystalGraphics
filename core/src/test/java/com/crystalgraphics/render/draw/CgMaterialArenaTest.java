package com.crystalgraphics.render.draw;

import com.crystalgraphics.render.shader.CgBindingModel;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;
import com.crystalgraphics.api.texture.CgTexture;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/** Materials of one arena share their snapshot's bindings and keep slots of their own; anything else tells them apart. */
public class CgMaterialArenaTest {

    private static final CgBindingModel.Slot POINT = CgBindingModel.MATERIAL_BLOCK;
    private static final CgTexture IMAGE = new CgTexture() {
        @Override public CgTextureView rhiView() { return null; }
        @Override public int getWidth() { return 4; }
        @Override public int getHeight() { return 4; }
    };

    private static CgBlockVersion block(CgMaterialArena arena, float r) {
        return CgBlockVersion.in(arena, new float[] {r, 0, 0, 1}, 4);
    }

    @Test
    public void twoMaterialsOfOneArenaShareAndTakeTheirOwnSlots() {
        CgMaterialArena arena = new CgMaterialArena("tint", 4);
        CgBindingTable table = new CgBindingTable();
        int red = table.begin().block(POINT, block(arena, 1)).end();
        int blue = table.begin().block(POINT, block(arena, 0)).end();
        assertNotEquals("each its own snapshot", red, blue);
        assertEquals("one share", table.share(red), table.share(blue));
        assertNotEquals(table.slotOf(red), table.slotOf(blue));
        assertEquals("asked again, the same slot", table.slotOf(red), table.slotOf(red));
    }

    @Test
    public void aTextureOrAnotherArenaTellsThemApart() {
        CgMaterialArena arena = new CgMaterialArena("tint", 4), other = new CgMaterialArena("other", 4);
        CgBindingTable table = new CgBindingTable();
        int plain = table.begin().block(POINT, block(arena, 1)).end();
        int textured = table.begin().block(POINT, block(arena, 0)).texture(0, IMAGE).end();
        int elsewhere = table.begin().block(POINT, block(other, 0)).end();
        int page = table.begin().block(POINT, CgBlockVersion.of(new float[] {0, 0, 0, 1}, 4)).end();
        assertNotEquals(table.share(plain), table.share(textured));
        assertNotEquals(table.share(plain), table.share(elsewhere));
        assertEquals("a block of its own shares with nothing", page, table.share(page));
    }

    @Test
    public void aSnapshotWithNoArenaBlockNamesSlotZero() {
        CgBindingTable table = new CgBindingTable();
        int id = table.begin().texture(0, IMAGE).end();
        assertEquals(0, table.slotOf(id));
        assertEquals(id, table.share(id));
    }

    @Test
    public void aSlotIsTheBlockRoundedToTheStorageAlignment() {
        assertEquals(256, CgMaterialArena.stride(1));
        assertEquals(256, CgMaterialArena.stride(64));
        assertEquals(512, CgMaterialArena.stride(65));
    }
}
