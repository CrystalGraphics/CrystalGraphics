#include "headers.h"
#include <cstring>

extern "C" {

JNIEXPORT jlong JNICALL Java_com_crystalgraphics_harfbuzz_HBBuffer_nCreate
  (JNIEnv *env, jclass) {
    hb_buffer_t *buf = hb_buffer_create();
    if (!hb_buffer_allocation_successful(buf)) {
        hb_buffer_destroy(buf);
        return 0;
    }
    return (jlong)(intptr_t)buf;
}

JNIEXPORT void JNICALL Java_com_crystalgraphics_harfbuzz_HBBuffer_nDestroy
  (JNIEnv *env, jclass, jlong bufferPtr) {
    hb_buffer_destroy((hb_buffer_t *)(intptr_t)bufferPtr);
}

JNIEXPORT void JNICALL Java_com_crystalgraphics_harfbuzz_HBBuffer_nAddUTF8
  (JNIEnv *env, jclass, jlong bufferPtr, jbyteArray jUtf8, jint textLen, jint itemOffset, jint itemLength) {
    hb_buffer_t *buf = (hb_buffer_t *)(intptr_t)bufferPtr;
    jbyte *utf8 = env->GetByteArrayElements(jUtf8, NULL);
    hb_buffer_add_utf8(buf, (const char *)utf8, textLen, itemOffset, itemLength);
    env->ReleaseByteArrayElements(jUtf8, utf8, JNI_ABORT);
}

JNIEXPORT void JNICALL Java_com_crystalgraphics_harfbuzz_HBBuffer_nAddCodepoints
  (JNIEnv *env, jclass, jlong bufferPtr, jintArray jCodepoints, jint len, jint itemOffset, jint itemLength) {
    hb_buffer_t *buf = (hb_buffer_t *)(intptr_t)bufferPtr;
    jint *codepoints = env->GetIntArrayElements(jCodepoints, NULL);
    hb_buffer_add_codepoints(buf, (const hb_codepoint_t *)codepoints, len, itemOffset, itemLength);
    env->ReleaseIntArrayElements(jCodepoints, codepoints, JNI_ABORT);
}

JNIEXPORT void JNICALL Java_com_crystalgraphics_harfbuzz_HBBuffer_nSetDirection
  (JNIEnv *env, jclass, jlong bufferPtr, jint direction) {
    hb_buffer_set_direction((hb_buffer_t *)(intptr_t)bufferPtr, (hb_direction_t)direction);
}

JNIEXPORT jint JNICALL Java_com_crystalgraphics_harfbuzz_HBBuffer_nGetDirection
  (JNIEnv *env, jclass, jlong bufferPtr) {
    return (jint)hb_buffer_get_direction((hb_buffer_t *)(intptr_t)bufferPtr);
}

JNIEXPORT void JNICALL Java_com_crystalgraphics_harfbuzz_HBBuffer_nSetScript
  (JNIEnv *env, jclass, jlong bufferPtr, jint script) {
    hb_buffer_set_script((hb_buffer_t *)(intptr_t)bufferPtr, (hb_script_t)script);
}

JNIEXPORT jint JNICALL Java_com_crystalgraphics_harfbuzz_HBBuffer_nGetScript
  (JNIEnv *env, jclass, jlong bufferPtr) {
    return (jint)hb_buffer_get_script((hb_buffer_t *)(intptr_t)bufferPtr);
}

JNIEXPORT void JNICALL Java_com_crystalgraphics_harfbuzz_HBBuffer_nSetLanguage
  (JNIEnv *env, jclass, jlong bufferPtr, jstring jLanguage) {
    hb_buffer_t *buf = (hb_buffer_t *)(intptr_t)bufferPtr;
    const char *lang = env->GetStringUTFChars(jLanguage, NULL);
    hb_buffer_set_language(buf, hb_language_from_string(lang, -1));
    env->ReleaseStringUTFChars(jLanguage, lang);
}

JNIEXPORT void JNICALL Java_com_crystalgraphics_harfbuzz_HBBuffer_nGuessSegmentProperties
  (JNIEnv *env, jclass, jlong bufferPtr) {
    hb_buffer_guess_segment_properties((hb_buffer_t *)(intptr_t)bufferPtr);
}

JNIEXPORT jint JNICALL Java_com_crystalgraphics_harfbuzz_HBBuffer_nGetLength
  (JNIEnv *env, jclass, jlong bufferPtr) {
    return (jint)hb_buffer_get_length((hb_buffer_t *)(intptr_t)bufferPtr);
}

JNIEXPORT jobjectArray JNICALL Java_com_crystalgraphics_harfbuzz_HBBuffer_nGetGlyphInfos
  (JNIEnv *env, jclass, jlong bufferPtr) {
    hb_buffer_t *buf = (hb_buffer_t *)(intptr_t)bufferPtr;
    unsigned int count;
    hb_glyph_info_t *infos = hb_buffer_get_glyph_infos(buf, &count);

    jclass cls = env->FindClass("com/crystalgraphics/harfbuzz/HBGlyphInfo");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(II)V");
    jobjectArray result = env->NewObjectArray(count, cls, NULL);

    for (unsigned int i = 0; i < count; i++) {
        jobject info = env->NewObject(cls, ctor,
            (jint)infos[i].codepoint, (jint)infos[i].cluster);
        env->SetObjectArrayElement(result, i, info);
        env->DeleteLocalRef(info);
    }

    env->DeleteLocalRef(cls);
    return result;
}

JNIEXPORT jobjectArray JNICALL Java_com_crystalgraphics_harfbuzz_HBBuffer_nGetGlyphPositions
  (JNIEnv *env, jclass, jlong bufferPtr) {
    hb_buffer_t *buf = (hb_buffer_t *)(intptr_t)bufferPtr;
    unsigned int count;
    hb_glyph_position_t *positions = hb_buffer_get_glyph_positions(buf, &count);

    jclass cls = env->FindClass("com/crystalgraphics/harfbuzz/HBGlyphPosition");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(IIII)V");
    jobjectArray result = env->NewObjectArray(count, cls, NULL);

    for (unsigned int i = 0; i < count; i++) {
        jobject pos = env->NewObject(cls, ctor,
            (jint)positions[i].x_advance, (jint)positions[i].y_advance,
            (jint)positions[i].x_offset, (jint)positions[i].y_offset);
        env->SetObjectArrayElement(result, i, pos);
        env->DeleteLocalRef(pos);
    }

    env->DeleteLocalRef(cls);
    return result;
}

JNIEXPORT void JNICALL Java_com_crystalgraphics_harfbuzz_HBBuffer_nReset
  (JNIEnv *env, jclass, jlong bufferPtr) {
    hb_buffer_reset((hb_buffer_t *)(intptr_t)bufferPtr);
}

JNIEXPORT void JNICALL Java_com_crystalgraphics_harfbuzz_HBBuffer_nClearContents
  (JNIEnv *env, jclass, jlong bufferPtr) {
    hb_buffer_clear_contents((hb_buffer_t *)(intptr_t)bufferPtr);
}

JNIEXPORT jlong JNICALL Java_com_crystalgraphics_harfbuzz_HBFont_nCreateFromFile
  (JNIEnv *env, jclass, jstring jFilePath, jint faceIndex) {
    const char *filePath = env->GetStringUTFChars(jFilePath, NULL);
    hb_blob_t *blob = hb_blob_create_from_file(filePath);
    env->ReleaseStringUTFChars(jFilePath, filePath);

    if (hb_blob_get_length(blob) == 0) {
        hb_blob_destroy(blob);
        return 0;
    }

    hb_face_t *hbFace = hb_face_create(blob, faceIndex);
    hb_blob_destroy(blob);
    hb_font_t *font = hb_font_create(hbFace);
    hb_face_destroy(hbFace);
    hb_font_set_scale(font, 64 * 16, 64 * 16);
    return (jlong)(intptr_t)font;
}

JNIEXPORT jlong JNICALL Java_com_crystalgraphics_harfbuzz_HBFont_nCreateFromMemory
  (JNIEnv *env, jclass, jbyteArray jData, jint dataLen, jint faceIndex) {
    jbyte *data = env->GetByteArrayElements(jData, NULL);

    // HarfBuzz takes ownership of the data copy via hb_blob_create, so we
    // need to copy the Java byte array into a persistent buffer.
    char *persistentData = (char *)malloc(dataLen);
    memcpy(persistentData, data, dataLen);
    env->ReleaseByteArrayElements(jData, data, JNI_ABORT);

    hb_blob_t *blob = hb_blob_create(persistentData, dataLen,
                                     HB_MEMORY_MODE_WRITABLE, persistentData, free);

    if (hb_blob_get_length(blob) == 0) {
        hb_blob_destroy(blob);
        return 0;
    }

    hb_face_t *hbFace = hb_face_create(blob, faceIndex);
    hb_blob_destroy(blob);
    hb_font_t *font = hb_font_create(hbFace);
    hb_face_destroy(hbFace);
    hb_font_set_scale(font, 64 * 16, 64 * 16);
    return (jlong)(intptr_t)font;
}

JNIEXPORT void JNICALL Java_com_crystalgraphics_harfbuzz_HBFont_nDestroy
  (JNIEnv *env, jclass, jlong fontPtr) {
    hb_font_destroy((hb_font_t *)(intptr_t)fontPtr);
}

JNIEXPORT void JNICALL Java_com_crystalgraphics_harfbuzz_HBFont_nSetScale
  (JNIEnv *env, jclass, jlong fontPtr, jint xScale, jint yScale) {
    hb_font_set_scale((hb_font_t *)(intptr_t)fontPtr, xScale, yScale);
}

JNIEXPORT jintArray JNICALL Java_com_crystalgraphics_harfbuzz_HBFont_nGetScale
  (JNIEnv *env, jclass, jlong fontPtr) {
    int xScale, yScale;
    hb_font_get_scale((hb_font_t *)(intptr_t)fontPtr, &xScale, &yScale);
    jintArray result = env->NewIntArray(2);
    jint values[2] = { xScale, yScale };
    env->SetIntArrayRegion(result, 0, 2, values);
    return result;
}

JNIEXPORT void JNICALL Java_com_crystalgraphics_harfbuzz_HBFont_nSetPpem
  (JNIEnv *env, jclass, jlong fontPtr, jint xPpem, jint yPpem) {
    hb_font_set_ppem((hb_font_t *)(intptr_t)fontPtr, xPpem, yPpem);
}

JNIEXPORT jintArray JNICALL Java_com_crystalgraphics_harfbuzz_HBFont_nGetPpem
  (JNIEnv *env, jclass, jlong fontPtr) {
    unsigned int xPpem, yPpem;
    hb_font_get_ppem((hb_font_t *)(intptr_t)fontPtr, &xPpem, &yPpem);
    jintArray result = env->NewIntArray(2);
    jint values[2] = { (jint)xPpem, (jint)yPpem };
    env->SetIntArrayRegion(result, 0, 2, values);
    return result;
}

JNIEXPORT void JNICALL Java_com_crystalgraphics_harfbuzz_HBShape_nShape
  (JNIEnv *env, jclass, jlong fontPtr, jlong bufferPtr, jobjectArray jFeatures) {
    hb_font_t *font = (hb_font_t *)(intptr_t)fontPtr;
    hb_buffer_t *buffer = (hb_buffer_t *)(intptr_t)bufferPtr;

    hb_feature_t *features = NULL;
    unsigned int numFeatures = 0;

    if (jFeatures != NULL) {
        numFeatures = env->GetArrayLength(jFeatures);
        if (numFeatures > 0) {
            features = (hb_feature_t *)malloc(numFeatures * sizeof(hb_feature_t));
            unsigned int validCount = 0;
            for (unsigned int i = 0; i < numFeatures; i++) {
                jstring jFeature = (jstring)env->GetObjectArrayElement(jFeatures, i);
                const char *featureStr = env->GetStringUTFChars(jFeature, NULL);
                if (hb_feature_from_string(featureStr, -1, &features[validCount])) {
                    validCount++;
                }
                env->ReleaseStringUTFChars(jFeature, featureStr);
                env->DeleteLocalRef(jFeature);
            }
            numFeatures = validCount;
        }
    }

    hb_shape(font, buffer, features, numFeatures);

    if (features != NULL) {
        free(features);
    }
}

} // extern "C"
