// The light an energy wave's body casts on the scene around it: each ring a point light on the surfaces nearby, so the
// ground under the beam floods with its colour. A volume layer (CgVfxLayer.volume): each chunk lights from only the
// rings it owns, on a sphere whose far wall covers each pixel once. Colour A is the light, its alpha a strength. The
// layer's radius is how far the light reaches, in the rings' radii. CgEnergyWave.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_tube.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_light.glsl"

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
Queue = "Transparent"

Properties {
    _FxPath   ("Path rings", sampler2D) = "black"
    _Strength    ("Brightness", float) = 1.6
    _StrengthHdr ("Brightness under HDR", float) = 0.8
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
        vec3 surface, normal;
        fx_scene_surface(eye, ray, FX_SCENE_DISTANCE(ray), surface, normal);
        vec3 centre = CG_OBJECT_TO_WORLD[3].xyz;
        if (distance(surface, centre) > length(CG_OBJECT_TO_WORLD[0].xyz)) discard;
        int row = int(CG_OBJECT_CUSTOM0.x + 0.5), first = int(CG_OBJECT_CUSTOM0.y + 0.5);
        int owned = int(CG_OBJECT_CUSTOM1.w + 0.5);
        vec4 header = fx_path_header(_FxPath, row);
        float spacing = header.y / max(header.x - 1.0, 1.0);
        vec3 origin = centre - CG_OBJECT_CUSTOM1.xyz;
        float light = 0.0;
        for (int k = 0; k < owned; k++) {
            vec4 ring = texelFetch(_FxPath, ivec2(1 + (first + k) * 3, row), 0);
            float size = max(ring.w, 1.0e-3);
            light += spacing / size * fx_point_light(surface, normal, origin + ring.xyz, size, size * CG_OBJECT_CUSTOM0.z);
        }
        fragColor = vec4(CG_OBJECT_CUSTOM2.rgb * CG_OBJECT_CUSTOM2.a * light * CG_HDR(_Strength, _StrengthHdr), 1.0);
    }
}
