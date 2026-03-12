#ifndef FREETYPE_HARFBUZZ_JNI_HEADERS_H
#define FREETYPE_HARFBUZZ_JNI_HEADERS_H

#include <jni.h>
#include <ft2build.h>
#include FT_FREETYPE_H
#include FT_GLYPH_H
#include FT_ADVANCES_H

#include <hb.h>
#include <hb-ft.h>

#ifdef __cplusplus
extern "C" {
#endif

void throwException(JNIEnv *env, const char *className, const char *message);
void throwFreeTypeException(JNIEnv *env, int errorCode, const char *message);

#ifdef __cplusplus
}
#endif

#endif
