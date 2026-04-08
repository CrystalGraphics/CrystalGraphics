package com.crystalgraphics.msdfgen.examples;

import com.crystalgraphics.msdfgen.*;

/**
 * Performance/stress test: generates many glyphs and measures timing and memory.
 * Demonstrates proper cleanup patterns for bulk operations.
 *
 * Run: java -cp msdfgen-java-bindings.jar com.msdfgen.examples.PerformanceExample
 */
public class PerformanceExample {

    private static final int[] BITMAP_SIZES = {16, 32, 64, 128};
    private static final int ITERATIONS_PER_SIZE = 100;

    public static void main(String[] args) {
        System.out.println("=== MSDFgen Performance Test ===");
        System.out.println();

        benchmarkManualShapes();
        System.out.println();

        benchmarkBitmapAllocation();
        System.out.println();

        if (FreeTypeMSDFIntegration.isAvailable()) {
            String fontPath = findSystemFont();
            if (fontPath != null) {
                benchmarkFreeTypeGlyphs(fontPath);
            } else {
                System.out.println("No system font found for FreeType benchmark.");
            }
        } else {
            System.out.println("FreeType not available - skipping font glyph benchmark.");
        }

        System.out.println();
        System.out.println("=== Performance Test Complete ===");
    }

    private static void benchmarkManualShapes() {
        System.out.println("--- Manual Shape Generation ---");

        for (int size : BITMAP_SIZES) {
            long totalNanos = 0;
            for (int i = 0; i < ITERATIONS_PER_SIZE; i++) {
                long start = System.nanoTime();

                MSDFShape shape = MSDFShape.create();
                MSDFContour contour = shape.addContour();

                MSDFSegment e1 = MSDFSegment.createLinear();
                e1.setPoint(0, 0, 0);
                e1.setPoint(1, 1, 0);
                contour.addEdge(e1);

                MSDFSegment e2 = MSDFSegment.createLinear();
                e2.setPoint(0, 1, 0);
                e2.setPoint(1, 1, 1);
                contour.addEdge(e2);

                MSDFSegment e3 = MSDFSegment.createLinear();
                e3.setPoint(0, 1, 1);
                e3.setPoint(1, 0, 1);
                contour.addEdge(e3);

                MSDFSegment e4 = MSDFSegment.createLinear();
                e4.setPoint(0, 0, 1);
                e4.setPoint(1, 0, 0);
                contour.addEdge(e4);

                shape.normalize();
                shape.edgeColoringSimple(3.0);

                MSDFBitmap bitmap = MSDFBitmap.allocMsdf(size, size);
                MSDFTransform transform = MSDFTransform.autoFrame(shape, size, size, 4.0);
                MSDFGenerator.generateMsdf(bitmap, shape, transform);

                bitmap.free();
                shape.free();

                totalNanos += System.nanoTime() - start;
            }

            double avgMs = (totalNanos / (double) ITERATIONS_PER_SIZE) / 1_000_000.0;
            System.out.printf("  %3dx%-3d: %.3f ms/glyph (%d iterations)%n",
                size, size, avgMs, ITERATIONS_PER_SIZE);
        }
    }

    private static void benchmarkBitmapAllocation() {
        System.out.println("--- Bitmap Allocation/Free ---");

        int allocCount = 1000;

        for (int size : BITMAP_SIZES) {
            long start = System.nanoTime();
            for (int i = 0; i < allocCount; i++) {
                MSDFBitmap bitmap = MSDFBitmap.allocMsdf(size, size);
                bitmap.free();
            }
            double totalMs = (System.nanoTime() - start) / 1_000_000.0;
            double avgUs = (totalMs / allocCount) * 1000.0;
            System.out.printf("  %3dx%-3d: %.1f us/alloc+free (%d iterations, %.1f ms total)%n",
                size, size, avgUs, allocCount, totalMs);
        }

        System.out.println();
        System.out.println("  Memory test: allocate many bitmaps, then free all...");
        int batchSize = 200;
        MSDFBitmap[] bitmaps = new MSDFBitmap[batchSize];
        long beforeMem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();

        for (int i = 0; i < batchSize; i++) {
            bitmaps[i] = MSDFBitmap.allocMsdf(64, 64);
        }

        long afterMem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        System.out.printf("  Allocated %d bitmaps (64x64 MSDF). Java heap delta: %d KB%n",
            batchSize, (afterMem - beforeMem) / 1024);

        for (int i = 0; i < batchSize; i++) {
            bitmaps[i].free();
        }
        System.out.println("  All bitmaps freed successfully.");
    }

