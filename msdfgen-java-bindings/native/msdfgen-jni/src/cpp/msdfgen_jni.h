#ifndef MSDFGEN_JNI_H
#define MSDFGEN_JNI_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

// Bitmap
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nBitmapAlloc(JNIEnv*, jclass, jint, jint, jint, jlongArray);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nBitmapGetChannelCount(JNIEnv*, jclass, jlong, jint);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nBitmapGetPixels(JNIEnv*, jclass, jlong, jint, jint, jint, jfloatArray);
JNIEXPORT jlong JNICALL Java_com_msdfgen_MsdfNative_nBitmapGetByteSize(JNIEnv*, jclass, jlong, jint, jint, jint);
JNIEXPORT void JNICALL Java_com_msdfgen_MsdfNative_nBitmapFree(JNIEnv*, jclass, jlong, jint);
JNIEXPORT jlong JNICALL Java_com_msdfgen_MsdfNative_nBitmapGetPixelPointer(JNIEnv*, jclass, jlong, jint);

// Shape
JNIEXPORT jlong JNICALL Java_com_msdfgen_MsdfNative_nShapeAlloc(JNIEnv*, jclass);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nShapeGetBounds(JNIEnv*, jclass, jlong, jdoubleArray);
JNIEXPORT jlong JNICALL Java_com_msdfgen_MsdfNative_nShapeAddContour(JNIEnv*, jclass, jlong);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nShapeRemoveContour(JNIEnv*, jclass, jlong, jlong);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nShapeGetContourCount(JNIEnv*, jclass, jlong);
JNIEXPORT jlong JNICALL Java_com_msdfgen_MsdfNative_nShapeGetContour(JNIEnv*, jclass, jlong, jint);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nShapeGetEdgeCount(JNIEnv*, jclass, jlong);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nShapeGetYAxisOrientation(JNIEnv*, jclass, jlong);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nShapeSetYAxisOrientation(JNIEnv*, jclass, jlong, jint);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nShapeNormalize(JNIEnv*, jclass, jlong);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nShapeValidate(JNIEnv*, jclass, jlong);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nShapeBound(JNIEnv*, jclass, jlong, jdoubleArray);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nShapeBoundMiters(JNIEnv*, jclass, jlong, jdoubleArray, jdouble, jdouble, jint);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nShapeOrientContours(JNIEnv*, jclass, jlong);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nShapeEdgeColorsSimple(JNIEnv*, jclass, jlong, jdouble);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nShapeEdgeColorsInkTrap(JNIEnv*, jclass, jlong, jdouble);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nShapeEdgeColorsByDistance(JNIEnv*, jclass, jlong, jdouble);
JNIEXPORT jdouble JNICALL Java_com_msdfgen_MsdfNative_nShapeOneShotDistance(JNIEnv*, jclass, jlong, jdouble, jdouble);
JNIEXPORT void JNICALL Java_com_msdfgen_MsdfNative_nShapeFree(JNIEnv*, jclass, jlong);

// Contour
JNIEXPORT jlong JNICALL Java_com_msdfgen_MsdfNative_nContourAlloc(JNIEnv*, jclass);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nContourAddEdge(JNIEnv*, jclass, jlong, jlong);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nContourRemoveEdge(JNIEnv*, jclass, jlong, jlong);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nContourGetEdgeCount(JNIEnv*, jclass, jlong);
JNIEXPORT jlong JNICALL Java_com_msdfgen_MsdfNative_nContourGetEdge(JNIEnv*, jclass, jlong, jint);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nContourGetWinding(JNIEnv*, jclass, jlong);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nContourReverse(JNIEnv*, jclass, jlong);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nContourBound(JNIEnv*, jclass, jlong, jdoubleArray);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nContourBoundMiters(JNIEnv*, jclass, jlong, jdoubleArray, jdouble, jdouble, jint);
JNIEXPORT void JNICALL Java_com_msdfgen_MsdfNative_nContourFree(JNIEnv*, jclass, jlong);

