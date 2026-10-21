// Unreal's Panner (UMaterialExpressionPanner): uv moved by time * speed. fractional keeps only the fraction of the
// move, as bFractionalPart does, so precision holds as time grows.
#pragma once

vec2 cg_panner(vec2 uv, float time, vec2 speed, bool fractional) {
    vec2 move = time * speed;
    return uv + (fractional ? fract(move) : move);
}
