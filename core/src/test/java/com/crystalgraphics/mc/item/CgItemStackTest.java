package com.crystalgraphics.mc.item;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.CgPlainOps;
import com.crystalgraphics.serialization.nbt.CgNbt;
import com.crystalgraphics.serialization.nbt.CgNbtCompound;
import com.crystalgraphics.serialization.nbt.CgNbtTag;
import org.junit.After;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** A stack as data on every era: each shape's saved form read through the facets and rebuilt exactly. */
public class CgItemStackTest {

    /** The game's stack as a test sees it: just the saved form. */
    private record Fake(CgNbtCompound saved) {
    }

    private static CgItems.Host host(CgItems.Shape shape) {
        return new CgItems.Host() {
            @Override public boolean isStack(Object o) { return o instanceof Fake; }
            @Override public CgNbtCompound save(Object stack) { return ((Fake) stack).saved().copy(); }
            @Override public Object load(CgNbtCompound saved) { return new Fake(saved.copy()); }
            @Override public int maxDamage(Object stack) { return 1561; }
            @Override public int maxStackSize(Object stack) { return 1; }
            @Override public int stamp(Object stack) { return ((Fake) stack).saved().hashCode(); }
            @Override public CgItems.Shape shape() { return shape; }
            @Override public String name(int itemId) { return null; }
            @Override public boolean inTag(int itemId, String tag) { return false; }
        };
    }

    private static void use(CgItems.Shape shape) {
        CgPlatform.provide(CgItems.HOST, host(shape));
    }

    @After
    public void noHost() {
        CgPlatform.provide(CgItems.HOST, null);
    }

    private static final String LEGACY_SWORD = "{id:\"minecraft:diamond_sword\",Count:1b,Damage:5s,tag:{display:{Name:\"§bExcalibur\","
            + "Lore:[\"first\",\"second\"],color:255},ench:[{id:16s,lvl:5s},{id:20s,lvl:2s}],Unbreakable:1b,RepairCost:7,mymod:{charge:3}}}";

    private static final String TAG_SWORD = "{id:\"minecraft:diamond_sword\",Count:1b,tag:{Damage:5,display:{Name:'{\"text\":\"Excalibur\",\"color\":\"aqua\"}',"
            + "Lore:['\"first\"','\"second\"'],color:255},Enchantments:[{id:\"minecraft:sharpness\",lvl:5s},{id:\"minecraft:fire_aspect\",lvl:2s}],"
            + "Unbreakable:1b,RepairCost:7,mymod:{charge:3}}}";

    private static final String COMPONENT_SWORD = "{id:\"minecraft:diamond_sword\",count:1,components:{\"minecraft:damage\":5,"
            + "\"minecraft:custom_name\":{text:\"Excalibur\",color:\"aqua\"},\"minecraft:lore\":[\"first\",\"second\"],"
            + "\"minecraft:enchantments\":{\"minecraft:sharpness\":5,\"minecraft:fire_aspect\":2},\"minecraft:unbreakable\":{},"
            + "\"minecraft:repair_cost\":7,\"minecraft:custom_data\":{mymod:{charge:3}}}}";

    private static CgItemStack read(CgItems.Shape shape, String snbt) {
        use(shape);
        return CgItemStack.of(new Fake(CgNbt.parse(snbt)));
    }

    @Test
    public void everyEraReadsTheSameFacets() {
        for (Object[] era : new Object[][]{{CgItems.Shape.LEGACY, LEGACY_SWORD}, {CgItems.Shape.TAG, TAG_SWORD}, {CgItems.Shape.COMPONENTS, COMPONENT_SWORD}}) {
            CgItems.Shape shape = (CgItems.Shape) era[0];
            CgItemStack s = read(shape, (String) era[1]);
            assertEquals(shape.name(), "minecraft:diamond_sword", s.item());
            assertEquals(shape.name(), 1, s.count());
            assertEquals(shape.name(), 5, s.damage());
            assertEquals(shape.name(), 1561, s.maxDamage());
            assertEquals(shape.name(), 1, s.maxStackSize());
            assertTrue(shape.name(), s.isDamaged());
            assertEquals(shape.name(), shape == CgItems.Shape.LEGACY ? "§bExcalibur" : "Excalibur", s.name());
            assertEquals(shape.name(), List.of("first", "second"), s.lore());
            assertTrue(shape.name(), s.isUnbreakable());
            assertEquals(shape.name(), 3, s.customData().getCompound("mymod").getInt("charge"));
            assertEquals(shape.name(), 7, s.component("minecraft:repair_cost").asValue().asInt());
            Map<String, Integer> enchants = s.enchantments();
            assertEquals(shape.name(), 2, enchants.size());
            assertEquals(shape.name(), 5, enchants.values().iterator().next().intValue());
        }
    }

    @Test
    public void legacyEnchantmentsAreNumbersAndModernOnesNames() {
        assertEquals(5, read(CgItems.Shape.LEGACY, LEGACY_SWORD).enchantment("16"));
        assertEquals(5, read(CgItems.Shape.TAG, TAG_SWORD).enchantment("minecraft:sharpness"));
        assertEquals(2, read(CgItems.Shape.COMPONENTS, COMPONENT_SWORD).enchantment("minecraft:fire_aspect"));
    }

    @Test
    public void everyEraRebuildsItsOwnSavedFormExactly() {
        for (Object[] era : new Object[][]{{CgItems.Shape.LEGACY, LEGACY_SWORD}, {CgItems.Shape.TAG, TAG_SWORD}, {CgItems.Shape.COMPONENTS, COMPONENT_SWORD}}) {
            CgItems.Shape shape = (CgItems.Shape) era[0];
            CgItemStack s = read(shape, (String) era[1]);
            Fake back = s.toHost();
            assertEquals(shape.name(), CgNbt.parse((String) era[1]), back.saved());
        }
    }

