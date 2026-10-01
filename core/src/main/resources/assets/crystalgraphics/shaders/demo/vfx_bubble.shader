// A soap bubble: a film swirling with interference colours as it drains, thinnest and darkest at the top, mirroring
// the studio and the floor on both its walls, and trembling as it floats. CgVfxShowcase.
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
        float t = CG_TIME;
        vec3 p = cg_Position;
        // Trembling: two slow wobbles running round it.
        p *= 1.0 + 0.015 * sin(t * 3.1 + p.y * 5.0) + 0.01 * sin(t * 4.7 + p.x * 6.0);
        vec4 world = CG_OBJECT_TO_WORLD * vec4(p, 1.0);
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
        float floorY = CG_OBJECT_TO_WORLD[3].y - CG_OBJECT_CUSTOM3.x;
        // The film drains down and swirls: thickness from warped noise, thinnest at the top, where it goes black.
        vec3 p = i.objPos;
        vec3 warp = vec3(vfx_fbm(p * 1.3 + vec3(t * 0.15, 0.0, 0.0), 3), vfx_fbm(p * 1.3 + vec3(5.2, t * 0.12, 1.3), 3), 0.0);
        float drain = 0.25 + 0.75 * (0.5 - p.y * 0.5);
        float thickness = (60.0 + 820.0 * vfx_fbm(p * 1.8 + warp * 2.5 + vec3(0.0, -t * 0.25, 0.0), 4)) * drain;
        float cosT = sqrt(1.0 - (1.0 - nv * nv) / (1.33 * 1.33));
        vec3 film = vec3(bubble_film(thickness, cosT, 650.0), bubble_film(thickness, cosT, 532.0),
                bubble_film(thickness, cosT, 450.0));
        // What the film mirrors, coloured by it: faint facing you, strong at the rim.
        float fresnel = 0.04 + 0.96 * pow(1.0 - nv, 5.0);
        vec3 mirrored = vfx_studio(i.worldPos, reflect(-v, n), 0.02, floorY)
                + vfx_direct(n, v, VFX_KEY_DIR, VFX_KEY_COLOR, vec3(1.0), 0.0, 0.03) * 2.0
                + vfx_direct(n, v, VFX_RIM_DIR, VFX_RIM_COLOR, vec3(1.0), 0.0, 0.03);
        vec3 reflection = mirrored * film * (0.3 + 1.4 * fresnel);
        if (!gl_FrontFacing) reflection *= 0.6;
        float alpha = clamp(fresnel * 0.45 + 0.03, 0.0, 1.0);
        fragColor = vec4(vfx_aces(reflection), alpha);
    }
}
