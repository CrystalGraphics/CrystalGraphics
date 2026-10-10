// Dust on the ground: a soft blob of dust resting on the floor under it, with no edge of its own, so the puffs of a
// surge overlap into one haze rather than reading one by one. A broad, gentle noise boils through it and, as it ages,
// eats holes where it is thin, until it breaks into wisps; it is densest low and thins upward. Shaded as a smooth dome:
// lit from the open sky and a key light above beside the eye (the billows' own), dark toward the ground and through
// its own thickness on the side away from the light, and warmed on the side facing the blast it rolls away from while
// young. A CgVfxQuads quad placed from its particle record (CgVfxFrame.particles, QUADS), facing the camera, _Aspect as
// tall as it is wide. Where the record has a floor (CG_PARTICLE_FLOOR: kinds with Ground) it stands on it, never sunk
// below _Sit of its height, and fades into it by its height over it; else it fades into what is behind it
// (_SoftDistance), as it does against anything else. Colour A is the dust, A's alpha a strength; colour B the blast's
// light, or none.
#type none
#pragma cg_use particle
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_ribbon.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_particle.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

Properties {
    _Noise ("Noise", sampler3D) = "cg_noise"
    _SoftDistance ("Fades into what is behind it over this many blocks; 0 for none", float) = 0.3
    _Aspect ("Its height, share of its width", float) = 0.8
    _Sit    ("Least height of its centre over the floor, share of its height", float) = 0.15
    _Fade   ("Blocks over the floor it fades in across", float) = 0.12
    _Falloff ("How fast it thins from its middle to its edge", float) = 3.0
    _Lumps  ("How far the noise moves its density, either way", float) = 0.8
    _Detail ("Noise features across it", float) = 1.1
    _Boil   ("How far the noise turns over its life, in features", float) = 0.8
    _Rise   ("How far the noise drifts up through it over its life, in features", float) = 0.5
    _Wisps  ("How much it thins into holes as it ages", float) = 0.35
    _Thin   ("How much thinner its top is than its foot", float) = 0.5
    _Sky    ("Light from the open sky", float) = 0.8
    _Under  ("Light reaching its underside, share of the sky's", float) = 0.5
    _Key    ("Light from the key above beside the eye", float) = 0.5
    _Absorb ("How dark its thick middle turns on the side away from the light", float) = 1.0
    _Glow   ("The blast's light over it while young", float) = 0.9
    _GlowFor ("Share of its life the blast's light lasts", float) = 0.45
    _NearFrom ("Blocks from the eye where it starts dissolving", float) = 5.0
    _NearTo  ("Blocks from the eye where it is gone", float) = 1.2
}

