package com.crystalgraphics.api.vector;

/** Which points a {@link CgVectorPath} with crossing or nested contours fills: SVG's and Skia's two rules. */
public enum CgFillRule {
    /** Inside where the contours wind round a point a non-zero number of times: a hole is a contour drawn the other way. */
    NON_ZERO,
    /** Inside where a ray from a point crosses the contours an odd number of times: every nested contour is a hole. */
    EVEN_ODD
}
