// Heat haze along a beam: a sheath of bent air round a path, past the body's glow where the scene still shows through,
// rippling forward along the beam and torn at its edge by rising noise, its strongest bend split slightly by colour.
// The bend is a share of the screen's height, held to _Hold of the sheath's width on screen, so a far beam keeps it. A
// tube layer: fx_tube.glsl's contract (_FxPath, CG_OBJECT_CUSTOM0..1 as CgVfxTube writes them), its radius the layer's
// times the ring's, capped at the layer's parameter in blocks (0 for no cap). Drawn on the tube's far wall, where the
// ray meets it found analytically, so it bends from inside it too; after the soft layers and before the sharp ones
// (ORDER_DISTORTION). A ray through the beam itself, within _Core times its ring's radius of the path,
// is left unbent. Reads cg_SceneColor and depth.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_tube.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_depth.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_haze.glsl"

// The bend reaches _Strength times about 1.1 of noise, times 1 + _Fringe for red: under 0.016 of the height.
Tags { "RenderType" = "Transparent" "SceneColorMargin" = "0.02" }
Queue = "Transparent"

Properties {
    _FxPath   ("Path rings", sampler2D) = "black"
    _Strength ("Bend at full intensity, share of the screen's height", float) = 0.012
    _Hold     ("The bend's most, as a share of the sheath's width on screen", float) = 0.12
    _Core     ("The beam's visible reach, in ring radii: left unbent", float) = 1
    _Peak     ("Where the bend is strongest, in ring radii: just past the body's glow", float) = 3.2
    _Flow     ("Share of the bend in ripples racing along the beam; the rest shimmers", float) = 0.6
    _Rings    ("Ripples a block along the beam", float) = 0.6
    _Pulse    ("Ripples passing a point a second, toward the head", float) = 3
    _Fringe   ("How much more red bends than green, and blue less", float) = 0.15
    _Scale    ("Shimmer frequency, a block", float) = 1.6
    _Rise     ("Rising speed, blocks a second", float) = 0.7
}

// reach: the ring's radius, the sheath's radius, blocks along the path
struct v2f { vec3 world; vec3 axis; vec3 tangent; vec2 time; vec3 reach; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA
        DepthTest ALWAYS
        DepthWrite OFF
        Cull FRONT
    }

    void vertex(out v2f o) {
        float scale = CG_OBJECT_CUSTOM0.z, cap = CG_OBJECT_CUSTOM0.w;
        FxTubeVertex v = fx_tube_vertex(_FxPath, int(CG_OBJECT_CUSTOM0.x + 0.5), int(CG_OBJECT_CUSTOM0.y + 0.5),
                                        cg_TexCoord0, scale, 0.0);
        // Capped below the body's throb, so the haze holds still while the beam pulses; the taper at its ends survives.
        float full = max(v.ring.radius * scale, 1.0e-4);
        float edge = cap > 0.0 ? min(full, cap) : full;
        v.position = v.ring.position + (v.position - v.ring.position) * (edge / full);
        vec3 origin = CG_OBJECT_TO_WORLD[3].xyz - CG_OBJECT_CUSTOM1.xyz;
        o.world = origin + v.position;
        o.axis = origin + v.ring.position;
        o.tangent = v.ring.tangent;
        o.reach = vec3(v.ring.radius, edge, v.ring.arc);
        // the effect's age and seed
        o.time = vec2(v.header.w, v.header.z);
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(o.world, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec3 eye = FX_CAMERA;
        vec3 ray = normalize(i.world - eye);
        vec3 t = normalize(i.tangent);
        float age = i.time.x, seed = i.time.y;
        float ring = i.reach.x, edge = i.reach.y;
        // Where the ray enters the sheath (0 from inside). Its strength is the air's at the point it sees nearest the
        // path (the eye itself, looking out from inside); its shimmer is sampled there from outside, and halfway to
        // where it leaves from inside, so the view from inside does not move as one sheet.
        vec4 q = fx_ray_axis(eye, ray, i.axis, t);
        float chord = sqrt(max(edge * edge - q.y * q.y, 0.0)) / max(q.w, 0.05);
        float enter = max(q.x - chord, 0.0);
        vec3 seen = eye + ray * max(q.x, 0.0) - i.axis;
        float passes = length(seen - t * dot(seen, t));
        vec3 at = eye + ray * max(q.x, 0.5 * (q.x + chord));
        vec3 rel = at - i.axis;
        float arc = i.reach.z + dot(rel, t);
        vec3 drift = at * _Scale * 0.45 + vec3(0.0, -age * _Rise * _Scale * 0.45, 0.0) + seed * 7.0;
        float tear = fx_noise(drift) * 0.7 + fx_noise(drift * 2.3 + 5.1) * 0.3;
        // Unbent through the beam, strongest past its glow, torn away toward the sheath's edge.
        float peak = min(_Peak * ring, edge * 0.7);
        float sheath = smoothstep(ring * _Core * 0.9, peak, passes)
                * (1.0 - smoothstep(peak, edge, passes + tear * 0.25 * (edge - peak)));
        float soft = smoothstep(0.0, 1.5, FX_SCENE_DISTANCE(ray) - enter);
        float strength = sheath * soft;
        if (strength < 0.002) discard;
        // Ripples racing toward the head push the scene along the beam on screen; the rest is rising shimmer.
        float ripple = sin((arc * _Rings - age * _Pulse + tear * 0.5) * 6.28318531);
        vec2 flow = FX_HAZE_SCREEN_DIR(t) * ripple * (0.6 + 0.4 * fx_noise(drift * 0.7 + 3.3));
        vec2 wobble = mix(fx_heat(at * _Scale, age, _Rise * _Scale, seed), flow, _Flow);
        float hold = FX_HAZE_HOLD(_Strength, edge, distance(eye, at), _Hold);
        vec2 uv = gl_FragCoord.xy / CG_RESOLUTION;
        vec2 offset = wobble * _Strength * hold * strength * vec2(CG_RESOLUTION.y / CG_RESOLUTION.x, 1.0);
        fragColor = vec4(FX_HAZE_SCENE(uv, offset, _Fringe), smoothstep(0.0, 0.1, strength));
    }
}