// Segment
JNIEXPORT jlong JNICALL Java_com_msdfgen_MsdfNative_nSegmentAlloc(JNIEnv*, jclass, jint);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nSegmentGetType(JNIEnv*, jclass, jlong);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nSegmentGetPointCount(JNIEnv*, jclass, jlong);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nSegmentGetPoint(JNIEnv*, jclass, jlong, jint, jdoubleArray);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nSegmentSetPoint(JNIEnv*, jclass, jlong, jint, jdouble, jdouble);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nSegmentGetColor(JNIEnv*, jclass, jlong);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nSegmentSetColor(JNIEnv*, jclass, jlong, jint);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nSegmentGetDirection(JNIEnv*, jclass, jlong, jdouble, jdoubleArray);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nSegmentGetDirectionChange(JNIEnv*, jclass, jlong, jdouble, jdoubleArray);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nSegmentPoint(JNIEnv*, jclass, jlong, jdouble, jdoubleArray);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nSegmentBound(JNIEnv*, jclass, jlong, jdoubleArray);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nSegmentMoveStartPoint(JNIEnv*, jclass, jlong, jdouble, jdouble);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nSegmentMoveEndPoint(JNIEnv*, jclass, jlong, jdouble, jdouble);
JNIEXPORT void JNICALL Java_com_msdfgen_MsdfNative_nSegmentFree(JNIEnv*, jclass, jlong);

// Generation (basic)
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nGenerateSdf(JNIEnv*, jclass, jlong, jint, jint, jint, jlong, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nGeneratePsdf(JNIEnv*, jclass, jlong, jint, jint, jint, jlong, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nGenerateMsdf(JNIEnv*, jclass, jlong, jint, jint, jint, jlong, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nGenerateMtsdf(JNIEnv*, jclass, jlong, jint, jint, jint, jlong, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble);

// Generation (with config)
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nGenerateSdfWithConfig(JNIEnv*, jclass, jlong, jint, jint, jint, jlong, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble, jboolean);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nGeneratePsdfWithConfig(JNIEnv*, jclass, jlong, jint, jint, jint, jlong, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble, jboolean);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nGenerateMsdfWithConfig(JNIEnv*, jclass, jlong, jint, jint, jint, jlong, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble, jboolean, jint, jint, jdouble, jdouble);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nGenerateMtsdfWithConfig(JNIEnv*, jclass, jlong, jint, jint, jint, jlong, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble, jboolean, jint, jint, jdouble, jdouble);

// Error correction
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nErrorCorrection(JNIEnv*, jclass, jlong, jint, jint, jint, jlong, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble, jint, jint, jdouble, jdouble);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nErrorCorrectionFastDistance(JNIEnv*, jclass, jlong, jint, jint, jint, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble);
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nErrorCorrectionFastEdge(JNIEnv*, jclass, jlong, jint, jint, jint, jdouble, jdouble, jdouble);

// Render SDF
JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nRenderSdf(JNIEnv*, jclass, jlong, jint, jint, jint, jlong, jint, jint, jint, jdouble, jdouble, jfloat);

// FreeType extension
JNIEXPORT jboolean JNICALL Java_com_msdfgen_MsdfNative_nHasFreetypeSupport(JNIEnv*, jclass);
JNIEXPORT jlong JNICALL Java_com_msdfgen_MsdfNative_nFreetypeInit(JNIEnv*, jclass);
JNIEXPORT void JNICALL Java_com_msdfgen_MsdfNative_nFreetypeDeinit(JNIEnv*, jclass, jlong);
JNIEXPORT jlong JNICALL Java_com_msdfgen_MsdfNative_nLoadFont(JNIEnv*, jclass, jlong, jstring);
JNIEXPORT void JNICALL Java_com_msdfgen_MsdfNative_nDestroyFont(JNIEnv*, jclass, jlong);
JNIEXPORT jlong JNICALL Java_com_msdfgen_MsdfNative_nLoadGlyph(JNIEnv*, jclass, jlong, jint, jlong);

#ifdef __cplusplus
}
#endif

#endif
