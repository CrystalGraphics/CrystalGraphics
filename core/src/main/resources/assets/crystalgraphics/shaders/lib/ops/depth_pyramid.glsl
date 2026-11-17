// The depth pyramid's kernels (depth_pyramid.compute, depth_pyramid_half.compute), after the file's Images: the
// same reduction into 32-bit or 16-bit float levels.
#pragma once

// A level per array in turn: the first level made in A (63 texels a side at most), the next in B (31), and so on.
shared float levelA[64 * 64];
shared float levelB[32 * 32];

const int TILE = 64;
// Below every eye depth: a fold's start.
const float NONE = -3.0e38;

ivec2 levelSize(ivec2 first, int k) {
    return max(first >> k, ivec2(1));
}

// Texels of a level of size n a texel of the next folds along an axis: one where n is 1, two where even, three where odd.
ivec2 taps(ivec2 n) {
    return ivec2(n.x == 1 ? 1 : 2 + (n.x & 1), n.y == 1 ? 1 : 2 + (n.y & 1));
}

// The margin past a group's tile that level k of this dispatch reduces, so its own texels of the last level are exact.
ivec2 margin(ivec2 first, int k, int levels) {
    ivec2 x = ivec2(0);
    for (int j = levels - 1; j >= k; j--) {   // LOOP-BOUND: levels is at most six
        ivec2 n = levelSize(first, j);
        x = 2 * x + ivec2(n.x > 1 ? n.x & 1 : 0, n.y > 1 ? n.y & 1 : 0);
    }
    return x;
}

void store(int k, ivec2 p, float v) {
    vec4 c = vec4(v, 0.0, 0.0, 1.0);
    if (k == 1) OUT1_STORE(p, c);
    else if (k == 2) OUT2_STORE(p, c);
    else if (k == 3) OUT3_STORE(p, c);
    else if (k == 4) OUT4_STORE(p, c);
    else if (k == 5) OUT5_STORE(p, c);
    else OUT6_STORE(p, c);
}

// The first level made, region texel r (the group's texel g): kept for the next level, written where the group owns it.
void keepFirst(ivec2 first, ivec2 r, ivec2 g, float m) {
    levelA[r.y * 64 + r.x] = m;
    if (all(lessThan(r, ivec2(TILE >> 1))) && all(lessThan(g, levelSize(first, 1)))) store(1, g, m);
}

ivec2 firstRegion(ivec2 first, int levels) {
    return ivec2(TILE >> 1) + margin(first, 1, levels);
}

// Levels 2 to levels from the first in levelA, each written where the group owns it.
void reduceShared(ivec2 first, ivec2 group, int levels) {
    for (int k = 2; k <= levels; k++) {   // LOOP-BOUND: levels is at most six
        ivec2 above = levelSize(first, k - 1), n = levelSize(first, k);
        ivec2 tile = ivec2(TILE >> k), start = group * tile, region = tile + margin(first, k, levels);
        ivec2 aboveStart = group * (TILE >> (k - 1));
        ivec2 aboveLast = max(min(above - 1 - aboveStart, ivec2(TILE >> (k - 1)) + margin(first, k - 1, levels) - 1), ivec2(0));
        ivec2 t = taps(above);
        bool fromA = (k & 1) == 0;
        for (int i = CG_LOCAL_INDEX; i < region.x * region.y; i += 256) {   // LOOP-BOUND: a work group's stride over its region
            ivec2 r = ivec2(i % region.x, i / region.x);
            float m = NONE;
            for (int dy = 0; dy < 3; dy++) {
                for (int dx = 0; dx < 3; dx++) {
                    if (dx >= t.x || dy >= t.y) continue;
                    ivec2 a = min(2 * r + ivec2(dx, dy), aboveLast);
                    m = max(m, fromA ? levelA[a.y * 64 + a.x] : levelB[a.y * 32 + a.x]);
                }
            }
            if (fromA) levelB[r.y * 32 + r.x] = m;
            else levelA[r.y * 64 + r.x] = m;
            ivec2 g = start + r;
            if (all(lessThan(r, tile)) && all(lessThan(g, n))) store(k, g, m);
        }
        barrier();
    }
}

float seedAt(ivec2 p) {
    return cg_LinearEyeDepth(texelFetch(cg_DepthBuffer, p, 0).r);
}

// Each kernel's body only in its own build: the other's reads images this one does not bind.
#ifdef CG_KERNEL_Seed
void pyramid_seed() {
    ivec2 first = OUT0_SIZE(), group = CG_GROUP_ID.xy, t = taps(first);
    int levels = clamp(_Levels, 1, 6);
    ivec2 region = firstRegion(first, levels), tile0 = group * TILE;
    for (int i = CG_LOCAL_INDEX; i < region.x * region.y; i += 256) {   // LOOP-BOUND: a work group's stride over its region
        ivec2 r = ivec2(i % region.x, i / region.x), g = group * (TILE >> 1) + r;
        float m = NONE;
        for (int dy = 0; dy < 3; dy++) {
            for (int dx = 0; dx < 3; dx++) {
                if (dx >= t.x || dy >= t.y) continue;
                ivec2 p = min(2 * g + ivec2(dx, dy), first - 1);
                float v = seedAt(p);
                m = max(m, v);
                // Level 0 where the group owns it, each texel once: its first two taps, or a third that is the last.
                bool own = all(greaterThanEqual(p, tile0)) && all(lessThan(p, tile0 + TILE));
                bool once = (dx < 2 || p.x == first.x - 1) && (dy < 2 || p.y == first.y - 1);
                if (own && once) OUT0_STORE(p, vec4(v, 0.0, 0.0, 1.0));
            }
        }
        keepFirst(first, r, g, m);
    }
    barrier();
    reduceShared(first, group, levels);
}
#endif

#ifdef CG_KERNEL_Reduce
void pyramid_reduce() {
    ivec2 first = SRC_SIZE(), group = CG_GROUP_ID.xy, t = taps(first);
    int levels = clamp(_Levels, 1, 6);
    ivec2 region = firstRegion(first, levels);
    for (int i = CG_LOCAL_INDEX; i < region.x * region.y; i += 256) {   // LOOP-BOUND: a work group's stride over its region
        ivec2 r = ivec2(i % region.x, i / region.x), g = group * (TILE >> 1) + r;
        float m = NONE;
        for (int dy = 0; dy < 3; dy++) {
            for (int dx = 0; dx < 3; dx++) {
                if (dx < t.x && dy < t.y) m = max(m, SRC_LOAD(min(2 * g + ivec2(dx, dy), first - 1)).r);
            }
        }
        keepFirst(first, r, g, m);
    }
    barrier();
    reduceShared(first, group, levels);
}
#endif
