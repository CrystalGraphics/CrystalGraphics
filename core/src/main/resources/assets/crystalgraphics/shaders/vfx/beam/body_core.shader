// An energy wave's core: a hard-edged volume, white where the ray crosses most of it and tinted at its rim, with hot
// knots streaming forward. Analytic against the view ray, on the hull's far wall, so it is right from any side, inside
// and end-on; the opaque scene hides it by depth. Colour A is the core, colour B its rim, A's alpha a strength. The
// layer's radius is the hull's; the core is 0.8 of it. CgEnergyWave.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_volume.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_depth.glsl"

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
Queue = "Transparent"

Properties {
    _FxPath  ("Path rings", sampler2D) = "black"
    _Flow    ("Flow, blocks a second", float) = 24.0
    _Density ("How fast it turns white", float) = 3.2
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
        // arc, the core's radius, the effect's age, the path's length
        o.ring = vec4(v.ring.arc, v.ring.radius * CG_OBJECT_CUSTOM0.z * 0.8, v.header.w, v.header.y);
        o.pulse = v.ring.intensity;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(o.world, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec3 eye = FX_CAMERA;
        vec3 ray = normalize(i.world - eye);
        vec4 q = fx_capsule(eye, ray, i.axis, normalize(i.tangent), i.ring.x, i.ring.w);
        // A boiling edge: the radius heaves along the beam, faster than the flow.
        float boil = fx_noise(vec3((q.w - i.ring.z * _Flow * 1.6) * 0.7, i.ring.z * 3.0, 2.5));
        float r = max(i.ring.y * (1.0 + 0.22 * boil), 1.0e-4);
        float thickness = fx_core_thickness(q.y, r, q.z);
        if (thickness <= 0.0) discard;
        float scene = FX_SCENE_DISTANCE(ray);
        float seen = smoothstep(-0.5 * r, 0.5 * r, scene - q.x);
        float knots = fx_noise(vec3((q.w - i.ring.z * _Flow) * 0.45, q.y / r * 0.8, i.ring.z * 0.7));
        float white = 1.0 - exp(-thickness * _Density * (0.8 + 0.35 * knots));
        vec3 col = mix(CG_OBJECT_CUSTOM3.rgb, CG_OBJECT_CUSTOM2.rgb, smoothstep(0.1, 0.65, thickness));
        float surge = fx_flicker(i.ring.z - q.w * 0.02, 0.37);
        fragColor = vec4(col * white * 1.5 * i.pulse * surge * CG_OBJECT_CUSTOM2.a * seen, 1.0);
    }
}

// Its light again, into the world's bloom: the Forward pass's code and state, blurred over the scene.
Pass { Tags { "LightMode" = "Emissive" } }
