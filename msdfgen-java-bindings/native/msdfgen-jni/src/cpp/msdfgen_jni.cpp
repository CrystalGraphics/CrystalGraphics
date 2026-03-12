#include "msdfgen_jni.h"
#include "msdfgen.h"
#include "core/Shape.h"
#include "core/Contour.h"
#include "core/edge-segments.h"
#include "core/edge-coloring.h"
#include "core/Bitmap.h"
#include "core/generator-config.h"
#include "core/msdf-error-correction.h"
#include "core/render-sdf.h"
#include <cstring>

#define MSDF_SUCCESS 0
#define MSDF_ERR_FAILED 1
#define MSDF_ERR_INVALID_ARG 2

using namespace msdfgen;

// ==================== Bitmap ====================

struct JniBitmapWrapper {
    int type;
    int width;
    int height;
    void* data;
};

static JniBitmapWrapper* allocBitmapWrapper(int type, int width, int height) {
    JniBitmapWrapper* wrapper = new JniBitmapWrapper();
    wrapper->type = type;
    wrapper->width = width;
    wrapper->height = height;

    switch (type) {
        case 0: wrapper->data = new Bitmap<float, 1>(width, height); break;
        case 1: wrapper->data = new Bitmap<float, 1>(width, height); break;
        case 2: wrapper->data = new Bitmap<float, 3>(width, height); break;
        case 3: wrapper->data = new Bitmap<float, 4>(width, height); break;
        default: delete wrapper; return nullptr;
    }
    return wrapper;
}

