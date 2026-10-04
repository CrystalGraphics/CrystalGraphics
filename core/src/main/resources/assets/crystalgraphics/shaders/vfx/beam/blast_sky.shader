// The sky answering a blast: the air over it tinted, brightest toward the blast, and the clouds' undersides lit where
// they pass over it, a band at the cloud height fading with distance from the point above the blast. Where the scene
// sits on the cloud plane (real clouds) the band is strongest; where only sky lies beyond it, noise stands in for the
// clouds. Brighter at night. Drawn on CgVfxFrame.mesh's sphere's far wall, large enough to hold the camera.
// CG_OBJECT_CUSTOM1: x the cloud height (a world y), y how far from the blast the clouds are lit, z an intensity. Colour A
// is the light, its alpha a strength. CgEnergyWave.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_depth.glsl"

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Transparent"

Properties {
    _Sky    ("Tint over the whole sky, share of the light", float) = 0.12
    _Toward ("Glow toward the blast, share of the light", float) = 0.6
    _Clouds ("Cloud undersides, share of the light", float) = 1.6
    _Slab   ("Cloud thickness, blocks", float) = 6.0
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
        float scene = FX_SCENE_DISTANCE(ray);
        float far = cg_LinearEyeDepth(CG_DEPTH_REVERSED ? 0.0 : 1.0) * 0.98;
        vec3 blast = CG_OBJECT_TO_WORLD[3].xyz;
        vec3 light = CG_OBJECT_CUSTOM2.rgb * CG_OBJECT_CUSTOM2.a * CG_OBJECT_CUSTOM1.z;
        float night = 1.3 - 0.8 * clamp(CG_DAYLIGHT, 0.0, 1.0);
        float glow = 0.0;
        if (scene >= far) {
            float toward = max(dot(ray, normalize(blast - eye)), 0.0);
            glow += _Sky + _Toward * pow(toward, 8.0);
        }
        // Where the ray meets the cloud plane, in camera-relative space.
        float cloudY = CG_OBJECT_CUSTOM1.x - cg_WorldOrigin.y;
        float t = (cloudY - eye.y) / (abs(ray.y) > 1.0e-3 ? ray.y : 1.0e-3);
        if (t > 0.0 && scene > t - _Slab / max(abs(ray.y), 0.05)) {
            vec3 p = eye + ray * t;
            float reach = max(CG_OBJECT_CUSTOM1.y, 1.0);
            float lit = exp(-dot(p.xz - blast.xz, p.xz - blast.xz) / (reach * reach));
            bool cloud = abs(scene - t) * abs(ray.y) < _Slab;
            float patches = cloud ? 1.0 : 0.6 * smoothstep(0.45, 0.75, fx_value_fbm(vec3(CG_ABSOLUTE_WORLD_POS(p).xz * 0.02, 0.0), 3));
            glow += _Clouds * lit * patches;
        }
        fragColor = vec4(light * glow * night, 1.0);
    }
}
