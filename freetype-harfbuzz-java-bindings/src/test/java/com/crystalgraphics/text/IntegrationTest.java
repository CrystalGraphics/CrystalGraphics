package com.crystalgraphics.text;

import com.crystalgraphics.freetype.*;
import com.crystalgraphics.harfbuzz.*;
import org.junit.Test;
import org.junit.Assume;
import static org.junit.Assert.*;

public class IntegrationTest {

    private boolean nativesAvailable() {
        try {
            com.crystalgraphics.freetype.NativeLoader.ensureLoaded();
            return true;
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    @Test
    public void testFreeTypeHarfBuzzPipeline() {
        Assume.assumeTrue("Natives not available", nativesAvailable());

        java.io.InputStream is = getClass().getResourceAsStream("/test-font.ttf");
        Assume.assumeNotNull("Test font not found", is);
        byte[] fontData = readAllBytes(is);

        FreeTypeLibrary ftLib = FreeTypeLibrary.create();
        FTFace face = ftLib.newFaceFromMemory(fontData, 0);
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

        int cursorX = 0;
        for (int i = 0; i < infos.length; i++) {
            int glyphId = infos[i].getCodepoint();
            int xOffset = positions[i].getXOffset();
            int yOffset = positions[i].getYOffset();
            int xAdvance = positions[i].getXAdvance();

            face.loadGlyph(glyphId, FTLoadFlags.FT_LOAD_RENDER);
            FTBitmap bitmap = face.getGlyphBitmap();

            int drawX = cursorX + (xOffset >> 6) + bitmap.getLeft();
            int drawY = -(yOffset >> 6) - bitmap.getTop();

            assertTrue("Glyph " + glyphId + " should have valid bitmap",
                    bitmap.getWidth() >= 0 && bitmap.getHeight() >= 0);

            cursorX += xAdvance >> 6;
        }

        assertTrue("Total advance should be positive", cursorX > 0);

        buffer.destroy();
        hbFont.destroy();
        face.destroy();
        ftLib.destroy();
    }

    @Test
    public void testSyncMetricsAfterSizeChange() {
        Assume.assumeTrue("Natives not available", nativesAvailable());

        java.io.InputStream is = getClass().getResourceAsStream("/test-font.ttf");
        Assume.assumeNotNull("Test font not found", is);
        byte[] fontData = readAllBytes(is);

        FreeTypeLibrary ftLib = FreeTypeLibrary.create();
        FTFace face = ftLib.newFaceFromMemory(fontData, 0);

        face.setPixelSizes(0, 16);
        HBFont hbFont16 = FreeTypeHarfBuzzIntegration.createHBFontFromFTFace(face);

        HBBuffer buf1 = HBBuffer.create();
        buf1.addUTF8("Test");
        buf1.guessSegmentProperties();
        HBShape.shape(hbFont16, buf1);
        int advance16 = 0;
        for (HBGlyphPosition p : buf1.getGlyphPositions()) {
            advance16 += p.getXAdvance();
        }
        buf1.destroy();
        hbFont16.destroy();

        face.setPixelSizes(0, 32);
        HBFont hbFont32 = FreeTypeHarfBuzzIntegration.createHBFontFromFTFace(face);

        HBBuffer buf2 = HBBuffer.create();
        buf2.addUTF8("Test");
        buf2.guessSegmentProperties();
        HBShape.shape(hbFont32, buf2);
        int advance32 = 0;
        for (HBGlyphPosition p : buf2.getGlyphPositions()) {
            advance32 += p.getXAdvance();
        }
        buf2.destroy();
        hbFont32.destroy();

        assertTrue("32px text should be wider than 16px text", advance32 > advance16);

        face.destroy();
        ftLib.destroy();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testIntegrationWithDestroyedFace() {
        Assume.assumeTrue("Natives not available", nativesAvailable());

        java.io.InputStream is = getClass().getResourceAsStream("/test-font.ttf");
        Assume.assumeNotNull("Test font not found", is);
        byte[] fontData = readAllBytes(is);

        FreeTypeLibrary ftLib = FreeTypeLibrary.create();
        try {
            FTFace face = ftLib.newFaceFromMemory(fontData, 0);
            face.destroy();
            FreeTypeHarfBuzzIntegration.createHBFontFromFTFace(face);
        } finally {
            ftLib.destroy();
        }
    }

    private static byte[] readAllBytes(java.io.InputStream is) {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            is.close();
            return bos.toByteArray();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
