package com.crystalgraphics.text.font;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.api.font.CgSystemFontFace;
import com.crystalgraphics.api.font.CgSystemFonts;
import com.crystalgraphics.freetype.FTFace;
import com.crystalgraphics.freetype.FreeTypeLibrary;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The font-file reader is what decides fallback coverage and which face of a collection loads —
 * both invisible when wrong, since a wrong answer draws a plausible glyph from the wrong font.
 */
public class SfntTest {

    private static final Path FONTS = Paths.get("src/test/resources/fonts");
    private static final int NAME = 0x6E616D65;
    private static final String[] FILES = {
            "IBMPlexSans-Regular.ttf", "IBMPlexSansArabic-Regular.ttf", "MPLUS1p-Regular.ttf",
            "MPLUSRounded1c-Regular.ttf", "NotoSansArabic-Regular.ttf", "test-font.ttf"};

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    /** Every code point of every test font, against what FreeType says. */
    @Test
    public void coverageAgreesWithFreeTypeOnEveryCodePoint() throws IOException {
        FreeTypeLibrary library = FreeTypeLibrary.create();
        try {
            for (String file : FILES) {
                Path path = FONTS.resolve(file);
                Sfnt.Face parsed = Sfnt.readFaces(path).get(0);
                FTFace face = library.newFace(path.toAbsolutePath().toString(), 0);
                try {
                    assertEquals(file + " family", face.getFamilyName(), parsed.family());
                    assertEquals(file + " style", face.getStyleName(), parsed.style());
                    List<String> wrong = new ArrayList<>();
                    for (int cp = 0; cp <= Character.MAX_CODE_POINT && wrong.size() < 5; cp++) {
                        if ((face.getCharIndex(cp) != 0) != parsed.coverage().contains(cp)) {
                            wrong.add("U+" + Integer.toHexString(cp).toUpperCase());
                        }
                    }
                    assertTrue(file + " disagrees with FreeType at " + wrong, wrong.isEmpty());
                    assertTrue(file + " has outlines", parsed.outlines());
                } finally {
                    face.destroy();
                }
            }
        } finally {
            library.destroy();
        }
    }

    @Test
    public void aCollectionFaceExtractsToTheSameTablesItWasBuiltFrom() throws IOException {
        byte[] latin = Files.readAllBytes(FONTS.resolve("IBMPlexSans-Regular.ttf"));
        byte[] arabic = Files.readAllBytes(FONTS.resolve("NotoSansArabic-Regular.ttf"));
        byte[] collection = collectionOf(latin, arabic);

        assertTrue(Sfnt.isCollection(collection));
        assertEquals(2, Sfnt.faceCount(collection));
        assertTablesEqual(latin, Sfnt.extractFace(collection, 0));
        assertTablesEqual(arabic, Sfnt.extractFace(collection, 1));
        assertTrue("a single font is its own face 0", Sfnt.extractFace(latin, 0) == latin);
    }

    @Test
    public void readFacesNamesEachFaceOfACollection() throws IOException {
        Path file = temp.newFile("pair.ttc").toPath();
        Files.write(file, collectionOf(Files.readAllBytes(FONTS.resolve("IBMPlexSans-Regular.ttf")),
                Files.readAllBytes(FONTS.resolve("NotoSansArabic-Regular.ttf"))));

        List<Sfnt.Face> faces = Sfnt.readFaces(file);
        assertEquals(2, faces.size());
        assertEquals(Sfnt.readFaces(FONTS.resolve("IBMPlexSans-Regular.ttf")).get(0).family(), faces.get(0).family());
        assertEquals(Sfnt.readFaces(FONTS.resolve("NotoSansArabic-Regular.ttf")).get(0).family(), faces.get(1).family());
        assertTrue(faces.get(1).coverage().contains(0x0639));
        assertFalse(faces.get(0).coverage().contains(0x0639));
    }