    @Test
    public void everyKeyOfTheOldTagSurvivesAnUnknownOneIncluded() {
        String saved = "{id:\"minecraft:stick\",Count:3b,Damage:0s,tag:{HideFlags:63,display:{color:1,Name:\"Rod\"},mymod:{a:1b}}}";
        CgItemStack s = read(CgItems.Shape.LEGACY, saved);
        assertEquals(63, s.customData().getInt("HideFlags"));
        assertEquals(1, s.customData().getCompound("display").getInt("color"));
        assertEquals("Rod", s.name());
        assertFalse(s.customData().getCompound("display").has("Name"));
        assertEquals(CgNbt.parse(saved), ((Fake) s.toHost()).saved());
    }

    @Test
    public void editsAnswerNewStacksAndLeaveTheOldAlone() {
        CgItemStack stone = CgItemStack.of("minecraft:stone", 16);
        CgItemStack named = stone.withName("Rock").withLore(List.of("heavy")).withEnchantment("minecraft:unbreaking", 3)
                .withDamage(2).withCustomData(d -> d.put("mymod:charge", 9)).withCount(4);
        assertNull(stone.name());
        assertEquals("Rock", named.name());
        assertEquals(List.of("heavy"), named.lore());
        assertEquals(3, named.enchantment("minecraft:unbreaking"));
        assertEquals(2, named.damage());
        assertEquals(9, named.customData().getInt("mymod:charge"));
        assertEquals(4, named.count());
        assertEquals(0, named.withEnchantment("minecraft:unbreaking", 0).enchantments().size());
        assertFalse(named.withCustomData(d -> d.clear()).has("minecraft:custom_data"));
        assertEquals(stone, stone.withName("x").withoutName());
    }

    @Test
    public void aValueEqualsByItemCountAndData() {
        CgItemStack a = CgItemStack.of("minecraft:stone", 2).withName("x");
        CgItemStack b = CgItemStack.of("minecraft:stone", 2).withName("x").withMaxDamage(5);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertFalse(a.equals(a.withCount(3)));
        assertTrue(a.stacksWith(a.withCount(3)));
        assertFalse(a.stacksWith(a.withName("y")));
        assertEquals(CgItemStack.EMPTY, CgItemStack.of("minecraft:stone", 0));
        assertTrue(CgItemStack.EMPTY.isEmpty());
    }

    @Test
    public void aStackIsACopyOfWhatItWasMadeFrom() {
        use(CgItems.Shape.COMPONENTS);
        CgNbtCompound saved = CgNbt.parse(COMPONENT_SWORD);
        CgItemStack s = CgItemStack.of(new Fake(saved));
        saved.getCompound("components").put("minecraft:damage", 99);
        assertEquals(5, s.damage());
        s.components().put("minecraft:damage", 77);
        assertEquals(5, s.damage());
    }

    @Test
    public void aCodecWritesTheModernShapeEverywhere() {
        CgItemStack s = read(CgItems.Shape.TAG, TAG_SWORD);
        Object tree = CgItemStack.CODEC.encode(CgPlainOps.INSTANCE, s);
        assertEquals(s, CgItemStack.CODEC.decode(CgPlainOps.INSTANCE, tree));
        CgNbtTag tag = CgNbt.encode(CgItemStack.CODEC, s);
        assertEquals(s, tag.as(CgItemStack.CODEC));
        assertEquals("minecraft:diamond_sword", tag.asCompound().getString("id"));
    }

    @Test
    public void aNewerComponentHasNoOldForm() {
        use(CgItems.Shape.LEGACY);
        CgItemStack food = CgItemStack.of("minecraft:apple", 1).with("minecraft:food", new CgNbtCompound().put("nutrition", 4));
        CgCodecException e = assertThrows(CgCodecException.class, food::toHost);
        assertTrue(e.getMessage(), e.getMessage().contains("minecraft:food"));
        use(CgItems.Shape.COMPONENTS);
        assertEquals(4, ((Fake) food.toHost()).saved().getCompound("components").getCompound("minecraft:food").getInt("nutrition"));
    }

    @Test
    public void textComponentsKeepTheirStylingThroughJson() {
        String json = "{\"text\":\"Hi\",\"bold\":true,\"extra\":[\" there\",{\"text\":\"!\",\"color\":\"red\"}]}";
        CgNbtTag tag = TextComponents.fromJson(json);
        assertEquals("Hi there!", TextComponents.plain(tag));
        assertEquals(1, tag.asCompound().getByte("bold"));
        assertEquals(CgNbt.parse("{text:\"Hi\",bold:1b,extra:[{text:\" there\"},{text:\"!\",color:\"red\"}]}"), tag);
        assertEquals("{\"text\":\"Hi\",\"bold\":true,\"extra\":[{\"text\":\" there\"},{\"text\":\"!\",\"color\":\"red\"}]}", TextComponents.toJson(tag));
        assertEquals("plain", TextComponents.plain(TextComponents.fromJson("\"plain\"")));
        assertEquals("not json {", TextComponents.plain(TextComponents.fromJson("not json {")));
    }

    @Test
    public void withNoGameTheGameCallsThrowAndTheRestRuns() {
        CgPlatform.provide(CgItems.HOST, null);
        assertThrows(IllegalArgumentException.class, () -> CgItemStack.of(new Object()));
        assertThrows(IllegalStateException.class, () -> CgItems.toHost(CgItemStack.of("minecraft:stone", 1)));
        assertNull(CgItemStack.of("minecraft:stone", 1).name());
    }
}
