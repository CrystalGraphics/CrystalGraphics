// The open sky over a host's world takes the nearest depth, its colour left alone, so clouds and weather the host
// draws after the world stages stay off the showcase's sky. Drawn last in the transparent stage, after everything
// blended there. CgVfxShowcase.submitSky.
#type spatial
#include "crystalgraphics:shaders/demo/vfx_sky.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

struct v2f { vec3 dir; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        ColorMask 0
        DepthTest ALWAYS
        DepthWrite ON
        Cull FRONT
    }

    void vertex(out v2f o) {
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        o.dir = world.xyz - VFX_CAMERA;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
        gl_Position.z = VFX_SKY_FAR_Z(gl_Position.w);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        if (!VFX_OPEN_SKY(texture(cg_DepthBuffer, gl_FragCoord.xy / CG_RESOLUTION).r)) discard;
        fragColor = vec4(0.0);
        gl_FragDepth = CG_DEPTH_REVERSED ? 1.0 : 0.0;
    }
}