    /** Two faces of one file are two fonts, and the face index survives sizing. */
    @Test
    public void cgFontLoadsTheFaceItIsAskedFor() throws IOException {
        byte[] collection = collectionOf(Files.readAllBytes(FONTS.resolve("IBMPlexSans-Regular.ttf")),
                Files.readAllBytes(FONTS.resolve("NotoSansArabic-Regular.ttf")));

        CgFont latin = CgFont.load(collection, "pair.ttc", 0, CgFontStyle.REGULAR, 16);
        CgFont arabic = CgFont.load(collection, "pair.ttc", 1, CgFontStyle.REGULAR, 16);
        try {
            assertTrue(latin.canDisplayCodePoint('A'));
            assertFalse(latin.canDisplayCodePoint(0x0639));
            assertTrue(arabic.canDisplayCodePoint(0x0639));
            assertNotEquals(latin.getKey(), arabic.getKey());
            assertEquals(1, arabic.getKey().getFaceIndex());
            assertEquals(1, arabic.getKey().withTargetPx(20).getFaceIndex());
            assertEquals(1, arabic.atSize(20).getKey().getFaceIndex());
            assertFalse("the loaded bytes are one font, not the collection", Sfnt.isCollection(arabic.getFontBytes()));
        } finally {
            latin.dispose();
            arabic.dispose();
        }
    }

    /**
     * An installed collection face is opened natively at its index, by FreeType and msdfgen alike; asked
     * for, its bytes are that face alone.
     */
    @Test
    public void anInstalledCollectionFaceOpensFromItsFile() throws IOException {
        Path installed = temp.newFolder("installed").toPath();
        Files.write(installed.resolve("pair.ttc"), collectionOf(
                Files.readAllBytes(FONTS.resolve("IBMPlexSans-Regular.ttf")),
                Files.readAllBytes(FONTS.resolve("NotoSansArabic-Regular.ttf"))));
        CgSystemFonts fonts = CgSystemFonts.of(List.of(installed));

        CgSystemFontFace face = fonts.find("Noto Sans Arabic", CgFontStyle.REGULAR);
        CgFont font = fonts.load(face, CgFontStyle.REGULAR, 16);
        assertEquals(1, font.getData().faceIndex());
        assertTrue(font.canDisplayCodePoint(0x0639));
        assertTrue("msdfgen reads the same face", font.getMsdfFont().getGlyphIndex(0x0639) != 0);
        assertFalse("its bytes are the face, not the collection", Sfnt.isCollection(font.getFontBytes()));
    }

    /**
     * Named as FreeType names it — a Windows name in English of any region, else an Apple one over a
     * Windows name in another language — and found by every name, as a browser finds {@code メイリオ}.
     */
    @Test
    public void aFaceIsNamedAsFreeTypeNamesItAndFoundInAnyLanguage() throws IOException {
        Path installed = temp.newFolder("named").toPath();
        byte[] plex = Files.readAllBytes(FONTS.resolve("IBMPlexSans-Regular.ttf"));
        Files.write(installed.resolve("british.ttf"), withTable(plex, NAME, nameTable(
                new Object[] {3, 1, 0x0411, 1, "メイリオ"},
                new Object[] {3, 1, 0x0809, 1, "Meiryo"},
                new Object[] {1, 0, 0, 1, "Meiryo Mac"},
                new Object[] {3, 1, 0x0809, 2, "Regular"})));
        Files.write(installed.resolve("apple.ttf"), withTable(plex, NAME, nameTable(
                new Object[] {3, 1, 0x0412, 1, "맑은 고딕"},
                new Object[] {1, 0, 0, 1, "Malgun Gothic"})));

        Sfnt.Face british = Sfnt.readFaces(installed.resolve("british.ttf")).get(0);
        assertEquals("English of any region", "Meiryo", british.family());
        assertEquals(List.of("Meiryo", "メイリオ", "Meiryo Mac"), british.familyNames());
        assertEquals("an English Apple name over a Windows one in another language",
                "Malgun Gothic", Sfnt.readFaces(installed.resolve("apple.ttf")).get(0).family());

        CgSystemFonts fonts = CgSystemFonts.of(List.of(installed));
        assertEquals("Meiryo", fonts.find("メイリオ", CgFontStyle.REGULAR).getFamily());
        assertEquals("Malgun Gothic", fonts.find("맑은 고딕", CgFontStyle.REGULAR).getFamily());
    }

