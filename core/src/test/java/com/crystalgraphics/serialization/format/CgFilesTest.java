package com.crystalgraphics.serialization.format;

import com.crystalgraphics.serialization.nbt.CgNbtCompound;
import com.crystalgraphics.serialization.nbt.CgNbt;
import com.crystalgraphics.serialization.nbt.CgNbtTag;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.CgCodecException;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** A codec's value in a file of its own, and as a host's tags. */
public class CgFilesTest {

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
        assertNull("no file yet", CgFiles.read(file, Character.CODEC));
        CgFiles.write(file, Character.CODEC, goku());
        CgFiles.write(file, Character.CODEC, goku());
        assertSame(goku(), CgFiles.read(file, Character.CODEC));
        assertFalse("the temporary file is moved over it", Files.exists(file.resolveSibling("goku.dat.tmp")));
        byte[] head = Arrays.copyOf(Files.readAllBytes(file), 2);
        assertArrayEquals("compressed", new byte[]{(byte) 0x1F, (byte) 0x8B}, head);
    }

    @Test
    public void aValueCrossesTagsAndBack() {
        CgNbtTag tag = CgNbt.encode(Character.CODEC, goku());
        CgNbtCompound compound = tag.asCompound();
        assertEquals("a boolean is a byte", (byte) 1, compound.getByte("ki"));
        assertEquals("bytes stay bytes", CgNbtTag.Type.BYTE_ARRAY, compound.get("seed").type());
        assertSame(goku(), CgNbt.decode(Character.CODEC, tag));
    }

    @Test
    public void withNoHostTheGameCallsNameTheirSlot() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> CgNbt.toHost(CgNbt.compound()));
        assertTrue(e.getMessage(), e.getMessage().contains("crystalgraphics:nbt"));
    }

    @Test
    public void anNbtKeyIsText() {
        Map<Integer, Integer> byNumber = new LinkedHashMap<>();
        byNumber.put(1, 2);
        CgCodec<Map<Integer, Integer>> ints = CgCodecs.mapOf(CgCodecs.INT, CgCodecs.INT);
        assertEquals("an INT key is written as its text", byNumber, CgNbt.decode(ints, CgNbt.encode(ints, byNumber)));
        assertThrows(CgCodecException.class,
                () -> CgNbt.encode(CgCodecs.mapOf(CgCodecs.DOUBLE, CgCodecs.INT), Collections.singletonMap(1.5, 2)));
    }
}
