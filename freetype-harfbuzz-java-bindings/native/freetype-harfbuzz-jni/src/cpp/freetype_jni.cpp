#include "headers.h"
#include <cstring>
#include <cstdlib>
#include <map>
#include <mutex>
#include <string>
#include <vector>
#include FT_MULTIPLE_MASTERS_H

// Tracks memory buffers allocated for FT_New_Memory_Face.
// FreeType requires the buffer to remain valid for the face's lifetime,
// but FT_Done_Face does NOT free the buffer — we must do it ourselves.
static std::map<FT_Face, FT_Byte*> memoryFaceBuffers;
static std::mutex memoryFaceBuffersMutex;

// All JNI functions must use C linkage to prevent C++ name mangling.
// Without this, the JVM cannot find the exported symbols at runtime
// (UnsatisfiedLinkError on native method calls).
extern "C" {

static std::string ftTagToString(FT_ULong tag) {
    char chars[5];
    chars[0] = (char)((tag >> 24) & 0xFF);
    chars[1] = (char)((tag >> 16) & 0xFF);
    chars[2] = (char)((tag >> 8) & 0xFF);
    chars[3] = (char)(tag & 0xFF);
    chars[4] = '\0';
    return std::string(chars, 4);
}

void throwException(JNIEnv *env, const char *className, const char *message) {
    jclass cls = env->FindClass(className);
    if (cls != NULL) {
        env->ThrowNew(cls, message);
        env->DeleteLocalRef(cls);
    }
}

void throwFreeTypeException(JNIEnv *env, int errorCode, const char *message) {
    jclass cls = env->FindClass("com/crystalgraphics/freetype/FreeTypeException");
    if (cls != NULL) {
        jmethodID ctor = env->GetMethodID(cls, "<init>", "(ILjava/lang/String;)V");
        if (ctor != NULL) {
            jstring msg = env->NewStringUTF(message);
            jobject ex = env->NewObject(cls, ctor, errorCode, msg);
            env->Throw((jthrowable)ex);
            env->DeleteLocalRef(msg);
            env->DeleteLocalRef(ex);
        }
        env->DeleteLocalRef(cls);
    }
}

JNIEXPORT jlong JNICALL Java_com_crystalgraphics_freetype_FreeTypeLibrary_nInitFreeType
  (JNIEnv *env, jclass) {
    FT_Library library;
    FT_Error err = FT_Init_FreeType(&library);
    if (err) {
        throwFreeTypeException(env, err, "FT_Init_FreeType failed");
        return 0;
    }
    return (jlong)(intptr_t)library;
}

JNIEXPORT void JNICALL Java_com_crystalgraphics_freetype_FreeTypeLibrary_nDoneFreeType
  (JNIEnv *env, jclass, jlong libraryPtr) {
    FT_Library library = (FT_Library)(intptr_t)libraryPtr;
    FT_Done_FreeType(library);
}

JNIEXPORT jlong JNICALL Java_com_crystalgraphics_freetype_FreeTypeLibrary_nNewFace
  (JNIEnv *env, jclass, jlong libraryPtr, jstring jFilePath, jint faceIndex) {
    FT_Library library = (FT_Library)(intptr_t)libraryPtr;
    const char *filePath = env->GetStringUTFChars(jFilePath, NULL);
    FT_Face face;
    FT_Error err = FT_New_Face(library, filePath, faceIndex, &face);
    env->ReleaseStringUTFChars(jFilePath, filePath);
    if (err) {
        throwFreeTypeException(env, err, "FT_New_Face failed");
        return 0;
    }
    return (jlong)(intptr_t)face;
}

JNIEXPORT jlong JNICALL Java_com_crystalgraphics_freetype_FreeTypeLibrary_nNewFaceFromMemory
  (JNIEnv *env, jclass, jlong libraryPtr, jbyteArray jData, jint dataLen, jint faceIndex) {
    FT_Library library = (FT_Library)(intptr_t)libraryPtr;
    jbyte *data = env->GetByteArrayElements(jData, NULL);

    FT_Byte *persistentData = (FT_Byte *)malloc(dataLen);
    memcpy(persistentData, data, dataLen);
    env->ReleaseByteArrayElements(jData, data, JNI_ABORT);

    FT_Face face;
    FT_Error err = FT_New_Memory_Face(library, persistentData, dataLen, faceIndex, &face);
    if (err) {
        free(persistentData);
        throwFreeTypeException(env, err, "FT_New_Memory_Face failed");
        return 0;
    }

    {
        std::lock_guard<std::mutex> lock(memoryFaceBuffersMutex);
        memoryFaceBuffers[face] = persistentData;
    }

    return (jlong)(intptr_t)face;
}

JNIEXPORT jintArray JNICALL Java_com_crystalgraphics_freetype_FreeTypeLibrary_nGetVersion
  (JNIEnv *env, jclass, jlong libraryPtr) {
    FT_Library library = (FT_Library)(intptr_t)libraryPtr;
    FT_Int major, minor, patch;
    FT_Library_Version(library, &major, &minor, &patch);

    jintArray result = env->NewIntArray(3);
    jint values[3] = { major, minor, patch };
    env->SetIntArrayRegion(result, 0, 3, values);
    return result;
}

JNIEXPORT jint JNICALL Java_com_crystalgraphics_freetype_FTFace_nSetPixelSizes
  (JNIEnv *env, jclass, jlong facePtr, jint width, jint height) {
    FT_Face face = (FT_Face)(intptr_t)facePtr;
    return FT_Set_Pixel_Sizes(face, width, height);
}

JNIEXPORT jint JNICALL Java_com_crystalgraphics_freetype_FTFace_nSetCharSize
  (JNIEnv *env, jclass, jlong facePtr, jint charWidth, jint charHeight, jint horzRes, jint vertRes) {
    FT_Face face = (FT_Face)(intptr_t)facePtr;
    return FT_Set_Char_Size(face, charWidth, charHeight, horzRes, vertRes);
}

JNIEXPORT jint JNICALL Java_com_crystalgraphics_freetype_FTFace_nGetCharIndex
  (JNIEnv *env, jclass, jlong facePtr, jint charCode) {
    FT_Face face = (FT_Face)(intptr_t)facePtr;
    return FT_Get_Char_Index(face, (FT_ULong)charCode);
}

JNIEXPORT jint JNICALL Java_com_crystalgraphics_freetype_FTFace_nLoadGlyph
  (JNIEnv *env, jclass, jlong facePtr, jint glyphIndex, jint loadFlags) {
    FT_Face face = (FT_Face)(intptr_t)facePtr;
    return FT_Load_Glyph(face, glyphIndex, loadFlags);
}

JNIEXPORT jint JNICALL Java_com_crystalgraphics_freetype_FTFace_nLoadChar
  (JNIEnv *env, jclass, jlong facePtr, jint charCode, jint loadFlags) {
    FT_Face face = (FT_Face)(intptr_t)facePtr;
    return FT_Load_Char(face, (FT_ULong)charCode, loadFlags);
}

JNIEXPORT jint JNICALL Java_com_crystalgraphics_freetype_FTFace_nRenderGlyph
  (JNIEnv *env, jclass, jlong facePtr, jint renderMode) {
    FT_Face face = (FT_Face)(intptr_t)facePtr;
    return FT_Render_Glyph(face->glyph, (FT_Render_Mode)renderMode);
}

JNIEXPORT jintArray JNICALL Java_com_crystalgraphics_freetype_FTFace_nGetGlyphMetrics
  (JNIEnv *env, jclass, jlong facePtr) {
    FT_Face face = (FT_Face)(intptr_t)facePtr;
    FT_GlyphSlot slot = face->glyph;
    FT_Glyph_Metrics *m = &slot->metrics;

    jintArray result = env->NewIntArray(8);
    jint values[8] = {
        (jint)m->width, (jint)m->height,
        (jint)m->horiBearingX, (jint)m->horiBearingY, (jint)m->horiAdvance,
        (jint)m->vertBearingX, (jint)m->vertBearingY, (jint)m->vertAdvance
    };
    env->SetIntArrayRegion(result, 0, 8, values);
    return result;
}

JNIEXPORT jobject JNICALL Java_com_crystalgraphics_freetype_FTFace_nGetGlyphBitmap
  (JNIEnv *env, jclass, jlong facePtr) {
    FT_Face face = (FT_Face)(intptr_t)facePtr;
    FT_GlyphSlot slot = face->glyph;
    FT_Bitmap *bitmap = &slot->bitmap;

    int bufferSize = abs(bitmap->pitch) * bitmap->rows;
    jbyteArray jBuffer = env->NewByteArray(bufferSize);
    if (bufferSize > 0 && bitmap->buffer != NULL) {
        env->SetByteArrayRegion(jBuffer, 0, bufferSize, (jbyte *)bitmap->buffer);
    }

    jclass cls = env->FindClass("com/crystalgraphics/freetype/FTBitmap");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(IIII[BII)V");
    jobject result = env->NewObject(cls, ctor,
        (jint)bitmap->width, (jint)bitmap->rows,
        (jint)bitmap->pitch, (jint)bitmap->pixel_mode,
        jBuffer,
        (jint)slot->bitmap_left, (jint)slot->bitmap_top);

    env->DeleteLocalRef(cls);
    env->DeleteLocalRef(jBuffer);
    return result;
}

JNIEXPORT jstring JNICALL Java_com_crystalgraphics_freetype_FTFace_nGetFamilyName
  (JNIEnv *env, jclass, jlong facePtr) {
    FT_Face face = (FT_Face)(intptr_t)facePtr;
    return face->family_name ? env->NewStringUTF(face->family_name) : NULL;
}

JNIEXPORT jstring JNICALL Java_com_crystalgraphics_freetype_FTFace_nGetStyleName
  (JNIEnv *env, jclass, jlong facePtr) {
    FT_Face face = (FT_Face)(intptr_t)facePtr;
    return face->style_name ? env->NewStringUTF(face->style_name) : NULL;
}

JNIEXPORT jint JNICALL Java_com_crystalgraphics_freetype_FTFace_nGetNumGlyphs
  (JNIEnv *env, jclass, jlong facePtr) {
    FT_Face face = (FT_Face)(intptr_t)facePtr;
    return (jint)face->num_glyphs;
}

JNIEXPORT jint JNICALL Java_com_crystalgraphics_freetype_FTFace_nGetNumFaces
  (JNIEnv *env, jclass, jlong facePtr) {
    FT_Face face = (FT_Face)(intptr_t)facePtr;
    return (jint)face->num_faces;
}

JNIEXPORT jint JNICALL Java_com_crystalgraphics_freetype_FTFace_nGetUnitsPerEM
  (JNIEnv *env, jclass, jlong facePtr) {
    FT_Face face = (FT_Face)(intptr_t)facePtr;
    return (jint)face->units_per_EM;
}

JNIEXPORT jint JNICALL Java_com_crystalgraphics_freetype_FTFace_nGetAscender
  (JNIEnv *env, jclass, jlong facePtr) {
    FT_Face face = (FT_Face)(intptr_t)facePtr;
    return (jint)face->ascender;
}

JNIEXPORT jint JNICALL Java_com_crystalgraphics_freetype_FTFace_nGetDescender
  (JNIEnv *env, jclass, jlong facePtr) {
    FT_Face face = (FT_Face)(intptr_t)facePtr;
    return (jint)face->descender;
}

JNIEXPORT jint JNICALL Java_com_crystalgraphics_freetype_FTFace_nGetHeight
  (JNIEnv *env, jclass, jlong facePtr) {
    FT_Face face = (FT_Face)(intptr_t)facePtr;
    return (jint)face->height;
}

JNIEXPORT jintArray JNICALL Java_com_crystalgraphics_freetype_FTFace_nGetKerning
  (JNIEnv *env, jclass, jlong facePtr, jint leftGlyph, jint rightGlyph, jint kernMode) {
    FT_Face face = (FT_Face)(intptr_t)facePtr;
    FT_Vector kerning;
    FT_Error err = FT_Get_Kerning(face, leftGlyph, rightGlyph, kernMode, &kerning);

    jintArray result = env->NewIntArray(2);
    jint values[2] = { err ? 0 : (jint)kerning.x, err ? 0 : (jint)kerning.y };
    env->SetIntArrayRegion(result, 0, 2, values);
    return result;
}

JNIEXPORT jobjectArray JNICALL Java_com_crystalgraphics_freetype_FTFace_nGetVariationAxes
  (JNIEnv *env, jclass, jlong facePtr) {
    FT_Face face = (FT_Face)(intptr_t)facePtr;
    jclass axisClass = env->FindClass("com/crystalgraphics/freetype/FTVariationAxisInfo");
    if (axisClass == NULL) {
        return NULL;
    }
    jmethodID ctor = env->GetMethodID(axisClass, "<init>", "(Ljava/lang/String;Ljava/lang/String;FFF)V");
    if (ctor == NULL) {
        env->DeleteLocalRef(axisClass);
        return NULL;
    }

    if (!(face->face_flags & FT_FACE_FLAG_MULTIPLE_MASTERS)) {
        jobjectArray empty = env->NewObjectArray(0, axisClass, NULL);
        env->DeleteLocalRef(axisClass);
        return empty;
    }

    FT_MM_Var *master = NULL;
    FT_Error err = FT_Get_MM_Var(face, &master);
    if (err || master == NULL) {
        jobjectArray empty = env->NewObjectArray(0, axisClass, NULL);
        env->DeleteLocalRef(axisClass);
        return empty;
    }

    jobjectArray result = env->NewObjectArray((jsize)master->num_axis, axisClass, NULL);
    for (FT_UInt i = 0; i < master->num_axis; i++) {
        std::string tagString = ftTagToString(master->axis[i].tag);
        jstring jTag = env->NewStringUTF(tagString.c_str());
        jstring jName = env->NewStringUTF(tagString.c_str());
        jobject axis = env->NewObject(axisClass, ctor,
                jTag,
                jName,
                (jfloat)(master->axis[i].minimum / 65536.0f),
                (jfloat)(master->axis[i].def / 65536.0f),
                (jfloat)(master->axis[i].maximum / 65536.0f));
        env->SetObjectArrayElement(result, (jsize)i, axis);
        env->DeleteLocalRef(axis);
        env->DeleteLocalRef(jTag);
        env->DeleteLocalRef(jName);
    }

    FT_Done_MM_Var(face->glyph->library, master);
    env->DeleteLocalRef(axisClass);
    return result;
}

JNIEXPORT void JNICALL Java_com_crystalgraphics_freetype_FTFace_nSetVariationCoordinates
  (JNIEnv *env, jclass, jlong facePtr, jobjectArray jAxisTags, jfloatArray jValues) {
    FT_Face face = (FT_Face)(intptr_t)facePtr;
    if (!(face->face_flags & FT_FACE_FLAG_MULTIPLE_MASTERS)) {
        throwException(env, "java/lang/IllegalStateException", "Face has no variable-font axes");
        return;
    }

    jsize tagCount = env->GetArrayLength(jAxisTags);
    jsize valueCount = env->GetArrayLength(jValues);
    if (tagCount != valueCount) {
        throwException(env, "java/lang/IllegalArgumentException", "axis tag count must match value count");
        return;
    }
    if (tagCount == 0) {
        return;
    }

    FT_MM_Var *master = NULL;
    FT_Error err = FT_Get_MM_Var(face, &master);
    if (err || master == NULL) {
        throwFreeTypeException(env, err, "FT_Get_MM_Var failed");
        return;
    }

    std::vector<FT_Fixed> coords(master->num_axis);
    for (FT_UInt i = 0; i < master->num_axis; i++) {
        coords[i] = master->axis[i].def;
    }
    FT_Get_Var_Design_Coordinates(face, (FT_UInt)coords.size(), &coords[0]);

    std::vector<jfloat> values((size_t)valueCount);
    env->GetFloatArrayRegion(jValues, 0, valueCount, &values[0]);

    for (jsize i = 0; i < tagCount; i++) {
        jstring jTag = (jstring) env->GetObjectArrayElement(jAxisTags, i);
        if (jTag == NULL) {
            FT_Done_MM_Var(face->glyph->library, master);
            throwException(env, "java/lang/IllegalArgumentException", "axis tag must not be null");
            return;
        }
        const char *tagChars = env->GetStringUTFChars(jTag, NULL);
        if (tagChars == NULL) {
            env->DeleteLocalRef(jTag);
            FT_Done_MM_Var(face->glyph->library, master);
            return;
        }
        bool matched = false;
        for (FT_UInt axisIndex = 0; axisIndex < master->num_axis; axisIndex++) {
            std::string axisTag = ftTagToString(master->axis[axisIndex].tag);
            if (axisTag == tagChars) {
                coords[axisIndex] = (FT_Fixed)(values[(size_t)i] * 65536.0f);
                matched = true;
                break;
            }
        }
        env->ReleaseStringUTFChars(jTag, tagChars);
        env->DeleteLocalRef(jTag);
        if (!matched) {
            FT_Done_MM_Var(face->glyph->library, master);
            throwException(env, "java/lang/IllegalArgumentException", "Unknown variation axis tag");
            return;
        }
    }

    err = FT_Set_Var_Design_Coordinates(face, (FT_UInt)coords.size(), &coords[0]);
    FT_Done_MM_Var(face->glyph->library, master);
    if (err) {
        throwFreeTypeException(env, err, "FT_Set_Var_Design_Coordinates failed");
    }
}

JNIEXPORT jboolean JNICALL Java_com_crystalgraphics_freetype_FTFace_nHasKerning
  (JNIEnv *env, jclass, jlong facePtr) {
    FT_Face face = (FT_Face)(intptr_t)facePtr;
    return FT_HAS_KERNING(face) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_com_crystalgraphics_freetype_FTFace_nGetAdvance
  (JNIEnv *env, jclass, jlong facePtr, jint glyphIndex, jint loadFlags) {
    FT_Face face = (FT_Face)(intptr_t)facePtr;
    FT_Fixed advance = 0;
    FT_Error err = FT_Get_Advance(face, (FT_UInt)glyphIndex, loadFlags, &advance);
    if (err) {
        return 0;
    }
    return (jint)advance;
}

JNIEXPORT void JNICALL Java_com_crystalgraphics_freetype_FTFace_nOutlineTranslate
  (JNIEnv *env, jclass, jlong facePtr, jlong xOffset, jlong yOffset) {
    FT_Face face = (FT_Face)(intptr_t)facePtr;
    if (!face->glyph) {
        throwException(env, "java/lang/IllegalStateException",
            "No glyph loaded. Call loadGlyph() or loadChar() before outlineTranslate().");
        return;
    }
    if (face->glyph->format != FT_GLYPH_FORMAT_OUTLINE) {
        throwException(env, "java/lang/IllegalStateException",
            "Loaded glyph has no outline (format is not FT_GLYPH_FORMAT_OUTLINE). "
            "Load with FT_LOAD_NO_BITMAP to ensure outline availability.");
        return;
    }
    FT_Outline_Translate(&face->glyph->outline, (FT_Pos)xOffset, (FT_Pos)yOffset);
}

JNIEXPORT void JNICALL Java_com_crystalgraphics_freetype_FTFace_nDoneFace
  (JNIEnv *env, jclass, jlong facePtr) {
    FT_Face face = (FT_Face)(intptr_t)facePtr;

    FT_Byte *bufferToFree = NULL;
    {
        std::lock_guard<std::mutex> lock(memoryFaceBuffersMutex);
        std::map<FT_Face, FT_Byte*>::iterator it = memoryFaceBuffers.find(face);
        if (it != memoryFaceBuffers.end()) {
            bufferToFree = it->second;
            memoryFaceBuffers.erase(it);
        }
    }

    FT_Done_Face(face);

    if (bufferToFree != NULL) {
        free(bufferToFree);
    }
}

JNIEXPORT jint JNICALL Java_com_crystalgraphics_freetype_FreeTypeLibrary_nGetFaceCount
  (JNIEnv *env, jclass, jlong libraryPtr, jstring jFilePath) {
    FT_Library library = (FT_Library)(intptr_t)libraryPtr;
    const char *filePath = env->GetStringUTFChars(jFilePath, NULL);
    FT_Face face;
    FT_Error err = FT_New_Face(library, filePath, 0, &face);
    env->ReleaseStringUTFChars(jFilePath, filePath);
    if (err) {
        throwFreeTypeException(env, err, "FT_New_Face failed (face count query)");
        return 0;
    }
    jint numFaces = (jint)face->num_faces;
    FT_Done_Face(face);
    return numFaces;
}

JNIEXPORT jint JNICALL Java_com_crystalgraphics_freetype_FreeTypeLibrary_nGetFaceCountFromMemory
  (JNIEnv *env, jclass, jlong libraryPtr, jbyteArray jData, jint dataLen) {
    FT_Library library = (FT_Library)(intptr_t)libraryPtr;
    jbyte *data = env->GetByteArrayElements(jData, NULL);

    FT_Face face;
    FT_Error err = FT_New_Memory_Face(library, (const FT_Byte *)data, dataLen, 0, &face);
    if (err) {
        env->ReleaseByteArrayElements(jData, data, JNI_ABORT);
        throwFreeTypeException(env, err, "FT_New_Memory_Face failed (face count query)");
        return 0;
    }
    jint numFaces = (jint)face->num_faces;
    FT_Done_Face(face);
    env->ReleaseByteArrayElements(jData, data, JNI_ABORT);
    return numFaces;
}

} // extern "C"
