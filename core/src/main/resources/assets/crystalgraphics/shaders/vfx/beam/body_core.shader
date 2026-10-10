// An energy wave's core: a hard-edged volume, white where the ray crosses most of it and tinted at its rim, with hot
// knots streaming forward. Analytic against the view ray, on the hull's far wall, so it is right from any side, inside
// and end-on; the opaque scene hides it by depth. It heaves along the beam and swells where a surge passes (the ring's
// intensity), so it surges harder than the shell. Colour A is the white, colour B the saturated band across its outer
// part (_Band on), A's alpha a strength. The
// layer's radius is the hull's; the core is _Core of it at rest, never past the hull. CgEnergyWave.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_volume.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_depth.glsl"

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
Queue = "Transparent"

Properties {
    _FxPath  ("Path rings", sampler2D) = "black"
    _Flow    ("Flow, blocks a second", float) = 24.0
    _Density ("How fast it turns white", float) = 3.2
    _Core    ("The core at rest, share of the hull", float) = 0.62
    _Heave   ("Slow swell along the beam, share of the core", float) = 0.2
    _Surge   ("Extra swell where a surge passes, share of the core", float) = 0.3
    _Band    ("Where the coloured band starts, share of the core's radius", float) = 0.5
    _Bright  ("Brightness of the white: past white, so it reads white", float) = 2.2
    _BandLight ("Brightness of the band: under white with the shell over it, so it shows as authored", float) = 0.8
    _Noise ("Noise", sampler3D) = "cg_noise"
}

struct v2f { vec3 world; vec3 axis; vec3 tangent; vec4 ring; float pulse; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE
        DepthTest ALWAYS
        DepthWrite OFF
        Cull FRONT
    }

    void vertex(out v2f o) {
        FxTubeVertex v = fx_tube_vertex(_FxPath, int(CG_OBJECT_CUSTOM0.x + 0.5), int(CG_OBJECT_CUSTOM0.y + 0.5),
                                        cg_TexCoord0, CG_OBJECT_CUSTOM0.z, 1.0);
        vec3 origin = CG_OBJECT_TO_WORLD[3].xyz - CG_OBJECT_CUSTOM1.xyz;
        o.world = origin + v.position;
        o.axis = origin + v.ring.position;
        o.tangent = v.ring.tangent;
        // arc, the hull's radius, the effect's age, the path's length
        o.ring = vec4(v.ring.arc, v.ring.radius * CG_OBJECT_CUSTOM0.z, v.header.w, v.header.y);
        o.pulse = v.ring.intensity;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(o.world, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec3 eye = FX_CAMERA;
        vec3 ray = normalize(i.world - eye);
        vec4 q = fx_capsule(eye, ray, i.axis, normalize(i.tangent), i.ring.x, i.ring.w);
        // A boiling edge faster than the flow, a slow heave riding it, and a surge's extra swell.
        float boil = fx_noise(vec3((q.w - i.ring.z * _Flow * 1.6) * 0.7, 2.5, 0.0) + cg_noise_time(i.ring.z * 3.0));
        float heave = fx_noise(vec3((q.w - i.ring.z * _Flow) * 0.15, 6.5, i.ring.z * 0.4));
        float swell = 1.0 + 0.15 * boil + _Heave * heave + _Surge * clamp(i.pulse - 1.0, 0.0, 1.0);
        float r = clamp(i.ring.y * _Core * swell, 1.0e-4, i.ring.y * 0.97);
        float thickness = fx_core_thickness(q.y, r, q.z);
        if (thickness <= 0.0) discard;
        float scene = FX_SCENE_DISTANCE(ray);
        float seen = smoothstep(-0.5 * r, 0.5 * r, scene - q.x);
        float d = q.y / r;
        float knots = fx_noise(vec3((q.w - i.ring.z * _Flow) * 0.45, d * 0.8, i.ring.z * 0.7));
        float white = 1.0 - exp(-thickness * _Density * (0.8 + 0.35 * knots));
        // A saturated band across the outer part, its inner edge wavering with the knots; full strength to the edge.
        float band = smoothstep(_Band - 0.1 * knots, 0.95, d);
        vec3 col = mix(CG_OBJECT_CUSTOM2.rgb * white * _Bright, CG_OBJECT_CUSTOM3.rgb * _BandLight, band);
        float edge = 1.0 - smoothstep(0.9, 1.0, d);
        float surge = fx_flicker(i.ring.z - q.w * 0.02, 0.37);
        fragColor = vec4(col * edge * i.pulse * surge * CG_OBJECT_CUSTOM2.a * seen, 1.0);
    }
}

// Its light again, into the world's bloom: the Forward pass's code and state, blurred over the scene.
Pass { Tags { "LightMode" = "Emissive" } }
