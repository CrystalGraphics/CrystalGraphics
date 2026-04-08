package com.crystalgraphics.test.memory;

import com.crystalgraphics.freetype.*;
import com.crystalgraphics.harfbuzz.*;
import com.crystalgraphics.text.FreeTypeHarfBuzzIntegration;
import org.junit.Test;
import static org.junit.Assert.*;

public class ErrorPathMemoryLeakTests extends MemoryLeakDetectionBase {

    @Test
    public void testD1_exceptionDuringFontLoading() {
        logTestStart();

        MemoryMetrics.Snapshot baseline = MemoryMetrics.takeSnapshot();
        logMemory("Baseline", baseline);

        FreeTypeLibrary ft = FreeTypeLibrary.create();

        for (int i = 0; i < 20; i++) {
            try {
                ft.newFace("/nonexistent/path/font" + i + ".ttf", 0);
                fail("Should have thrown for nonexistent file");
            } catch (FreeTypeException e) {
                // Expected
            }

            try {
                ft.newFaceFromMemory(new byte[]{(byte) 0xFF, (byte) 0xFE}, 0);
                fail("Should have thrown for invalid font data");
            } catch (FreeTypeException e) {
                // Expected
            } catch (RuntimeException e) {
                // Some implementations throw RuntimeException
            }
        }

        ft.destroy();

        MemoryMetrics.Snapshot afterCleanup = MemoryMetrics.takeSnapshot();
        logMemory("After 40 failed load attempts", afterCleanup);

        assertMemoryReturnsToBaseline(baseline, afterCleanup);
    }

    @Test
    public void testD2_exceptionDuringTextShaping() {
        logTestStart();
        byte[] fontData = loadTestFontData();

        MemoryMetrics.Snapshot baseline = MemoryMetrics.takeSnapshot();
        logMemory("Baseline", baseline);

        FreeTypeLibrary ft = FreeTypeLibrary.create();
        FTFace face = ft.newFaceFromMemory(fontData, 0);
        face.setPixelSizes(0, 24);
        HBFont hbFont = FreeTypeHarfBuzzIntegration.createHBFontFromFTFace(face);

        for (int i = 0; i < 20; i++) {
            try {
                HBShape.shape(null, HBBuffer.create());
                fail("Should have thrown for null font");
            } catch (IllegalArgumentException e) {
                // Expected
            }

            try {
                HBShape.shape(hbFont, null);
                fail("Should have thrown for null buffer");
            } catch (IllegalArgumentException e) {
                // Expected
            }

            HBBuffer buffer = HBBuffer.create();
            buffer.addUTF8("Valid text " + i);
            buffer.guessSegmentProperties();
            HBShape.shape(hbFont, buffer);
            buffer.destroy();
        }

        hbFont.destroy();
        face.destroy();
        ft.destroy();

        MemoryMetrics.Snapshot afterCleanup = MemoryMetrics.takeSnapshot();
        logMemory("After error-path shaping attempts", afterCleanup);

        assertMemoryReturnsToBaseline(baseline, afterCleanup);
    }

    @Test
    public void testD3_exceptionDuringIntegrationOperations() {
        logTestStart();

        MemoryMetrics.Snapshot baseline = MemoryMetrics.takeSnapshot();
        logMemory("Baseline", baseline);

        FreeTypeLibrary ft = FreeTypeLibrary.create();

        for (int i = 0; i < 20; i++) {
            try {
                FreeTypeHarfBuzzIntegration.createHBFontFromFTFace(null);
                fail("Should throw for null face");
            } catch (IllegalArgumentException e) {
                // Expected
            }
        }

        byte[] fontData = loadTestFontData();
        for (int i = 0; i < 20; i++) {
            FTFace face = ft.newFaceFromMemory(fontData, 0);
            face.setPixelSizes(0, 16);
            HBFont hbFont = FreeTypeHarfBuzzIntegration.createHBFontFromFTFace(face);

            hbFont.destroy();
            face.destroy();
        }

        ft.destroy();

        MemoryMetrics.Snapshot afterCleanup = MemoryMetrics.takeSnapshot();
        logMemory("After integration error-path tests", afterCleanup);

        assertMemoryReturnsToBaseline(baseline, afterCleanup);
    }
}
