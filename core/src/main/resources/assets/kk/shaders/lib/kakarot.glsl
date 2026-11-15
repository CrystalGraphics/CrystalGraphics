// Kit: the studio's values are in Unreal's units (centimetres), which a port keeps.
#pragma once
// Host blocks a Kakarot centimetre.
#define KK_CM 0.01

// A [0, 1] texel as a signed offset: Unreal's (x * 2 - 1) on a normal map's channels.
float kk_signed(float x) { return x * 2.0 - 1.0; }
vec2 kk_signed(vec2 x) { return x * 2.0 - 1.0; }

// A tangent-space normal map's xy as Unreal reconstructs it: z from the unit length.
vec3 kk_normal_xy(vec2 xy) { return vec3(xy, sqrt(max(0.0, 1.0 - dot(xy, xy)))); }

// An Unreal world vector's components on the host's axes: Unreal is Z up, the host Y up (CgUnits.UNREAL's (X, Z, Y)).
vec3 kk_unreal_axes(vec3 v) { return v.xzy; }

// An Unreal view-space vector in the host's world: Unreal's view is x right, y up, z forward (LocalPlayer.cpp's
// ViewRotationMatrix), the host's looks down -z. `view` is the host's view matrix (rotation and translation only).
vec3 kk_unreal_view_to_world(vec3 v, mat4 view) { return transpose(mat3(view)) * vec3(v.xy, -v.z); }
