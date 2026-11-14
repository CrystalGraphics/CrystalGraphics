package com.crystalgraphics.serialization.nbt;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.CgStateMap;
import com.crystalgraphics.serialization.format.CgJsonOps;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** The tag model with no game: building, reading, SNBT, binary, codecs, and the game's tags through a stand-in host. */
public class CgNbtTest {

    @After
    public void untag() {
        CgPlatform.provide(CgNbt.HOST, null);
    }

    private static CgNbtCompound everything() {
        return CgNbt.compound().putByte("b", 1).putShort("s", 2).put("i", 3).put("l", 4L).put("f", 5.5f).put("d", 6.25d)
                .put("str", "seven \"quoted\"").put("bytes", new byte[]{1, -2}).put("ints", new int[]{3, 4})
                .put("longs", new long[]{5L, 6L}).put("list", CgNbtList.of("a", "b"))
                .put("nested", CgNbt.compound().put("x", 1).put("list", CgNbt.list().add(CgNbt.compound().put("k", true))));
    }

    @Test
    public void readsNeverThrowAndNumbersReadAsAnyWidth() {
        CgNbtCompound tag = CgNbt.compound().put("level", 9).put("hp", 20.5d).put("name", "Goku");
        assertEquals(9, tag.getInt("level"));
        assertEquals(9L, tag.getLong("level"));
        assertEquals(20, tag.getInt("hp"));
        assertEquals("a number is not a string", "", tag.getString("level"));
        assertEquals("a string is not a number", 5, tag.getInt("name", 5));
        assertEquals(0, tag.getInt("missing"));
        assertEquals(3, tag.getInt("missing", 3));
        assertTrue(tag.getCompound("missing").isEmpty());
        assertFalse("an absent compound is not stored", tag.has("missing"));
        assertTrue(tag.getBool("level"));
        assertFalse(tag.getBool("missing"));
    }

    @Test
    public void aCompoundIsEditedInPlaceAndByPath() {
        CgNbtCompound tag = CgNbt.compound();
        tag.getOrCreateCompound("display").put("Name", "Sword");
        tag.getOrCreateList("lore").add("a").add("b");
        tag.putAt("a.b.c", CgNbtValue.ofInt(7));
        assertEquals("Sword", tag.at("display.Name").asString());
        assertEquals("b", tag.at("lore.1").asString());
        assertEquals(7, tag.at("a.b.c").asValue().asInt());
        assertNull(tag.at("a.x.c"));
        assertEquals("a null tag removes the key", 0, tag.put("a", (CgNbtTag) null).getCompound("a").size());
    }

    @Test
    public void mergeIsDeepAndCopies() {
        CgNbtCompound base = CgNbt.compound().put("keep", 1).put("sub", CgNbt.compound().put("a", 1));
        CgNbtCompound patch = CgNbt.compound().put("sub", CgNbt.compound().put("b", 2)).put("new", "x");
        base.merge(patch);
        assertEquals("{keep:1,sub:{a:1,b:2},new:\"x\"}", base.toString());
        patch.getCompound("sub").put("b", 99);
        assertEquals("a merge copies", 2, base.at("sub.b").asValue().asInt());
    }

    @Test
    public void aListHoldsOneKind() {
        CgNbtList list = CgNbtList.of("a");
        assertThrows(CgCodecException.class, () -> list.add(1));
        assertEquals(CgNbtTag.Type.STRING, list.elementType());
        assertEquals("", list.getString(5));
        assertNull(list.get(5));
    }

    @Test
    public void snbtRoundTripsEveryKind() {
        CgNbtCompound tag = everything();
        String text = tag.toString();
        assertEquals(tag, CgNbt.parse(text));
        assertTrue(text, text.startsWith("{b:1b,s:2s,i:3,l:4L,f:5.5f,d:6.25d,str:\"seven \\\"quoted\\\"\""));
        assertTrue(text, text.contains("bytes:[B;1b,-2b]") && text.contains("ints:[I;3,4]") && text.contains("longs:[L;5L,6L]"));
    }

    @Test
    public void snbtReadsWhatCommandsPrint() {
        CgNbtCompound tag = CgNbt.parse("{style:3b, dyed:true, colors:[I;16711680,255], name:'Goku', 'odd key':1.5, n:+7}");
        assertEquals(3, tag.getByte("style"));
        assertTrue(tag.getBool("dyed"));
        assertArrayEquals(new int[]{16711680, 255}, tag.getInts("colors"));
        assertEquals("Goku", tag.getString("name"));
        assertEquals(1.5d, tag.getDouble("odd key"), 0d);
        assertEquals(CgNbtTag.Type.INT, tag.get("n").type());
        assertThrows(CgCodecException.class, () -> CgNbt.parse("{a:1,}x"));
        assertThrows("a list holds one kind", CgCodecException.class, () -> CgNbt.parse("[1,\"a\"]"));
    }

