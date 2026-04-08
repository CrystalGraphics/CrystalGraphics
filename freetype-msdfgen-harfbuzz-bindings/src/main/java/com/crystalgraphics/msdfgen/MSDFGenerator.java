package com.crystalgraphics.msdfgen;

public final class MSDFGenerator {

    private MSDFGenerator() {}

    public static void generateSdf(MSDFBitmap output, MSDFShape shape, MSDFTransform transform) {
        MSDFResult.check(MSDFNative.nGenerateSdf(
            output.getNativeHandle(), output.getType(), output.getWidth(), output.getHeight(),
            shape.getNativeHandle(),
            transform.getScaleX(), transform.getScaleY(),
            transform.getTranslateX(), transform.getTranslateY(),
            transform.getRangeLower(), transform.getRangeUpper()
        ));
    }

    public static void generatePsdf(MSDFBitmap output, MSDFShape shape, MSDFTransform transform) {
        MSDFResult.check(MSDFNative.nGeneratePsdf(
            output.getNativeHandle(), output.getType(), output.getWidth(), output.getHeight(),
            shape.getNativeHandle(),
            transform.getScaleX(), transform.getScaleY(),
            transform.getTranslateX(), transform.getTranslateY(),
            transform.getRangeLower(), transform.getRangeUpper()
        ));
    }

    public static void generateMsdf(MSDFBitmap output, MSDFShape shape, MSDFTransform transform) {
        MSDFResult.check(MSDFNative.nGenerateMsdf(
            output.getNativeHandle(), output.getType(), output.getWidth(), output.getHeight(),
            shape.getNativeHandle(),
            transform.getScaleX(), transform.getScaleY(),
            transform.getTranslateX(), transform.getTranslateY(),
            transform.getRangeLower(), transform.getRangeUpper()
        ));
    }

    public static void generateMtsdf(MSDFBitmap output, MSDFShape shape, MSDFTransform transform) {
        MSDFResult.check(MSDFNative.nGenerateMtsdf(
            output.getNativeHandle(), output.getType(), output.getWidth(), output.getHeight(),
            shape.getNativeHandle(),
            transform.getScaleX(), transform.getScaleY(),
            transform.getTranslateX(), transform.getTranslateY(),
            transform.getRangeLower(), transform.getRangeUpper()
        ));
    }

    public static void generateSdf(MSDFBitmap output, MSDFShape shape, MSDFTransform transform,
                                   boolean overlapSupport) {
        MSDFResult.check(MSDFNative.nGenerateSdfWithConfig(
            output.getNativeHandle(), output.getType(), output.getWidth(), output.getHeight(),
            shape.getNativeHandle(),
            transform.getScaleX(), transform.getScaleY(),
            transform.getTranslateX(), transform.getTranslateY(),
            transform.getRangeLower(), transform.getRangeUpper(),
            overlapSupport
        ));
    }

    public static void generateMsdf(MSDFBitmap output, MSDFShape shape, MSDFTransform transform,
                                    boolean overlapSupport,
                                    int errorCorrectionMode,
                                    int distanceCheckMode,
                                    double minDeviationRatio,
                                    double minImproveRatio) {
        MSDFResult.check(MSDFNative.nGenerateMsdfWithConfig(
            output.getNativeHandle(), output.getType(), output.getWidth(), output.getHeight(),
            shape.getNativeHandle(),
            transform.getScaleX(), transform.getScaleY(),
            transform.getTranslateX(), transform.getTranslateY(),
            transform.getRangeLower(), transform.getRangeUpper(),
            overlapSupport,
            errorCorrectionMode, distanceCheckMode,
            minDeviationRatio, minImproveRatio
        ));
    }

    public static void generateMtsdf(MSDFBitmap output, MSDFShape shape, MSDFTransform transform,
                                     boolean overlapSupport,
                                     int errorCorrectionMode,
                                     int distanceCheckMode,
                                     double minDeviationRatio,
                                     double minImproveRatio) {
        MSDFResult.check(MSDFNative.nGenerateMtsdfWithConfig(
            output.getNativeHandle(), output.getType(), output.getWidth(), output.getHeight(),
            shape.getNativeHandle(),
            transform.getScaleX(), transform.getScaleY(),
            transform.getTranslateX(), transform.getTranslateY(),
            transform.getRangeLower(), transform.getRangeUpper(),
            overlapSupport,
            errorCorrectionMode, distanceCheckMode,
            minDeviationRatio, minImproveRatio
        ));
    }

