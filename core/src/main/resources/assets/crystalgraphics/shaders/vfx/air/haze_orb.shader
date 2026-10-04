// Heat haze round an energy orb (a wave's charge ball, its contact orb, a blast's heart): a sheath of bent air past the
// orb's glow, where the scene still shows through, rippling out of the orb in rings broken up by rising noise, its
// strongest bend split slightly by colour. The bend is a share of the screen's height, held to _Hold of the sheath's
// size on screen, so a far orb keeps it. Drawn on CgVfxFrame.mesh's sphere's far wall, its entry found analytically,
// so it bends from inside it too; a Distortion pass (ORDER_DISTORTION), so the sharp layers stay unbent. CG_OBJECT_CUSTOM0.x
// is the layer's radius and .y its parameter, the orb's radius in the same units, so the orb fills y/x of the sphere and
// is left unbent; .zw the effect's age and seed. CG_OBJECT_CUSTOM1.z an intensity, taken at most 1. Reads depth.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_depth.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_haze.glsl"

// The bend reaches _Strength times about 1.1 of noise, times 1 + _Fringe for red: under 0.02 of the height.
Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Transparent"

Properties {
    _Strength ("Bend at full intensity, share of the screen's height", float) = 0.014
    _Hold     ("The bend's most, as a share of the sheath's height on screen", float) = 0.12
    _Peak     ("Where the bend is strongest, in the orb's radii: just past its glow", float) = 2.6
    _Flow     ("Share of the bend in rings rippling outward; the rest shimmers", float) = 0.7
    _Rings    ("Rings per orb radius", float) = 0.8
    _Pulse    ("Rings leaving the orb a second", float) = 1.6
    _Fringe   ("How much more red bends than green, and blue less", float) = 0.18
    _Scale    ("Shimmer frequency, a block", float) = 1.6
    _Rise     ("Rising speed, blocks a second", float) = 0.7
    _Noise ("Noise", sampler3D) = "cg_noise"
}

struct v2f { vec3 world; };

Pass {
    Tags { "LightMode" = "Forward" }
    // Writes nothing, so the world renderer skips it: the Distortion pass is the haze.
    RenderState {
        ColorMask 0
        Blend OFF
        DepthTest ALWAYS
        DepthWrite OFF
        Cull FRONT
    }

    void vertex(out v2f o) {
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        o.world = world.xyz;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        fragColor = vec4(0.0);
    }
}

Pass {
    Tags { "LightMode" = "Distortion" }
    // Its own depth fade, from the scene's distance.
    RenderState {
        DepthTest ALWAYS
        DepthWrite OFF
        Cull FRONT
    }

    void fragment(in v2f i, out vec4 distortion) {
        vec3 eye = FX_CAMERA;
        vec3 ray = normalize(i.world - eye);
        vec3 centre = CG_OBJECT_TO_WORLD[3].xyz;
        float radius = length(CG_OBJECT_TO_WORLD[0].xyz);
        float age = CG_OBJECT_CUSTOM0.z, seed = CG_OBJECT_CUSTOM0.w;
        // The orb's radius in blocks. Where the ray enters (0 from inside). Its strength is the air's at the point it sees
        // nearest the centre (the eye itself, looking out from inside), in orb radii; its shimmer is sampled there from
        // outside, and halfway to where it leaves from inside, so the view from inside does not move as one sheet.
        float orb = max(radius * CG_OBJECT_CUSTOM0.y / max(CG_OBJECT_CUSTOM0.x, 1.0e-4), 1.0e-4);
        float along = dot(centre - eye, ray);
        vec3 line = eye + ray * along - centre;
        float chord = sqrt(max(radius * radius - dot(line, line), 0.0));
        float enter = max(along - chord, 0.0);
        vec3 nearest = eye + ray * max(along, 0.0) - centre;
        vec3 spot = eye + ray * max(along, 0.5 * (along + chord)) - centre;
        float u = length(nearest) / orb, rim = radius / orb;
        // Hot air rises: the sheath reaches farther above than below, its edge torn by noise rising with the air.
        vec3 plume = vec3(nearest.x, nearest.y * mix(1.5, 0.85, smoothstep(-0.4, 0.4, nearest.y / radius)), nearest.z) / orb;
        vec3 drift = spot * _Scale * 0.45 + vec3(0.0, -age * _Rise * _Scale * 0.45, 0.0) + seed * 7.0;
        float tear = fx_noise(drift) * 0.7 + fx_noise(drift * 2.3 + 5.1) * 0.3;
        // Unbent through the orb, strongest past its glow, gone by the rim.
        float sheath = smoothstep(1.1, _Peak, u)
                * (1.0 - smoothstep(_Peak, rim, length(plume) + tear * 0.25 * (rim - _Peak)))
                * (1.0 - smoothstep(0.85 * rim, rim, u));
        float soft = smoothstep(0.0, 1.5, FX_SCENE_DISTANCE(ray) - enter);
        float strength = sheath * soft * min(CG_OBJECT_CUSTOM1.z, 1.0);
        if (strength < 0.002) discard;
        // Rings rippling out of the orb push the scene along the screen's radial; the rest is rising shimmer.
        float ring = sin((u * _Rings - age * _Pulse + tear * 0.6) * 6.28318531);
        vec2 flow = FX_HAZE_SCREEN_DIR(spot) * ring * (0.6 + 0.4 * fx_noise(drift * 0.7 + 3.3));
        vec2 wobble = mix(fx_heat((centre + spot) * _Scale, age, _Rise * _Scale, seed), flow, _Flow);
        float hold = FX_HAZE_HOLD(_Strength, radius, distance(eye, centre), _Hold);
        vec2 offset = wobble * _Strength * hold * strength * vec2(CG_RESOLUTION.y / CG_RESOLUTION.x, 1.0);
        float fade = smoothstep(0.0, 0.1, strength);
        distortion = CG_DISTORTION(offset * fade, _Fringe * fade, FX_EYE_DEPTH(ray, enter));
    }
}
