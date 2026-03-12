#include "headers.h"

JNIEXPORT jlong JNICALL Java_com_crystalgraphics_text_FreeTypeHarfBuzzIntegration_nCreateHBFontFromFTFace
  (JNIEnv *env, jclass, jlong ftFacePtr) {
    FT_Face face = (FT_Face)(intptr_t)ftFacePtr;
    hb_font_t *hbFont = hb_ft_font_create(face, NULL);
    if (hbFont == NULL) {
        return 0;
    }
    return (jlong)(intptr_t)hbFont;
}

JNIEXPORT void JNICALL Java_com_crystalgraphics_text_FreeTypeHarfBuzzIntegration_nSyncFontMetrics
  (JNIEnv *env, jclass, jlong hbFontPtr, jlong ftFacePtr) {
    hb_font_t *hbFont = (hb_font_t *)(intptr_t)hbFontPtr;
    FT_Face face = (FT_Face)(intptr_t)ftFacePtr;
    hb_ft_font_set_funcs(hbFont);
    int x_scale = (int)face->size->metrics.x_ppem * 64;
    int y_scale = (int)face->size->metrics.y_ppem * 64;
    hb_font_set_scale(hbFont, x_scale, y_scale);
    hb_font_set_ppem(hbFont, face->size->metrics.x_ppem, face->size->metrics.y_ppem);
}
