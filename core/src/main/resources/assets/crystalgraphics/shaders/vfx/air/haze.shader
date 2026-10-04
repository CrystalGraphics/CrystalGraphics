// Heat haze round something hot: the scene behind a sphere shimmering in rising, gusting noise (fx_heat), strongest
// where the ray passes nearest the centre and gone at the silhouette and where the sphere meets the scene. Its outline
// is a plume, not the sphere's disc: it reaches the rim above and fades sooner below and to the sides, and its edge is
// torn by noise rising with the air, so it has no shape of its own.
// Drawn on CgVfxFrame.mesh's sphere's far wall, its entry found analytically, so it bends from inside it too; a
// Distortion pass (ORDER_DISTORTION), so the sharp layers stay unbent. A ray through the hot thing itself is left unbent, so the haze shimmers round it and never
// warps it: CG_OBJECT_CUSTOM0.y, the layer's parameter, is the share of the unit sphere it fills,
// 0 for nothing inside; .x is the layer's radius.
// CG_OBJECT_CUSTOM0.zw are the effect's age and seed, CG_OBJECT_CUSTOM1.z an intensity. Reads depth.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_depth.glsl"

// The bend reaches _Strength times the intensity times the noise, at most about 0.08 of the height.
Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Transparent"

Properties {
    _Strength ("Shimmer at full intensity, share of the screen's height", float) = 0.04
    _Reference ("Within this many blocks the bend is _Strength; farther it shrinks as the haze does on screen", float) = 6
    _Scale    ("Shimmer frequency, a block", float) = 1.6
    _Rise     ("Rising speed, blocks a second", float) = 0.7
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
        Blend ONE ONE
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
        // Where the ray enters (0 from inside). Its strength is the air's at the point it sees nearest the centre (the
        // eye itself, looking out from inside); its shimmer is sampled there from outside, and halfway to where it
        // leaves from inside, so the view from inside does not move as one sheet.
        float along = dot(centre - eye, ray);
        vec3 line = eye + ray * along - centre;
        float chord = sqrt(max(radius * radius - dot(line, line), 0.0));
        float enter = max(along - chord, 0.0);
        vec3 nearest = eye + ray * max(along, 0.0) - centre;
        vec3 spot = eye + ray * max(along, 0.5 * (along + chord)) - centre;
        vec3 d = nearest / radius;
        float passes = length(d);
        // Hot air rises: squeezed below the centre, let out above.
        vec3 plume = vec3(d.x, d.y * mix(1.7, 0.8, smoothstep(-0.4, 0.4, d.y)), d.z);
        vec3 drift = spot * _Scale * 0.45 + vec3(0.0, -age * _Rise * _Scale * 0.45, 0.0) + seed * 7.0;
        float tear = fx_noise(drift) * 0.7 + fx_noise(drift * 2.3 + 5.1) * 0.3;
        // Full out to half the radius, so a haze round a hot body keeps its strength.
        float body = (1.0 - smoothstep(0.5, 1.0, length(plume) + tear * 0.25)) * (1.0 - smoothstep(0.85, 1.0, passes));
        // The hot thing itself: a ray through it stays unbent.
        float core = CG_OBJECT_CUSTOM0.y / max(CG_OBJECT_CUSTOM0.x, 1.0e-4);
        if (core > 0.0) body *= smoothstep(core * 0.8, core * 1.3, passes);
        float soft = smoothstep(0.0, 1.5, FX_SCENE_DISTANCE(ray) - enter);
        float strength = body * soft * CG_OBJECT_CUSTOM1.z;
        if (strength < 0.002) discard;
        vec2 wobble = fx_heat((centre + spot) * _Scale, age, _Rise * _Scale, seed);
        // Perspective: a far haze moves the scene behind it as little as it covers.
        float far = min(1.0, _Reference / max(enter, 1.0e-3));
        vec2 offset = wobble * _Strength * far * strength * vec2(CG_RESOLUTION.y / CG_RESOLUTION.x, 1.0);
        float fade = smoothstep(0.0, 0.1, strength);
        distortion = vec4(offset * fade, 0.0, fade);
    }
}
