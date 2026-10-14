package com.crystalgraphics.serialization.format;

import com.crystalgraphics.serialization.format.nbt.CgHostFormats;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.CgCodecException;

import com.crystalgraphics.platform.CgPlatform;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** A codec's value in a file of its own, and as a host's tags. */
public class CgFormatsTest {

    record Character(String name, int level, boolean ki, Map<String, Integer> skills, byte[] seed) {
        static final CgCodec<Map<String, Integer>> SKILLS = CgCodecs.mapOf(CgCodecs.STRING, CgCodecs.INT);
        static final CgCodec<Character> CODEC = CgCodecs.record(
                CgCodecs.field("name", CgCodecs.STRING, Character::name),
                CgCodecs.field("level", CgCodecs.INT, Character::level),
                CgCodecs.field("ki", CgCodecs.BOOL, Character::ki),
                CgCodecs.field("skills", SKILLS, Character::skills),
                CgCodecs.field("seed", CgCodecs.BYTES, Character::seed),
                Character::new);
    }

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @After
    public void tearDown() {
        CgPlatform.provide(CgHostFormats.SERVICE, null);
    }

    private static Character goku() {
        Map<String, Integer> skills = new LinkedHashMap<>();
        skills.put("kamehameha", 3);
        skills.put("instant_transmission", 1);
        return new Character("Goku", 42, true, skills, new byte[]{1, 2, 3, -4});
    }

    private static void assertSame(Character want, Character got) {
        assertEquals(want.name(), got.name());
        assertEquals(want.level(), got.level());
        assertEquals(want.ki(), got.ki());
        assertEquals(want.skills(), got.skills());
        assertArrayEquals(want.seed(), got.seed());
    }

    @Test
    public void aFileHoldsWhatWasWrittenAndNothingBeside() throws IOException {
        Path file = folder.getRoot().toPath().resolve("players/goku.dat");
        assertNull("no file yet", CgFormats.read(file, Character.CODEC));
        CgFormats.write(file, Character.CODEC, goku());
        CgFormats.write(file, Character.CODEC, goku());
        assertSame(goku(), CgFormats.read(file, Character.CODEC));
        assertFalse("the temporary file is moved over it", Files.exists(file.resolveSibling("goku.dat.tmp")));
        byte[] head = Arrays.copyOf(Files.readAllBytes(file), 2);
        assertArrayEquals("compressed", new byte[]{(byte) 0x1F, (byte) 0x8B}, head);
    }

    @Test
    public void aValueCrossesTheHostsTagsAndBack() {
        CgPlatform.provide(CgHostFormats.SERVICE, new Tags());
        Object tag = CgFormats.toNbt(Character.CODEC, goku());
        assertTrue("a compound", tag instanceof Map);
        assertEquals("a boolean is a byte", (byte) 1, ((Map<?, ?>) tag).get("ki"));
        assertTrue("bytes stay bytes", ((Map<?, ?>) tag).get("seed") instanceof byte[]);
        assertSame(goku(), CgFormats.fromNbt(Character.CODEC, tag));
    }

    @Test
    public void withNoHostTheBridgeNamesItsSlot() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> CgFormats.toNbt(CgCodecs.INT, 1));
        assertTrue(e.getMessage(), e.getMessage().contains("crystalgraphics:host_formats"));
    }

    @Test
    public void anNbtKeyIsText() {
        CgPlatform.provide(CgHostFormats.SERVICE, new Tags());
        Map<Integer, Integer> byNumber = new LinkedHashMap<>();
        byNumber.put(1, 2);
        assertThrows(CgCodecException.class,
                () -> CgFormats.toNbt(CgCodecs.mapOf(CgCodecs.INT, CgCodecs.INT), byNumber));
    }

    /** Tags as Java values: a compound a map, a list a list, a string a string. */
    static final class Tags implements CgHostFormats {
        @Override public boolean present() { return true; }
        @Override public Object compound() { return new LinkedHashMap<String, Object>(); }
        @SuppressWarnings("unchecked")
        @Override public void put(Object compound, String key, Object value) { ((Map<String, Object>) compound).put(key, value); }
        @SuppressWarnings("unchecked")
        @Override public Collection<String> keys(Object tag) { return tag instanceof Map ? ((Map<String, Object>) tag).keySet() : null; }
        @Override public Object get(Object compound, String key) { return ((Map<?, ?>) compound).get(key); }
        @Override public Object list() { return new ArrayList<>(); }
        @SuppressWarnings("unchecked")
        @Override public void add(Object list, Object value) { ((List<Object>) list).add(value); }
        @Override public List<?> elements(Object tag) { return tag instanceof List<?> l ? l : null; }
        @Override public Object string(String value) { return value; }
        @Override public String stringOf(Object tag) { return tag instanceof String s ? s : null; }
        @Override public Object number(Number value) { return value; }
        @Override public Number numberOf(Object tag) { return tag instanceof Number n ? n : null; }
        @Override public Object bytes(byte[] value) { return value.clone(); }
        @Override public byte[] bytesOf(Object tag) { return tag instanceof byte[] b ? b : null; }
    }
}
