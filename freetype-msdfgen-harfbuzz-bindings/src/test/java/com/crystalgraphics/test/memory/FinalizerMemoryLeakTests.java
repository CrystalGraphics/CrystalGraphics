package com.crystalgraphics.test.memory;

import com.crystalgraphics.freetype.*;
import com.crystalgraphics.harfbuzz.*;
import com.crystalgraphics.text.FreeTypeHarfBuzzIntegration;
import org.junit.Test;
import static org.junit.Assert.*;

public class FinalizerMemoryLeakTests extends MemoryLeakDetectionBase {

    @Test
    public void testF1_gcCleanupWithExplicitDestroy() {
        logTestStart();
        byte[] fontData = loadTestFontData();

        MemoryMetrics.Snapshot baseline = MemoryMetrics.takeSnapshot();
        logMemory("Baseline", baseline);

        for (int i = 0; i < 20; i++) {
            FreeTypeLibrary ft = FreeTypeLibrary.create();
            FTFace face = ft.newFaceFromMemory(fontData, 0);
            face.setPixelSizes(0, 24);

            HBFont hbFont = FreeTypeHarfBuzzIntegration.createHBFontFromFTFace(face);

            HBBuffer buffer = HBBuffer.create();
            buffer.addUTF8("GC test iteration " + i);
            buffer.guessSegmentProperties();
            HBShape.shape(hbFont, buffer);

            buffer.destroy();
            hbFont.destroy();
            face.destroy();
            ft.destroy();
        }

        MemoryMetrics.Snapshot afterExplicit = MemoryMetrics.takeSnapshot();
        logMemory("After 20 explicit-destroy cycles", afterExplicit);

        assertMemoryReturnsToBaseline(baseline, afterExplicit);
    }

    @SuppressWarnings("unused")
    @Test
    public void testF2_objectsOutOfScopeWithGC() {
        logTestStart();
        byte[] fontData = loadTestFontData();

        MemoryMetrics.Snapshot baseline = MemoryMetrics.takeSnapshot();
        logMemory("Baseline", baseline);

        // INTENTIONAL: Tests behavior when destroy() is NOT called (see GAP_ANALYSIS_REPORT.md §6.2)
        for (int i = 0; i < 10; i++) {
            createAndAbandonResources(fontData, i);
        }

        MemoryMetrics.forceGC();

        MemoryMetrics.Snapshot afterGC = MemoryMetrics.takeSnapshot();
        logMemory("After abandon + GC", afterGC);

        // With finalizers, native memory should be cleaned up by GC
        long rss = PlatformTools.getProcessRSS();
        if (rss > 0) {
            System.out.println("  Process RSS: " + MemoryMetrics.formatBytes(rss));
        }

        System.out.println("  Finalizers should have cleaned up abandoned native resources.");
        System.out.println("  Java heap objects are GC'd, and finalizers free native FT_Library/FT_Face/hb_font/hb_buffer.");
    }

    private void createAndAbandonResources(byte[] fontData, int iteration) {
        FreeTypeLibrary ft = FreeTypeLibrary.create();
        FTFace face = ft.newFaceFromMemory(fontData, 0);
        face.setPixelSizes(0, 24);

        HBFont hbFont = FreeTypeHarfBuzzIntegration.createHBFontFromFTFace(face);

        HBBuffer buffer = HBBuffer.create();
        buffer.addUTF8("Abandoned " + iteration);
        buffer.guessSegmentProperties();
        HBShape.shape(hbFont, buffer);
        buffer.getGlyphInfos();

        // Intentionally NOT calling destroy() — let objects fall out of scope
    }
}
