package com.crystalgraphics.test.memory;

import com.crystalgraphics.harfbuzz.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class HarfBuzzMemoryLeakTests extends MemoryLeakDetectionBase {

    @Test
    public void testB1_bufferCreateShapeDestroy() {
        logTestStart();
        String fontPath = extractTestFontToFile();

        MemoryMetrics.Snapshot baseline = MemoryMetrics.takeSnapshot();
        logMemory("Baseline", baseline);

        HBFont font = HBFont.createFromFile(fontPath, 0);
        HBBuffer buffer = HBBuffer.create();
        buffer.addUTF8("Hello, World!");
        buffer.guessSegmentProperties();
        HBShape.shape(font, buffer);

        HBGlyphInfo[] infos = buffer.getGlyphInfos();
        HBGlyphPosition[] positions = buffer.getGlyphPositions();
        assertTrue("Should produce glyphs", infos.length > 0);
        assertEquals(infos.length, positions.length);

        buffer.destroy();
        font.destroy();

        MemoryMetrics.Snapshot afterCleanup = MemoryMetrics.takeSnapshot();
        logMemory("After cleanup", afterCleanup);

        assertMemoryReturnsToBaseline(baseline, afterCleanup);
    }

    @Test
    public void testB2_multipleBufferStress() {
        logTestStart();
        String fontPath = extractTestFontToFile();
        int bufferCount = 1000;

        MemoryMetrics.Snapshot baseline = MemoryMetrics.takeSnapshot();
        logMemory("Baseline", baseline);

        HBFont font = HBFont.createFromFile(fontPath, 0);

        long[] measurements = new long[10];
        int batchSize = bufferCount / measurements.length;

        for (int batch = 0; batch < measurements.length; batch++) {
            for (int i = 0; i < batchSize; i++) {
                HBBuffer buffer = HBBuffer.create();
                buffer.addUTF8("Iteration " + (batch * batchSize + i));
                buffer.guessSegmentProperties();
                HBShape.shape(font, buffer);
                buffer.getGlyphInfos();
                buffer.getGlyphPositions();
                buffer.destroy();
            }
            MemoryMetrics.Snapshot snap = MemoryMetrics.takeSnapshot();
            measurements[batch] = snap.heapUsed;
        }

        font.destroy();

        MemoryMetrics.Snapshot afterCleanup = MemoryMetrics.takeSnapshot();
        logMemory("After " + bufferCount + " buffers", afterCleanup);

        assertNoMemoryGrowthTrend(measurements, 1024.0);
        assertMemoryReturnsToBaseline(baseline, afterCleanup);
    }

    @Test
    public void testB3_shapeOutputExtraction() {
        logTestStart();
        String fontPath = extractTestFontToFile();

        MemoryMetrics.Snapshot baseline = MemoryMetrics.takeSnapshot();
        logMemory("Baseline", baseline);

        HBFont font = HBFont.createFromFile(fontPath, 0);

        for (int i = 0; i < 100; i++) {
            HBBuffer buffer = HBBuffer.create();
            buffer.addUTF8("Extract test " + i + " with some longer text for realism");
            buffer.guessSegmentProperties();
            HBShape.shape(font, buffer);

            HBGlyphInfo[] infos = buffer.getGlyphInfos();
            HBGlyphPosition[] positions = buffer.getGlyphPositions();

            for (int j = 0; j < infos.length; j++) {
                int codepoint = infos[j].getCodepoint();
                int cluster = infos[j].getCluster();
                int xAdvance = positions[j].getXAdvance();
                int yAdvance = positions[j].getYAdvance();
                int xOffset = positions[j].getXOffset();
                int yOffset = positions[j].getYOffset();
                assertTrue("Codepoint should be non-negative", codepoint >= 0);
            }

            buffer.destroy();
        }

        font.destroy();

        MemoryMetrics.Snapshot afterCleanup = MemoryMetrics.takeSnapshot();
        logMemory("After cleanup", afterCleanup);

        assertMemoryReturnsToBaseline(baseline, afterCleanup);
    }
}