    @Test
    public void binaryNbtRoundTripsAndDetectsGzip() throws IOException {
        CgNbtCompound tag = everything();
        assertEquals(tag, CgNbt.fromBytes(CgNbt.toBytes(tag)));
        java.io.ByteArrayOutputStream plain = new java.io.ByteArrayOutputStream();
        CgNbt.writeBinary(plain, tag, false);
        assertEquals("not gzipped", 10, plain.toByteArray()[0]);
        assertEquals(tag, CgNbt.readBinary(new java.io.ByteArrayInputStream(plain.toByteArray())));
        assertEquals("gzipped by default", (byte) 0x1f, CgNbt.toBytes(tag)[0]);
    }

    @Test
    public void aDamagedFileFailsByNameAndNeverAllocatesWildly() {
        byte[] hostile = {10, 0, 0, 7, 0, 1, 'k', 0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff};   // a byte array 2 billion long
        assertThrows(CgCodecException.class, () -> CgNbt.readBinary(new java.io.ByteArrayInputStream(hostile)));
    }

    record Spell(String name, int cost, boolean fire) {
        static final CgCodec<Spell> CODEC = CgCodecs.record(
                CgCodecs.field("name", CgCodecs.STRING, Spell::name),
                CgCodecs.field("cost", CgCodecs.INT, Spell::cost),
                CgCodecs.field("fire", CgCodecs.BOOL, Spell::fire),
                Spell::new);
    }

    @Test
    public void aCodecWritesTagsWithNoHost() {
        Spell spell = new Spell("Kamehameha", 30, true);
        CgNbtCompound tag = CgNbt.encode(Spell.CODEC, spell).asCompound();
        assertEquals("a boolean is a byte", CgNbtTag.Type.BYTE, tag.get("fire").type());
        assertEquals("{name:\"Kamehameha\",cost:30,fire:1b}", tag.toString());
        assertEquals(spell, tag.as(Spell.CODEC));
        assertEquals(spell, CgNbt.parse("{name:\"Kamehameha\",cost:30,fire:true}").as(Spell.CODEC));
        assertTrue(CgNbt.encode(CgCodecs.PLAIN, "text") instanceof CgNbtValue);
    }

    @Test
    public void aTagCarriesStructureInAKeyOfACodec() {
        CgNbtCompound tag = CgNbt.compound().put("spell", Spell.CODEC, new Spell("Beam", 5, false)).put("n", 1);
        assertEquals(new Spell("Beam", 5, false), tag.get("spell", Spell.CODEC));
        assertNull(tag.get("nothing", Spell.CODEC));
        assertEquals(new Spell("x", 0, false), tag.get("nothing", Spell.CODEC, new Spell("x", 0, false)));
        CgNbtList spells = CgNbtList.of(Spell.CODEC, Arrays.asList(new Spell("a", 1, true), new Spell("b", 2, false)));
        assertEquals(2, spells.toList(Spell.CODEC).get(1).cost());
    }

    @Test
    public void aDynamicTagTravelsThroughEveryFormat() {
        CgNbtCompound dynamic = CgNbt.compound().put("a", 1).put("b", CgNbtList.of("x", "y")).put("c", CgNbt.compound().put("d", 2.5d));
        assertEquals("the same tree in NBT", dynamic, CgNbtCompound.CODEC.decode(CgNbtOps.INSTANCE, CgNbtCompound.CODEC.encode(CgNbtOps.INSTANCE, dynamic)));
        Object json = CgNbtTag.CODEC.encode(CgJsonOps.INSTANCE, dynamic);
        assertEquals("{\"a\":1,\"b\":[\"x\",\"y\"],\"c\":{\"d\":2.5}}", json.toString());
        CgNbtCompound back = CgNbtCompound.CODEC.decode(CgJsonOps.INSTANCE, (com.google.gson.JsonElement) json);
        assertEquals(1, back.getInt("a"));
        assertEquals(2.5d, back.at("c.d").asValue().asDouble(), 0d);
    }

    @Test
    public void aStateMapReadsAndWritesATag() {
        CgStateMap<CgNbtTag> out = CgNbt.state().putInt("zoom", 3).putBool("grid", true).putString("name", "x");
        CgNbtCompound tag = out.encode().asCompound();
        assertEquals("{zoom:3,grid:1b,name:\"x\"}", tag.toString());
        CgStateMap<CgNbtTag> in = CgNbt.state(tag);
        assertEquals(3, in.getInt("zoom", 0));
        assertTrue(in.getBool("grid", false));
        assertEquals(7, in.getInt("missing", 7));
    }

