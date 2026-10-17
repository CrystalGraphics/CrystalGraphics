// Baked clips (CgVfxClipAtlas): where a particle's age falls in its clip. A node's row r, frame f is vec4
// base + r * length + f; this says which two frames to read and how far between, and the reader mixes them. Shared by
// the Step kernel's clip events and fx_sequence.glsl's draws, so both play a clip at the same frame.
#pragma once

// The frames to read at `age` seconds, `rate` frames a second, held at `frames`, the last: a then b, t of the way. A
// row a CONSTANT channel feeds (`steps`) takes a alone.
void fx_clip_frames(float age, float rate, float frames, bool steps, out int a, out int b, out float t) {
    float f = clamp(age * rate, 0.0, frames);
    float whole = floor(f);
    a = int(whole);
    b = min(a + 1, int(frames));
    t = steps ? 0.0 : f - whole;
}

// The place a node's matrix rows put its origin, and its +Y axis: rows 0 to 2 of CgVfxClipAtlas's layout.
vec3 fx_clip_origin(vec4 r0, vec4 r1, vec4 r2) {
    return vec3(r0.w, r1.w, r2.w);
}

vec3 fx_clip_up(vec4 r0, vec4 r1, vec4 r2) {
    return vec3(r0.y, r1.y, r2.y);
}
