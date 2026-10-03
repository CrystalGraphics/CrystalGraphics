// An energy orb's core (a wave's head, its charge ball): a white-hot, hard-edged ellipsoid evaluated per pixel against
// the view ray, so it holds from any side and from inside; the opaque scene hides it by depth. Drawn on CgVfxFrame.mesh's
// sphere at the layer's radius, the core 0.8 of it; CG_OBJECT_CUSTOM1.z an intensity. Colour A is the core, colour B its
// rim, A's alpha a strength. CgEnergyWave.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_volume.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_depth.glsl"

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
Queue = "Transparent"

Properties {
    _Density ("How fast it turns white", float) = 3.4
}

struct v2f { vec3 world; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE
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
        vec3 eye = FX_CAMERA;
        vec3 ray = normalize(i.world - eye);
        vec3 centre = CG_OBJECT_TO_WORLD[3].xyz;
        // Into the hull's own space, where it is the unit sphere and the core a sphere of 0.8.
        mat3 toLocal = inverse(mat3(CG_OBJECT_TO_WORLD));
        vec3 o = toLocal * (eye - centre), d = toLocal * ray;
        float along = -dot(o, d) / dot(d, d);
        float b = length(o + d * along);
        const float core = 0.8;
        // Its share of the core's diameter, lengthened where the ray runs along the ellipsoid's long axis.
        float hullWidth = length(CG_OBJECT_TO_WORLD[0].xyz);
        float thickness = sqrt(max(1.0 - b * b / (core * core), 0.0)) / (length(d) * hullWidth);
        if (thickness <= 0.0) discard;
        float seen = smoothstep(-0.5 * hullWidth, 0.5 * hullWidth, FX_SCENE_DISTANCE(ray) - along);
        float age = CG_OBJECT_CUSTOM0.z;
        float flicker = 0.85 + 0.3 * fx_noise(vec3((o + d * along) * 2.2) + vec3(0.0, 0.0, age * 3.1));
        float white = 1.0 - exp(-thickness * _Density * flicker);
        vec3 col = mix(CG_OBJECT_CUSTOM3.rgb, CG_OBJECT_CUSTOM2.rgb, smoothstep(0.1, 0.6, thickness));
        fragColor = vec4(col * white * 1.6 * CG_OBJECT_CUSTOM2.a * CG_OBJECT_CUSTOM1.z * seen, 1.0);
    }
}

// Its light again, into the world's bloom: the Forward pass's code and state, blurred over the scene.
Pass { Tags { "LightMode" = "Emissive" } }
