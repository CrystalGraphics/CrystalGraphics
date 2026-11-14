package com.crystalgraphics.serialization.nbt;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.format.CgFileFormat;
import com.crystalgraphics.serialization.format.CgFiles;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Tags read from and written to every file format {@link CgFiles} has. */
public class CgNbtFilesTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    /** Every kind of tag, byte arrays and the int and long widths included: only NBT and SNBT keep them all. */
    private static CgNbtCompound exact() {
        return CgNbt.compound().putByte("b", 1).putShort("s", 2).put("i", 3).put("l", 4L).put("f", 5.5f).put("d", 6.25d)
                .put("str", "seven").put("bytes", new byte[]{1, -2}).put("ints", new int[]{3, 4}).put("longs", new long[]{5L, 6L})
                .put("minecraft:id", "key with a colon")
                .put("list", CgNbtList.of("a", "b")).put("empty", CgNbt.list())
                .put("nested", CgNbt.compound().put("x", 1).put("rows", CgNbtList.of(
                        CgNbt.compound().put("k", "v").put("n", 2.5d), CgNbt.compound().put("k", "w").put("n", 3.5d))))
                .put("none", CgNbt.compound());
    }

    /** What a person would put in a text config: strings, numbers, nested tables and lists, with no byte arrays. */
    private static CgNbtCompound text() {
        return CgNbt.compound().put("name", "Goku").put("level", 9).put("hp", 20.5d).put("minecraft:id", "colon key")
                .put("tags", CgNbtList.of("saiyan", "z"))
                .put("stats", CgNbt.compound().put("str", 10).put("ki", 99.5d))
                .put("rows", CgNbtList.of(CgNbt.compound().put("k", "v").put("n", 2), CgNbt.compound().put("k", "w").put("n", 3)))
                .put("empty", CgNbt.list()).put("none", CgNbt.compound());
    }

    private CgNbtCompound roundTrip(CgFileFormat format, String name, CgNbtCompound tag) throws IOException {
        Path file = folder.getRoot().toPath().resolve(name);
        format.write(file, CgNbtCompound.CODEC, tag);
        return format.read(file, CgNbtCompound.CODEC);
    }

    @Test
    public void nbtAndSnbtFilesKeepEveryTagExactly() throws IOException {
        assertEquals(exact(), roundTrip(CgFiles.NBT, "a.nbt", exact()));
        assertEquals(exact(), roundTrip(CgFiles.SNBT, "a.snbt", exact()));
    }

    @Test
    public void everyTextFormatReadsAndWritesATag() throws IOException {
        for (CgFileFormat format : new CgFileFormat[]{CgFiles.JSON, CgFiles.JSONC, CgFiles.TOML, CgFiles.YAML, CgFiles.BINARY}) {
            CgNbtCompound back = roundTrip(format, "t." + format.getClass().getSimpleName(), text());
            assertEquals(format.getClass().getSimpleName(), "Goku", back.getString("name"));
            assertEquals(9, back.getInt("level"));
            assertEquals(20.5d, back.getDouble("hp"), 0d);
            assertEquals("colon key", back.getString("minecraft:id"));
            assertEquals("z", back.getList("tags").getString(1));
            assertEquals(99.5d, back.at("stats.ki").asValue().asDouble(), 0d);
            assertEquals(3, back.getList("rows").getCompound(1).getInt("n"));
            assertTrue(back.getList("empty").isEmpty());
            assertTrue(back.has("none", CgNbtTag.Type.COMPOUND));
        }
    }

    @Test
    public void aMissingFileReadsAsNoTag() throws IOException {
        assertNull(CgFiles.NBT.read(folder.getRoot().toPath().resolve("missing.nbt"), CgNbtCompound.CODEC));
    }

    @Test
    public void theExtensionPicksTheNbtFormats() {
        assertEquals(CgFiles.NBT, CgFiles.of(Path.of("data/level.nbt")));
        assertEquals(CgFiles.SNBT, CgFiles.of(Path.of("data/preset.SNBT")));
    }

    record Spell(String name, int cost, boolean fire) {
        static final CgCodec<Spell> CODEC = CgCodecs.record(
                CgCodecs.field("name", CgCodecs.STRING, Spell::name),
                CgCodecs.field("cost", CgCodecs.INT, Spell::cost),
                CgCodecs.field("fire", CgCodecs.BOOL, Spell::fire), Spell::new);
    }

    @Test
    public void aCodecsValueIsRealNbtInAFile() throws IOException {
        Path file = folder.getRoot().toPath().resolve("spell.nbt");
        CgFiles.NBT.write(file, Spell.CODEC, new Spell("Kamehameha", 30, true));
        assertEquals(new Spell("Kamehameha", 30, true), CgFiles.NBT.read(file, Spell.CODEC));
        CgNbtCompound raw = CgFiles.NBT.read(file, CgNbtCompound.CODEC);
        assertEquals("a boolean is a byte, as the game stores it", CgNbtTag.Type.BYTE, raw.get("fire").type());
        assertEquals("{name:\"Kamehameha\",cost:30,fire:1b}", CgFiles.SNBT.toText(Spell.CODEC, new Spell("Kamehameha", 30, true)));
    }

    @Test
    public void aNbtFileHoldsACompoundOnly() {
        CgCodecException e = assertThrows(CgCodecException.class,
                () -> CgFiles.NBT.write(folder.getRoot().toPath().resolve("n.nbt"), CgCodecs.INT, 5));
        assertTrue(e.getMessage(), e.getMessage().contains("compound"));
    }

    @Test
    public void aDamagedNbtFileFailsByNameAndLeavesNoFile() throws IOException {
        Path file = folder.getRoot().toPath().resolve("bad.nbt");
        Files.write(file, new byte[]{1, 2, 3});
        assertThrows(CgCodecException.class, () -> CgFiles.NBT.read(file, CgNbtCompound.CODEC));
    }
}
