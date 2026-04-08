package com.crystalgraphics.msdfgen;

/**
 * Constants for MSDFgen bitmap types, segment types, orientations,
 * and error correction modes.
 */
public final class MSDFConstants {

    // Bitmap types
    public static final int BITMAP_TYPE_SDF = 0;
    public static final int BITMAP_TYPE_PSDF = 1;
    public static final int BITMAP_TYPE_MSDF = 2;
    public static final int BITMAP_TYPE_MTSDF = 3;

    // Segment types
    public static final int SEGMENT_TYPE_LINEAR = 0;
    public static final int SEGMENT_TYPE_QUADRATIC = 1;
    public static final int SEGMENT_TYPE_CUBIC = 2;

    // Y-axis orientations
    public static final int ORIENTATION_Y_UPWARD = 0;
    public static final int ORIENTATION_Y_DOWNWARD = 1;

    // Error correction modes
    public static final int ERROR_CORRECTION_DISABLED = 0;
    public static final int ERROR_CORRECTION_INDISCRIMINATE = 1;
    public static final int ERROR_CORRECTION_EDGE_PRIORITY = 2;
    public static final int ERROR_CORRECTION_EDGE_ONLY = 3;

    // Distance check modes
    public static final int DISTANCE_CHECK_NONE = 0;
    public static final int DISTANCE_CHECK_AT_EDGE = 1;
    public static final int DISTANCE_CHECK_ALWAYS = 2;

    // Default error correction parameters (matches msdfgen C++ defaults)
    public static final double DEFAULT_MIN_DEVIATION_RATIO = 1.11111111111111111;
    public static final double DEFAULT_MIN_IMPROVE_RATIO = 1.11111111111111111;

    // Boolean constants (matches C API MSDF_FALSE/MSDF_TRUE)
    public static final int MSDF_FALSE = 0;
    public static final int MSDF_TRUE = 1;

    // Type range maximums (for validation)
    public static final int BITMAP_TYPE_MAX = 3;
    public static final int SEGMENT_TYPE_MAX = 2;

    private MSDFConstants() {}

    /**
     * Returns the channel count for a given bitmap type.
     * SDF/PSDF = 1 channel, MSDF = 3 channels, MTSDF = 4 channels.
     */
    public static int channelCountForType(int bitmapType) {
        switch (bitmapType) {
            case BITMAP_TYPE_SDF:
            case BITMAP_TYPE_PSDF:
                return 1;
            case BITMAP_TYPE_MSDF:
                return 3;
            case BITMAP_TYPE_MTSDF:
                return 4;
            default:
                throw new IllegalArgumentException("Unknown bitmap type: " + bitmapType);
        }
    }
}
