package com.crystalgraphics.render.draw;

import com.crystalgraphics.render.shader.CgBindingModel;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;
import com.crystalgraphics.api.texture.CgTexture;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/** Materials of one arena share their bindings' set and keep slots of their own; anything else tells them apart. */
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
        CgFrameBindings table = new CgFrameBindings();
        int red = table.add(CgMaterialBindings.builder().block(POINT, block(arena, 1)).build());
        int blue = table.add(CgMaterialBindings.builder().block(POINT, block(arena, 0)).build());
        assertNotEquals("each its own entry", red, blue);
        assertEquals("one share", table.share(red), table.share(blue));
        assertNotEquals(table.slotOf(red), table.slotOf(blue));
        assertEquals("asked again, the same slot", table.slotOf(red), table.slotOf(red));
    }

    @Test
    public void aTextureOrAnotherArenaTellsThemApart() {
        CgMaterialArena arena = new CgMaterialArena("tint", 4), other = new CgMaterialArena("other", 4);
        CgFrameBindings table = new CgFrameBindings();
        int plain = table.add(CgMaterialBindings.builder().block(POINT, block(arena, 1)).build());
        int textured = table.add(CgMaterialBindings.builder().block(POINT, block(arena, 0)).texture(0, IMAGE).build());
        int elsewhere = table.add(CgMaterialBindings.builder().block(POINT, block(other, 0)).build());
        int page = table.add(CgMaterialBindings.builder().block(POINT, CgBlockVersion.of(new float[] {0, 0, 0, 1}, 4)).build());
        assertNotEquals(table.share(plain), table.share(textured));
        assertNotEquals(table.share(plain), table.share(elsewhere));
        assertEquals("a block of its own shares with nothing", page, table.share(page));
    }

    @Test
    public void bindingsWithNoArenaBlockNameSlotZero() {
        CgFrameBindings table = new CgFrameBindings();
        int id = table.add(CgMaterialBindings.builder().texture(0, IMAGE).build());
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