// where on it, its seed, how far through its life; its height over the floor (huge for none), its half height;
// its velocity's heading, its speed
struct v2f { vec3 world; vec4 puff; float opacity; vec2 light; vec2 ground; vec3 motion; };

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
        int k = max(n, 0);
        float size = n < 0 ? 0.0 : CG_PARTICLE_SIZE(k) * CG_OBJECT_CUSTOM0.z;
        vec2 extent = vec2(size, size * _Aspect);
        vec3 centre = origin + CG_PARTICLE_POSITION(k);
        // Standing on its floor: a puff held low by the ground is lifted so its foot, not its middle, meets it.
        vec2 floorAt = CG_PARTICLE_FLOOR(k);
        bool floored = floorAt.y > 0.5;
        float raise = floored ? max(extent.y * _Sit - floorAt.x, 0.0) : 0.0;
        centre.y += raise;
        vec3 world = fx_particle_corner(centre, corner, extent, CG_PARTICLE_SPIN(k), right, up);
        o.world = world;
        o.puff = vec4(corner, CG_PARTICLE_SEED(k), CG_PARTICLE_PROGRESS(k));
        o.opacity = CG_PARTICLE_OPACITY(k);
        o.light = CG_PARTICLE_LIGHT(k);
        o.ground = vec2(floored ? floorAt.x + raise + (world.y - centre.y) : 1.0e4, extent.y);
        vec3 velocity = CG_PARTICLE_VELOCITY(k);
        float speed = length(velocity.xz);
        o.motion = vec3(speed > 1.0e-3 ? velocity.xz / speed : vec2(0.0), speed);
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(world, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        cg_Light = i.light;
        vec2 q = i.puff.xy;
        float seed = i.puff.z, life = i.puff.w, r2 = dot(q, q);
        // No edge: thinning from its middle, and gone before the quad's.
        float falloff = exp(-_Falloff * r2) * (1.0 - smoothstep(0.6, 1.0, sqrt(r2)));
        float n = fx_fbm(vec3(q * _Detail, 0.0) + vec3(seed * 37.0, seed * 23.0 - life * _Rise, seed * 11.0 + life * _Boil), 2);
        float density = falloff * clamp(1.0 + n * _Lumps, 0.0, 1.6);
        // Holes opening where it is thin as it ages.
        density = max(density - life * _Wisps * (0.6 - n), 0.0);
        bool floored = i.ground.x < 1.0e3;
        float above = i.ground.x, tall = i.ground.y;
        density *= floored ? mix(1.0, exp(-max(above, 0.0) / max(tall * 1.6, 1.0e-3)), _Thin) : 1.0 - 0.5 * _Thin;
        float soft = fx_particle_soft(CG_SCENE_EYE_DEPTH(gl_FragCoord.xy / CG_RESOLUTION),
                cg_LinearEyeDepth(gl_FragCoord.z), _SoftDistance);
        float settle = floored ? smoothstep(0.0, _Fade, above) : 1.0;
        float near = smoothstep(_NearTo, _NearFrom, distance(FX_CAMERA, i.world));
        float alpha = min(density, 1.0) * i.opacity * CG_OBJECT_CUSTOM2.a * soft * settle * near;
        if (alpha < 0.003) discard;

        vec3 right = vec3(cg_ViewMatrix[0][0], cg_ViewMatrix[1][0], cg_ViewMatrix[2][0]);
        vec3 up = vec3(cg_ViewMatrix[0][1], cg_ViewMatrix[1][1], cg_ViewMatrix[2][1]);
        vec3 back = vec3(cg_ViewMatrix[0][2], cg_ViewMatrix[1][2], cg_ViewMatrix[2][2]);
        float z = sqrt(max(1.0 - r2, 0.0));
        vec3 normal = normalize(right * q.x + up * q.y + back * z);
        vec3 toEye = normalize(FX_CAMERA - i.world);
        vec3 key = normalize(vec3(0.0, 0.8, 0.0) + toEye * 0.45 - right * 0.35);
        float wrap = clamp((dot(normal, key) + 0.5) / 1.5, 0.0, 1.0);
        // Light through its own thickness: the thick middle darkens on the side away from the light.
        float through = exp(-_Absorb * min(density, 1.0) * z * (1.0 - wrap));
        float sky = mix(_Under, 1.0, normal.y * 0.5 + 0.5);
        // The ground shades its foot, the shade climbing a little way up it.
        float shade = floored ? mix(0.65, 1.0, smoothstep(0.0, tall * 1.2, above)) : 1.0;
        float value = 0.97 + 0.06 * fract(seed * 7.13) + 0.04 * n;
        vec3 col = CG_OBJECT_CUSTOM2.rgb * (_Sky * sky + _Key * wrap * through) * shade * value;
        // The blast's light, from behind it along its motion, on the side facing the blast, while young.
        vec3 toBlast = vec3(-i.motion.x, 0.0, -i.motion.y);
        float faces = dot(normal, toBlast) * 0.5 + 0.5;
        float young = 1.0 - smoothstep(0.0, _GlowFor, life);
        col += CG_OBJECT_CUSTOM3.rgb * faces * faces * young * _Glow;
        fragColor = vec4(col * alpha, alpha);
    }
}
