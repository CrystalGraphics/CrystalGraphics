#ifndef MSDFGEN_JNI_H
#define MSDFGEN_JNI_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

// Bitmap
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nBitmapAlloc(JNIEnv*, jclass, jint, jint, jint, jlongArray);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nBitmapGetChannelCount(JNIEnv*, jclass, jlong, jint);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nBitmapGetPixels(JNIEnv*, jclass, jlong, jint, jint, jint, jfloatArray);
JNIEXPORT jlong JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nBitmapGetByteSize(JNIEnv*, jclass, jlong, jint, jint, jint);
JNIEXPORT void JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nBitmapFree(JNIEnv*, jclass, jlong, jint);
JNIEXPORT jlong JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nBitmapGetPixelPointer(JNIEnv*, jclass, jlong, jint);

// Shape
JNIEXPORT jlong JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nShapeAlloc(JNIEnv*, jclass);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nShapeGetBounds(JNIEnv*, jclass, jlong, jdoubleArray);
JNIEXPORT jlong JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nShapeAddContour(JNIEnv*, jclass, jlong);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nShapeRemoveContour(JNIEnv*, jclass, jlong, jlong);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nShapeGetContourCount(JNIEnv*, jclass, jlong);
JNIEXPORT jlong JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nShapeGetContour(JNIEnv*, jclass, jlong, jint);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nShapeGetEdgeCount(JNIEnv*, jclass, jlong);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nShapeGetYAxisOrientation(JNIEnv*, jclass, jlong);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nShapeSetYAxisOrientation(JNIEnv*, jclass, jlong, jint);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nShapeNormalize(JNIEnv*, jclass, jlong);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nShapeValidate(JNIEnv*, jclass, jlong);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nShapeBound(JNIEnv*, jclass, jlong, jdoubleArray);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nShapeBoundMiters(JNIEnv*, jclass, jlong, jdoubleArray, jdouble, jdouble, jint);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nShapeOrientContours(JNIEnv*, jclass, jlong);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nShapeEdgeColorsSimple(JNIEnv*, jclass, jlong, jdouble);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nShapeEdgeColorsInkTrap(JNIEnv*, jclass, jlong, jdouble);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nShapeEdgeColorsByDistance(JNIEnv*, jclass, jlong, jdouble);
JNIEXPORT jdouble JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nShapeOneShotDistance(JNIEnv*, jclass, jlong, jdouble, jdouble);
JNIEXPORT void JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nShapeFree(JNIEnv*, jclass, jlong);

// Contour
JNIEXPORT jlong JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nContourAlloc(JNIEnv*, jclass);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nContourAddEdge(JNIEnv*, jclass, jlong, jlong);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nContourRemoveEdge(JNIEnv*, jclass, jlong, jlong);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nContourGetEdgeCount(JNIEnv*, jclass, jlong);
JNIEXPORT jlong JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nContourGetEdge(JNIEnv*, jclass, jlong, jint);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nContourGetWinding(JNIEnv*, jclass, jlong);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nContourReverse(JNIEnv*, jclass, jlong);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nContourBound(JNIEnv*, jclass, jlong, jdoubleArray);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nContourBoundMiters(JNIEnv*, jclass, jlong, jdoubleArray, jdouble, jdouble, jint);
JNIEXPORT void JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nContourFree(JNIEnv*, jclass, jlong);

// Segment
JNIEXPORT jlong JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nSegmentAlloc(JNIEnv*, jclass, jint);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nSegmentGetType(JNIEnv*, jclass, jlong);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nSegmentGetPointCount(JNIEnv*, jclass, jlong);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nSegmentGetPoint(JNIEnv*, jclass, jlong, jint, jdoubleArray);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nSegmentSetPoint(JNIEnv*, jclass, jlong, jint, jdouble, jdouble);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nSegmentGetColor(JNIEnv*, jclass, jlong);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nSegmentSetColor(JNIEnv*, jclass, jlong, jint);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nSegmentGetDirection(JNIEnv*, jclass, jlong, jdouble, jdoubleArray);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nSegmentGetDirectionChange(JNIEnv*, jclass, jlong, jdouble, jdoubleArray);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nSegmentPoint(JNIEnv*, jclass, jlong, jdouble, jdoubleArray);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nSegmentBound(JNIEnv*, jclass, jlong, jdoubleArray);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nSegmentMoveStartPoint(JNIEnv*, jclass, jlong, jdouble, jdouble);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nSegmentMoveEndPoint(JNIEnv*, jclass, jlong, jdouble, jdouble);
JNIEXPORT void JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nSegmentFree(JNIEnv*, jclass, jlong);

// Generation (basic)
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nGenerateSdf(JNIEnv*, jclass, jlong, jint, jint, jint, jlong, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nGeneratePsdf(JNIEnv*, jclass, jlong, jint, jint, jint, jlong, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nGenerateMsdf(JNIEnv*, jclass, jlong, jint, jint, jint, jlong, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nGenerateMtsdf(JNIEnv*, jclass, jlong, jint, jint, jint, jlong, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble);

// Generation (with config)
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nGenerateSdfWithConfig(JNIEnv*, jclass, jlong, jint, jint, jint, jlong, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble, jboolean);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nGeneratePsdfWithConfig(JNIEnv*, jclass, jlong, jint, jint, jint, jlong, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble, jboolean);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nGenerateMsdfWithConfig(JNIEnv*, jclass, jlong, jint, jint, jint, jlong, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble, jboolean, jint, jint, jdouble, jdouble);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nGenerateMtsdfWithConfig(JNIEnv*, jclass, jlong, jint, jint, jint, jlong, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble, jboolean, jint, jint, jdouble, jdouble);

// Error correction
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nErrorCorrection(JNIEnv*, jclass, jlong, jint, jint, jint, jlong, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble, jint, jint, jdouble, jdouble);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nErrorCorrectionFastDistance(JNIEnv*, jclass, jlong, jint, jint, jint, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nErrorCorrectionFastEdge(JNIEnv*, jclass, jlong, jint, jint, jint, jdouble, jdouble, jdouble);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nDistanceSignCorrection(JNIEnv*, jclass, jlong, jint, jint, jint, jlong, jdouble, jdouble, jdouble, jdouble, jdouble, jdouble, jint);

// Render SDF
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nRenderSdf(JNIEnv*, jclass, jlong, jint, jint, jint, jlong, jint, jint, jint, jdouble, jdouble, jfloat);

// FreeType extension
JNIEXPORT jboolean JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nHasFreetypeSupport(JNIEnv*, jclass);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nFreetypeInit(JNIEnv*, jclass, jlongArray);
JNIEXPORT void JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nFreetypeDeinit(JNIEnv*, jclass, jlong);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nLoadFont(JNIEnv*, jclass, jlong, jstring, jlongArray);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nLoadFontData(JNIEnv*, jclass, jlong, jbyteArray, jint, jlongArray);
JNIEXPORT void JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nDestroyFont(JNIEnv*, jclass, jlong);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nLoadGlyph(JNIEnv*, jclass, jlong, jint, jint, jdoubleArray, jlongArray);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nLoadGlyphByIndex(JNIEnv*, jclass, jlong, jint, jint, jdoubleArray, jlongArray);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nGetGlyphIndex(JNIEnv*, jclass, jlong, jint, jintArray);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nGetKerning(JNIEnv*, jclass, jlong, jint, jint, jdoubleArray);
JNIEXPORT jint JNICALL Java_com_crystalgraphics_msdfgen_MSDFNative_nGetKerningByIndex(JNIEnv*, jclass, jlong, jint, jint, jdoubleArray);

#ifdef __cplusplus
}
#endif

#endif