    @Test
    public void copiesShareNothing() {
        CgNbtCompound original = everything();
        CgNbtCompound copy = original.copy();
        assertEquals(original, copy);
        assertNotSame(original.get("nested"), copy.get("nested"));
        copy.getCompound("nested").put("x", 99);
        assertEquals(1, original.at("nested.x").asValue().asInt());
        byte[] bytes = original.getBytes("bytes");
        bytes[0] = 99;
        assertEquals("an array comes out as a copy", 1, original.getBytes("bytes")[0]);
    }

    // ── the game's tags, through a stand-in host of Java values ─────────────

    @Test
    public void theGamesTagsCrossBothWaysUnchanged() {
        CgPlatform.provide(CgNbt.HOST, new JavaTags());
        CgNbtCompound tag = everything();
        Object host = CgNbt.toHost(tag);
        assertTrue("a compound of the game's", host instanceof Map);
        assertEquals("a boolean is the game's byte", (byte) 1, ((Map<?, ?>) ((Map<?, ?>) host).get("nested")).get("list") instanceof List<?> l
                ? ((Map<?, ?>) l.get(0)).get("k") : null);
        CgNbtCompound back = CgNbt.of(host);
        assertEquals(tag, back);
        assertNotSame(tag, back);
        assertSame("a tag of ours is answered as it is", tag, CgNbt.of(tag));
        assertNull(CgNbt.of(null));
    }

    @Test
    public void applyToEditsTheGamesCompoundInPlace() {
        CgPlatform.provide(CgNbt.HOST, new JavaTags());
        Object game = CgNbt.toHost(CgNbt.compound().put("keep", 1).put("charge", 1));
        CgNbt.applyTo(game, CgNbt.compound().put("charge", 3).put("added", "x"));
        assertEquals("{keep:1,charge:3,added:\"x\"}", CgNbt.<CgNbtCompound>of(game).toString());
    }

    @Test
    public void aCodecCrossesTheGamesTagsAndBack() {
        CgPlatform.provide(CgNbt.HOST, new JavaTags());
        Spell spell = new Spell("Kamehameha", 30, true);
        Object host = CgNbt.toHost(Spell.CODEC, spell);
        assertEquals(spell, CgNbt.fromHost(Spell.CODEC, host));
    }

    /** The game's tags as Java values: a compound a map, a list a list, a string a string, each number its own box. */
    static final class JavaTags implements CgNbt.Host {
        @Override public int kind(Object tag) {
            if (tag instanceof Byte) return 1;
            if (tag instanceof Short) return 2;
            if (tag instanceof Integer) return 3;
            if (tag instanceof Long) return 4;
            if (tag instanceof Float) return 5;
            if (tag instanceof Double) return 6;
            if (tag instanceof byte[]) return 7;
            if (tag instanceof String) return 8;
            if (tag instanceof List) return 9;
            if (tag instanceof Map) return 10;
            if (tag instanceof int[]) return 11;
            if (tag instanceof long[]) return 12;
            return 0;
        }
        @Override public Object compound() { return new LinkedHashMap<String, Object>(); }
        @SuppressWarnings("unchecked")
        @Override public void put(Object compound, String key, Object value) { ((Map<String, Object>) compound).put(key, value); }
        @SuppressWarnings("unchecked")
        @Override public Collection<String> keys(Object compound) { return new ArrayList<>(((Map<String, Object>) compound).keySet()); }
        @Override public Object get(Object compound, String key) { return ((Map<?, ?>) compound).get(key); }
        @Override public Object list() { return new ArrayList<>(); }
        @SuppressWarnings("unchecked")
        @Override public void add(Object list, Object value) { ((List<Object>) list).add(value); }
        @Override public List<?> elements(Object list) { return (List<?>) list; }
        @Override public Object string(String value) { return value; }
        @Override public String stringOf(Object tag) { return (String) tag; }
        @Override public Object number(CgNbtTag.Type type, Number value) {
            switch (type) {
                case BYTE: return value.byteValue();
                case SHORT: return value.shortValue();
                case INT: return value.intValue();
                case LONG: return value.longValue();
                case FLOAT: return value.floatValue();
                default: return value.doubleValue();
            }
        }
        @Override public Number numberOf(Object tag) { return (Number) tag; }
        @Override public Object bytes(byte[] value) { return value.clone(); }
        @Override public byte[] bytesOf(Object tag) { return (byte[]) tag; }
        @Override public Object ints(int[] value) { return value.clone(); }
        @Override public int[] intsOf(Object tag) { return (int[]) tag; }
        @Override public Object longs(long[] value) { return value.clone(); }
        @Override public long[] longsOf(Object tag) { return (long[]) tag; }
    }
}
