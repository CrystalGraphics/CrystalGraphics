// The showcase's sky (vfx_sky.glsl) over a host's world, drawn after everything else in its translucent pass. Distant
// terrain fades into the sky before the host's fog would tint it, and the open sky takes the nearest depth, so clouds
// and weather the host draws later stay off it. The fade follows Minecraft's terrain fog: a cylinder round the
// camera reaching the render distance, a quarter of the far plane. CgVfxShowcase.submitSky.
#type spatial
#include "crystalgraphics:shaders/demo/vfx_sky.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

struct v2f { vec3 dir; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA
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
        vec3 d = normalize(i.dir);
        float pixel = max(length(dFdx(d)), length(dFdy(d)));
        float scene = texture(cg_DepthBuffer, gl_FragCoord.xy / CG_RESOLUTION).r;
        if (CG_DEPTH_REVERSED ? scene <= 1.0e-6 : scene >= 1.0 - 1.0e-6) {
            fragColor = vec4(0.0);
            gl_FragDepth = CG_DEPTH_REVERSED ? 1.0 : 0.0;
            return;
        }
        vec3 forward = -vec3(cg_ViewMatrix[0][2], cg_ViewMatrix[1][2], cg_ViewMatrix[2][2]);
        vec3 p = d * (cg_LinearEyeDepth(scene) / max(dot(d, forward), 1.0e-4));
        float reach = max(length(p.xz), abs(p.y));
        float range = max(cg_LinearEyeDepth(CG_DEPTH_REVERSED ? 0.0 : 1.0) * 0.25, 32.0);
        float fogStart = range - clamp(range * 0.1, 4.0, 64.0);
        float fade = smoothstep(fogStart - range * 0.3, fogStart, reach);
        if (fade <= 0.0) discard;
        fragColor = vec4(vfx_sky(d, CG_TIME, pixel), fade);
        gl_FragDepth = scene;
    }
}
