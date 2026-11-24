package com.crystalgraphics.mc;

import java.util.List;

import com.crystalgraphics.mc.block.CgBlockState;
import com.crystalgraphics.mc.entity.CgEntityData;
import com.crystalgraphics.mc.fluid.CgFluidStack;
import com.crystalgraphics.mc.item.CgIngredient;
import com.crystalgraphics.mc.item.CgItemStack;
import com.crystalgraphics.mc.item.CgItems;
import com.crystalgraphics.mc.level.CgBlockBox;
import com.crystalgraphics.mc.level.CgBlockPos;
import com.crystalgraphics.mc.level.CgDirection;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.nbt.CgNbt;
import com.crystalgraphics.serialization.nbt.CgNbtCompound;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Every value parsed from its command's text, printed back, and read again unchanged; and what each refuses. */
public class CgCommandValuesTest {

    @After
    public void noHost() {
        CgPlatform.provide(CgItems.HOST, CgItems.Host.NONE);
    }

    @Test
    public void blockStateParsesPrintsAndRoundTrips() {
        CgBlockState s = CgBlockState.parse("furnace[lit=true, facing=east]{BurnTime:200s}");
        assertEquals("minecraft:furnace", s.block());
        assertEquals("east", s.property("facing"));
        assertEquals(200, s.blockEntity().getInt("BurnTime"));
        assertEquals("minecraft:furnace[facing=east,lit=true]{BurnTime:200s}", s.toString());
        assertEquals(s, CgBlockState.parse(s.toString()));
        assertEquals(s, CgNbt.decode(CgBlockState.CODEC, CgNbt.encode(CgBlockState.CODEC, s)));
        assertEquals(CgBlockState.of("minecraft:stone"), CgBlockState.parse("stone[]"));
    }

    @Test
    public void blockStateRefusesNamingThePosition() {
        CgCodecException e = assertThrows(CgCodecException.class, () -> CgBlockState.parse("stone[lit=]"));
        assertTrue(e.getMessage(), e.getMessage().contains("at 10"));
        assertThrows(CgCodecException.class, () -> CgBlockState.parse("stone[a=1,a=2]"));
        assertThrows(CgCodecException.class, () -> CgBlockState.parse("stone extra"));
    }

    @Test
    public void itemReadsEachVersionsGiveSyntax() {
        CgItemStack modern = CgItemStack.parse("diamond_sword[damage=9,!food,custom_name='\"Excalibur\"'] 2");
        assertEquals("minecraft:diamond_sword", modern.item());
        assertEquals(2, modern.count());
        assertEquals(9, modern.damage());
        assertTrue(modern.has("!minecraft:food"));
        assertEquals(modern, CgItemStack.parse(modern.toString()));

        CgItemStack tagged = CgItemStack.parse("minecraft:diamond_sword{Damage:9} 2");      // 1.13 to 1.20.4
        assertEquals(9, tagged.damage());
        assertEquals(2, tagged.count());

        CgItemStack legacy = CgItemStack.parse("minecraft:wool 3 14");                     // before 1.13
        assertEquals(3, legacy.count());
        assertEquals(14, legacy.damage());

        assertEquals(CgItemStack.of("minecraft:stone", 1), CgItemStack.parse("stone"));
        assertTrue(CgItemStack.parse(CgItemStack.EMPTY.toString()).isEmpty());
        assertThrows(CgCodecException.class, () -> CgItemStack.parse("stone{a:1} 1 2"));
    }

    @Test
    public void itemWritesTheRunningVersionsOwnSyntax() {
        CgItemStack sword = CgItemStack.of("minecraft:diamond_sword", 2).withDamage(9);
        assertEquals("minecraft:diamond_sword[minecraft:damage=9] 2", sword.toCommand());   // no game: 1.20.5's

        CgPlatform.provide(CgItems.HOST, shaped(CgItems.Shape.TAG));
        assertEquals("minecraft:diamond_sword{Damage:9} 2", sword.toCommand());
        assertEquals(sword, CgItemStack.parse(sword.toCommand()));

        CgPlatform.provide(CgItems.HOST, shaped(CgItems.Shape.LEGACY));
        assertEquals("minecraft:diamond_sword 2 9", sword.toCommand());
        assertEquals(sword, CgItemStack.parse(sword.toCommand()));
    }

