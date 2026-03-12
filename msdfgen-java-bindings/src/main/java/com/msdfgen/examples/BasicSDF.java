package com.msdfgen.examples;

import com.msdfgen.*;

/**
 * Minimal example: construct a shape manually and generate an MSDF bitmap.
 * No FreeType required - builds a triangle shape from scratch.
 *
 * Run: java -cp msdfgen-java-bindings.jar com.msdfgen.examples.BasicSDF
 */
public class BasicSDF {

    public static void main(String[] args) {
        Shape shape = Shape.create();
        try {
            Contour contour = shape.addContour();

            Segment edge1 = Segment.createLinear();
            edge1.setPoint(0, 0.0, 0.0);
            edge1.setPoint(1, 0.5, 1.0);
            contour.addEdge(edge1);

            Segment edge2 = Segment.createLinear();
            edge2.setPoint(0, 0.5, 1.0);
            edge2.setPoint(1, 1.0, 0.0);
            contour.addEdge(edge2);

            Segment edge3 = Segment.createLinear();
            edge3.setPoint(0, 1.0, 0.0);
            edge3.setPoint(1, 0.0, 0.0);
            contour.addEdge(edge3);

            shape.normalize();
            shape.edgeColoringSimple(3.0);

            if (!shape.validate()) {
                System.err.println("Shape validation failed!");
                return;
            }

            int size = 32;
            double pxRange = 4.0;
            Bitmap bitmap = Bitmap.allocMsdf(size, size);
            try {
                Transform transform = Transform.autoFrame(shape, size, size, pxRange);
                Generator.generateMsdf(bitmap, shape, transform);

                float[] pixels = bitmap.getPixelData();
                System.out.println("Generated " + size + "x" + size + " MSDF bitmap");
                System.out.println("Channels: " + bitmap.getChannelCount());
                System.out.println("Total floats: " + pixels.length);
                System.out.println("Byte size: " + bitmap.getByteSize());

                printBitmapPreview(pixels, size, size, 3);
            } finally {
                bitmap.free();
            }
        } finally {
            shape.free();
        }
    }

    private static void printBitmapPreview(float[] pixels, int width, int height, int channels) {
        System.out.println("\nBitmap preview (median channel, . = inside, # = outside):");
        for (int y = height - 1; y >= 0; y--) {
            StringBuilder row = new StringBuilder();
            for (int x = 0; x < width; x++) {
                int idx = (y * width + x) * channels;
                float r = pixels[idx];
                float g = (channels > 1) ? pixels[idx + 1] : r;
                float b = (channels > 2) ? pixels[idx + 2] : r;
                float median = Math.max(Math.min(r, g), Math.min(Math.max(r, g), b));
                row.append(median > 0.5f ? '.' : '#');
            }
            System.out.println(row.toString());
        }
    }
}
