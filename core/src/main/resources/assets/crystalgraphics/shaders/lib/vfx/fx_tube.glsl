// The rings of a CgVfxPath as CgVfxPathTexture lays them out, and a CgVfxTube vertex placed on them. A tube shader
// declares the texture as a property and passes it in:
//
//     Properties { _FxPath ("Path rings", sampler2D) = "black" }
//     FxTubeVertex v = fx_tube_vertex(_FxPath, int(CG_OBJECT_CUSTOM0.x + 0.5), int(CG_OBJECT_CUSTOM0.y + 0.5),
//                                     cg_TexCoord0, CG_OBJECT_CUSTOM0.z, 0.0);
//     vec3 origin = CG_OBJECT_TO_WORLD[3].xyz - CG_OBJECT_CUSTOM1.xyz;   // the effect's origin, camera-relative
//     vec3 world = origin + v.position;
//
// Nothing here names cg_* or CG_*: an included file compiles ahead of the frame block.
#pragma once

struct FxRing {
    vec3 position;   // relative to the effect's origin
    float radius;
    vec3 tangent;
    float arc;       // blocks from the path's start
    vec3 normal;     // rotation-minimising: does not twist along the path
    float intensity;
};

// x ring count, y length in blocks, z the effect's seed, w its age in seconds.
vec4 fx_path_header(sampler2D path, int row) {
    return texelFetch(path, ivec2(0, row), 0);
}

FxRing fx_ring(sampler2D path, int row, int ring) {
    int x = 1 + ring * 3;
    vec4 a = texelFetch(path, ivec2(x, row), 0);
    vec4 b = texelFetch(path, ivec2(x + 1, row), 0);
    vec4 c = texelFetch(path, ivec2(x + 2, row), 0);
    FxRing r;
    r.position = a.xyz;
    r.radius = a.w;
    r.tangent = b.xyz;
    r.arc = b.w;
    r.normal = c.xyz;
    r.intensity = c.w;
    return r;
}

struct FxTubeVertex {
    vec3 position;   // relative to the effect's origin
    FxRing ring;
    float angle;     // 0..1 around the tube, from the ring's normal toward cross(tangent, normal)
    float cap;       // -1 on the first ring, 1 on the last, 0 between
    vec4 header;
};

// One vertex of a CgVfxTube chunk. uv is the chunk mesh's (ring within the chunk, angle 0..1); radiusScale multiplies
// the ring's radius; capPush moves the first and last rings out along the axis by that many radii, so a volume's hull
// covers rounded ends.
FxTubeVertex fx_tube_vertex(sampler2D path, int row, int first, vec2 uv, float radiusScale, float capPush) {
    FxTubeVertex v;
    v.header = fx_path_header(path, row);
    int count = max(int(v.header.x + 0.5), 1);
    int index = clamp(first + int(uv.x + 0.5), 0, count - 1);
    v.ring = fx_ring(path, row, index);
    v.angle = uv.y;
    v.cap = index == 0 ? -1.0 : (index == count - 1 ? 1.0 : 0.0);
    float a = uv.y * 6.28318531;
    vec3 binormal = cross(v.ring.tangent, v.ring.normal);
    float radius = v.ring.radius * radiusScale;
    v.position = v.ring.position + radius * (cos(a) * v.ring.normal + sin(a) * binormal)
            + v.ring.tangent * (v.cap * capPush * radius);
    return v;
}

// Where a view ray from eye along ray (unit) passes the axis through a with direction t (unit): x how far along the
// ray, y how near it comes, z how far along the axis from a, w the sine of the angle between them.
vec4 fx_ray_axis(vec3 eye, vec3 ray, vec3 a, vec3 t) {
    vec3 w = eye - a;
    float b = dot(ray, t);
    float denom = max(1.0 - b * b, 1.0e-5);
    float d = dot(ray, w), e = dot(t, w);
    float along = (b * e - d) / denom;
    float axial = (e - b * d) / denom;
    return vec4(along, length(eye + ray * along - (a + t * axial)), axial, sqrt(denom));
}

// The path at arc length s (blocks from its start), between the rings either side: what a shader riding the body
// (CgVfxFrame.pathRibbons) places itself on. Clamped to the path's ends.
FxRing fx_ring_at(sampler2D path, int row, float s, vec4 header) {
    int count = max(int(header.x + 0.5), 1);
    float spacing = header.y / max(header.x - 1.0, 1.0);
    float f = clamp(s / max(spacing, 1.0e-4), 0.0, float(count - 1));
    int i = min(int(f), count - 2);
    float k = f - float(i);
    FxRing a = fx_ring(path, row, max(i, 0)), b = fx_ring(path, row, min(i + 1, count - 1));
    FxRing r;
    r.position = mix(a.position, b.position, k);
    r.radius = mix(a.radius, b.radius, k);
    r.tangent = normalize(mix(a.tangent, b.tangent, k));
    r.arc = mix(a.arc, b.arc, k);
    r.normal = normalize(mix(a.normal, b.normal, k));
    r.intensity = mix(a.intensity, b.intensity, k);
    return r;
}
