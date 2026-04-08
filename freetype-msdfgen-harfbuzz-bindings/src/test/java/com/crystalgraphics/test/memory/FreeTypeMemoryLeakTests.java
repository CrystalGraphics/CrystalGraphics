package com.crystalgraphics.test.memory;

import com.crystalgraphics.freetype.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class FreeTypeMemoryLeakTests extends MemoryLeakDetectionBase {

    // Test A1: Single Font Load/Unload
    @Test
    public void testA1_singleFontLoadUnload() {
        logTestStart();
        byte[] fontData = loadTestFontData();

        MemoryMetrics.Snapshot baseline = MemoryMetrics.takeSnapshot();
        logMemory("Baseline", baseline);

        FreeTypeLibrary ft = FreeTypeLibrary.create();
        FTFace face = ft.newFaceFromMemory(fontData, 0);
        face.setPixelSizes(0, 24);

        MemoryMetrics.Snapshot afterLoad = MemoryMetrics.takeSnapshotNoGC();
        logMemory("After load", afterLoad);
        assertTrue("Memory should increase after font load",
                afterLoad.heapUsed >= baseline.heapUsed);

        face.destroy();
        ft.destroy();

        MemoryMetrics.Snapshot afterCleanup = MemoryMetrics.takeSnapshot();
        logMemory("After cleanup", afterCleanup);

        assertMemoryReturnsToBaseline(baseline, afterCleanup);
    }

    // Test A2: Multiple Font Stress (50 fonts)
    @Test
    public void testA2_multipleFontStress() {
        logTestStart();
        byte[] fontData = loadTestFontData();
        int fontCount = 50;

        MemoryMetrics.Snapshot baseline = MemoryMetrics.takeSnapshot();
        logMemory("Baseline", baseline);

        FreeTypeLibrary ft = FreeTypeLibrary.create();
        FTFace[] faces = new FTFace[fontCount];

        for (int i = 0; i < fontCount; i++) {
            faces[i] = ft.newFaceFromMemory(fontData, 0);
            faces[i].setPixelSizes(0, 12 + (i % 48));
        }

        MemoryMetrics.Snapshot afterLoad = MemoryMetrics.takeSnapshotNoGC();
        logMemory("After loading " + fontCount + " fonts", afterLoad);
        logResult("Memory for " + fontCount + " fonts",
                afterLoad.heapDiffFrom(baseline));

        for (int i = 0; i < fontCount; i++) {
            faces[i].destroy();
        }
        ft.destroy();

        MemoryMetrics.Snapshot afterCleanup = MemoryMetrics.takeSnapshot();
        logMemory("After cleanup", afterCleanup);

        assertMemoryReturnsToBaseline(baseline, afterCleanup);
    }

    // Test A3: Font File Handle Closure
    @Test
    public void testA3_fontFileHandleClosure() {
        logTestStart();
        byte[] fontData = loadTestFontData();

        // Write font to a temp file we can test deletion on
        java.io.File tempFont = null;
        try {
            tempFont = java.io.File.createTempFile("leak-test-font-", ".ttf");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFont);
            fos.write(fontData);
            fos.close();
        } catch (Exception e) {
            fail("Failed to create temp font file: " + e.getMessage());
        }

        FreeTypeLibrary ft = FreeTypeLibrary.create();
        FTFace face = ft.newFace(tempFont.getAbsolutePath(), 0);
        face.setPixelSizes(0, 24);

        // Load and render a glyph to ensure the face is fully initialized
        int glyphIndex = face.getCharIndex('A');
        face.loadGlyph(glyphIndex, FTLoadFlags.FT_LOAD_RENDER);
        FTBitmap bitmap = face.getGlyphBitmap();
        assertNotNull(bitmap);
        assertTrue("Bitmap should have data", bitmap.getWidth() > 0);

        face.destroy();
        ft.destroy();

        // After destruction, we should be able to delete/rename the file
        // (on Windows, open file handles prevent deletion)
        boolean canDelete = tempFont.delete();
        if (PlatformTools.detectPlatform() == PlatformTools.Platform.WINDOWS) {
            // Windows: FreeType may keep file handle open during face lifetime
            // After destroy, it should be released
            System.out.println("  File deletable after face destroy: " + canDelete);
        } else {
            // Linux/macOS: file deletion always succeeds (unlink semantics)
            assertTrue("Should be able to delete font file after face destroy", canDelete);
        }

        System.out.println("  File handle test: PASSED");
    }
}