    public static void generatePsdf(MSDFBitmap output, MSDFShape shape, MSDFTransform transform,
                                    boolean overlapSupport) {
        MSDFResult.check(MSDFNative.nGeneratePsdfWithConfig(
            output.getNativeHandle(), output.getType(), output.getWidth(), output.getHeight(),
            shape.getNativeHandle(),
            transform.getScaleX(), transform.getScaleY(),
            transform.getTranslateX(), transform.getTranslateY(),
            transform.getRangeLower(), transform.getRangeUpper(),
            overlapSupport
        ));
    }

    public static void errorCorrection(MSDFBitmap bitmap, MSDFShape shape, MSDFTransform transform,
                                       int errorCorrectionMode, int distanceCheckMode,
                                       double minDeviationRatio, double minImproveRatio) {
        MSDFResult.check(MSDFNative.nErrorCorrection(
            bitmap.getNativeHandle(), bitmap.getType(), bitmap.getWidth(), bitmap.getHeight(),
            shape.getNativeHandle(),
            transform.getScaleX(), transform.getScaleY(),
            transform.getTranslateX(), transform.getTranslateY(),
            transform.getRangeLower(), transform.getRangeUpper(),
            errorCorrectionMode, distanceCheckMode,
            minDeviationRatio, minImproveRatio
        ));
    }

    public static void errorCorrection(MSDFBitmap bitmap, MSDFShape shape, MSDFTransform transform) {
        errorCorrection(bitmap, shape, transform,
            MSDFConstants.ERROR_CORRECTION_EDGE_PRIORITY,
            MSDFConstants.DISTANCE_CHECK_AT_EDGE,
            MSDFConstants.DEFAULT_MIN_DEVIATION_RATIO,
            MSDFConstants.DEFAULT_MIN_IMPROVE_RATIO);
    }

    public static void errorCorrectionFastDistance(MSDFBitmap bitmap, MSDFTransform transform,
                                                   double minDeviationRatio) {
        MSDFResult.check(MSDFNative.nErrorCorrectionFastDistance(
            bitmap.getNativeHandle(), bitmap.getType(), bitmap.getWidth(), bitmap.getHeight(),
            transform.getScaleX(), transform.getScaleY(),
            transform.getTranslateX(), transform.getTranslateY(),
            transform.getRangeLower(), transform.getRangeUpper(),
            minDeviationRatio
        ));
    }

    public static void errorCorrectionFastDistance(MSDFBitmap bitmap, MSDFTransform transform) {
        errorCorrectionFastDistance(bitmap, transform, MSDFConstants.DEFAULT_MIN_DEVIATION_RATIO);
    }

    public static void errorCorrectionFastEdge(MSDFBitmap bitmap, MSDFTransform transform,
                                               double minDeviationRatio) {
        MSDFResult.check(MSDFNative.nErrorCorrectionFastEdge(
            bitmap.getNativeHandle(), bitmap.getType(), bitmap.getWidth(), bitmap.getHeight(),
            transform.getRangeLower(), transform.getRangeUpper(),
            minDeviationRatio
        ));
    }

    public static void errorCorrectionFastEdge(MSDFBitmap bitmap, MSDFTransform transform) {
        errorCorrectionFastEdge(bitmap, transform, MSDFConstants.DEFAULT_MIN_DEVIATION_RATIO);
    }

    public static void distanceSignCorrection(MSDFBitmap bitmap, MSDFShape shape, MSDFTransform transform, int fillRule) {
        MSDFResult.check(MSDFNative.nDistanceSignCorrection(
            bitmap.getNativeHandle(), bitmap.getType(), bitmap.getWidth(), bitmap.getHeight(),
            shape.getNativeHandle(),
            transform.getScaleX(), transform.getScaleY(),
            transform.getTranslateX(), transform.getTranslateY(),
            transform.getRangeLower(), transform.getRangeUpper(),
            fillRule
        ));
    }

    public static void renderSdf(MSDFBitmap output, MSDFBitmap sdf, MSDFTransform transform, float sdThreshold) {
        MSDFResult.check(MSDFNative.nRenderSdf(
            output.getNativeHandle(), output.getType(), output.getWidth(), output.getHeight(),
            sdf.getNativeHandle(), sdf.getType(), sdf.getWidth(), sdf.getHeight(),
            transform.getRangeLower(), transform.getRangeUpper(),
            sdThreshold
        ));
    }

    public static void renderSdf(MSDFBitmap output, MSDFBitmap sdf, MSDFTransform transform) {
        renderSdf(output, sdf, transform, 0.5f);
    }
}
