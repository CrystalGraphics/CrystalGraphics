// Reading a sparse volume (CgVfxTiles: the gases, the grids): which pool slot holds a cell through its page table, and
// where a cell is read in a pool laid out with two cells of apron round each tile, filtered or under a cubic B-spline,
// never a neighbour tile's texels. Every value is an argument; the volumes are passed in.
//
//     vec3 c = (p - gridLo) / cell;                            // the volume's grid cells, its origin a SPAN corner
//     int slot = fx_sparse_slot(pages, g, c);                  // -1: no tile there
//     vec4 v = textureLod(pool, fx_sparse_pool_uvw(pool, slot, c), 0.0);
//     vec4 s = fx_sparse_smooth(pool, slot, c);                // smooth across cell faces
//     vec4 w = fx_sparse_read(pool, pages, g, c);              // the two at once; 0 where it has no tile
#pragma once

// CgVfxTiles' layout: cells a tile side, tiles a page table side, cells a grid side, pool tiles a layer side, a
// tile's side in the apron'd pools, and the apron.
#define FX_SPARSE_TILE 16
#define FX_SPARSE_PAGES 32
#define FX_SPARSE_SPAN 512
#define FX_SPARSE_LAYER 8
#define FX_SPARSE_APRON_TILE 20
#define FX_SPARSE_APRON 2

// The tile holding cell c (the volume's grid cells), in tiles.
ivec3 fx_sparse_tile(vec3 c) {
    return ivec3(floor(c / float(FX_SPARSE_TILE)));
}

// The pool slot of volume g's tile t: -1 where it has no tile, and past its grid.
int fx_sparse_slot_of(sampler3D pages, int g, ivec3 t) {
    if (any(lessThan(t, ivec3(0))) || any(greaterThanEqual(t, ivec3(FX_SPARSE_PAGES)))) return -1;
    return int(texelFetch(pages, ivec3(t.x, t.y, t.z + FX_SPARSE_PAGES * g), 0).x + 0.5) - 1;
}

int fx_sparse_slot(sampler3D pages, int g, vec3 c) {
    return fx_sparse_slot_of(pages, g, fx_sparse_tile(c));
}

// Where cell c of slot s's tile is in an apron'd pool (shade, flow), in texels.
vec3 fx_sparse_pool_texel(int s, vec3 c) {
    ivec3 p = ivec3(s % FX_SPARSE_LAYER, (s / FX_SPARSE_LAYER) % FX_SPARSE_LAYER, s / (FX_SPARSE_LAYER * FX_SPARSE_LAYER));
    return vec3(p * FX_SPARSE_APRON_TILE + FX_SPARSE_APRON) + c - vec3(fx_sparse_tile(c) * FX_SPARSE_TILE);
}

// Where cell c of slot s's tile is read, filtered, in an apron'd pool: never its neighbour's texels.
vec3 fx_sparse_pool_uvw(sampler3D pool, int s, vec3 c) {
    return fx_sparse_pool_texel(s, c) / vec3(textureSize(pool, 0));
}

// The pool's value at cell c of slot s's tile under a cubic B-spline, from eight filtered reads (Sigg and Hadwiger,
// GPU Gems 2 ch. 20): smooth across cell faces, where a trilinear read bends and shows the grid. Its reach is the
// two cells of apron.
vec4 fx_sparse_smooth(sampler3D pool, int s, vec3 c) {
    vec3 size = vec3(textureSize(pool, 0));
    vec3 x = fx_sparse_pool_texel(s, c) - 0.5;
    vec3 i = floor(x), f = x - i, f2 = f * f, f3 = f2 * f;
    vec3 w0 = (1.0 - 3.0 * f + 3.0 * f2 - f3) / 6.0;
    vec3 w1 = (4.0 - 6.0 * f2 + 3.0 * f3) / 6.0;
    vec3 w3 = f3 / 6.0;
    vec3 g1 = 1.0 - w0 - w1;
    vec3 h0 = (i - 0.5 + w1 / (w0 + w1)) / size, h1 = (i + 1.5 + w3 / max(g1, 1.0e-6)) / size;
    vec4 a = mix(textureLod(pool, h0, 0.0), textureLod(pool, vec3(h1.x, h0.y, h0.z), 0.0), g1.x);
    vec4 b = mix(textureLod(pool, vec3(h0.x, h1.y, h0.z), 0.0), textureLod(pool, vec3(h1.x, h1.y, h0.z), 0.0), g1.x);
    vec4 d = mix(textureLod(pool, vec3(h0.x, h0.y, h1.z), 0.0), textureLod(pool, vec3(h1.x, h0.y, h1.z), 0.0), g1.x);
    vec4 e = mix(textureLod(pool, vec3(h0.x, h1.y, h1.z), 0.0), textureLod(pool, h1, 0.0), g1.x);
    return mix(mix(a, b, g1.y), mix(d, e, g1.y), g1.z);
}

// A pool's value at cell c of volume g, filtered; 0 where it has no tile.
vec4 fx_sparse_read(sampler3D pool, sampler3D pages, int g, vec3 c) {
    int s = fx_sparse_slot(pages, g, c);
    return s < 0 ? vec4(0.0) : textureLod(pool, fx_sparse_pool_uvw(pool, s, c), 0.0);
}
