package com.crystalgraphics.test.memory;

import com.crystalgraphics.freetype.*;
import com.crystalgraphics.harfbuzz.*;
import com.crystalgraphics.text.FreeTypeHarfBuzzIntegration;
import org.junit.Test;
import static org.junit.Assert.*;

public class StressMemoryLeakTests extends MemoryLeakDetectionBase {

    @Test
    public void testE1_highVolumeStress() {
        logTestStart();
        byte[] fontData = loadTestFontData();
        int fontCount = 100;
        int totalBuffers = 1000;

        MemoryMetrics.Snapshot baseline = MemoryMetrics.takeSnapshot();
        logMemory("Baseline", baseline);

        FreeTypeLibrary ft = FreeTypeLibrary.create();
        FTFace[] faces = new FTFace[fontCount];
        HBFont[] hbFonts = new HBFont[fontCount];

        for (int f = 0; f < fontCount; f++) {
            faces[f] = ft.newFaceFromMemory(fontData, 0);
            faces[f].setPixelSizes(0, 8 + (f % 56));
            hbFonts[f] = FreeTypeHarfBuzzIntegration.createHBFontFromFTFace(faces[f]);
        }

        logMemory("After loading " + fontCount + " fonts", MemoryMetrics.takeSnapshotNoGC());

        int buffersPerFont = totalBuffers / fontCount;
        for (int f = 0; f < fontCount; f++) {
            for (int b = 0; b < buffersPerFont; b++) {
                HBBuffer buffer = HBBuffer.create();
                buffer.addUTF8("Stress F" + f + "B" + b);
                buffer.guessSegmentProperties();
                HBShape.shape(hbFonts[f], buffer);

                HBGlyphInfo[] infos = buffer.getGlyphInfos();
                assertTrue(infos.length > 0);

                buffer.destroy();
            }
        }

        logMemory("After " + totalBuffers + " shape operations", MemoryMetrics.takeSnapshotNoGC());

        for (int f = 0; f < fontCount; f++) {
            hbFonts[f].destroy();
            faces[f].destroy();
        }
        ft.destroy();

        MemoryMetrics.Snapshot afterCleanup = MemoryMetrics.takeSnapshot();
        logMemory("After cleanup", afterCleanup);

        assertMemoryReturnsToBaseline(baseline, afterCleanup);
    }

    @Test
    public void testE2_complexUnicodeStress() {
        logTestStart();
        byte[] fontData = loadTestFontData();

        String[] testStrings = {
            "Hello, World!",
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ 0123456789",
            "The quick brown fox jumps over the lazy dog",
            "!@#$%^&*()_+-=[]{}|;':\",./<>?",
            "Mixed ASCII and numbers 123 456 789",
            "Longer text with repeated patterns: aaa bbb ccc ddd eee fff",
            "Programming: if (x > 0) { return true; } else { return false; }",
            "Mathematical: a^2 + b^2 = c^2, E = mc^2",
            "Punctuation heavy... Really? Yes! No... Maybe?!",
            "spaces   tabs\there   mixed   whitespace"
        };
        int repetitions = 100;

        MemoryMetrics.Snapshot baseline = MemoryMetrics.takeSnapshot();
        logMemory("Baseline", baseline);

        FreeTypeLibrary ft = FreeTypeLibrary.create();
        FTFace face = ft.newFaceFromMemory(fontData, 0);
        face.setPixelSizes(0, 24);
        HBFont hbFont = FreeTypeHarfBuzzIntegration.createHBFontFromFTFace(face);

        long[] measurements = new long[repetitions / 10];

        for (int rep = 0; rep < repetitions; rep++) {
            for (String text : testStrings) {
                HBBuffer buffer = HBBuffer.create();
                buffer.addUTF8(text);
                buffer.guessSegmentProperties();
                HBShape.shape(hbFont, buffer);

                HBGlyphInfo[] infos = buffer.getGlyphInfos();
                HBGlyphPosition[] positions = buffer.getGlyphPositions();
                assertEquals(infos.length, positions.length);

                buffer.destroy();
            }

            if (rep % 10 == 9) {
                MemoryMetrics.Snapshot snap = MemoryMetrics.takeSnapshot();
                measurements[rep / 10] = snap.heapUsed;
            }
        }

        hbFont.destroy();
        face.destroy();
        ft.destroy();

        MemoryMetrics.Snapshot afterCleanup = MemoryMetrics.takeSnapshot();
        logMemory("After " + (repetitions * testStrings.length) + " shape operations", afterCleanup);

        assertNoMemoryGrowthTrend(measurements, 2048.0);
        assertMemoryReturnsToBaseline(baseline, afterCleanup);
    }

    @Test
    public void testE3_memoryFragmentationMonitoring() {
        logTestStart();
        byte[] fontData = loadTestFontData();
        int iterations = 1000;

        MemoryMetrics.Snapshot baseline = MemoryMetrics.takeSnapshot();
        logMemory("Baseline", baseline);

        long[] peakUsage = new long[10];
        long[] troughUsage = new long[10];

        FreeTypeLibrary ft = FreeTypeLibrary.create();
        int batchSize = iterations / peakUsage.length;

        for (int batch = 0; batch < peakUsage.length; batch++) {
            FTFace[] faces = new FTFace[batchSize / 10];
            HBFont[] hbFonts = new HBFont[faces.length];

            for (int i = 0; i < faces.length; i++) {
                int size = 8 + ((batch * faces.length + i) % 56);
                faces[i] = ft.newFaceFromMemory(fontData, 0);
                faces[i].setPixelSizes(0, size);
                hbFonts[i] = FreeTypeHarfBuzzIntegration.createHBFontFromFTFace(faces[i]);

                HBBuffer buffer = HBBuffer.create();
                buffer.addUTF8("Fragment batch " + batch + " item " + i);
                buffer.guessSegmentProperties();
                HBShape.shape(hbFonts[i], buffer);
                buffer.destroy();
            }

            MemoryMetrics.Snapshot peak = MemoryMetrics.takeSnapshotNoGC();
            peakUsage[batch] = peak.heapUsed;

            for (int i = 0; i < faces.length; i++) {
                hbFonts[i].destroy();
                faces[i].destroy();
            }

            MemoryMetrics.Snapshot trough = MemoryMetrics.takeSnapshot();
            troughUsage[batch] = trough.heapUsed;
        }

        ft.destroy();

        MemoryMetrics.Snapshot afterCleanup = MemoryMetrics.takeSnapshot();
        logMemory("After fragmentation test", afterCleanup);

        // Check fragmentation: trough should not grow over batches
        assertNoMemoryGrowthTrend(troughUsage, 2048.0);
        assertMemoryReturnsToBaseline(baseline, afterCleanup);

        // Report fragmentation ratio for each batch
        for (int i = 0; i < peakUsage.length; i++) {
            long peakDelta = peakUsage[i] - baseline.heapUsed;
            long troughDelta = troughUsage[i] - baseline.heapUsed;
            if (peakDelta > 0) {
                double fragRatio = (double) troughDelta / peakDelta;
                System.out.println("  Batch " + i + ": peak=" + MemoryMetrics.formatBytes(peakDelta)
                        + " trough=" + MemoryMetrics.formatBytes(troughDelta)
                        + " frag=" + String.format("%.1f%%", fragRatio * 100));
            }
        }
    }
}
