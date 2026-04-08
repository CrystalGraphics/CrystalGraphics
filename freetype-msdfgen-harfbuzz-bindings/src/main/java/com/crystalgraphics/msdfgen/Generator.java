package com.crystalgraphics.msdfgen;

public final class Generator {

    private Generator() {}

    public static void generateSdf(Bitmap output, Shape shape, Transform transform) {
        MsdfResult.check(MsdfNative.nGenerateSdf(
            output.getNativeHandle(), output.getType(), output.getWidth(), output.getHeight(),
            shape.getNativeHandle(),
            transform.getScaleX(), transform.getScaleY(),
            transform.getTranslateX(), transform.getTranslateY(),
            transform.getRangeLower(), transform.getRangeUpper()
        ));
    }

    public static void generatePsdf(Bitmap output, Shape shape, Transform transform) {
        MsdfResult.check(MsdfNative.nGeneratePsdf(
            output.getNativeHandle(), output.getType(), output.getWidth(), output.getHeight(),
            shape.getNativeHandle(),
            transform.getScaleX(), transform.getScaleY(),
            transform.getTranslateX(), transform.getTranslateY(),
            transform.getRangeLower(), transform.getRangeUpper()
        ));
    }

    public static void generateMsdf(Bitmap output, Shape shape, Transform transform) {
        MsdfResult.check(MsdfNative.nGenerateMsdf(
            output.getNativeHandle(), output.getType(), output.getWidth(), output.getHeight(),
            shape.getNativeHandle(),
            transform.getScaleX(), transform.getScaleY(),
            transform.getTranslateX(), transform.getTranslateY(),
            transform.getRangeLower(), transform.getRangeUpper()
        ));
    }

    public static void generateMtsdf(Bitmap output, Shape shape, Transform transform) {
        MsdfResult.check(MsdfNative.nGenerateMtsdf(
            output.getNativeHandle(), output.getType(), output.getWidth(), output.getHeight(),
            shape.getNativeHandle(),
            transform.getScaleX(), transform.getScaleY(),
            transform.getTranslateX(), transform.getTranslateY(),
            transform.getRangeLower(), transform.getRangeUpper()
        ));
    }

    public static void generateSdf(Bitmap output, Shape shape, Transform transform,
                                    boolean overlapSupport) {
        MsdfResult.check(MsdfNative.nGenerateSdfWithConfig(
            output.getNativeHandle(), output.getType(), output.getWidth(), output.getHeight(),
            shape.getNativeHandle(),
            transform.getScaleX(), transform.getScaleY(),
            transform.getTranslateX(), transform.getTranslateY(),
            transform.getRangeLower(), transform.getRangeUpper(),
            overlapSupport
        ));
    }