    private static void benchmarkFreeTypeGlyphs(String fontPath) {
        System.out.println("--- FreeType Glyph Loading ---");
        System.out.println("  Font: " + fontPath);

        FreeTypeMSDFIntegration ft = FreeTypeMSDFIntegration.create();
        try {
            FreeTypeMSDFIntegration.Font font = ft.loadFont(fontPath);
            try {
                int glyphCount = 0;
                int failCount = 0;
                int startCp = 0x21;
                int endCp = 0x7E;

                long start = System.nanoTime();
                for (int cp = startCp; cp <= endCp; cp++) {
                    try {
                        FreeTypeMSDFIntegration.GlyphData glyph = font.loadGlyph(cp);
                        MSDFShape shape = glyph.getShape();
                        if (shape.getEdgeCount() > 0) {
                            shape.normalize();
                            shape.edgeColoringSimple(3.0);

                            MSDFBitmap bitmap = MSDFBitmap.allocMsdf(32, 32);
                            MSDFTransform transform = MSDFTransform.autoFrame(shape, 32, 32, 4.0);
                            MSDFGenerator.generateMsdf(bitmap, shape, transform);
                            bitmap.free();
                        }
                        shape.free();
                        glyphCount++;
                    } catch (MSDFException e) {
                        failCount++;
                    }
                }
                double totalMs = (System.nanoTime() - start) / 1_000_000.0;

                System.out.printf("  Rendered %d glyphs (U+%04X..U+%04X) in %.1f ms%n",
                    glyphCount, startCp, endCp, totalMs);
                System.out.printf("  Average: %.3f ms/glyph%n",
                    totalMs / Math.max(glyphCount, 1));
                if (failCount > 0) {
                    System.out.printf("  Failed: %d glyphs%n", failCount);
                }

                System.out.println();
                System.out.println("  Kerning query benchmark...");
                start = System.nanoTime();
                int kerningQueries = 0;
                for (int c1 = 'A'; c1 <= 'Z'; c1++) {
                    for (int c2 = 'A'; c2 <= 'Z'; c2++) {
                        try {
                            font.getKerning(c1, c2);
                            kerningQueries++;
                        } catch (MSDFException e) {
                            break;
                        }
                    }
                }
                double kerningMs = (System.nanoTime() - start) / 1_000_000.0;
                System.out.printf("  %d kerning queries in %.2f ms (%.1f us/query)%n",
                    kerningQueries, kerningMs,
                    kerningQueries > 0 ? (kerningMs / kerningQueries) * 1000.0 : 0);

            } finally {
                font.destroy();
            }
        } finally {
            ft.destroy();
        }
    }

    private static String findSystemFont() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String[] candidates;
        if (os.contains("win")) {
            candidates = new String[]{
                "C:\\Windows\\Fonts\\arial.ttf",
                "C:\\Windows\\Fonts\\consola.ttf"
            };
        } else if (os.contains("mac") || os.contains("darwin")) {
            candidates = new String[]{
                "/System/Library/Fonts/Helvetica.ttc",
                "/Library/Fonts/Arial.ttf"
            };
        } else {
            candidates = new String[]{
                "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                "/usr/share/fonts/TTF/DejaVuSans.ttf"
            };
        }
        for (String path : candidates) {
            if (new java.io.File(path).exists()) {
                return path;
            }
        }
        return null;
    }
}
