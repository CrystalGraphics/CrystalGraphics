// An energy wave's glow, standing in for bloom: a Gaussian of light around the path, integrated along each view ray and
// counting only what lies in front of the opaque scene, so terrain dims it by how much of it is behind rather than
// cutting it. A volume layer (CgVfxLayer.volume): each chunk sums the light of only the rings it owns, as a line of
// point lights one ring apart, on a sphere whose far wall covers each pixel once, so the chunks add up to the whole
// body with nothing counted twice however the path bends. Colour A is the glow, its alpha a strength. The layer's
// radius is how far the light reaches, in the rings' radii; the Gaussian's width is 1/3.2 of it. CgEnergyWave.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_volume.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_depth.glsl"

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
Queue = "Transparent"

Properties {
    _FxPath ("Path rings", sampler2D) = "black"
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
        int row = int(CG_OBJECT_CUSTOM0.x + 0.5), first = int(CG_OBJECT_CUSTOM0.y + 0.5);
        int owned = int(CG_OBJECT_CUSTOM1.w + 0.5);
        vec4 header = fx_path_header(_FxPath, row);
        float spacing = header.y / max(header.x - 1.0, 1.0);
        vec3 origin = CG_OBJECT_TO_WORLD[3].xyz - CG_OBJECT_CUSTOM1.xyz;
        float scene = FX_SCENE_DISTANCE(ray);
        float glow = 0.0;
        for (int k = 0; k < owned; k++) {
            vec4 ring = texelFetch(_FxPath, ivec2(1 + (first + k) * 3, row), 0);
            float sigma = max(ring.w * CG_OBJECT_CUSTOM0.z / 3.2, 1.0e-4);
            // A point light per ring, weighted so a straight line of them seen side-on peaks at 1.
            glow += spacing / (sigma * 1.7724539) * fx_point_glow(eye, ray, origin + ring.xyz, sigma, scene);
        }
        fragColor = vec4(CG_OBJECT_CUSTOM2.rgb * CG_OBJECT_CUSTOM2.a * glow * fx_flicker(header.w, header.z), 1.0);
    }
}
