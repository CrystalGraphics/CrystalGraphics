// The showcase's sky (vfx_sky.glsl) on a sphere round the camera at the far plane, so everything draws in front of
// it. CgVfxShowcase.
#type spatial
#include "crystalgraphics:shaders/demo/vfx_sky.glsl"

Tags { "RenderType" = "Background" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Background"

Properties {
    _ValueNoise ("Value noise", sampler3D) = "cg_value_noise"
}

struct v2f { vec3 dir; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        DepthTest LEQUAL
        DepthWrite OFF
        Cull FRONT
    }

    void vertex(out v2f o) {
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        o.dir = world.xyz - FX_CAMERA;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
        gl_Position.z = VFX_SKY_FAR_Z(gl_Position.w);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec3 d = normalize(i.dir);
        // A pixel's angular size: the larger of its two screen steps. The length of fwidth overstates it up to 2.5x.
        fragColor = vec4(vfx_sky(_ValueNoise, d, CG_TIME, max(length(dFdx(d)), length(dFdy(d)))), 1.0);
    }
}
