package com.crystalgraphics.test.memory;

import com.crystalgraphics.freetype.*;
import com.crystalgraphics.harfbuzz.*;
import com.crystalgraphics.text.FreeTypeHarfBuzzIntegration;
import org.junit.Test;
import static org.junit.Assert.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class ThreadSafetyMemoryLeakTests extends MemoryLeakDetectionBase {

    @Test
    public void testG1_multiThreadedFontLoading() throws Exception {
        logTestStart();
        byte[] fontData = loadTestFontData();
        int threadCount = 4;
        int fontsPerThread = 25;

        MemoryMetrics.Snapshot baseline = MemoryMetrics.takeSnapshot();
        logMemory("Baseline", baseline);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger errorCount = new AtomicInteger(0);
        AtomicReference<Throwable> firstError = new AtomicReference<Throwable>(null);

        Thread[] threads = new Thread[threadCount];
        for (int t = 0; t < threadCount; t++) {
            final int threadIdx = t;
            threads[t] = new Thread(new Runnable() {
                public void run() {
                    try {
                        startLatch.await();
                        FreeTypeLibrary ft = FreeTypeLibrary.create();
                        FTFace[] faces = new FTFace[fontsPerThread];

                        for (int i = 0; i < fontsPerThread; i++) {
                            faces[i] = ft.newFaceFromMemory(fontData, 0);
                            faces[i].setPixelSizes(0, 12 + (i % 48));

                            int glyphIdx = faces[i].getCharIndex('A' + (i % 26));
                            faces[i].loadGlyph(glyphIdx, FTLoadFlags.FT_LOAD_RENDER);
                            FTBitmap bitmap = faces[i].getGlyphBitmap();
                            assertNotNull(bitmap);
                        }

                        for (int i = fontsPerThread - 1; i >= 0; i--) {
                            faces[i].destroy();
                        }
                        ft.destroy();
                    } catch (Throwable e) {
                        errorCount.incrementAndGet();
                        firstError.compareAndSet(null, e);
                    } finally {
                        doneLatch.countDown();
                    }
                }
            }, "font-loader-" + threadIdx);
            threads[t].start();
        }

        startLatch.countDown();
        doneLatch.await();

        if (firstError.get() != null) {
            firstError.get().printStackTrace();
            fail("Thread error: " + firstError.get().getMessage());
        }
        assertEquals("No thread errors expected", 0, errorCount.get());

        MemoryMetrics.Snapshot afterCleanup = MemoryMetrics.takeSnapshot();
        logMemory("After " + (threadCount * fontsPerThread) + " fonts across " + threadCount + " threads", afterCleanup);

        assertMemoryReturnsToBaseline(baseline, afterCleanup);
    }

    @Test
    public void testG2_multiThreadedTextShaping() throws Exception {
        logTestStart();
        byte[] fontData = loadTestFontData();
        int threadCount = 4;
        int buffersPerThread = 100;

        MemoryMetrics.Snapshot baseline = MemoryMetrics.takeSnapshot();
        logMemory("Baseline", baseline);

        FreeTypeLibrary ft = FreeTypeLibrary.create();
        FTFace face = ft.newFaceFromMemory(fontData, 0);
        face.setPixelSizes(0, 24);
        final HBFont hbFont = FreeTypeHarfBuzzIntegration.createHBFontFromFTFace(face);

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger errorCount = new AtomicInteger(0);
        AtomicReference<Throwable> firstError = new AtomicReference<Throwable>(null);

        Thread[] threads = new Thread[threadCount];
        for (int t = 0; t < threadCount; t++) {
            final int threadIdx = t;
            threads[t] = new Thread(new Runnable() {
                public void run() {
                    try {
                        startLatch.await();
                        for (int i = 0; i < buffersPerThread; i++) {
                            HBBuffer buffer = HBBuffer.create();
                            buffer.addUTF8("Thread " + threadIdx + " buffer " + i);
                            buffer.guessSegmentProperties();
                            HBShape.shape(hbFont, buffer);

                            HBGlyphInfo[] infos = buffer.getGlyphInfos();
                            HBGlyphPosition[] positions = buffer.getGlyphPositions();
                            assertTrue(infos.length > 0);
                            assertEquals(infos.length, positions.length);

                            buffer.destroy();
                        }
                    } catch (Throwable e) {
                        errorCount.incrementAndGet();
                        firstError.compareAndSet(null, e);
                    } finally {
                        doneLatch.countDown();
                    }
                }
            }, "shaper-" + threadIdx);
            threads[t].start();
        }

        startLatch.countDown();
        doneLatch.await();

        if (firstError.get() != null) {
            firstError.get().printStackTrace();
            fail("Thread error: " + firstError.get().getMessage());
        }
        assertEquals("No thread errors expected", 0, errorCount.get());

        hbFont.destroy();
        face.destroy();
        ft.destroy();

        MemoryMetrics.Snapshot afterCleanup = MemoryMetrics.takeSnapshot();
        logMemory("After " + (threadCount * buffersPerThread) + " shapes across " + threadCount + " threads", afterCleanup);

        assertMemoryReturnsToBaseline(baseline, afterCleanup);
    }
}
