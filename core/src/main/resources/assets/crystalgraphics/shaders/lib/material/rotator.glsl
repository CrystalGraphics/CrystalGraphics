// Unreal's Rotator (UMaterialExpressionRotator): uv turned about center by time * speed radians, counter-clockwise.
#pragma once

vec2 cg_rotator(vec2 uv, vec2 center, float time, float speed) {
    float c = cos(time * speed), s = sin(time * speed);
    vec2 d = uv - center;
    return vec2(c * d.x - s * d.y, s * d.x + c * d.y) + center;
}