    public static void generateMsdf(Bitmap output, Shape shape, Transform transform,
                                     boolean overlapSupport,
                                     int errorCorrectionMode,
                                     int distanceCheckMode,
                                     double minDeviationRatio,
                                     double minImproveRatio) {
        MsdfResult.check(MsdfNative.nGenerateMsdfWithConfig(
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

    public static void generateMtsdf(Bitmap output, Shape shape, Transform transform,
                                       boolean overlapSupport,
                                       int errorCorrectionMode,
                                       int distanceCheckMode,
                                       double minDeviationRatio,
                                       double minImproveRatio) {
        MsdfResult.check(MsdfNative.nGenerateMtsdfWithConfig(
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

    public static void generatePsdf(Bitmap output, Shape shape, Transform transform,
                                      boolean overlapSupport) {
        MsdfResult.check(MsdfNative.nGeneratePsdfWithConfig(
            output.getNativeHandle(), output.getType(), output.getWidth(), output.getHeight(),
            shape.getNativeHandle(),
            transform.getScaleX(), transform.getScaleY(),
            transform.getTranslateX(), transform.getTranslateY(),
            transform.getRangeLower(), transform.getRangeUpper(),
            overlapSupport
        ));
    }

    public static void errorCorrection(Bitmap bitmap, Shape shape, Transform transform,
                                        int errorCorrectionMode, int distanceCheckMode,
                                        double minDeviationRatio, double minImproveRatio) {
        MsdfResult.check(MsdfNative.nErrorCorrection(
            bitmap.getNativeHandle(), bitmap.getType(), bitmap.getWidth(), bitmap.getHeight(),
            shape.getNativeHandle(),
            transform.getScaleX(), transform.getScaleY(),
            transform.getTranslateX(), transform.getTranslateY(),
            transform.getRangeLower(), transform.getRangeUpper(),
            errorCorrectionMode, distanceCheckMode,
            minDeviationRatio, minImproveRatio
        ));
    }

    public static void errorCorrection(Bitmap bitmap, Shape shape, Transform transform) {
        errorCorrection(bitmap, shape, transform,
            MsdfConstants.ERROR_CORRECTION_EDGE_PRIORITY,
            MsdfConstants.DISTANCE_CHECK_AT_EDGE,
            MsdfConstants.DEFAULT_MIN_DEVIATION_RATIO,
            MsdfConstants.DEFAULT_MIN_IMPROVE_RATIO);
    }

    public static void errorCorrectionFastDistance(Bitmap bitmap, Transform transform,
                                                    double minDeviationRatio) {
        MsdfResult.check(MsdfNative.nErrorCorrectionFastDistance(
            bitmap.getNativeHandle(), bitmap.getType(), bitmap.getWidth(), bitmap.getHeight(),
            transform.getScaleX(), transform.getScaleY(),
            transform.getTranslateX(), transform.getTranslateY(),
            transform.getRangeLower(), transform.getRangeUpper(),
            minDeviationRatio
        ));
    }

    public static void errorCorrectionFastDistance(Bitmap bitmap, Transform transform) {
        errorCorrectionFastDistance(bitmap, transform, MsdfConstants.DEFAULT_MIN_DEVIATION_RATIO);
    }

    public static void errorCorrectionFastEdge(Bitmap bitmap, Transform transform,
                                                double minDeviationRatio) {
        MsdfResult.check(MsdfNative.nErrorCorrectionFastEdge(
            bitmap.getNativeHandle(), bitmap.getType(), bitmap.getWidth(), bitmap.getHeight(),
            transform.getRangeLower(), transform.getRangeUpper(),
            minDeviationRatio
        ));
    }

    public static void errorCorrectionFastEdge(Bitmap bitmap, Transform transform) {
        errorCorrectionFastEdge(bitmap, transform, MsdfConstants.DEFAULT_MIN_DEVIATION_RATIO);
    }

    public static void distanceSignCorrection(Bitmap bitmap, Shape shape, Transform transform, int fillRule) {
        MsdfResult.check(MsdfNative.nDistanceSignCorrection(
            bitmap.getNativeHandle(), bitmap.getType(), bitmap.getWidth(), bitmap.getHeight(),
            shape.getNativeHandle(),
            transform.getScaleX(), transform.getScaleY(),
            transform.getTranslateX(), transform.getTranslateY(),
            transform.getRangeLower(), transform.getRangeUpper(),
            fillRule
        ));
    }

    public static void renderSdf(Bitmap output, Bitmap sdf, Transform transform, float sdThreshold) {
        MsdfResult.check(MsdfNative.nRenderSdf(
            output.getNativeHandle(), output.getType(), output.getWidth(), output.getHeight(),
            sdf.getNativeHandle(), sdf.getType(), sdf.getWidth(), sdf.getHeight(),
            transform.getRangeLower(), transform.getRangeUpper(),
            sdThreshold
        ));
    }

    public static void renderSdf(Bitmap output, Bitmap sdf, Transform transform) {
        renderSdf(output, sdf, transform, 0.5f);
    }
}
