// A soap bubble: thin-film interference over a swirling film, nearly clear facing you and mirror-bright at the rim,
// both walls seen. CgVfxShowcase.
#type spatial
#include "crystalgraphics:shaders/demo/vfx_common.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

struct v2f { vec3 worldPos; vec3 normalWs; vec3 objPos; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE_MINUS_SRC_ALPHA
        DepthTest LEQUAL
        DepthWrite OFF
        Cull OFF
    }

    // Reflectance of a soap film {@code nm} thick at one wavelength: two reflections interfering.
    float bubble_film(float nm, float cosT, float wavelength) {
        float phase = 4.0 * 3.14159265 * 1.33 * nm * cosT / wavelength;
        return 0.5 - 0.5 * cos(phase);
    }

    void vertex(out v2f o) {
        vec4 world = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        o.worldPos = world.xyz;
        o.normalWs = CG_NORMAL_MATRIX * cg_Normal;
        o.objPos = cg_Position;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float t = CG_TIME;
        vec3 n = normalize(i.normalWs);
        vec3 v = normalize(VFX_CAMERA - i.worldPos);
        if (!gl_FrontFacing) n = -n;
        float nv = max(dot(n, v), 0.0);
        // The film drains down and swirls: thickness from domain-warped noise.
        vec3 p = i.objPos;
        vec3 warp = vec3(vfx_fbm(p * 1.3 + vec3(t * 0.15, 0.0, 0.0), 3), vfx_fbm(p * 1.3 + vec3(5.2, t * 0.12, 1.3), 3), 0.0);
        float thickness = 180.0 + 520.0 * vfx_fbm(p * 1.8 + warp * 2.5 + vec3(0.0, -t * 0.25, 0.0), 4) + 160.0 * (0.5 - p.y * 0.5);
        float cosT = sqrt(1.0 - (1.0 - nv * nv) / (1.33 * 1.33));
        vec3 film = vec3(bubble_film(thickness, cosT, 650.0), bubble_film(thickness, cosT, 532.0),
                bubble_film(thickness, cosT, 450.0));
        float fresnel = 0.05 + 0.95 * pow(1.0 - nv, 3.0);
        vec3 reflection = vfx_env(reflect(-v, n), 0.02) * film * 1.6;
        reflection += vfx_direct(n, v, VFX_KEY_DIR, VFX_KEY_COLOR, vec3(1.0), 0.0, 0.03) * 3.0;
        float alpha = clamp(fresnel * 0.85 + 0.04, 0.0, 1.0);
        fragColor = vec4(vfx_aces(reflection) * alpha, alpha * 0.6);
    }
}
