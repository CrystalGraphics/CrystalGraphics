// A debris speck: a small rock chunk, sliver or shard, each its own stretch, raggedness, tone and shine from its seed,
// a lumpy dome inside its ragged outline lit by the sun (a key light from above where the world has none) and by the
// world where it is, its light turning as its spin tumbles it. A CgVfxQuads quad placed from its particle record
// (CgVfxFrame.particles, QUADS). Colour A is the rock, A's alpha a strength.
#type none
#pragma cg_use particle
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_ribbon.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_particle.glsl"

Tags { "RenderType" = "Transparent" "Depth" = "Clip" }
Queue = "Transparent"

Properties {
    _ValueNoise ("Value noise", sampler3D) = "cg_value_noise"
    _CameraOffset ("Drawn this many of its sizes toward the eye, so one resting on a surface is not cut by it", float) = 1
}

struct v2f { vec3 world; vec4 speck; vec4 tone; float opacity; vec2 light; vec3 across; vec3 along; vec3 facing; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE_MINUS_SRC_ALPHA
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }

    void vertex(out v2f o) {
        int n = fx_particle_index(FX_QUAD_INDEX, CG_OBJECT_CUSTOM0.x, CG_OBJECT_CUSTOM0.y);
        vec2 corner = FX_QUAD_CORNER;
        vec3 origin = CG_OBJECT_TO_WORLD[3].xyz - CG_OBJECT_CUSTOM1.xyz;
        vec3 right = vec3(cg_ViewMatrix[0][0], cg_ViewMatrix[1][0], cg_ViewMatrix[2][0]);
        vec3 up = vec3(cg_ViewMatrix[0][1], cg_ViewMatrix[1][1], cg_ViewMatrix[2][1]);
        vec3 eye = -(transpose(mat3(cg_ViewMatrix)) * cg_ViewMatrix[3].xyz);
        int i = max(n, 0);
        float seed = CG_PARTICLE_SEED(i), spin = CG_PARTICLE_SPIN(i);
        vec4 m = fx_hash41(seed * 97.0);
        float size = n < 0 ? 0.0 : CG_PARTICLE_SIZE(i) * CG_OBJECT_CUSTOM0.z, keep;
        vec3 centre = fx_particle_toward_eye(origin + CG_PARTICLE_POSITION(i), eye, _CameraOffset * size, keep);
        size *= keep;
        // From chunks to slivers.
        vec2 extent = vec2(size, size * mix(0.25, 1.0, m.x * m.x));
        vec3 world = fx_particle_corner(centre, corner, extent, spin, right, up);
        o.world = world;
        // where on it, its shape seed, how spiky its edge is
        o.speck = vec4(corner, seed * 40.0, mix(1.2, 4.0, m.y));
        // how far its tumble has tilted it out of the quad's plane, its tone, its shine
        o.tone = vec4(spin * 0.8 + m.w * 6.2831853, mix(0.7, 1.35, m.z), m.w * m.w, 0.0);
        // the quad's own axes in the world, turned by its spin as fx_particle_corner turns its corners
        float c = cos(spin), s = sin(spin);
        o.across = right * c + up * s;
        o.along = up * c - right * s;
        o.facing = normalize(eye - centre);
        o.opacity = CG_PARTICLE_OPACITY(i);
        o.light = CG_PARTICLE_LIGHT(i);
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(world, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        cg_Light = i.light;
        vec2 q = i.speck.xy;
        float r = length(q);
        // A ragged chunk: its edge wanders with the angle, lumpy at low frequency and spiky at high.
        vec2 around = q / max(r, 1.0e-4);
        float edge = 0.5 + 0.48 * fx_value_noise(vec3(around * i.speck.w, i.speck.z));
        float aa = fwidth(r) + 1.0e-3;
        // Solid: it writes depth wherever it covers half a pixel ("Depth" = "Clip").
        float cover = 1.0 - smoothstep(edge - aa, edge + aa, r);
        cg_Clip(cover);
        float alpha = cover * i.opacity * CG_OBJECT_CUSTOM2.a;

        // A lumpy dome inside the outline, roughened by noise into facets.
        float inside = clamp(r / max(edge, 1.0e-3), 0.0, 1.0);
        float dome = sqrt(max(1.0 - inside * inside, 0.0));
        vec3 at = vec3(q * 3.0, i.speck.z + 11.0);
        vec2 bump = vec2(fx_value_noise(at + vec3(0.08, 0.0, 0.0)) - fx_value_noise(at - vec3(0.08, 0.0, 0.0)),
                         fx_value_noise(at + vec3(0.0, 0.08, 0.0)) - fx_value_noise(at - vec3(0.0, 0.08, 0.0))) / 0.16;
        vec3 local = normalize(vec3(q / max(edge, 1.0e-3) + bump * 0.35, dome + 0.3));
        // Tumbling: tilted about its own long axis as it spins, so the light moves across it.
        float t = i.tone.x, ct = cos(t), st = sin(t);
        local = vec3(local.x, local.y * ct - local.z * st, local.y * st + local.z * ct);
        local.z = abs(local.z);
        vec3 normal = normalize(local.x * i.across + local.y * i.along + local.z * i.facing);

        vec3 sun = CG_SUN_DIRECTION;
        vec3 key = dot(sun, sun) > 0.01 ? normalize(sun) : normalize(vec3(0.35, 0.85, 0.25));
        float diffuse = max(dot(normal, key), 0.0);
        float sky = 0.3 + 0.2 * normal.y;
        float occlusion = mix(0.55, 1.0, dome);
        float glint = pow(max(dot(reflect(-key, normal), i.facing), 0.0), 24.0) * i.tone.z;
        vec3 rock = CG_OBJECT_CUSTOM2.rgb * i.tone.y * (sky + 0.95 * diffuse) * occlusion + vec3(0.35 * glint);
        fragColor = vec4(rock * alpha, alpha);
    }
}
