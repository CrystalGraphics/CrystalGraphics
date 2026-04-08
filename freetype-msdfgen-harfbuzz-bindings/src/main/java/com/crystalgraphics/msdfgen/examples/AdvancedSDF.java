package com.crystalgraphics.msdfgen.examples;

import com.crystalgraphics.msdfgen.*;

/**
 * Advanced example: load a font via FreeType, render multiple glyphs as MSDF,
 * apply error correction, and output glyph metrics.
 *
 * Requires: native library compiled with -DMSDFGEN_USE_FREETYPE=ON
 * Run: java -cp msdfgen-java-bindings.jar com.msdfgen.examples.AdvancedSDF /path/to/font.ttf
 */
public class AdvancedSDF {

    private static final int BITMAP_SIZE = 48;
    private static final double PX_RANGE = 4.0;
    private static final String DEFAULT_TEXT = "Hello!";

    public static void main(String[] args) {
        if (!FreeTypeMSDFIntegration.isAvailable()) {
            System.err.println("FreeType support is not available in the native library.");
            System.err.println("Rebuild with: MSDFGEN_USE_FREETYPE=ON ./build-natives.sh");
            System.err.println();
            System.err.println("Falling back to manual shape demo...");
            runManualShapeDemo();
            return;
        }

        String fontPath = (args.length > 0) ? args[0] : findSystemFont();
        if (fontPath == null) {
            System.err.println("Usage: AdvancedSDF <path-to-font.ttf>");
            System.err.println("No font path provided and no system font found.");
            return;
        }

        String text = (args.length > 1) ? args[1] : DEFAULT_TEXT;
        System.out.println("Font: " + fontPath);
        System.out.println("Text: \"" + text + "\"");
        System.out.println("Bitmap size: " + BITMAP_SIZE + "x" + BITMAP_SIZE);
        System.out.println("Pixel range: " + PX_RANGE);
        System.out.println();

        FreeTypeMSDFIntegration ft = FreeTypeMSDFIntegration.create();
        try {
            FreeTypeMSDFIntegration.Font font = ft.loadFont(fontPath);
            try {
                for (int i = 0; i < text.length(); i++) {
                    char ch = text.charAt(i);
                    renderGlyph(font, ch);
                }
            } finally {
                font.destroy();
            }
        } finally {
            ft.destroy();
        }
    }

    private static void renderGlyph(FreeTypeMSDFIntegration.Font font, int codepoint) {
        System.out.println("--- Glyph: '" + (char) codepoint + "' (U+"
            + String.format("%04X", codepoint) + ") ---");

        FreeTypeMSDFIntegration.GlyphData glyphData;
        try {
            glyphData = font.loadGlyph(codepoint);
        } catch (MSDFException e) {
            System.out.println("  Skipped (not found in font): " + e.getMessage());
            System.out.println();
            return;
        }

        MSDFShape shape = glyphData.getShape();
        try {
            System.out.println("  Advance: " + glyphData.getAdvance());
            System.out.println("  Contours: " + shape.getContourCount());
            System.out.println("  Edges: " + shape.getEdgeCount());

            if (shape.getEdgeCount() == 0) {
                System.out.println("  (empty glyph - space character?)");
                System.out.println();
                return;
            }

            double[] bounds = shape.getBounds();
            System.out.println("  Bounds: [" + bounds[0] + ", " + bounds[1]
                + "] - [" + bounds[2] + ", " + bounds[3] + "]");

            shape.normalize();
            shape.edgeColoringSimple(3.0);

            MSDFBitmap bitmap = MSDFBitmap.allocMsdf(BITMAP_SIZE, BITMAP_SIZE);
            try {
                MSDFTransform transform = MSDFTransform.autoFrame(shape, BITMAP_SIZE, BITMAP_SIZE, PX_RANGE);

                MSDFGenerator.generateMsdf(bitmap, shape, transform,
                    true,
                    MSDFConstants.ERROR_CORRECTION_EDGE_PRIORITY,
                    MSDFConstants.DISTANCE_CHECK_AT_EDGE,
                    MSDFConstants.DEFAULT_MIN_DEVIATION_RATIO,
                    MSDFConstants.DEFAULT_MIN_IMPROVE_RATIO);

                float[] pixels = bitmap.getPixelData();
                printCompactPreview(pixels, BITMAP_SIZE, BITMAP_SIZE, 3);
            } finally {
                bitmap.free();
            }
        } finally {
            shape.free();
        }
        System.out.println();
    }

    private static void runManualShapeDemo() {
        System.out.println("Generating MSDF for a manually-constructed square shape...");

        MSDFShape shape = MSDFShape.create();
        try {
            MSDFContour contour = shape.addContour();

            double[][] points = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
            for (int i = 0; i < points.length; i++) {
                int next = (i + 1) % points.length;
                MSDFSegment edge = MSDFSegment.createLinear();
                edge.setPoint(0, points[i][0], points[i][1]);
                edge.setPoint(1, points[next][0], points[next][1]);
                contour.addEdge(edge);
            }

            shape.normalize();
            shape.edgeColoringSimple(3.0);

            int size = 32;
            MSDFBitmap bitmap = MSDFBitmap.allocMsdf(size, size);
            try {
                MSDFTransform transform = MSDFTransform.autoFrame(shape, size, size, 4.0);
                MSDFGenerator.generateMsdf(bitmap, shape, transform);

                MSDFGenerator.errorCorrection(bitmap, shape, transform);

                float[] pixels = bitmap.getPixelData();
                System.out.println("Generated " + size + "x" + size + " MSDF with error correction");
                printCompactPreview(pixels, size, size, 3);
            } finally {
                bitmap.free();
            }
        } finally {
            shape.free();
        }
    }

    private static void printCompactPreview(float[] pixels, int width, int height, int channels) {
        int step = Math.max(1, height / 16);
        for (int y = height - 1; y >= 0; y -= step) {
            StringBuilder row = new StringBuilder("  ");
            int xStep = Math.max(1, width / 32);
            for (int x = 0; x < width; x += xStep) {
                int idx = (y * width + x) * channels;
                float r = pixels[idx];
                float g = (channels > 1) ? pixels[idx + 1] : r;
                float b = (channels > 2) ? pixels[idx + 2] : r;
                float median = Math.max(Math.min(r, g), Math.min(Math.max(r, g), b));
                if (median > 0.7f) row.append('\u2588');
                else if (median > 0.5f) row.append('\u2593');
                else if (median > 0.3f) row.append('\u2591');
                else row.append(' ');
            }
            System.out.println(row.toString());
        }
    }

    private static String findSystemFont() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String[] candidates;
        if (os.contains("win")) {
            candidates = new String[]{
                "C:\\Windows\\Fonts\\arial.ttf",
                "C:\\Windows\\Fonts\\segoeui.ttf",
                "C:\\Windows\\Fonts\\consola.ttf"
            };
        } else if (os.contains("mac") || os.contains("darwin")) {
            candidates = new String[]{
                "/System/Library/Fonts/Helvetica.ttc",
                "/Library/Fonts/Arial.ttf",
                "/System/Library/Fonts/SFPro.ttf"
            };
        } else {
            candidates = new String[]{
                "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                "/usr/share/fonts/TTF/DejaVuSans.ttf",
                "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf"
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
