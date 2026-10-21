// Unreal's RotateAboutAxis (UMaterialExpressionRotateAboutAxis, MaterialTemplate.ush): position turned about the axis
// through pivot by axisAndAngle.w radians. Returns the offset to add to position, as Unreal's does, so a World
// Position Offset adds it directly. Unreal's node takes its angle in periods: the port multiplies by 2pi / Period.
#pragma once

vec3 cg_rotate_about_axis(vec4 axisAndAngle, vec3 pivot, vec3 position) {
    vec3 axis = axisAndAngle.xyz;
    vec3 closest = pivot + axis * dot(axis, position - pivot);
    vec3 u = position - closest, v = cross(axis, u);
    vec3 rotated = closest + u * cos(axisAndAngle.w) + v * sin(axisAndAngle.w);
    return rotated - position;
}
