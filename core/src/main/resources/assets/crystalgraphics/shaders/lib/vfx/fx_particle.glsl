// What a particle shader needs to place a CgVfxQuads quad or a CgVfxRibbons stroke from a particle record. Pure
// functions: nothing here names cg_* or CG_*, so the record itself is read by the shader through CG_PARTICLE_*.
//
//     int n = fx_particle_index(FX_QUAD_INDEX, CG_OBJECT_CUSTOM0.x, CG_OBJECT_CUSTOM0.y);   // -1 past the draw's count
//     vec3 world = fx_particle_corner(centre, corner, vec2(length, width), angle, right, up);
#pragma once

// The record a vertex's quad or stroke reads: base + its index, or -1 when the draw has fewer particles than slots.
int fx_particle_index(float slot, float base, float count) {
    return slot < count ? int(base + slot + 0.5) : -1;
}

// A corner of a camera-facing quad: corner -1..1 on each axis, scaled by size (half-length along x, half-width along
// y), turned by angle in the eye's plane.
vec3 fx_particle_corner(vec3 centre, vec2 corner, vec2 size, float angle, vec3 right, vec3 up) {
    float c = cos(angle), s = sin(angle);
    vec2 scaled = corner * size;
    return centre + right * (c * scaled.x - s * scaled.y) + up * (s * scaled.x + c * scaled.y);
}

// Niagara's sprite camera offset: centre pulled offset blocks toward the eye, so a particle resting on a surface draws in
// front of it rather than half through it. keep scales its size so it covers the same share of the screen.
vec3 fx_particle_toward_eye(vec3 centre, vec3 eye, float offset, out float keep) {
    vec3 toEye = eye - centre;
    float d = max(length(toEye), 1.0e-4), moved = min(offset, d * 0.5);
    keep = (d - moved) / d;
    return centre + toEye * (moved / d);
}

// Soft particles (Unity's soft particles, Unreal's DepthFade, Godot's proximity fade): 0 where a fragment meets what is
// behind it, rising to 1 distance blocks in front, so a sprite crossing a surface fades into it rather than being cut.
// Eye depths both; 1 for a distance of 0.
float fx_particle_soft(float sceneEye, float eye, float distance) {
    return distance > 0.0 ? clamp((sceneEye - eye) / distance, 0.0, 1.0) : 1.0;
}

// The angle in the eye's plane a velocity points at: what a streak lines up with.
float fx_particle_heading(vec3 velocity, vec3 right, vec3 up) {
    return atan(dot(velocity, up), dot(velocity, right) + 1.0e-6);
}

// How a quad faces, the plane fx_particle_facing answers. CAMERA lies in the eye's plane (Godot's billboard, bevy_hanabi's
// ParallelCameraDepthPlane); CAMERA_POSITION turns toward the eye itself (FaceCameraPosition), so wide sprites do not
// shear at the screen's edge; UPRIGHT turns round world up alone (Godot's FIXED_Y), for flames and trees; VELOCITY lies
// along the motion, facing the eye as far as it can (AlongVelocity), a streak in three dimensions; AXIS lies flat across
// a fixed axis, a ring on the ground.
#define FX_FACE_CAMERA 0
#define FX_FACE_CAMERA_POSITION 1
#define FX_FACE_UPRIGHT 2
#define FX_FACE_VELOCITY 3
#define FX_FACE_AXIS 4

// The quad's axes for mode at centre: right along its length, up across it. eye is the camera's position, eyeRight and
// eyeUp its axes, axis the AXIS mode's normal. Ported from Godot's billboard modes (material.cpp) and bevy_hanabi's
// OrientModifier (MIT both); a degenerate case falls back to the eye's plane.
void fx_particle_facing(int mode, vec3 centre, vec3 velocity, vec3 axis, vec3 eye, vec3 eyeRight, vec3 eyeUp,
                        out vec3 right, out vec3 up) {
    right = eyeRight;
    up = eyeUp;
    vec3 toEye = eye - centre;
    if (mode == FX_FACE_CAMERA_POSITION) {
        vec3 z = normalize(toEye);
        vec3 x = cross(eyeUp, z);
        if (dot(x, x) > 1.0e-8) {
            right = normalize(x);
            up = cross(z, right);
        }
    } else if (mode == FX_FACE_UPRIGHT) {
        vec3 x = cross(vec3(0.0, 1.0, 0.0), toEye);
        if (dot(x, x) > 1.0e-8) {
            right = normalize(x);
            up = vec3(0.0, 1.0, 0.0);
        }
    } else if (mode == FX_FACE_VELOCITY) {
        float speed = length(velocity);
        if (speed > 1.0e-5) {
            vec3 x = velocity / speed;
            vec3 y = cross(normalize(toEye), x);
            if (dot(y, y) > 1.0e-8) {
                right = x;
                up = normalize(y);
            }
        }
    } else if (mode == FX_FACE_AXIS) {
        vec3 n = normalize(axis);
        vec3 x = cross(abs(n.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0), n);
        right = normalize(x);
        up = cross(n, right);
    }
}

// A flipbook frame: Godot's (frame = floor((offset + progress * cycles) * frames), looping or held at the last) laid
// out as bevy_hanabi's (column frame % columns, row frame / columns, the first row at the top). sheet: columns, rows,
// frames, cycles over the particle's life; uv 0..1 across the quad, y up. Answers the sheet's uv of this frame in xy and
// of the next in zw, and in blend how far between them: Unity's flipbook blending, for a sheet with few frames.
vec4 fx_particle_flipbook(vec2 uv, float progress, float offset, vec4 sheet, bool loops, out float blend) {
    float frames = max(sheet.z, 1.0);
    float at = (offset + progress * sheet.w) * frames;
    float first = floor(at);
    blend = at - first;
    float second = first + 1.0;
    if (loops) {
        first = mod(first, frames);
        second = mod(second, frames);
    } else {
        first = min(first, frames - 1.0);
        second = min(second, frames - 1.0);
        if (first >= frames - 1.0) blend = 0.0;
    }
    vec2 cell = vec2(1.0) / sheet.xy;
    vec2 a = vec2(mod(first, sheet.x), sheet.y - 1.0 - floor((first + 0.5) / sheet.x));
    vec2 b = vec2(mod(second, sheet.x), sheet.y - 1.0 - floor((second + 0.5) / sheet.x));
    return vec4((a + uv) * cell, (b + uv) * cell);
}
