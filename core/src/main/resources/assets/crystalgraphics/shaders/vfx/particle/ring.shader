// An ink shockwave ring, as anime explosions draw them: a thin horizontal band expanding fast round the blast, seen near
// edge-on as a long dark line across the screen. A mesh particle (CgVfxFrame.particles, MESHES) whose sphere the vertex
// stage bends into the band: its radius is the particle's drawn size, its height the band's thickness. The band thins as
// it grows, wobbles in thickness along its length, and breaks into dashes as it fades. It is never thinner than about a
// pixel and a half on screen, fading as it is widened, so a far ring does not flicker. CG_OBJECT_CUSTOM1: x its life
// 0..1, y its seed, z its opacity. Colour A is the ink, A's alpha a strength.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"

Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
Queue = "Transparent"

Properties {
    _Thickness ("Half the band's height at birth, blocks", float) = 0.18
    _Tilt      ("Largest tilt off level, radians", float) = 0.06
    _Breaks    ("How many dashes it breaks into", float) = 5.0
    _ValueNoise ("Value noise", sampler3D) = "cg_value_noise"
}

struct v2f { vec3 world; vec3 band; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE_MINUS_SRC_ALPHA
        DepthTest LEQUAL
        DepthWrite ON
        Cull OFF
    }

    void vertex(out v2f o) {
        float life = CG_OBJECT_CUSTOM1.x, seed = CG_OBJECT_CUSTOM1.y;
        vec3 centre = CG_OBJECT_TO_WORLD[3].xyz;
        float radius = length(CG_OBJECT_TO_WORLD[0].xyz);
        vec3 h = fx_hash33(vec3(seed * 17.0, seed * 5.0 + 1.0, 3.0));
        // Around the band from the sphere's longitude, across it from its latitude.
        float angle = cg_TexCoord0.x * 6.28318531, across = 1.0 - 2.0 * cg_TexCoord0.y;
        vec2 dir = vec2(cos(angle), sin(angle));
        float wobble = 0.7 + 0.6 * fx_value_noise(vec3(dir * 2.5, seed * 11.0));
        float thick = _Thickness * mix(0.2, 1.6, h.x * h.x) * (1.0 - 0.65 * life) * wobble;
        // Tilted a little about a horizontal axis.
        float tilt = _Tilt * (h.y * 2.0 - 1.0), turn = h.z * 6.28318531;
        vec3 axis = vec3(cos(turn), 0.0, sin(turn));
        vec3 rim = vec3(dir.x, 0.0, dir.y);
        vec3 up = vec3(0.0, 1.0, 0.0);
        rim = rim * cos(tilt) + cross(axis, rim) * sin(tilt) + axis * dot(axis, rim) * (1.0 - cos(tilt));
        up = up * cos(tilt) + cross(axis, up) * sin(tilt);
        // A pixel's height in blocks at this distance: the band is held to at least 0.75 of one each side.
        vec3 onRim = centre + rim * radius;
        float pixel = 2.0 * distance(onRim, FX_CAMERA) / (cg_ProjMatrix[1][1] * CG_RESOLUTION.y);
        float shown = max(thick, 0.75 * pixel);
        vec3 world = onRim + up * across * shown;
        o.world = world;
        o.band = vec3(across, thick / shown, 0.0);
        o.band.z = fx_value_noise(vec3(dir * _Breaks, seed * 7.0 + 2.0));
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(world, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float life = CG_OBJECT_CUSTOM1.x, opacity = CG_OBJECT_CUSTOM1.z;
        float edge = abs(i.band.x);
        float aa = fwidth(edge) + 1.0e-3;
        float cover = 1.0 - smoothstep(1.0 - 2.0 * aa, 1.0, edge);
        // Breaking into dashes from half its life, never wholly: the fade takes the last of it.
        float erode = smoothstep(0.5, 1.0, life) * 0.7;
        float dash = i.band.z - erode;
        cover *= smoothstep(0.0, fwidth(i.band.z) * 2.0 + 0.01, dash);
        // Solid ink: it writes depth wherever it covers half a pixel (CgVfxLayer.ORDER_SOLID).
        if (cover < 0.5) discard;
        float alpha = cover * i.band.y * opacity * CG_OBJECT_CUSTOM2.a;
        fragColor = vec4(CG_OBJECT_CUSTOM2.rgb * alpha, alpha);
    }
}
