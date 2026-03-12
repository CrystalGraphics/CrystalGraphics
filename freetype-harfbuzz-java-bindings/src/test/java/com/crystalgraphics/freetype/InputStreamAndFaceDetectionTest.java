package com.crystalgraphics.freetype;

import com.crystalgraphics.harfbuzz.*;
import com.crystalgraphics.text.FreeTypeHarfBuzzIntegration;
import org.junit.Test;
import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

public class InputStreamAndFaceDetectionTest {

    private void requireNativesAvailable() {
        try {
            NativeLoader.ensureLoaded();
        } catch (UnsatisfiedLinkError e) {
            fail("Native library not available: " + e.getMessage());
        }
    }

    private byte[] loadTestFontData() {
        InputStream is = getClass().getResourceAsStream("/test-font.ttf");
        assertNotNull("Test font not found", is);
        return readAllBytes(is);
    }

    // --- PART 1: InputStream Support ---

    @Test
    public void testLoadFaceFromByteArrayInputStream() {
        requireNativesAvailable();
        byte[] fontData = loadTestFontData();

        FreeTypeLibrary lib = FreeTypeLibrary.create();
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(fontData);
            FTFace face = lib.newFaceFromStream(bais, 0);
            assertNotNull(face);
            assertFalse(face.isDestroyed());

            String familyName = face.getFamilyName();
            assertNotNull(familyName);
            assertTrue(face.getNumGlyphs() > 0);

            face.destroy();
        } catch (IOException e) {
            fail("Unexpected IOException: " + e.getMessage());
        } finally {
            lib.destroy();
        }
    }

    @Test
    public void testLoadFaceFromStreamDefaultIndex() {
        requireNativesAvailable();
        byte[] fontData = loadTestFontData();

        FreeTypeLibrary lib = FreeTypeLibrary.create();
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(fontData);
            FTFace face = lib.newFaceFromStream(bais);
            assertNotNull(face);

            assertTrue(face.getNumGlyphs() > 0);
            face.destroy();
        } catch (IOException e) {
            fail("Unexpected IOException: " + e.getMessage());
        } finally {
            lib.destroy();
        }
    }

    @Test
    public void testStreamLoadedFaceWorksIdenticallyToMemoryLoaded() {
        requireNativesAvailable();
        byte[] fontData = loadTestFontData();

        FreeTypeLibrary lib = FreeTypeLibrary.create();
        try {
            FTFace memFace = lib.newFaceFromMemory(fontData, 0);
            ByteArrayInputStream bais = new ByteArrayInputStream(fontData);
            FTFace streamFace = lib.newFaceFromStream(bais, 0);

            assertEquals(memFace.getFamilyName(), streamFace.getFamilyName());
            assertEquals(memFace.getStyleName(), streamFace.getStyleName());
            assertEquals(memFace.getNumGlyphs(), streamFace.getNumGlyphs());
            assertEquals(memFace.getUnitsPerEM(), streamFace.getUnitsPerEM());
            assertEquals(memFace.getAscender(), streamFace.getAscender());
            assertEquals(memFace.getDescender(), streamFace.getDescender());

            memFace.setPixelSizes(0, 24);
            streamFace.setPixelSizes(0, 24);

            int glyphA = memFace.getCharIndex('A');
            assertEquals(glyphA, streamFace.getCharIndex('A'));

            memFace.loadGlyph(glyphA, FTLoadFlags.FT_LOAD_DEFAULT);
            streamFace.loadGlyph(glyphA, FTLoadFlags.FT_LOAD_DEFAULT);

            FTGlyphMetrics memMetrics = memFace.getGlyphMetrics();
            FTGlyphMetrics streamMetrics = streamFace.getGlyphMetrics();
            assertEquals(memMetrics.getWidth(), streamMetrics.getWidth());
            assertEquals(memMetrics.getHeight(), streamMetrics.getHeight());
            assertEquals(memMetrics.getHoriAdvance(), streamMetrics.getHoriAdvance());

            streamFace.destroy();
            memFace.destroy();
        } catch (IOException e) {
            fail("Unexpected IOException: " + e.getMessage());
        } finally {
            lib.destroy();
        }
    }

    @Test
    public void testMultipleFacesFromSameData() {
        requireNativesAvailable();
        byte[] fontData = loadTestFontData();

        FreeTypeLibrary lib = FreeTypeLibrary.create();
        try {
            FTFace face1 = lib.newFaceFromMemory(fontData, 0);
            FTFace face2 = lib.newFaceFromMemory(fontData, 0);

            assertNotSame(face1, face2);
            assertEquals(face1.getFamilyName(), face2.getFamilyName());
            assertEquals(face1.getNumGlyphs(), face2.getNumGlyphs());

            face1.setPixelSizes(0, 16);
            face2.setPixelSizes(0, 32);

            int glyphA = face1.getCharIndex('A');
            face1.loadGlyph(glyphA, FTLoadFlags.FT_LOAD_RENDER);
            face2.loadGlyph(glyphA, FTLoadFlags.FT_LOAD_RENDER);

            FTBitmap bm1 = face1.getGlyphBitmap();
            FTBitmap bm2 = face2.getGlyphBitmap();
            assertTrue("Larger size should produce larger bitmap",
                    bm2.getWidth() > bm1.getWidth() || bm2.getHeight() > bm1.getHeight());

            face2.destroy();
            face1.destroy();
        } finally {
            lib.destroy();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testLoadFaceFromNullStream() throws IOException {
        requireNativesAvailable();
        FreeTypeLibrary lib = FreeTypeLibrary.create();
        try {
            lib.newFaceFromStream(null, 0);
        } finally {
            lib.destroy();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testLoadFaceFromNullData() {
        requireNativesAvailable();
        FreeTypeLibrary lib = FreeTypeLibrary.create();
        try {
            lib.newFaceFromMemory(null, 0);
        } finally {
            lib.destroy();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testLoadFaceFromEmptyData() {
        requireNativesAvailable();
        FreeTypeLibrary lib = FreeTypeLibrary.create();
        try {
            lib.newFaceFromMemory(new byte[0], 0);
        } finally {
            lib.destroy();
        }
    }

    @Test
    public void testStreamLoadedFaceWithHarfBuzzShaping() {
        requireNativesAvailable();
        byte[] fontData = loadTestFontData();

        FreeTypeLibrary lib = FreeTypeLibrary.create();
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(fontData);
            FTFace face = lib.newFaceFromStream(bais, 0);
            face.setPixelSizes(0, 24);

            HBFont hbFont = FreeTypeHarfBuzzIntegration.createHBFontFromFTFace(face);
            assertNotNull(hbFont);

            HBBuffer buffer = HBBuffer.create();
            buffer.addUTF8("Hello, World!");
            buffer.guessSegmentProperties();
            HBShape.shape(hbFont, buffer);

            HBGlyphInfo[] infos = buffer.getGlyphInfos();
            HBGlyphPosition[] positions = buffer.getGlyphPositions();
            assertTrue("Should produce glyphs", infos.length > 0);
            assertEquals(infos.length, positions.length);

            buffer.destroy();
            hbFont.destroy();
            face.destroy();
        } catch (IOException e) {
            fail("Unexpected IOException: " + e.getMessage());
        } finally {
            lib.destroy();
        }
    }

    // --- HBFont InputStream/Memory support ---

    @Test
    public void testHBFontCreateFromMemory() {
        requireNativesAvailable();
        byte[] fontData = loadTestFontData();

        HBFont font = HBFont.createFromMemory(fontData, 0);
        assertNotNull(font);
        assertFalse(font.isDestroyed());

        HBBuffer buffer = HBBuffer.create();
        buffer.addUTF8("Test");
        buffer.guessSegmentProperties();
        HBShape.shape(font, buffer);

        HBGlyphInfo[] infos = buffer.getGlyphInfos();
        assertTrue("Should produce glyphs", infos.length > 0);

        buffer.destroy();
        font.destroy();
        assertTrue(font.isDestroyed());
    }

    @Test
    public void testHBFontCreateFromMemoryDefaultIndex() {
        requireNativesAvailable();
        byte[] fontData = loadTestFontData();

        HBFont font = HBFont.createFromMemory(fontData);
        assertNotNull(font);

        HBBuffer buffer = HBBuffer.create();
        buffer.addUTF8("Hi");
        buffer.guessSegmentProperties();
        HBShape.shape(font, buffer);
        assertTrue(buffer.getGlyphInfos().length > 0);

        buffer.destroy();
        font.destroy();
    }

    @Test
    public void testHBFontCreateFromStream() throws IOException {
        requireNativesAvailable();
        byte[] fontData = loadTestFontData();

        ByteArrayInputStream bais = new ByteArrayInputStream(fontData);
        HBFont font = HBFont.createFromStream(bais, 0);
        assertNotNull(font);

        HBBuffer buffer = HBBuffer.create();
        buffer.addUTF8("Stream");
        buffer.guessSegmentProperties();
        HBShape.shape(font, buffer);
        assertTrue(buffer.getGlyphInfos().length > 0);

        buffer.destroy();
        font.destroy();
    }

    @Test
    public void testHBFontCreateFromStreamDefaultIndex() throws IOException {
        requireNativesAvailable();
        byte[] fontData = loadTestFontData();

        ByteArrayInputStream bais = new ByteArrayInputStream(fontData);
        HBFont font = HBFont.createFromStream(bais);
        assertNotNull(font);

        font.destroy();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testHBFontCreateFromNullMemory() {
        requireNativesAvailable();
        HBFont.createFromMemory(null, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testHBFontCreateFromEmptyMemory() {
        requireNativesAvailable();
        HBFont.createFromMemory(new byte[0], 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testHBFontCreateFromNullStream() throws IOException {
        requireNativesAvailable();
        HBFont.createFromStream(null, 0);
    }

    // --- PART 2: Face Detection ---

    @Test
    public void testGetFaceCountSingleFaceFont() {
        requireNativesAvailable();
        byte[] fontData = loadTestFontData();

        FreeTypeLibrary lib = FreeTypeLibrary.create();
        try {
            int count = lib.getFaceCount(fontData);
            assertEquals("Single-face TTF should have exactly 1 face", 1, count);
        } finally {
            lib.destroy();
        }
    }

    @Test
    public void testGetFaceCountFromStream() throws IOException {
        requireNativesAvailable();
        byte[] fontData = loadTestFontData();

        FreeTypeLibrary lib = FreeTypeLibrary.create();
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(fontData);
            int count = lib.getFaceCount(bais);
            assertEquals("Single-face TTF should have exactly 1 face", 1, count);
        } finally {
            lib.destroy();
        }
    }

    @Test
    public void testGetNumFacesFromLoadedFace() {
        requireNativesAvailable();
        byte[] fontData = loadTestFontData();

        FreeTypeLibrary lib = FreeTypeLibrary.create();
        try {
            FTFace face = lib.newFaceFromMemory(fontData, 0);
            int numFaces = face.getNumFaces();
            assertEquals("Single-face TTF should report 1 face", 1, numFaces);
            face.destroy();
        } finally {
            lib.destroy();
        }
    }

    @Test
    public void testDetectFaceIndicesSingleFont() {
        requireNativesAvailable();
        byte[] fontData = loadTestFontData();

        FreeTypeLibrary lib = FreeTypeLibrary.create();
        try {
            int[] indices = FTFaceDetector.detectFaceIndices(lib, fontData);
            assertNotNull(indices);
            assertEquals(1, indices.length);
            assertEquals(0, indices[0]);
        } finally {
            lib.destroy();
        }
    }

    @Test
    public void testDetectFaceIndicesFromStream() throws IOException {
        requireNativesAvailable();
        byte[] fontData = loadTestFontData();

        FreeTypeLibrary lib = FreeTypeLibrary.create();
        try {
            ByteArrayInputStream bais = new ByteArrayInputStream(fontData);
            int[] indices = FTFaceDetector.detectFaceIndices(lib, bais);
            assertNotNull(indices);
            assertEquals(1, indices.length);
            assertEquals(0, indices[0]);
        } finally {
            lib.destroy();
        }
    }

    @Test
    public void testFaceDetectorGetFaceCount() {
        requireNativesAvailable();
        byte[] fontData = loadTestFontData();

        FreeTypeLibrary lib = FreeTypeLibrary.create();
        try {
            int count = FTFaceDetector.getFaceCount(lib, fontData);
            assertEquals(1, count);
        } finally {
            lib.destroy();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetFaceCountNullData() {
        requireNativesAvailable();
        FreeTypeLibrary lib = FreeTypeLibrary.create();
        try {
            lib.getFaceCount((byte[]) null);
        } finally {
            lib.destroy();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testGetFaceCountEmptyData() {
        requireNativesAvailable();
        FreeTypeLibrary lib = FreeTypeLibrary.create();
        try {
            lib.getFaceCount(new byte[0]);
        } finally {
            lib.destroy();
        }
    }

    // --- Combined: InputStream + Face Detection ---

    @Test
    public void testDetectAndLoadFromStream() throws IOException {
        requireNativesAvailable();
        byte[] fontData = loadTestFontData();

        FreeTypeLibrary lib = FreeTypeLibrary.create();
        try {
            int[] indices = FTFaceDetector.detectFaceIndices(lib, fontData);
            assertTrue("Should detect at least one face", indices.length > 0);

            ByteArrayInputStream bais = new ByteArrayInputStream(fontData);
            FTFace face = lib.newFaceFromStream(bais, indices[0]);
            assertNotNull(face);
            assertTrue(face.getNumGlyphs() > 0);

            face.destroy();
        } finally {
            lib.destroy();
        }
    }

    @Test
    public void testMemoryCleanupAfterStreamLoad() {
        requireNativesAvailable();
        byte[] fontData = loadTestFontData();

        FreeTypeLibrary lib = FreeTypeLibrary.create();
        try {
            for (int i = 0; i < 10; i++) {
                ByteArrayInputStream bais = new ByteArrayInputStream(fontData);
                FTFace face;
                try {
                    face = lib.newFaceFromStream(bais, 0);
                } catch (IOException e) {
                    fail("Unexpected IOException on iteration " + i);
                    return;
                }
                face.setPixelSizes(0, 24);
                int glyphA = face.getCharIndex('A');
                face.loadGlyph(glyphA, FTLoadFlags.FT_LOAD_RENDER);
                FTBitmap bitmap = face.getGlyphBitmap();
                assertNotNull(bitmap);
                face.destroy();
            }
        } finally {
            lib.destroy();
        }
    }

    @Test
    public void testInvalidFontDataThrows() {
        requireNativesAvailable();
        FreeTypeLibrary lib = FreeTypeLibrary.create();
        try {
            byte[] garbage = new byte[]{0x00, 0x01, 0x02, 0x03, 0x04};
            try {
                lib.newFaceFromMemory(garbage, 0);
                fail("Should throw FreeTypeException for invalid font data");
            } catch (FreeTypeException expected) {
                assertNotNull(expected.getMessage());
            }
        } finally {
            lib.destroy();
        }
    }

    @Test
    public void testInvalidFontDataFaceCountThrows() {
        requireNativesAvailable();
        FreeTypeLibrary lib = FreeTypeLibrary.create();
        try {
            byte[] garbage = new byte[]{0x00, 0x01, 0x02, 0x03, 0x04};
            try {
                lib.getFaceCount(garbage);
                fail("Should throw FreeTypeException for invalid font data");
            } catch (FreeTypeException expected) {
                assertNotNull(expected.getMessage());
            }
        } finally {
            lib.destroy();
        }
    }

    private static byte[] readAllBytes(InputStream is) {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            is.close();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
