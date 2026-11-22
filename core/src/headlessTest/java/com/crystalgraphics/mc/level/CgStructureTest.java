package com.crystalgraphics.mc.level;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.crystalgraphics.mc.block.CgBlockState;
import com.crystalgraphics.mc.entity.CgEntityData;
import com.crystalgraphics.serialization.nbt.CgNbt;
import com.crystalgraphics.serialization.nbt.CgNbtCompound;

/** A structure as vanilla's file holds it: built, written, read back the same; the shapes a structure block saves. */
public class CgStructureTest {

    private static final CgBlockState BRICKS = CgBlockState.parse("minecraft:stone_bricks");
    private static final CgBlockState CHEST = CgBlockState.parse("minecraft:chest[facing=south]{Items:[]}");

    private static CgStructure ring() {
        return CgStructure.builder(7, 2, 7)
                .fill(0, 0, 0, 6, 0, 6, BRICKS)
                .block(3, 1, 3, CHEST)
                .entity(1.5, 1, 1.5, CgEntityData.parse("minecraft:armor_stand{ShowArms:1b}"))
                .build();
    }

    @Test
    public void builtHoldsWhatWasSet() {
        CgStructure ring = ring();
        assertEquals(50, ring.blockCount());
        assertEquals(BRICKS, ring.block(6, 0, 6));
        assertEquals(CHEST, ring.block(3, 1, 3));
        assertNull("a cell never set is the world's", ring.block(0, 1, 0));
        assertNull(ring.block(7, 0, 0));
        assertEquals(1, ring.entities().size());
        List<String> seen = new ArrayList<>();
        ring.forEachBlock((x, y, z, s) -> { if (y == 1) seen.add(x + "," + z + " " + s.block()); });
        assertEquals(List.of("3,3 minecraft:chest"), seen);
    }

    @Test
    public void roundTripsThroughAFile() throws Exception {
        CgStructure ring = ring();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CgNbt.writeBinary(out, ring.toNbt());
        CgStructure back = CgStructure.read(new ByteArrayInputStream(out.toByteArray()));
        assertEquals(ring, back);
        assertEquals("the chest's data travels with its block", CHEST.blockEntity(), back.block(3, 1, 3).blockEntity());
    }

    @Test
    public void writesOnePaletteEntryPerState() {
        CgNbtCompound saved = ring().toNbt();
        assertEquals(2, saved.getList("palette").size());
        assertEquals("minecraft:chest", saved.getList("palette").getCompound(1).getString("Name"));
        assertEquals("south", saved.getList("palette").getCompound(1).getCompound("Properties").getString("facing"));
        assertEquals("a block's data is under nbt, never in the palette", 0,
                saved.getList("palette").getCompound(1).getCompound("Properties").getCompound("Items").size());
    }

    @Test
    public void readsTheFirstOfSeveralPalettes() {
        CgNbtCompound saved = CgNbt.parse("{size:[1,1,1],palettes:[[{Name:\"minecraft:oak_planks\"}],"
                + "[{Name:\"minecraft:spruce_planks\"}]],blocks:[{pos:[0,0,0],state:0}],entities:[],DataVersion:3465}");
        CgStructure s = CgStructure.fromNbt(saved);
        assertEquals(CgBlockState.of("minecraft:oak_planks"), s.block(0, 0, 0));
        assertEquals(3465, s.dataVersion());
        assertEquals(3465, s.toNbt().getInt("DataVersion"));
    }

    @Test
    public void refusesWhatIsNotAStructure() {
        assertThrows(IllegalArgumentException.class, () -> CgStructure.builder(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> CgStructure.builder(2, 2, 2).block(2, 0, 0, BRICKS));
        assertThrows(IllegalArgumentException.class, () -> CgStructure.fromNbt(
                CgNbt.parse("{size:[1,1,1],palette:[],blocks:[{pos:[0,0,0],state:0}]}")));
        assertThrows(IllegalArgumentException.class, () -> CgStructure.load("cgtest:nothing_here"));
    }

    @Test
    public void lastSetWins() {
        CgStructure s = CgStructure.builder(2, 1, 1).block(0, 0, 0, CHEST).fill(0, 0, 0, 1, 0, 0, BRICKS).build();
        assertEquals(BRICKS, s.block(0, 0, 0));
        assertEquals(2, s.blockCount());
    }
}