    @Test
    public void entityDataIsSummonsText() {
        CgEntityData sheep = CgEntityData.parse("sheep{Color:2b}");
        assertEquals("minecraft:sheep", sheep.type());
        assertEquals("minecraft:sheep{Color:2b}", sheep.toString());
        assertEquals(sheep, CgEntityData.parse(sheep.toString()));
        CgNbtCompound saved = sheep.toNbt();
        assertEquals("minecraft:sheep", saved.getString("id"));
        assertEquals(sheep, CgEntityData.fromNbt(saved));
        assertFalse(sheep.data().has("id"));
    }

    @Test
    public void fluidKeepsDropletsExact() {
        CgFluidStack water = CgFluidStack.parse("water 250");
        assertEquals(250, water.millibuckets());
        assertEquals(250 * CgFluidStack.DROPLETS_PER_MILLIBUCKET, water.droplets());
        assertEquals(1000, CgFluidStack.parse("minecraft:lava").millibuckets());
        CgFluidStack third = CgFluidStack.parse("minecraft:water 27d");
        assertEquals(27, third.droplets());
        assertEquals("minecraft:water 27d", third.toString());
        assertEquals(third, CgFluidStack.parse(third.toString()));
        assertEquals(water, CgFluidStack.parse(water.toString()));
    }

    @Test
    public void positionsPackAsTheGameDoes() {
        CgBlockPos p = CgBlockPos.parse(" 1 2 3");
        assertEquals((1L << 38) | (3L << 12) | 2L, p.asLong());          // BlockPos.asLong, 1.14 on
        for (CgBlockPos q : List.of(p, new CgBlockPos(-30000000, -2048, 29999999), new CgBlockPos(-1, -1, -1))) {
            assertEquals(q, CgBlockPos.of(q.asLong()));
        }
        assertEquals(p, CgBlockPos.parse(p.toString()));
        assertEquals(new CgBlockPos(1, 2, 2), p.relative(CgDirection.NORTH));
        assertEquals(CgDirection.SOUTH, CgDirection.parse("NORTH").opposite());

        CgBlockBox box = CgBlockBox.parse("9 70 9 0 64 0");
        assertEquals(new CgBlockPos(0, 64, 0), box.min());
        assertEquals(10 * 7 * 10, box.sizeX() * box.sizeY() * box.sizeZ());
        assertEquals(box, CgBlockBox.parse(box.toString()));
    }

    @Test
    public void ingredientIsATagOrAlternatives() {
        CgIngredient planks = CgIngredient.parse("#planks");
        assertEquals("minecraft:planks", planks.tag());
        assertEquals("#minecraft:planks", planks.toString());
        assertTrue(planks.items().isEmpty());                              // no game, no tag contents

        CgIngredient either = CgIngredient.parse("coal | stone[custom_name='\"a|b\"']");
        assertEquals(2, either.stacks().size());
        assertEquals(either, CgIngredient.parse(either.toString()));
        assertTrue(either.test(CgItemStack.of("minecraft:coal", 5)));
    }

    private static CgItems.Host shaped(CgItems.Shape shape) {
        return new CgItems.Host() {
            @Override public boolean isStack(Object o) { return false; }
            @Override public CgNbtCompound save(Object stack) { throw new UnsupportedOperationException(); }
            @Override public Object load(CgNbtCompound saved) { throw new UnsupportedOperationException(); }
            @Override public int maxDamage(Object stack) { return 0; }
            @Override public int maxStackSize(Object stack) { return 64; }
            @Override public int stamp(Object stack) { return 0; }
            @Override public CgItems.Shape shape() { return shape; }
            @Override public String name(int itemId) { return null; }
            @Override public boolean inTag(int itemId, String tag) { return false; }
        };
    }
}
