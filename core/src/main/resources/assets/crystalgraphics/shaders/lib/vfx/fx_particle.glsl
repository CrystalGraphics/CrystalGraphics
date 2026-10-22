// What a particle shader needs to place a CgVfxQuads quad or a CgVfxRibbons stroke from a particle record. Pure
// functions: nothing here reads the record, which the shader reads through CG_PARTICLE_* and passes in.
//
//     int n = fx_particle_index(FX_QUAD_INDEX, CG_OBJECT_CUSTOM0.x, CG_OBJECT_CUSTOM0.y);   // -1 past the draw's count
//     vec3 world = fx_particle_corner(centre, corner, vec2(length, width), angle, right, up);
#pragma once
#include "crystalgraphics:shaders/lib/rng.glsl"

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

// How a quad faces: Niagara's sprite facing and alignment (NiagaraSpriteVertexFactory.ush, GetTangents), in a
// right-handed Y-up frame. The factory's _cg_Facing is facing + 8 * alignment.
//   facing: CAMERA lies in the eye's plane, CAMERA_PLANE too (the same without a headset's roll), CUSTOM faces a vector
//           (_cg_FacingVector: flat across it, a ring on the ground), CAMERA_POSITION turns toward the eye round world up.
//   alignment: UNALIGNED, VELOCITY stands its top along the motion (stretched only where the draw asks, _cg_Stretch),
//           CUSTOM stands it along a vector (_cg_AlignmentVector: upright flames on world up).
#define FX_FACE_CAMERA 0
#define FX_FACE_CAMERA_PLANE 1
#define FX_FACE_CUSTOM 2
#define FX_FACE_CAMERA_POSITION 3
#define FX_ALIGN_UNALIGNED 0
#define FX_ALIGN_VELOCITY 1
#define FX_ALIGN_CUSTOM 2

// The quad's axes at centre, right along its length and up across it, for facing and alignment: facingVector and
// alignmentVector their CUSTOM vectors, eye the camera's position, eyeRight and eyeUp its axes. A degenerate case (a
// vector along the one it is crossed with, a particle at rest aligned to its motion) keeps the eye's plane.
void fx_particle_facing(int facing, int alignment, vec3 centre, vec3 velocity, vec3 facingVector, vec3 alignmentVector,
                        vec3 eye, vec3 eyeRight, vec3 eyeUp, out vec3 right, out vec3 up) {
    right = eyeRight;
    up = eyeUp;
    vec3 toEye = normalize(eye - centre);
    vec3 faces = facing == FX_FACE_CUSTOM ? normalize(facingVector) : toEye;
    if (alignment != FX_ALIGN_UNALIGNED) {
        float speed = length(velocity);
        if (alignment == FX_ALIGN_VELOCITY && speed <= 1.0e-5) return;
        vec3 along = alignment == FX_ALIGN_CUSTOM ? normalize(alignmentVector) : velocity / speed;
        vec3 x = cross(along, faces);
        if (dot(x, x) <= 1.0e-8) return;
        right = normalize(x);
        up = facing == FX_FACE_CUSTOM ? cross(faces, right) : along;
    } else if (facing == FX_FACE_CUSTOM) {
        vec3 x = cross(eyeUp, faces);
        if (dot(x, x) <= 1.0e-8) return;
        right = normalize(x);
        up = cross(faces, right);
    } else if (facing == FX_FACE_CAMERA_POSITION) {
        vec3 x = cross(vec3(0.0, 1.0, 0.0), toEye);
        if (dot(x, x) <= 1.0e-8) return;
        right = normalize(x);
        up = cross(toEye, right);
    }
}

// A flipbook (CgVfxFlipbook): Niagara's sub-image index, then the rect of each frame it names.
// subImage: columns, rows, blends (0 or 1), a loop's random start (0 or 1); rows 0 is a rect table of `columns` frames,
// read from the table texture. subUv: mode (FX_SUBUV_*), first frame, last frame (-1 the last), rate (a loop's frames a
// second, a random frame's changes a second, 0 for once at birth).
#define FX_SUBUV_LINEAR 0
#define FX_SUBUV_LOOP 1
#define FX_SUBUV_RANDOM 2
#define FX_SUBUV_DIRECT 3
// A rect table's texels to a row: GL 3.3's smallest texture limit.
#define FX_SUBIMAGE_ROW 1024

float fx_subimage_count(vec4 subImage) {
    return max(subImage.y > 0.5 ? subImage.x * subImage.y : subImage.x, 1.0);
}

// The frames a particle shows, a and b, and how far it is from a to b: Niagara's stateless Sub UV Animation from its age
// (seconds), progress (0..1) and seed. Linear runs first to last over the life; a loop wraps inside the range once every
// count / rate seconds; random holds a whole frame between changes and never blends; direct shows frame index, the
// particle's SubImageIndex (Cascade's SubUV, ParticleModules.cpp:2209-2230), blending into the next where it blends.
void fx_subuv_frames(vec4 subUv, vec4 subImage, float age, float progress, float seed, float index,
                     out float a, out float b, out float blend) {
    float frames = fx_subimage_count(subImage);
    float first = clamp(floor(subUv.y + 0.5), 0.0, frames - 1.0);
    float last = subUv.z < 0.0 ? frames - 1.0 : clamp(floor(subUv.z + 0.5), first, frames - 1.0);
    float count = last - first + 1.0;
    int mode = int(subUv.x + 0.5);
    float at;
    if (mode == FX_SUBUV_RANDOM) {
        uint step = subUv.w > 0.0 ? uint(max(age, 0.0) * subUv.w) : 0u;
        at = min(floor(cg_rng_unit(cg_rng(floatBitsToUint(seed), 0u, step, 7u)) * count), count - 1.0);
    } else if (mode == FX_SUBUV_DIRECT) {
        at = clamp(index - first, 0.0, count - 1.0);
    } else if (mode == FX_SUBUV_LOOP) {
        at = mod(max(age, 0.0) * subUv.w + (subImage.w > 0.5 ? seed * count : 0.0), count);
    } else {
        at = clamp(progress, 0.0, 1.0) * (count - 1.0);
    }
    float whole = min(floor(at), count - 1.0);
    blend = subImage.z > 0.5 && mode != FX_SUBUV_RANDOM ? at - whole : 0.0;
    a = first + whole;
    b = mode == FX_SUBUV_LOOP ? first + mod(whole + 1.0, count) : min(a + 1.0, last);
}

// Frame f's rect in the image, (u0, v0, u1, v1) with v down from its top: a cell of the grid, frame 0 at the top left
// and rows going down (Niagara's SubImageSize), or rect f of the table.
vec4 fx_subimage_rect(sampler2D table, vec4 subImage, float f) {
    int i = int(f + 0.5);
    if (subImage.y < 0.5) return texelFetch(table, ivec2(i % FX_SUBIMAGE_ROW, i / FX_SUBIMAGE_ROW), 0);
    float column = mod(float(i), subImage.x), row = floor((float(i) + 0.5) / subImage.x);
    return vec4(column, row, column + 1.0, row + 1.0) / subImage.xyxy;
}

// Where in the image a quad's point samples frame f: corner -1..1 across the quad, y up, the quad's top the rect's top.
vec2 fx_subimage_uv(sampler2D table, vec4 subImage, float f, vec2 corner) {
    vec4 rect = fx_subimage_rect(table, subImage, f);
    return mix(rect.xy, rect.zw, vec2(corner.x * 0.5 + 0.5, 0.5 - corner.y * 0.5));
}
