package com.crystalgraphics.test.memory;

import com.crystalgraphics.freetype.*;
import com.crystalgraphics.harfbuzz.*;
import com.crystalgraphics.text.FreeTypeHarfBuzzIntegration;
import org.junit.Test;
import static org.junit.Assert.*;

public class IntegrationMemoryLeakTests extends MemoryLeakDetectionBase {

    @Test
    public void testC1_correctCleanupOrder() {
        logTestStart();
        byte[] fontData = loadTestFontData();

        MemoryMetrics.Snapshot baseline = MemoryMetrics.takeSnapshot();
        logMemory("Baseline", baseline);

        FreeTypeLibrary ft = FreeTypeLibrary.create();
        FTFace face = ft.newFaceFromMemory(fontData, 0);
        face.setPixelSizes(0, 24);

        HBFont hbFont = FreeTypeHarfBuzzIntegration.createHBFontFromFTFace(face);
        assertNotNull(hbFont);

        HBBuffer buffer = HBBuffer.create();
        buffer.addUTF8("Integration test — correct order");
        buffer.guessSegmentProperties();
        HBShape.shape(hbFont, buffer);

        HBGlyphInfo[] infos = buffer.getGlyphInfos();
        HBGlyphPosition[] positions = buffer.getGlyphPositions();
        assertTrue("Should produce glyphs", infos.length > 0);

        for (int i = 0; i < infos.length; i++) {
            face.loadGlyph(infos[i].getCodepoint(), FTLoadFlags.FT_LOAD_RENDER);
            FTBitmap bitmap = face.getGlyphBitmap();
            assertNotNull(bitmap);
        }

        // Correct order: buffer -> hbFont -> face -> library
        buffer.destroy();
        assertTrue(buffer.isDestroyed());

        hbFont.destroy();
        assertTrue(hbFont.isDestroyed());

        face.destroy();
        assertTrue(face.isDestroyed());

        ft.destroy();
        assertTrue(ft.isDestroyed());

        MemoryMetrics.Snapshot afterCleanup = MemoryMetrics.takeSnapshot();
        logMemory("After cleanup", afterCleanup);

        assertMemoryReturnsToBaseline(baseline, afterCleanup);
    }

    @Test
    public void testC2_wrongCleanupOrderDocumented() {
        logTestStart();
        byte[] fontData = loadTestFontData();

        FreeTypeLibrary ft = FreeTypeLibrary.create();
        FTFace face = ft.newFaceFromMemory(fontData, 0);
        face.setPixelSizes(0, 24);

        HBFont hbFont = FreeTypeHarfBuzzIntegration.createHBFontFromFTFace(face);

        // WRONG ORDER: destroy FT_Face first while HBFont still references it
        // This should now throw IllegalStateException instead of causing use-after-free
        try {
            face.destroy();
            org.junit.Assert.fail("Should have thrown IllegalStateException for wrong destroy order");
        } catch (IllegalStateException e) {
            System.out.println("  Caught expected IllegalStateException: " + e.getMessage());
        }

        assertFalse("FTFace should NOT be destroyed (was prevented)", face.isDestroyed());

        // Correct cleanup order
        hbFont.destroy();
        assertTrue("HBFont should be marked destroyed", hbFont.isDestroyed());

        face.destroy();
        assertTrue("FTFace should now be destroyed", face.isDestroyed());

        ft.destroy();
        System.out.println("  Test C2 PASSED: Wrong order prevented with clear exception");
    }

    @Test
    public void testC3_multipleFontsMultipleBuffers() {
        logTestStart();
        byte[] fontData = loadTestFontData();
        int fontCount = 5;
        int buffersPerFont = 5;

        MemoryMetrics.Snapshot baseline = MemoryMetrics.takeSnapshot();
        logMemory("Baseline", baseline);

        FreeTypeLibrary ft = FreeTypeLibrary.create();
        FTFace[] faces = new FTFace[fontCount];
        HBFont[] hbFonts = new HBFont[fontCount];
        HBBuffer[][] buffers = new HBBuffer[fontCount][buffersPerFont];

        for (int f = 0; f < fontCount; f++) {
            faces[f] = ft.newFaceFromMemory(fontData, 0);
            faces[f].setPixelSizes(0, 12 + f * 4);
            hbFonts[f] = FreeTypeHarfBuzzIntegration.createHBFontFromFTFace(faces[f]);

            for (int b = 0; b < buffersPerFont; b++) {
                buffers[f][b] = HBBuffer.create();
                buffers[f][b].addUTF8("Font " + f + " buffer " + b);
                buffers[f][b].guessSegmentProperties();
                HBShape.shape(hbFonts[f], buffers[f][b]);

                HBGlyphInfo[] infos = buffers[f][b].getGlyphInfos();
                assertTrue("Should produce glyphs", infos.length > 0);
            }
        }

        logMemory("After loading " + fontCount + " fonts × " + buffersPerFont + " buffers",
                MemoryMetrics.takeSnapshotNoGC());

        // Correct cleanup: buffers first, then HBFonts, then faces
        for (int f = 0; f < fontCount; f++) {
            for (int b = 0; b < buffersPerFont; b++) {
                buffers[f][b].destroy();
            }
            hbFonts[f].destroy();
            faces[f].destroy();
        }
        ft.destroy();

        MemoryMetrics.Snapshot afterCleanup = MemoryMetrics.takeSnapshot();
        logMemory("After cleanup", afterCleanup);

        assertMemoryReturnsToBaseline(baseline, afterCleanup);
    }

