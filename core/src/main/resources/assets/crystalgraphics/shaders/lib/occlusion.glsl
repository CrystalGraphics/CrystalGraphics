#pragma once

// Whether a box hides behind the scene's depth: CgGpuOps.depthPyramid's pyramid, each level the farthest eye depth it
// covers. What CgGpuOps.cull and vfx Range test. Everything arrives as an argument: clip is projection times view by
// rows (camera-relative), eye is projection [2][2], [2][3], [3][2], [3][3], size the pyramid's width, height and levels.
// CgGpuOpsBodies.occluded is the Java twin.

// The eye depth a clip-space depth over w stands for, as cg_LinearEyeDepth reads the pyramid's.
float cg_occlusion_eye_depth(vec4 eye, float ndc) {
    if (eye.y == 0.0) return (eye.z - ndc) / eye.x;
    float p22 = -eye.x / eye.y;
    float p32 = eye.z + p22 * eye.w;
    return p32 / (ndc + p22);
}

// The level-0 texel a clip-space coordinate falls in, clamped to the pyramid while still a float.
int cg_occlusion_texel(float ndc, int size) {
    return int(clamp(floor((ndc * 0.5 + 0.5) * float(size)), 0.0, float(size - 1)));
}

// Whether the box lo..hi hides behind the pyramid: its nearest point farther than the farthest the scene holds over
// every texel its screen rect touches, at the level where that rect spans two texels at most. False with no levels, and
// for a box crossing the near plane.
bool cg_occluded(sampler2D pyramid, vec4 size, vec4 clipX, vec4 clipY, vec4 clipZ, vec4 clipW, vec4 eye, vec3 lo, vec3 hi) {
    int levels = int(size.z);
    if (levels <= 0) return false;
    float x0 = 3.4e38, y0 = 3.4e38, x1 = -3.4e38, y1 = -3.4e38, nearest = 3.4e38;
    for (int c = 0; c < 8; c++) {
        vec4 p = vec4((c & 1) == 0 ? lo.x : hi.x, (c & 2) == 0 ? lo.y : hi.y, (c & 4) == 0 ? lo.z : hi.z, 1.0);
        float w = dot(clipW, p);
        if (w < 0.01) return false;   // crossing the near plane: CgWorldRenderer's NEAR_W
        float nx = dot(clipX, p) / w, ny = dot(clipY, p) / w;
        x0 = min(x0, nx);
        x1 = max(x1, nx);
        y0 = min(y0, ny);
        y1 = max(y1, ny);
        nearest = min(nearest, cg_occlusion_eye_depth(eye, dot(clipZ, p) / w));
    }
    int width = int(size.x), height = int(size.y);
    int tx0 = cg_occlusion_texel(x0, width), tx1 = cg_occlusion_texel(x1, width);
    int ty0 = cg_occlusion_texel(y0, height), ty1 = cg_occlusion_texel(y1, height);
    int level = 0;
    while (level < levels - 1 && ((tx1 >> level) - (tx0 >> level) > 1 || (ty1 >> level) - (ty0 >> level) > 1)) level++;
    int lastX = max(1, width >> level) - 1, lastY = max(1, height >> level) - 1;
    int ax = min(tx0 >> level, lastX), bx = min(tx1 >> level, lastX);
    int ay = min(ty0 >> level, lastY), by = min(ty1 >> level, lastY);
    float farthest = max(max(texelFetch(pyramid, ivec2(ax, ay), level).r, texelFetch(pyramid, ivec2(bx, ay), level).r),
                         max(texelFetch(pyramid, ivec2(ax, by), level).r, texelFetch(pyramid, ivec2(bx, by), level).r));
    return nearest > farthest;
}
