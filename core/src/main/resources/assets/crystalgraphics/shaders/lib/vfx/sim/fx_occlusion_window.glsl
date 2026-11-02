// CgVfxModule.Occlusion's sample window, shared by its two sources: Niagara's Occlusion data interface's circle (its
// centre, then rings of samples) and rectangle (a grid), each sample an offset in blocks across the screen from the
// particle. m0: window (0 circle, 1 rectangle), source, diameter, rings; m1: samples per ring, centre sample, width,
// height; m2.x: steps per line.
#pragma once

// How many samples the window takes.
int fx_occlusion_samples(vec4 m0, vec4 m1, vec4 m2) {
    if (m0.x < 0.5) return (m1.y > 0.5 ? 1 : 0) + max(int(m0.w), 0) * max(int(m1.x), 0);
    int steps = max(int(m2.x), 1);
    return steps * steps;
}

// Sample i's offset across the screen, in blocks: x along the camera's right, y along its up.
vec2 fx_occlusion_offset(vec4 m0, vec4 m1, vec4 m2, int i) {
    if (m0.x < 0.5) {
        if (m1.y > 0.5) {
            if (i == 0) return vec2(0.0);
            i--;
        }
        int rings = max(int(m0.w), 1), perRing = max(int(m1.x), 1);
        int r = i / perRing, k = i - r * perRing;
        float ring = 0.5 * m0.z * float(r + 1) / float(rings);
        float a = 6.28318530718 * (float(k) + 0.5 * float(r & 1)) / float(perRing);
        return ring * vec2(cos(a), sin(a));
    }
    int steps = max(int(m2.x), 1);
    int y = i / steps, x = i - y * steps;
    vec2 t = steps == 1 ? vec2(0.5) : vec2(float(x), float(y)) / float(steps - 1);
    return (t - 0.5) * m1.zw;
}