static void freeBitmapWrapper(JniBitmapWrapper* wrapper) {
    if (!wrapper) return;
    switch (wrapper->type) {
        case 0: case 1: delete static_cast<Bitmap<float, 1>*>(wrapper->data); break;
        case 2: delete static_cast<Bitmap<float, 3>*>(wrapper->data); break;
        case 3: delete static_cast<Bitmap<float, 4>*>(wrapper->data); break;
    }
    delete wrapper;
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nBitmapAlloc(
    JNIEnv* env, jclass, jint type, jint width, jint height, jlongArray handleOut) {
    JniBitmapWrapper* wrapper = allocBitmapWrapper(type, width, height);
    if (!wrapper) return MSDF_ERR_INVALID_ARG;
    jlong handle = reinterpret_cast<jlong>(wrapper);
    env->SetLongArrayRegion(handleOut, 0, 1, &handle);
    return MSDF_SUCCESS;
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nBitmapGetChannelCount(
    JNIEnv*, jclass, jlong, jint type) {
    switch (type) {
        case 0: case 1: return 1;
        case 2: return 3;
        case 3: return 4;
        default: return 0;
    }
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nBitmapGetPixels(
    JNIEnv* env, jclass, jlong bitmapHandle, jint type, jint width, jint height, jfloatArray pixelsOut) {
    JniBitmapWrapper* wrapper = reinterpret_cast<JniBitmapWrapper*>(bitmapHandle);
    if (!wrapper) return MSDF_ERR_INVALID_ARG;

    int channels;
    const float* src;
    switch (type) {
        case 0: case 1: {
            auto* bmp = static_cast<Bitmap<float, 1>*>(wrapper->data);
            channels = 1;
            src = static_cast<const float*>(*bmp);
            break;
        }
        case 2: {
            auto* bmp = static_cast<Bitmap<float, 3>*>(wrapper->data);
            channels = 3;
            src = reinterpret_cast<const float*>(static_cast<const float*>(*bmp));
            break;
        }
        case 3: {
            auto* bmp = static_cast<Bitmap<float, 4>*>(wrapper->data);
            channels = 4;
            src = reinterpret_cast<const float*>(static_cast<const float*>(*bmp));
            break;
        }
        default: return MSDF_ERR_INVALID_ARG;
    }

    int totalFloats = width * height * channels;
    env->SetFloatArrayRegion(pixelsOut, 0, totalFloats, src);
    return MSDF_SUCCESS;
}

JNIEXPORT jlong JNICALL Java_com_msdfgen_MsdfNative_nBitmapGetByteSize(
    JNIEnv*, jclass, jlong, jint type, jint width, jint height) {
    int channels;
    switch (type) {
        case 0: case 1: channels = 1; break;
        case 2: channels = 3; break;
        case 3: channels = 4; break;
        default: return 0;
    }
    return (jlong)(width * height * channels * sizeof(float));
}

JNIEXPORT void JNICALL Java_com_msdfgen_MsdfNative_nBitmapFree(
    JNIEnv*, jclass, jlong bitmapHandle, jint) {
    freeBitmapWrapper(reinterpret_cast<JniBitmapWrapper*>(bitmapHandle));
}

// ==================== Shape ====================

JNIEXPORT jlong JNICALL Java_com_msdfgen_MsdfNative_nShapeAlloc(JNIEnv*, jclass) {
    Shape* shape = new Shape();
    return reinterpret_cast<jlong>(shape);
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nShapeGetBounds(
    JNIEnv* env, jclass, jlong shapePtr, jdoubleArray boundsOut) {
    Shape* shape = reinterpret_cast<Shape*>(shapePtr);
    if (!shape) return MSDF_ERR_INVALID_ARG;
    Shape::Bounds b = shape->getBounds();
    jdouble bounds[4] = { b.l, b.b, b.r, b.t };
    env->SetDoubleArrayRegion(boundsOut, 0, 4, bounds);
    return MSDF_SUCCESS;
}

JNIEXPORT jlong JNICALL Java_com_msdfgen_MsdfNative_nShapeAddContour(JNIEnv*, jclass, jlong shapePtr) {
    Shape* shape = reinterpret_cast<Shape*>(shapePtr);
    if (!shape) return 0;
    Contour& contour = shape->addContour();
    return reinterpret_cast<jlong>(&contour);
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nShapeRemoveContour(
    JNIEnv*, jclass, jlong shapePtr, jlong contourPtr) {
    Shape* shape = reinterpret_cast<Shape*>(shapePtr);
    Contour* contour = reinterpret_cast<Contour*>(contourPtr);
    if (!shape || !contour) return MSDF_ERR_INVALID_ARG;
    for (size_t i = 0; i < shape->contours.size(); i++) {
        if (&shape->contours[i] == contour) {
            shape->contours.erase(shape->contours.begin() + i);
            return MSDF_SUCCESS;
        }
    }
    return MSDF_ERR_FAILED;
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nShapeGetContourCount(JNIEnv*, jclass, jlong shapePtr) {
    Shape* shape = reinterpret_cast<Shape*>(shapePtr);
    if (!shape) return 0;
    return static_cast<jint>(shape->contours.size());
}

JNIEXPORT jlong JNICALL Java_com_msdfgen_MsdfNative_nShapeGetContour(
    JNIEnv*, jclass, jlong shapePtr, jint index) {
    Shape* shape = reinterpret_cast<Shape*>(shapePtr);
    if (!shape || index < 0 || (size_t)index >= shape->contours.size()) return 0;
    return reinterpret_cast<jlong>(&shape->contours[index]);
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nShapeGetEdgeCount(JNIEnv*, jclass, jlong shapePtr) {
    Shape* shape = reinterpret_cast<Shape*>(shapePtr);
    if (!shape) return 0;
    return shape->edgeCount();
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nShapeGetYAxisOrientation(JNIEnv*, jclass, jlong shapePtr) {
    Shape* shape = reinterpret_cast<Shape*>(shapePtr);
    if (!shape) return 0;
    return shape->inverseYAxis ? 1 : 0;
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nShapeSetYAxisOrientation(
    JNIEnv*, jclass, jlong shapePtr, jint orientation) {
    Shape* shape = reinterpret_cast<Shape*>(shapePtr);
    if (!shape) return MSDF_ERR_INVALID_ARG;
    shape->inverseYAxis = (orientation == 1);
    return MSDF_SUCCESS;
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nShapeNormalize(JNIEnv*, jclass, jlong shapePtr) {
    Shape* shape = reinterpret_cast<Shape*>(shapePtr);
    if (!shape) return MSDF_ERR_INVALID_ARG;
    shape->normalize();
    return MSDF_SUCCESS;
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nShapeValidate(JNIEnv*, jclass, jlong shapePtr) {
    Shape* shape = reinterpret_cast<Shape*>(shapePtr);
    if (!shape) return 0;
    return shape->validate() ? 1 : 0;
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nShapeBound(
    JNIEnv* env, jclass, jlong shapePtr, jdoubleArray boundsOut) {
    Shape* shape = reinterpret_cast<Shape*>(shapePtr);
    if (!shape) return MSDF_ERR_INVALID_ARG;
    double l = 1e240, b = 1e240, r = -1e240, t = -1e240;
    shape->bound(l, b, r, t);
    jdouble bounds[4] = { l, b, r, t };
    env->SetDoubleArrayRegion(boundsOut, 0, 4, bounds);
    return MSDF_SUCCESS;
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nShapeBoundMiters(
    JNIEnv* env, jclass, jlong shapePtr, jdoubleArray boundsInOut,
    jdouble border, jdouble miterLimit, jint polarity) {
    Shape* shape = reinterpret_cast<Shape*>(shapePtr);
    if (!shape) return MSDF_ERR_INVALID_ARG;
    jdouble boundsArr[4];
    env->GetDoubleArrayRegion(boundsInOut, 0, 4, boundsArr);
    shape->boundMiters(boundsArr[0], boundsArr[1], boundsArr[2], boundsArr[3], border, miterLimit, polarity);
    env->SetDoubleArrayRegion(boundsInOut, 0, 4, boundsArr);
    return MSDF_SUCCESS;
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nShapeOrientContours(JNIEnv*, jclass, jlong shapePtr) {
    Shape* shape = reinterpret_cast<Shape*>(shapePtr);
    if (!shape) return MSDF_ERR_INVALID_ARG;
    shape->orientContours();
    return MSDF_SUCCESS;
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nShapeEdgeColorsSimple(
    JNIEnv*, jclass, jlong shapePtr, jdouble angleThreshold) {
    Shape* shape = reinterpret_cast<Shape*>(shapePtr);
    if (!shape) return MSDF_ERR_INVALID_ARG;
    edgeColoringSimple(*shape, angleThreshold);
    return MSDF_SUCCESS;
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nShapeEdgeColorsInkTrap(
    JNIEnv*, jclass, jlong shapePtr, jdouble angleThreshold) {
    Shape* shape = reinterpret_cast<Shape*>(shapePtr);
    if (!shape) return MSDF_ERR_INVALID_ARG;
    edgeColoringInkTrap(*shape, angleThreshold);
    return MSDF_SUCCESS;
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nShapeEdgeColorsByDistance(
    JNIEnv*, jclass, jlong shapePtr, jdouble angleThreshold) {
    Shape* shape = reinterpret_cast<Shape*>(shapePtr);
    if (!shape) return MSDF_ERR_INVALID_ARG;
    edgeColoringByDistance(*shape, angleThreshold);
    return MSDF_SUCCESS;
}

JNIEXPORT jdouble JNICALL Java_com_msdfgen_MsdfNative_nShapeOneShotDistance(
    JNIEnv*, jclass, jlong shapePtr, jdouble originX, jdouble originY) {
    Shape* shape = reinterpret_cast<Shape*>(shapePtr);
    if (!shape) return 0.0;
    Point2 origin(originX, originY);
    double minDist = 1e240;
    for (size_t i = 0; i < shape->contours.size(); i++) {
        const Contour& contour = shape->contours[i];
        for (size_t j = 0; j < contour.edges.size(); j++) {
            double param;
            SignedDistance dist = contour.edges[j]->signedDistance(origin, param);
            if (fabs(dist.distance) < fabs(minDist)) {
                minDist = dist.distance;
            }
        }
    }
    return minDist;
}

JNIEXPORT void JNICALL Java_com_msdfgen_MsdfNative_nShapeFree(JNIEnv*, jclass, jlong shapePtr) {
    delete reinterpret_cast<Shape*>(shapePtr);
}

// ==================== Contour ====================

JNIEXPORT jlong JNICALL Java_com_msdfgen_MsdfNative_nContourAlloc(JNIEnv*, jclass) {
    return reinterpret_cast<jlong>(new Contour());
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nContourAddEdge(
    JNIEnv*, jclass, jlong contourPtr, jlong segmentPtr) {
    Contour* contour = reinterpret_cast<Contour*>(contourPtr);
    EdgeSegment* segment = reinterpret_cast<EdgeSegment*>(segmentPtr);
    if (!contour || !segment) return MSDF_ERR_INVALID_ARG;
    contour->addEdge(EdgeHolder(segment->clone()));
    return MSDF_SUCCESS;
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nContourRemoveEdge(
    JNIEnv*, jclass, jlong contourPtr, jlong segmentPtr) {
    Contour* contour = reinterpret_cast<Contour*>(contourPtr);
    EdgeSegment* segment = reinterpret_cast<EdgeSegment*>(segmentPtr);
    if (!contour || !segment) return MSDF_ERR_INVALID_ARG;
    for (size_t i = 0; i < contour->edges.size(); i++) {
        if (contour->edges[i] == segment) {
            contour->edges.erase(contour->edges.begin() + i);
            return MSDF_SUCCESS;
        }
    }
    return MSDF_ERR_FAILED;
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nContourGetEdgeCount(
    JNIEnv*, jclass, jlong contourPtr) {
    Contour* contour = reinterpret_cast<Contour*>(contourPtr);
    if (!contour) return 0;
    return static_cast<jint>(contour->edges.size());
}

JNIEXPORT jlong JNICALL Java_com_msdfgen_MsdfNative_nContourGetEdge(
    JNIEnv*, jclass, jlong contourPtr, jint index) {
    Contour* contour = reinterpret_cast<Contour*>(contourPtr);
    if (!contour || index < 0 || (size_t)index >= contour->edges.size()) return 0;
    EdgeSegment* seg = contour->edges[index];
    return reinterpret_cast<jlong>(seg);
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nContourGetWinding(
    JNIEnv*, jclass, jlong contourPtr) {
    Contour* contour = reinterpret_cast<Contour*>(contourPtr);
    if (!contour) return 0;
    return contour->winding();
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nContourReverse(
    JNIEnv*, jclass, jlong contourPtr) {
    Contour* contour = reinterpret_cast<Contour*>(contourPtr);
    if (!contour) return MSDF_ERR_INVALID_ARG;
    contour->reverse();
    return MSDF_SUCCESS;
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nContourBound(
    JNIEnv* env, jclass, jlong contourPtr, jdoubleArray boundsOut) {
    Contour* contour = reinterpret_cast<Contour*>(contourPtr);
    if (!contour) return MSDF_ERR_INVALID_ARG;
    double l = 1e240, b = 1e240, r = -1e240, t = -1e240;
    contour->bound(l, b, r, t);
    jdouble bounds[4] = { l, b, r, t };
    env->SetDoubleArrayRegion(boundsOut, 0, 4, bounds);
    return MSDF_SUCCESS;
}

JNIEXPORT void JNICALL Java_com_msdfgen_MsdfNative_nContourFree(JNIEnv*, jclass, jlong contourPtr) {
    delete reinterpret_cast<Contour*>(contourPtr);
}

// ==================== Segment ====================

static EdgeSegment* createSegment(int type) {
    switch (type) {
        case 0: return new LinearSegment(Point2(0, 0), Point2(0, 0));
        case 1: return new QuadraticSegment(Point2(0, 0), Point2(0, 0), Point2(0, 0));
        case 2: return new CubicSegment(Point2(0, 0), Point2(0, 0), Point2(0, 0), Point2(0, 0));
        default: return nullptr;
    }
}

JNIEXPORT jlong JNICALL Java_com_msdfgen_MsdfNative_nSegmentAlloc(JNIEnv*, jclass, jint type) {
    EdgeSegment* seg = createSegment(type);
    return reinterpret_cast<jlong>(seg);
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nSegmentGetType(JNIEnv*, jclass, jlong segPtr) {
    EdgeSegment* seg = reinterpret_cast<EdgeSegment*>(segPtr);
    if (!seg) return -1;
    return seg->type() - 1;
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nSegmentGetPointCount(JNIEnv*, jclass, jlong segPtr) {
    EdgeSegment* seg = reinterpret_cast<EdgeSegment*>(segPtr);
    if (!seg) return 0;
    return seg->type() + 1;
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nSegmentGetPoint(
    JNIEnv* env, jclass, jlong segPtr, jint index, jdoubleArray pointOut) {
    EdgeSegment* seg = reinterpret_cast<EdgeSegment*>(segPtr);
    if (!seg) return MSDF_ERR_INVALID_ARG;
    const Point2* pts = seg->controlPoints();
    int count = seg->type() + 1;
    if (index < 0 || index >= count) return MSDF_ERR_INVALID_ARG;
    jdouble pt[2] = { pts[index].x, pts[index].y };
    env->SetDoubleArrayRegion(pointOut, 0, 2, pt);
    return MSDF_SUCCESS;
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nSegmentSetPoint(
    JNIEnv*, jclass, jlong segPtr, jint index, jdouble x, jdouble y) {
    EdgeSegment* seg = reinterpret_cast<EdgeSegment*>(segPtr);
    if (!seg) return MSDF_ERR_INVALID_ARG;
    int count = seg->type() + 1;
    if (index < 0 || index >= count) return MSDF_ERR_INVALID_ARG;

    switch (seg->type()) {
        case 1: static_cast<LinearSegment*>(seg)->p[index] = Point2(x, y); break;
        case 2: static_cast<QuadraticSegment*>(seg)->p[index] = Point2(x, y); break;
        case 3: static_cast<CubicSegment*>(seg)->p[index] = Point2(x, y); break;
        default: return MSDF_ERR_INVALID_ARG;
    }
    return MSDF_SUCCESS;
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nSegmentGetColor(JNIEnv*, jclass, jlong segPtr) {
    EdgeSegment* seg = reinterpret_cast<EdgeSegment*>(segPtr);
    if (!seg) return 0;
    return static_cast<int>(seg->color);
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nSegmentSetColor(
    JNIEnv*, jclass, jlong segPtr, jint color) {
    EdgeSegment* seg = reinterpret_cast<EdgeSegment*>(segPtr);
    if (!seg) return MSDF_ERR_INVALID_ARG;
    seg->color = static_cast<EdgeColor>(color);
    return MSDF_SUCCESS;
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nSegmentGetDirection(
    JNIEnv* env, jclass, jlong segPtr, jdouble param, jdoubleArray dirOut) {
    EdgeSegment* seg = reinterpret_cast<EdgeSegment*>(segPtr);
    if (!seg) return MSDF_ERR_INVALID_ARG;
    Vector2 dir = seg->direction(param);
    jdouble d[2] = { dir.x, dir.y };
    env->SetDoubleArrayRegion(dirOut, 0, 2, d);
    return MSDF_SUCCESS;
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nSegmentPoint(
    JNIEnv* env, jclass, jlong segPtr, jdouble param, jdoubleArray pointOut) {
    EdgeSegment* seg = reinterpret_cast<EdgeSegment*>(segPtr);
    if (!seg) return MSDF_ERR_INVALID_ARG;
    Point2 pt = seg->point(param);
    jdouble p[2] = { pt.x, pt.y };
    env->SetDoubleArrayRegion(pointOut, 0, 2, p);
    return MSDF_SUCCESS;
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nSegmentBound(
    JNIEnv* env, jclass, jlong segPtr, jdoubleArray boundsOut) {
    EdgeSegment* seg = reinterpret_cast<EdgeSegment*>(segPtr);
    if (!seg) return MSDF_ERR_INVALID_ARG;
    double l = 1e240, b = 1e240, r = -1e240, t = -1e240;
    seg->bound(l, b, r, t);
    jdouble bounds[4] = { l, b, r, t };
    env->SetDoubleArrayRegion(boundsOut, 0, 4, bounds);
    return MSDF_SUCCESS;
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nSegmentMoveStartPoint(
    JNIEnv*, jclass, jlong segPtr, jdouble x, jdouble y) {
    EdgeSegment* seg = reinterpret_cast<EdgeSegment*>(segPtr);
    if (!seg) return MSDF_ERR_INVALID_ARG;
    seg->moveStartPoint(Point2(x, y));
    return MSDF_SUCCESS;
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nSegmentMoveEndPoint(
    JNIEnv*, jclass, jlong segPtr, jdouble x, jdouble y) {
    EdgeSegment* seg = reinterpret_cast<EdgeSegment*>(segPtr);
    if (!seg) return MSDF_ERR_INVALID_ARG;
    seg->moveEndPoint(Point2(x, y));
    return MSDF_SUCCESS;
}

JNIEXPORT void JNICALL Java_com_msdfgen_MsdfNative_nSegmentFree(JNIEnv*, jclass, jlong segPtr) {
    delete reinterpret_cast<EdgeSegment*>(segPtr);
}

// ==================== Generation ====================

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nGenerateSdf(
    JNIEnv*, jclass, jlong bitmapHandle, jint, jint, jint,
    jlong shapePtr, jdouble scaleX, jdouble scaleY,
    jdouble translateX, jdouble translateY, jdouble rangeLower, jdouble rangeUpper) {
    JniBitmapWrapper* bmp = reinterpret_cast<JniBitmapWrapper*>(bitmapHandle);
    Shape* shape = reinterpret_cast<Shape*>(shapePtr);
    if (!bmp || !shape) return MSDF_ERR_INVALID_ARG;

    Bitmap<float, 1>* bitmap = static_cast<Bitmap<float, 1>*>(bmp->data);
    Projection projection(Vector2(scaleX, scaleY), Vector2(translateX, translateY));
    Range range(rangeLower, rangeUpper);
    generateSDF(*bitmap, *shape, projection, range);
    return MSDF_SUCCESS;
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nGeneratePsdf(
    JNIEnv*, jclass, jlong bitmapHandle, jint, jint, jint,
    jlong shapePtr, jdouble scaleX, jdouble scaleY,
    jdouble translateX, jdouble translateY, jdouble rangeLower, jdouble rangeUpper) {
    JniBitmapWrapper* bmp = reinterpret_cast<JniBitmapWrapper*>(bitmapHandle);
    Shape* shape = reinterpret_cast<Shape*>(shapePtr);
    if (!bmp || !shape) return MSDF_ERR_INVALID_ARG;

    Bitmap<float, 1>* bitmap = static_cast<Bitmap<float, 1>*>(bmp->data);
    Projection projection(Vector2(scaleX, scaleY), Vector2(translateX, translateY));
    Range range(rangeLower, rangeUpper);
    generatePSDF(*bitmap, *shape, projection, range);
    return MSDF_SUCCESS;
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nGenerateMsdf(
    JNIEnv*, jclass, jlong bitmapHandle, jint, jint, jint,
    jlong shapePtr, jdouble scaleX, jdouble scaleY,
    jdouble translateX, jdouble translateY, jdouble rangeLower, jdouble rangeUpper) {
    JniBitmapWrapper* bmp = reinterpret_cast<JniBitmapWrapper*>(bitmapHandle);
    Shape* shape = reinterpret_cast<Shape*>(shapePtr);
    if (!bmp || !shape) return MSDF_ERR_INVALID_ARG;

    Bitmap<float, 3>* bitmap = static_cast<Bitmap<float, 3>*>(bmp->data);
    Projection projection(Vector2(scaleX, scaleY), Vector2(translateX, translateY));
    Range range(rangeLower, rangeUpper);
    generateMSDF(*bitmap, *shape, projection, range);
    return MSDF_SUCCESS;
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nGenerateMtsdf(
    JNIEnv*, jclass, jlong bitmapHandle, jint, jint, jint,
    jlong shapePtr, jdouble scaleX, jdouble scaleY,
    jdouble translateX, jdouble translateY, jdouble rangeLower, jdouble rangeUpper) {
    JniBitmapWrapper* bmp = reinterpret_cast<JniBitmapWrapper*>(bitmapHandle);
    Shape* shape = reinterpret_cast<Shape*>(shapePtr);
    if (!bmp || !shape) return MSDF_ERR_INVALID_ARG;

    Bitmap<float, 4>* bitmap = static_cast<Bitmap<float, 4>*>(bmp->data);
    Projection projection(Vector2(scaleX, scaleY), Vector2(translateX, translateY));
    Range range(rangeLower, rangeUpper);
    generateMTSDF(*bitmap, *shape, projection, range);
    return MSDF_SUCCESS;
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nGenerateSdfWithConfig(
    JNIEnv*, jclass, jlong bitmapHandle, jint, jint, jint,
    jlong shapePtr, jdouble scaleX, jdouble scaleY,
    jdouble translateX, jdouble translateY, jdouble rangeLower, jdouble rangeUpper,
    jboolean overlapSupport) {
    JniBitmapWrapper* bmp = reinterpret_cast<JniBitmapWrapper*>(bitmapHandle);
    Shape* shape = reinterpret_cast<Shape*>(shapePtr);
    if (!bmp || !shape) return MSDF_ERR_INVALID_ARG;

    Bitmap<float, 1>* bitmap = static_cast<Bitmap<float, 1>*>(bmp->data);
    Projection projection(Vector2(scaleX, scaleY), Vector2(translateX, translateY));
    Range range(rangeLower, rangeUpper);
    GeneratorConfig config(overlapSupport);
    generateSDF(*bitmap, *shape, projection, range, config);
    return MSDF_SUCCESS;
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nGenerateMsdfWithConfig(
    JNIEnv*, jclass, jlong bitmapHandle, jint, jint, jint,
    jlong shapePtr, jdouble scaleX, jdouble scaleY,
    jdouble translateX, jdouble translateY, jdouble rangeLower, jdouble rangeUpper,
    jboolean overlapSupport, jint errorCorrectionMode, jint distanceCheckMode,
    jdouble minDeviationRatio, jdouble minImproveRatio) {
    JniBitmapWrapper* bmp = reinterpret_cast<JniBitmapWrapper*>(bitmapHandle);
    Shape* shape = reinterpret_cast<Shape*>(shapePtr);
    if (!bmp || !shape) return MSDF_ERR_INVALID_ARG;

    Bitmap<float, 3>* bitmap = static_cast<Bitmap<float, 3>*>(bmp->data);
    Projection projection(Vector2(scaleX, scaleY), Vector2(translateX, translateY));
    Range range(rangeLower, rangeUpper);
    ErrorCorrectionConfig ecConfig(
        static_cast<ErrorCorrectionConfig::Mode>(errorCorrectionMode),
        static_cast<ErrorCorrectionConfig::DistanceCheckMode>(distanceCheckMode),
        minDeviationRatio, minImproveRatio
    );
    MSDFGeneratorConfig config(overlapSupport, ecConfig);
    generateMSDF(*bitmap, *shape, projection, range, config);
    return MSDF_SUCCESS;
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nGenerateMtsdfWithConfig(
    JNIEnv*, jclass, jlong bitmapHandle, jint, jint, jint,
    jlong shapePtr, jdouble scaleX, jdouble scaleY,
    jdouble translateX, jdouble translateY, jdouble rangeLower, jdouble rangeUpper,
    jboolean overlapSupport, jint errorCorrectionMode, jint distanceCheckMode,
    jdouble minDeviationRatio, jdouble minImproveRatio) {
    JniBitmapWrapper* bmp = reinterpret_cast<JniBitmapWrapper*>(bitmapHandle);
    Shape* shape = reinterpret_cast<Shape*>(shapePtr);
    if (!bmp || !shape) return MSDF_ERR_INVALID_ARG;

    Bitmap<float, 4>* bitmap = static_cast<Bitmap<float, 4>*>(bmp->data);
    Projection projection(Vector2(scaleX, scaleY), Vector2(translateX, translateY));
    Range range(rangeLower, rangeUpper);
    ErrorCorrectionConfig ecConfig(
        static_cast<ErrorCorrectionConfig::Mode>(errorCorrectionMode),
        static_cast<ErrorCorrectionConfig::DistanceCheckMode>(distanceCheckMode),
        minDeviationRatio, minImproveRatio
    );
    MSDFGeneratorConfig config(overlapSupport, ecConfig);
    generateMTSDF(*bitmap, *shape, projection, range, config);
    return MSDF_SUCCESS;
}

// ==================== PSDF with Config ====================

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nGeneratePsdfWithConfig(
    JNIEnv*, jclass, jlong bitmapHandle, jint, jint, jint,
    jlong shapePtr, jdouble scaleX, jdouble scaleY,
    jdouble translateX, jdouble translateY, jdouble rangeLower, jdouble rangeUpper,
    jboolean overlapSupport) {
    JniBitmapWrapper* bmp = reinterpret_cast<JniBitmapWrapper*>(bitmapHandle);
    Shape* shape = reinterpret_cast<Shape*>(shapePtr);
    if (!bmp || !shape) return MSDF_ERR_INVALID_ARG;

    Bitmap<float, 1>* bitmap = static_cast<Bitmap<float, 1>*>(bmp->data);
    Projection projection(Vector2(scaleX, scaleY), Vector2(translateX, translateY));
    Range range(rangeLower, rangeUpper);
    GeneratorConfig config(overlapSupport);
    generatePSDF(*bitmap, *shape, projection, range, config);
    return MSDF_SUCCESS;
}

// ==================== Error Correction ====================

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nErrorCorrection(
    JNIEnv*, jclass, jlong bitmapHandle, jint bitmapType, jint, jint,
    jlong shapePtr, jdouble scaleX, jdouble scaleY,
    jdouble translateX, jdouble translateY, jdouble rangeLower, jdouble rangeUpper,
    jint errorCorrectionMode, jint distanceCheckMode,
    jdouble minDeviationRatio, jdouble minImproveRatio) {
    JniBitmapWrapper* bmp = reinterpret_cast<JniBitmapWrapper*>(bitmapHandle);
    Shape* shape = reinterpret_cast<Shape*>(shapePtr);
    if (!bmp || !shape) return MSDF_ERR_INVALID_ARG;

    Projection projection(Vector2(scaleX, scaleY), Vector2(translateX, translateY));
    Range range(rangeLower, rangeUpper);
    ErrorCorrectionConfig ecConfig(
        static_cast<ErrorCorrectionConfig::Mode>(errorCorrectionMode),
        static_cast<ErrorCorrectionConfig::DistanceCheckMode>(distanceCheckMode),
        minDeviationRatio, minImproveRatio
    );
    MSDFGeneratorConfig config(false, ecConfig);

    switch (bitmapType) {
        case 2: {
            Bitmap<float, 3>* bitmap = static_cast<Bitmap<float, 3>*>(bmp->data);
            msdfErrorCorrection(*bitmap, *shape, projection, range, config);
            break;
        }
        case 3: {
            Bitmap<float, 4>* bitmap = static_cast<Bitmap<float, 4>*>(bmp->data);
            msdfErrorCorrection(*bitmap, *shape, projection, range, config);
            break;
        }
        default:
            return MSDF_ERR_INVALID_ARG;
    }
    return MSDF_SUCCESS;
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nErrorCorrectionFastDistance(
    JNIEnv*, jclass, jlong bitmapHandle, jint bitmapType, jint, jint,
    jdouble scaleX, jdouble scaleY,
    jdouble translateX, jdouble translateY, jdouble rangeLower, jdouble rangeUpper,
    jdouble minDeviationRatio) {
    JniBitmapWrapper* bmp = reinterpret_cast<JniBitmapWrapper*>(bitmapHandle);
    if (!bmp) return MSDF_ERR_INVALID_ARG;

    Projection projection(Vector2(scaleX, scaleY), Vector2(translateX, translateY));
    Range range(rangeLower, rangeUpper);

    switch (bitmapType) {
        case 2: {
            Bitmap<float, 3>* bitmap = static_cast<Bitmap<float, 3>*>(bmp->data);
            msdfFastDistanceErrorCorrection(*bitmap, projection, range, minDeviationRatio);
            break;
        }
        case 3: {
            Bitmap<float, 4>* bitmap = static_cast<Bitmap<float, 4>*>(bmp->data);
            msdfFastDistanceErrorCorrection(*bitmap, projection, range, minDeviationRatio);
            break;
        }
        default:
            return MSDF_ERR_INVALID_ARG;
    }
    return MSDF_SUCCESS;
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nErrorCorrectionFastEdge(
    JNIEnv*, jclass, jlong bitmapHandle, jint bitmapType, jint, jint,
    jdouble rangeLower, jdouble rangeUpper,
    jdouble minDeviationRatio) {
    JniBitmapWrapper* bmp = reinterpret_cast<JniBitmapWrapper*>(bitmapHandle);
    if (!bmp) return MSDF_ERR_INVALID_ARG;

    Range range(rangeLower, rangeUpper);

    switch (bitmapType) {
        case 2: {
            Bitmap<float, 3>* bitmap = static_cast<Bitmap<float, 3>*>(bmp->data);
            msdfFastEdgeErrorCorrection(*bitmap, range, minDeviationRatio);
            break;
        }
        case 3: {
            Bitmap<float, 4>* bitmap = static_cast<Bitmap<float, 4>*>(bmp->data);
            msdfFastEdgeErrorCorrection(*bitmap, range, minDeviationRatio);
            break;
        }
        default:
            return MSDF_ERR_INVALID_ARG;
    }
    return MSDF_SUCCESS;
}

// ==================== Contour Bound Miters ====================

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nContourBoundMiters(
    JNIEnv* env, jclass, jlong contourPtr, jdoubleArray boundsInOut,
    jdouble border, jdouble miterLimit, jint polarity) {
    Contour* contour = reinterpret_cast<Contour*>(contourPtr);
    if (!contour) return MSDF_ERR_INVALID_ARG;
    jdouble boundsArr[4];
    env->GetDoubleArrayRegion(boundsInOut, 0, 4, boundsArr);
    contour->boundMiters(boundsArr[0], boundsArr[1], boundsArr[2], boundsArr[3], border, miterLimit, polarity);
    env->SetDoubleArrayRegion(boundsInOut, 0, 4, boundsArr);
    return MSDF_SUCCESS;
}

// ==================== Segment Direction Change ====================

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nSegmentGetDirectionChange(
    JNIEnv* env, jclass, jlong segPtr, jdouble param, jdoubleArray dirChangeOut) {
    EdgeSegment* seg = reinterpret_cast<EdgeSegment*>(segPtr);
    if (!seg) return MSDF_ERR_INVALID_ARG;
    Vector2 dc = seg->directionChange(param);
    jdouble d[2] = { dc.x, dc.y };
    env->SetDoubleArrayRegion(dirChangeOut, 0, 2, d);
    return MSDF_SUCCESS;
}

// ==================== Render SDF ====================

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nRenderSdf(
    JNIEnv*, jclass,
    jlong outputHandle, jint outputType, jint, jint,
    jlong sdfHandle, jint sdfType, jint, jint,
    jdouble rangeLower, jdouble rangeUpper, jfloat sdThreshold) {
    JniBitmapWrapper* outBmp = reinterpret_cast<JniBitmapWrapper*>(outputHandle);
    JniBitmapWrapper* sdfBmp = reinterpret_cast<JniBitmapWrapper*>(sdfHandle);
    if (!outBmp || !sdfBmp) return MSDF_ERR_INVALID_ARG;

    Range range(rangeLower, rangeUpper);

    if (sdfType == 0 || sdfType == 1) {
        Bitmap<float, 1>* sdf = static_cast<Bitmap<float, 1>*>(sdfBmp->data);
        if (outputType == 0 || outputType == 1) {
            Bitmap<float, 1>* out = static_cast<Bitmap<float, 1>*>(outBmp->data);
            renderSDF(*out, *sdf, range, sdThreshold);
        } else if (outputType == 2) {
            Bitmap<float, 3>* out = static_cast<Bitmap<float, 3>*>(outBmp->data);
            renderSDF(*out, *sdf, range, sdThreshold);
        } else {
            return MSDF_ERR_INVALID_ARG;
        }
    } else if (sdfType == 2) {
        Bitmap<float, 3>* sdf = static_cast<Bitmap<float, 3>*>(sdfBmp->data);
        if (outputType == 0 || outputType == 1) {
            Bitmap<float, 1>* out = static_cast<Bitmap<float, 1>*>(outBmp->data);
            renderSDF(*out, *sdf, range, sdThreshold);
        } else if (outputType == 2) {
            Bitmap<float, 3>* out = static_cast<Bitmap<float, 3>*>(outBmp->data);
            renderSDF(*out, *sdf, range, sdThreshold);
        } else {
            return MSDF_ERR_INVALID_ARG;
        }
    } else if (sdfType == 3) {
        Bitmap<float, 4>* sdf = static_cast<Bitmap<float, 4>*>(sdfBmp->data);
        if (outputType == 0 || outputType == 1) {
            Bitmap<float, 1>* out = static_cast<Bitmap<float, 1>*>(outBmp->data);
            renderSDF(*out, *sdf, range, sdThreshold);
        } else if (outputType == 3) {
            Bitmap<float, 4>* out = static_cast<Bitmap<float, 4>*>(outBmp->data);
            renderSDF(*out, *sdf, range, sdThreshold);
        } else {
            return MSDF_ERR_INVALID_ARG;
        }
    } else {
        return MSDF_ERR_INVALID_ARG;
    }
    return MSDF_SUCCESS;
}

// ==================== Bitmap Pixel Pointer ====================

JNIEXPORT jlong JNICALL Java_com_msdfgen_MsdfNative_nBitmapGetPixelPointer(
    JNIEnv*, jclass, jlong bitmapHandle, jint type) {
    JniBitmapWrapper* wrapper = reinterpret_cast<JniBitmapWrapper*>(bitmapHandle);
    if (!wrapper) return 0;
    switch (type) {
        case 0: case 1: return reinterpret_cast<jlong>(static_cast<const float*>(*static_cast<Bitmap<float, 1>*>(wrapper->data)));
        case 2: return reinterpret_cast<jlong>(static_cast<const float*>(*static_cast<Bitmap<float, 3>*>(wrapper->data)));
        case 3: return reinterpret_cast<jlong>(static_cast<const float*>(*static_cast<Bitmap<float, 4>*>(wrapper->data)));
        default: return 0;
    }
}

// ==================== FreeType Extension ====================

#ifdef MSDFGEN_USE_FREETYPE
#include "ext/import-font.h"
#endif

JNIEXPORT jboolean JNICALL Java_com_msdfgen_MsdfNative_nHasFreetypeSupport(JNIEnv*, jclass) {
#ifdef MSDFGEN_USE_FREETYPE
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nFreetypeInit(JNIEnv* env, jclass, jlongArray handleOut) {
#ifdef MSDFGEN_USE_FREETYPE
    msdfgen::FreetypeHandle* ft = msdfgen::initializeFreetype();
    if (!ft) {
        return MSDF_ERR_FAILED;
    }
    jlong handle = reinterpret_cast<jlong>(ft);
    env->SetLongArrayRegion(handleOut, 0, 1, &handle);
    return MSDF_SUCCESS;
#else
    (void)env; (void)handleOut;
    return MSDF_ERR_FAILED;
#endif
}

JNIEXPORT void JNICALL Java_com_msdfgen_MsdfNative_nFreetypeDeinit(JNIEnv*, jclass, jlong ftHandle) {
#ifdef MSDFGEN_USE_FREETYPE
    if (ftHandle != 0) {
        msdfgen::deinitializeFreetype(reinterpret_cast<msdfgen::FreetypeHandle*>(ftHandle));
    }
#else
    (void)ftHandle;
#endif
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nLoadFont(
    JNIEnv* env, jclass, jlong ftHandle, jstring filename, jlongArray fontHandleOut) {
#ifdef MSDFGEN_USE_FREETYPE
    if (ftHandle == 0 || filename == nullptr) return MSDF_ERR_INVALID_ARG;
    const char* path = env->GetStringUTFChars(filename, nullptr);
    if (!path) return MSDF_ERR_FAILED;
    msdfgen::FontHandle* font = msdfgen::loadFont(
        reinterpret_cast<msdfgen::FreetypeHandle*>(ftHandle), path);
    env->ReleaseStringUTFChars(filename, path);
    if (!font) return MSDF_ERR_FAILED;
    jlong handle = reinterpret_cast<jlong>(font);
    env->SetLongArrayRegion(fontHandleOut, 0, 1, &handle);
    return MSDF_SUCCESS;
#else
    (void)env; (void)ftHandle; (void)filename; (void)fontHandleOut;
    return MSDF_ERR_FAILED;
#endif
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nLoadFontData(
    JNIEnv* env, jclass, jlong ftHandle, jbyteArray data, jint dataLen, jlongArray fontHandleOut) {
#ifdef MSDFGEN_USE_FREETYPE
    if (ftHandle == 0 || data == nullptr) return MSDF_ERR_INVALID_ARG;
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) return MSDF_ERR_FAILED;
    msdfgen::FontHandle* font = msdfgen::loadFontData(
        reinterpret_cast<msdfgen::FreetypeHandle*>(ftHandle),
        reinterpret_cast<const msdfgen::byte*>(bytes), dataLen);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    if (!font) return MSDF_ERR_FAILED;
    jlong handle = reinterpret_cast<jlong>(font);
    env->SetLongArrayRegion(fontHandleOut, 0, 1, &handle);
    return MSDF_SUCCESS;
#else
    (void)env; (void)ftHandle; (void)data; (void)dataLen; (void)fontHandleOut;
    return MSDF_ERR_FAILED;
#endif
}

JNIEXPORT void JNICALL Java_com_msdfgen_MsdfNative_nDestroyFont(JNIEnv*, jclass, jlong fontHandle) {
#ifdef MSDFGEN_USE_FREETYPE
    if (fontHandle != 0) {
        msdfgen::destroyFont(reinterpret_cast<msdfgen::FontHandle*>(fontHandle));
    }
#else
    (void)fontHandle;
#endif
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nLoadGlyph(
    JNIEnv* env, jclass, jlong fontHandle, jint unicode, jint coordinateScaling,
    jdoubleArray advanceOut, jlongArray shapeHandleOut) {
#ifdef MSDFGEN_USE_FREETYPE
    if (fontHandle == 0) return MSDF_ERR_INVALID_ARG;
    Shape* shape = new Shape();
    double advance = 0.0;
    bool ok = msdfgen::loadGlyph(*shape, reinterpret_cast<msdfgen::FontHandle*>(fontHandle),
        static_cast<unsigned>(unicode),
        static_cast<msdfgen::FontCoordinateScaling>(coordinateScaling),
        &advance);
    if (!ok) {
        delete shape;
        return MSDF_ERR_FAILED;
    }
    jlong handle = reinterpret_cast<jlong>(shape);
    env->SetLongArrayRegion(shapeHandleOut, 0, 1, &handle);
    if (advanceOut != nullptr) {
        env->SetDoubleArrayRegion(advanceOut, 0, 1, &advance);
    }
    return MSDF_SUCCESS;
#else
    (void)env; (void)fontHandle; (void)unicode; (void)coordinateScaling;
    (void)advanceOut; (void)shapeHandleOut;
    return MSDF_ERR_FAILED;
#endif
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nLoadGlyphByIndex(
    JNIEnv* env, jclass, jlong fontHandle, jint glyphIndex, jint coordinateScaling,
    jdoubleArray advanceOut, jlongArray shapeHandleOut) {
#ifdef MSDFGEN_USE_FREETYPE
    if (fontHandle == 0) return MSDF_ERR_INVALID_ARG;
    Shape* shape = new Shape();
    double advance = 0.0;
    bool ok = msdfgen::loadGlyph(*shape, reinterpret_cast<msdfgen::FontHandle*>(fontHandle),
        msdfgen::GlyphIndex(static_cast<unsigned>(glyphIndex)),
        static_cast<msdfgen::FontCoordinateScaling>(coordinateScaling),
        &advance);
    if (!ok) {
        delete shape;
        return MSDF_ERR_FAILED;
    }
    jlong handle = reinterpret_cast<jlong>(shape);
    env->SetLongArrayRegion(shapeHandleOut, 0, 1, &handle);
    if (advanceOut != nullptr) {
        env->SetDoubleArrayRegion(advanceOut, 0, 1, &advance);
    }
    return MSDF_SUCCESS;
#else
    (void)env; (void)fontHandle; (void)glyphIndex; (void)coordinateScaling;
    (void)advanceOut; (void)shapeHandleOut;
    return MSDF_ERR_FAILED;
#endif
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nGetGlyphIndex(
    JNIEnv* env, jclass, jlong fontHandle, jint unicode, jintArray indexOut) {
#ifdef MSDFGEN_USE_FREETYPE
    if (fontHandle == 0) return MSDF_ERR_INVALID_ARG;
    msdfgen::GlyphIndex glyphIndex{};
    bool ok = msdfgen::getGlyphIndex(glyphIndex,
        reinterpret_cast<msdfgen::FontHandle*>(fontHandle),
        static_cast<unsigned>(unicode));
    if (!ok) return MSDF_ERR_FAILED;
    jint idx = static_cast<jint>(glyphIndex.getIndex());
    env->SetIntArrayRegion(indexOut, 0, 1, &idx);
    return MSDF_SUCCESS;
#else
    (void)env; (void)fontHandle; (void)unicode; (void)indexOut;
    return MSDF_ERR_FAILED;
#endif
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nGetKerning(
    JNIEnv* env, jclass, jlong fontHandle, jint cp1, jint cp2, jdoubleArray kerningOut) {
#ifdef MSDFGEN_USE_FREETYPE
    if (fontHandle == 0) return MSDF_ERR_INVALID_ARG;
    double kerning = 0.0;
    bool ok = msdfgen::getKerning(kerning,
        reinterpret_cast<msdfgen::FontHandle*>(fontHandle),
        static_cast<unsigned>(cp1), static_cast<unsigned>(cp2));
    if (!ok) return MSDF_ERR_FAILED;
    env->SetDoubleArrayRegion(kerningOut, 0, 1, &kerning);
    return MSDF_SUCCESS;
#else
    (void)env; (void)fontHandle; (void)cp1; (void)cp2; (void)kerningOut;
    return MSDF_ERR_FAILED;
#endif
}

JNIEXPORT jint JNICALL Java_com_msdfgen_MsdfNative_nGetKerningByIndex(
    JNIEnv* env, jclass, jlong fontHandle, jint index1, jint index2, jdoubleArray kerningOut) {
#ifdef MSDFGEN_USE_FREETYPE
    if (fontHandle == 0) return MSDF_ERR_INVALID_ARG;
    double kerning = 0.0;
    bool ok = msdfgen::getKerning(kerning,
        reinterpret_cast<msdfgen::FontHandle*>(fontHandle),
        msdfgen::GlyphIndex(static_cast<unsigned>(index1)),
        msdfgen::GlyphIndex(static_cast<unsigned>(index2)));
    if (!ok) return MSDF_ERR_FAILED;
    env->SetDoubleArrayRegion(kerningOut, 0, 1, &kerning);
    return MSDF_SUCCESS;
#else
    (void)env; (void)fontHandle; (void)index1; (void)index2; (void)kerningOut;
    return MSDF_ERR_FAILED;
#endif
}
