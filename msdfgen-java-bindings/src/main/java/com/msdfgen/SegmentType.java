package com.msdfgen;

public final class SegmentType {

    public static final int LINEAR = MsdfConstants.SEGMENT_TYPE_LINEAR;
    public static final int QUADRATIC = MsdfConstants.SEGMENT_TYPE_QUADRATIC;
    public static final int CUBIC = MsdfConstants.SEGMENT_TYPE_CUBIC;

    private SegmentType() {}

    public static boolean isValid(int type) {
        return type >= LINEAR && type <= CUBIC;
    }

    public static int pointCount(int type) {
        switch (type) {
            case LINEAR:    return 2;
            case QUADRATIC: return 3;
            case CUBIC:     return 4;
            default:
                throw new IllegalArgumentException("Unknown segment type: " + type);
        }
    }

    public static String name(int type) {
        switch (type) {
            case LINEAR:    return "LINEAR";
            case QUADRATIC: return "QUADRATIC";
            case CUBIC:     return "CUBIC";
            default:        return "UNKNOWN(" + type + ")";
        }
    }
}
