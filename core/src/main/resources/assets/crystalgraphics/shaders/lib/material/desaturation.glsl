// Unreal's Desaturation (UMaterialExpressionDesaturation, MaterialExpressions.cpp:5998-6008): the colour's grey, its
// dot with the luminance factors, blended in by fraction: lerp(color, grey, fraction). 1 is fully grey.
//
//     #include "crystalgraphics:shaders/lib/material/desaturation.glsl"
//     vec3 c = cg_desaturation(texture(_Tex, uv).rgb, 0.5);
//
// The factors default to Unreal's (0.3, 0.59, 0.11), not Rec. 709's. Not `desaturate()` in lib/color.glsl: that one
// weighs by Rec. 709 and its amount runs the other way.
#pragma once

vec3 cg_desaturation(vec3 color, float fraction, vec3 luminanceFactors) {
    return mix(color, vec3(dot(color, luminanceFactors)), fraction);
}

vec3 cg_desaturation(vec3 color, float fraction) {
    return cg_desaturation(color, fraction, vec3(0.3, 0.59, 0.11));
}
