// The showcase's sky (vfx_sky.glsl) over a host's distant terrain, which fades into it before the host's fog would
// tint it. Drawn first in the transparent stage, so glows and glass blended there stay over it. The fade follows
// Minecraft's terrain fog: a cylinder round the camera reaching the render distance, a quarter of the far plane.
// CgVfxShowcase.submitSky.
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
        DepthWrite OFF
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
        if (VFX_OPEN_SKY(scene)) discard;
        // The scene point along this ray: where its clip w, the eye depth, meets the scene's. The w row carries any
        // turn the host folded into its projection, so the depth is measured along the axis it was taken on.
        mat4 toClip = cg_ProjMatrix * cg_ViewMatrix;
        vec4 w = vec4(toClip[0][3], toClip[1][3], toClip[2][3], toClip[3][3]);
        vec3 p = d * ((cg_LinearEyeDepth(scene) - dot(w, vec4(VFX_CAMERA, 1.0))) / max(dot(w.xyz, d), 1.0e-4));
        float reach = max(length(p.xz), abs(p.y));
        float range = max(cg_LinearEyeDepth(CG_DEPTH_REVERSED ? 0.0 : 1.0) * 0.25, 32.0);
        float fogStart = range - clamp(range * 0.1, 4.0, 64.0);
        float fade = smoothstep(fogStart - range * 0.3, fogStart, reach);
        if (fade <= 0.0) discard;
        fragColor = vec4(vfx_sky(d, CG_TIME, pixel), fade);
    }
}