    @Test
    public void testC4_fontReuseAcrossBuffers() {
        logTestStart();
        byte[] fontData = loadTestFontData();
        int bufferCount = 10;

        MemoryMetrics.Snapshot baseline = MemoryMetrics.takeSnapshot();
        logMemory("Baseline", baseline);

        FreeTypeLibrary ft = FreeTypeLibrary.create();
        FTFace face = ft.newFaceFromMemory(fontData, 0);
        face.setPixelSizes(0, 24);
        HBFont hbFont = FreeTypeHarfBuzzIntegration.createHBFontFromFTFace(face);

        HBBuffer[] buffers = new HBBuffer[bufferCount];
        String[] texts = {
            "Hello", "World", "Testing", "Multiple", "Buffers",
            "With", "Same", "Font", "Object", "Shared"
        };

        for (int i = 0; i < bufferCount; i++) {
            buffers[i] = HBBuffer.create();
            buffers[i].addUTF8(texts[i]);
            buffers[i].guessSegmentProperties();
            HBShape.shape(hbFont, buffers[i]);

            HBGlyphInfo[] infos = buffers[i].getGlyphInfos();
            assertTrue("Buffer " + i + " should produce glyphs", infos.length > 0);
        }

        for (int i = 0; i < bufferCount; i++) {
            buffers[i].destroy();
        }
        hbFont.destroy();
        face.destroy();
        ft.destroy();

        MemoryMetrics.Snapshot afterCleanup = MemoryMetrics.takeSnapshot();
        logMemory("After cleanup", afterCleanup);

        assertMemoryReturnsToBaseline(baseline, afterCleanup);
    }

    @Test
    public void testC5_integrationErrorPathInvalidFont() {
        logTestStart();

        MemoryMetrics.Snapshot baseline = MemoryMetrics.takeSnapshot();
        logMemory("Baseline", baseline);

        FreeTypeLibrary ft = FreeTypeLibrary.create();

        // Attempt to load garbage data as a font
        try {
            FTFace face = ft.newFaceFromMemory(new byte[]{0, 1, 2, 3}, 0);
            // If we get here, the format was somehow accepted — clean up
            face.destroy();
            System.out.println("  Garbage data was accepted as font (unexpected but handled)");
        } catch (FreeTypeException e) {
            System.out.println("  FreeTypeException caught as expected: " + e.getMessage());
        } catch (RuntimeException e) {
            System.out.println("  RuntimeException caught: " + e.getMessage());
        }

        // Attempt to create HBFont from destroyed face
        try {
            FTFace face = ft.newFaceFromMemory(loadTestFontData(), 0);
            face.destroy();
            FreeTypeHarfBuzzIntegration.createHBFontFromFTFace(face);
            fail("Should have thrown for destroyed face");
        } catch (IllegalArgumentException e) {
            System.out.println("  IllegalArgumentException for destroyed face: expected");
        }

        ft.destroy();

        MemoryMetrics.Snapshot afterCleanup = MemoryMetrics.takeSnapshot();
        logMemory("After cleanup", afterCleanup);

        assertMemoryReturnsToBaseline(baseline, afterCleanup);
    }

    @Test
    public void testC6_integrationStressWithRepeatedCreateDestroy() {
        logTestStart();
        byte[] fontData = loadTestFontData();
        int iterations = 50;

        MemoryMetrics.Snapshot baseline = MemoryMetrics.takeSnapshot();
        logMemory("Baseline", baseline);

        long[] measurements = new long[iterations];

        for (int i = 0; i < iterations; i++) {
            FreeTypeLibrary ft = FreeTypeLibrary.create();
            FTFace face = ft.newFaceFromMemory(fontData, 0);
            face.setPixelSizes(0, 24);

            HBFont hbFont = FreeTypeHarfBuzzIntegration.createHBFontFromFTFace(face);

            HBBuffer buffer = HBBuffer.create();
            buffer.addUTF8("Iteration " + i);
            buffer.guessSegmentProperties();
            HBShape.shape(hbFont, buffer);

            HBGlyphInfo[] infos = buffer.getGlyphInfos();
            assertTrue(infos.length > 0);

            buffer.destroy();
            hbFont.destroy();
            face.destroy();
            ft.destroy();

            if (i % 10 == 9) {
                MemoryMetrics.Snapshot snap = MemoryMetrics.takeSnapshot();
                measurements[i / 10] = snap.heapUsed;
            }
        }

        MemoryMetrics.Snapshot afterCleanup = MemoryMetrics.takeSnapshot();
        logMemory("After " + iterations + " full cycles", afterCleanup);

        // Trim measurements to only filled entries
        long[] filledMeasurements = new long[iterations / 10];
        System.arraycopy(measurements, 0, filledMeasurements, 0, filledMeasurements.length);
        assertNoMemoryGrowthTrend(filledMeasurements, 2048.0);
        assertMemoryReturnsToBaseline(baseline, afterCleanup);
    }
}
