package com.crystalgraphics.freetype;

import java.io.IOException;
import java.io.InputStream;

/**
 * Utility for detecting faces in font files.
 * Useful for TrueType Collections (.ttc) which contain multiple fonts.
 */
public final class FTFaceDetector {

    private FTFaceDetector() {
    }

    public static int getFaceCount(FreeTypeLibrary library, String filePath) {
        return library.getFaceCount(filePath);
    }

    public static int getFaceCount(FreeTypeLibrary library, byte[] data) {
        return library.getFaceCount(data);
    }

    public static int getFaceCount(FreeTypeLibrary library, InputStream stream) throws IOException {
        return library.getFaceCount(stream);
    }

    /**
     * Returns an array of valid face indices.
     * For a single-face font returns {@code [0]}, for a TTC with 3 fonts returns {@code [0, 1, 2]}.
     */
    public static int[] detectFaceIndices(FreeTypeLibrary library, String filePath) {
        return makeIndexArray(library.getFaceCount(filePath));
    }

    public static int[] detectFaceIndices(FreeTypeLibrary library, byte[] data) {
        return makeIndexArray(library.getFaceCount(data));
    }

    public static int[] detectFaceIndices(FreeTypeLibrary library, InputStream stream) throws IOException {
        return makeIndexArray(library.getFaceCount(stream));
    }

    private static int[] makeIndexArray(int count) {
        int[] indices = new int[count];
        for (int i = 0; i < count; i++) {
            indices[i] = i;
        }
        return indices;
    }
}
