package com.crystalgraphics.serialization.nbt;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecs;
import org.junit.After;
import org.junit.Test;

import java.lang.management.ManagementFactory;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;

/**
 * What the NBT layer costs on an item-sized tag (about 30 tags): time and bytes allocated per call, printed so a
 * change can be compared before and after. It asserts only that each operation is right and that none is slower than a
 * generous ceiling, which catches an accidental quadratic, not a few percent.
 */
public class CgNbtCostTest {

    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    @After
    public void untag() {
        CgPlatform.provide(CgNbt.HOST, null);
    }

    record Enchant(String id, int level) {
        static final CgCodec<Enchant> CODEC = CgCodecs.record(
                CgCodecs.field("id", CgCodecs.STRING, Enchant::id),
                CgCodecs.field("lvl", CgCodecs.INT, Enchant::level), Enchant::new);
    }

    /** A sword with a name, lore, enchantments, damage and custom data: what a stack's data looks like in 26.x. */
    static CgNbtCompound item() {
        CgNbtCompound components = CgNbt.compound()
                .put("minecraft:custom_name", "Blade of Dawn")
                .put("minecraft:lore", CgNbtList.of("first line", "second line", "third line"))
                .put("minecraft:enchantments", CgNbt.compound().put("levels", CgNbt.compound()
                        .put("minecraft:sharpness", 5).put("minecraft:unbreaking", 3).put("minecraft:mending", 1)))
                .put("minecraft:damage", 12)
                .put("minecraft:custom_data", CgNbt.compound().put("charge", 3).put("owner", "goku").put("alive", true)
                        .put("seed", new int[]{1, 2, 3, 4}).put("tags", CgNbtList.of("a", "b", "c", "d")));
        CgNbtList enchants = new CgNbtList();
        for (int i = 0; i < 4; i++) enchants.add(CgNbt.encode(Enchant.CODEC, new Enchant("minecraft:e" + i, i + 1)));
        components.put("extra", enchants);
        return CgNbt.compound().put("id", "minecraft:diamond_sword").put("count", 1).put("components", components);
    }

    private interface Op {
        Object run();
    }

    private static long sink;

    private static void measure(String name, double ceilingMicros, Op op) {
        for (int i = 0; i < 30_000; i++) sink += op.run().hashCode();
        int runs = 60_000;
        long id = Thread.currentThread().getId();
        long bytes0 = THREADS.getThreadAllocatedBytes(id);
        long t0 = System.nanoTime();
        for (int i = 0; i < runs; i++) sink += op.run().hashCode();
        long ns = System.nanoTime() - t0;
        long bytes = THREADS.getThreadAllocatedBytes(id) - bytes0;
        double micros = ns / 1000.0 / runs;
        System.out.printf("[nbt-cost] %-22s %8.2f us  %8d B%n", name, micros, bytes / runs);
        if (micros > ceilingMicros) throw new AssertionError(name + " took " + micros + " us, ceiling " + ceilingMicros);
    }

    @Test
    public void whatTheLayerCostsOnAnItem() {
        CgNbtCompound item = item();
        String snbt = item.toString();
        byte[] binary = CgNbt.toBytes(item);
        CgPlatform.provide(CgNbt.HOST, new CgNbtTest.JavaTags());
        Object host = CgNbt.toHost(item);
        Enchant enchant = new Enchant("minecraft:sharpness", 5);
        CgNbtCompound enchantTag = CgNbt.encode(Enchant.CODEC, enchant).asCompound();

        assertEquals(item, CgNbt.parse(snbt));
        assertEquals(item, CgNbt.fromBytes(binary));
        assertEquals(item, CgNbt.<CgNbtCompound>of(host));

        System.out.println("[nbt-cost] item tag " + snbt.length() + " chars SNBT, " + binary.length + " bytes gzipped");
        measure("copy", 200, item::copy);
        measure("toString (SNBT)", 400, item::toString);
        measure("parse (SNBT)", 800, () -> CgNbt.parse(snbt));
        measure("toBytes (gzip)", 3000, () -> CgNbt.toBytes(item));
        measure("fromBytes (gzip)", 3000, () -> CgNbt.fromBytes(binary));
        measure("toHost (stand-in)", 300, () -> CgNbt.toHost(item));
        measure("of (stand-in)", 300, () -> CgNbt.of(host));
        measure("encode record", 100, () -> CgNbt.encode(Enchant.CODEC, enchant));
        measure("decode record", 100, () -> CgNbt.decode(Enchant.CODEC, enchantTag));
        measure("at(path)", 50, () -> item.at("components.minecraft:enchantments.levels.minecraft:sharpness"));
        measure("getInt x4", 50, () -> item.getCompound("components").getInt("minecraft:damage")
                + item.getInt("count") + item.getCompound("components").getCompound("minecraft:custom_data").getInt("charge"));
        measure("equals", 200, () -> item.equals(item.copy()));
        if (sink == 42) System.out.println();
        assertEquals(Arrays.asList(4), Arrays.asList(item.at("components.extra").asList().size()));
    }
}