    @Test
    public void aFaceIndexOutsideTheFileIsRefused() throws IOException {
        byte[] latin = Files.readAllBytes(FONTS.resolve("IBMPlexSans-Regular.ttf"));
        byte[] collection = collectionOf(latin, latin);
        expectRefused(() -> Sfnt.extractFace(latin, 1));
        expectRefused(() -> Sfnt.extractFace(collection, 2));
        expectRefused(() -> Sfnt.extractFace(collection, -1));
    }

    private static void expectRefused(Runnable call) {
        try {
            call.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // refused, as it should be
        }
    }

    /** A {@code ttcf} collection: each font copied whole, its table offsets rebased to the file. */
    static byte[] collectionOf(byte[]... fonts) {
        int[] at = new int[fonts.length];
        int total = 12 + 4 * fonts.length;
        for (int i = 0; i < fonts.length; i++) {
            at[i] = total;
            total += (fonts[i].length + 3) & ~3;
        }
        byte[] out = new byte[total];
        ByteBuffer o = ByteBuffer.wrap(out);
        o.putInt(0, 0x74746366);
        o.putInt(4, 0x00010000);
        o.putInt(8, fonts.length);
        for (int i = 0; i < fonts.length; i++) {
            o.putInt(12 + 4 * i, at[i]);
            System.arraycopy(fonts[i], 0, out, at[i], fonts[i].length);
            int numTables = o.getShort(at[i] + 4) & 0xFFFF;
            for (int t = 0; t < numTables; t++) {
                int record = at[i] + 12 + 16 * t;
                o.putInt(record + 8, o.getInt(record + 8) + at[i]);
            }
        }
        return out;
    }

    /** A {@code name} table of {@code {platform, encoding, language, nameId, value}} records. */
    private static byte[] nameTable(Object[]... records) {
        List<byte[]> values = new ArrayList<>();
        int storage = 6 + 12 * records.length;
        int size = storage;
        for (Object[] record : records) {
            byte[] value = ((String) record[4]).getBytes(
                    (Integer) record[0] == 1 ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_16BE);
            values.add(value);
            size += value.length;
        }
        ByteBuffer out = ByteBuffer.allocate(size);
        out.putShort((short) 0).putShort((short) records.length).putShort((short) storage);
        int offset = 0;
        for (int i = 0; i < records.length; i++) {
            for (int field = 0; field < 4; field++) {
                out.putShort(((Integer) records[i][field]).shortValue());
            }
            out.putShort((short) values.get(i).length).putShort((short) offset);
            offset += values.get(i).length;
        }
        for (byte[] value : values) {
            out.put(value);
        }
        return out.array();
    }

    /** {@code font} with its {@code tag} table replaced by {@code table}, appended after the rest. */
    private static byte[] withTable(byte[] font, int tag, byte[] table) {
        int at = (font.length + 3) & ~3;
        byte[] out = Arrays.copyOf(font, at + table.length);
        System.arraycopy(table, 0, out, at, table.length);
        ByteBuffer o = ByteBuffer.wrap(out);
        int numTables = o.getShort(4) & 0xFFFF;
        for (int t = 0; t < numTables; t++) {
            int record = 12 + 16 * t;
            if (o.getInt(record) == tag) {
                o.putInt(record + 8, at);
                o.putInt(record + 12, table.length);
            }
        }
        return out;
    }

    private static void assertTablesEqual(byte[] expected, byte[] actual) {
        Map<Integer, byte[]> want = tables(expected);
        Map<Integer, byte[]> got = tables(actual);
        assertEquals(want.keySet(), got.keySet());
        for (Map.Entry<Integer, byte[]> table : want.entrySet()) {
            assertArrayEquals("table " + Integer.toHexString(table.getKey()), table.getValue(), got.get(table.getKey()));
        }
    }

    private static Map<Integer, byte[]> tables(byte[] font) {
        ByteBuffer b = ByteBuffer.wrap(font);
        Map<Integer, byte[]> tables = new HashMap<>();
        int numTables = b.getShort(4) & 0xFFFF;
        for (int t = 0; t < numTables; t++) {
            int record = 12 + 16 * t;
            int offset = b.getInt(record + 8);
            int length = b.getInt(record + 12);
            tables.put(b.getInt(record), Arrays.copyOfRange(font, offset, offset + length));
        }
        return tables;
    }
}
