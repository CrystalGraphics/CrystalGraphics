// A cel-shaded billow as a real mesh, the way anime-look explosions are built in UE5 Niagara: a sphere whose vertices
// Voronoi noise pushes out, so every cell is a rounded lobe, with smaller domes on the lobes. Opaque, with a depth
// prepass, so billows cut into each other and into the ground in three dimensions and hidden ones cost nothing. Styled as
// Sparking Zero draws its blasts: a saturated body, a darker band only on the undersides, an irregular glowing core on
// each lobe pushed toward a light that follows the eye while it burns and turns to the sun as it cools, the body taking
// the world's light as it does, and dark contour strokes wherever the surface turns away from the
// eye, so every lobe and every bump on it is drawn round its edge. All the noise is per vertex; a pixel only shades. It
// erodes away at the end of its life, and as the camera comes near it, so a player inside a blast still sees out. Drawn on CgVfxFrame.mesh's sphere, turned and sized per billow. CG_OBJECT_CUSTOM1:
// x its life 0..1, y its seed, z its opacity, w how hot it still is 0..1. Colour A is the body, colour B the core.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"

Tags { "RenderType" = "Opaque" "CastShadows" = "Off" "Lighting" = "Unlit" }
Queue = "Geometry"

Properties {
    _Cells   ("Lobes around the sphere, cells per unit", float) = 1.5
    _Bulge   ("Lobe height, share of the radius", float) = 0.42
    _Fine    ("Small domes on the lobes, share of the bulge", float) = 0.4
    _Deep    ("Contours and undersides, share of the body colour", float) = 0.2
    _Contour ("Contour width, as how far the surface may turn from the eye", float) = 0.3
    _Core    ("How much of each lobe its core covers, lower is more", float) = 0.8
    _Glow    ("Brightness of the core while hot", float) = 1.7
    _NearFrom ("Blocks from the eye where it starts eroding away", float) = 8.0
    _NearTo  ("Blocks from the eye where it is gone", float) = 2.0
    _ValueNoise ("Value noise", sampler3D) = "cg_value_noise"
    _Voronoi ("Voronoi", sampler3D) = "cg_voronoi"
}

struct v2f { vec3 world; vec3 normal; vec3 lobe; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ZERO
        DepthTest LEQUAL
        DepthWrite ON
        Cull BACK
    }

    // A round dome over a Voronoi cell, from the distance to its centre: 1 at the centre, 0 by the cell's edge.
    float dome(float f1) {
        return sqrt(max(1.0 - f1 * f1 * 1.6, 0.0));
    }

    // One octave of domes at frequency c: the dome under p, and its gradient, through the slope of the distance to the
    // nearest feature point across a quarter cell.
    float domes(vec3 p, float c, vec3 offset, out vec3 gradient) {
        vec3 q = p * c + offset;
        const float e = 0.25;
        float f1 = fx_voronoi(q).x;
        vec3 slope = vec3(fx_voronoi(q + vec3(e, 0.0, 0.0)).x - fx_voronoi(q - vec3(e, 0.0, 0.0)).x,
                          fx_voronoi(q + vec3(0.0, e, 0.0)).x - fx_voronoi(q - vec3(0.0, e, 0.0)).x,
                          fx_voronoi(q + vec3(0.0, 0.0, e)).x - fx_voronoi(q - vec3(0.0, 0.0, e)).x) / (2.0 * e);
        float height = dome(f1);
        gradient = -c * 1.6 * f1 * slope / max(height, 0.08);
        return height;
    }

    void vertex(out v2f o) {
        float seed = CG_OBJECT_CUSTOM1.y, opacity = CG_OBJECT_CUSTOM1.z;
        vec3 p = normalize(cg_Position);
        vec3 gLarge, gFine;
        float large = domes(p, _Cells, vec3(seed * 31.0), gLarge);
        float fine = domes(p, _Cells * 2.6, vec3(seed * 17.0 + 5.0), gFine);
        float h = 1.0 + _Bulge * (large - 0.5 + _Fine * (fine - 0.5));
        // The displaced surface's normal: the sphere's, tilted against the height's slope along it.
        vec3 g = _Bulge * (gLarge + _Fine * gFine);
        vec3 n = normalize(p - (g - dot(g, p) * p) / h);
        // Shrinking as it fades, alongside its erosion.
        vec3 local = p * h * (0.4 + 0.6 * sqrt(opacity));
        vec4 world = CG_OBJECT_TO_WORLD * vec4(local, 1.0);
        o.world = world.xyz;
        o.normal = normalize(mat3(CG_OBJECT_TO_WORLD) * n);
        // the lobe's dome, the small dome on it, the erosion value
        o.lobe = vec3(large, fine, fx_value_noise(p * 2.6 + seed * 9.0));
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * world;
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float life = CG_OBJECT_CUSTOM1.x, opacity = CG_OBJECT_CUSTOM1.z, hot = CG_OBJECT_CUSTOM1.w;
        // Eroding: holes eat through as it fades, and as the eye comes near, the same way.
        float near = 1.0 - smoothstep(_NearTo, _NearFrom, distance(FX_CAMERA, i.world));
        if (i.lobe.z < max(1.0 - opacity, near) * 1.05) discard;
        vec3 n = normalize(i.normal);
        vec3 eye = FX_CAMERA;
        vec3 toEye = normalize(eye - i.world);
        // A light that follows the eye, offset up and to the left, while it burns; the sun's once it is smoke.
        vec3 right = vec3(cg_ViewMatrix[0][0], cg_ViewMatrix[1][0], cg_ViewMatrix[2][0]);
        vec3 up = vec3(cg_ViewMatrix[0][1], cg_ViewMatrix[1][1], cg_ViewMatrix[2][1]);
        vec3 eyeLight = normalize(toEye * 0.75 + up * 0.6 - right * 0.35);
        vec3 key = normalize(mix(CG_SUN_DIRECTION, eyeLight, hot) + up * 1.0e-3);
        float lit = dot(n, key) * 0.5 + 0.5;
        float core = hot * (0.6 + 0.4 * (1.0 - life));
        vec3 body = CG_OBJECT_CUSTOM2.rgb, coreColour = CG_OBJECT_CUSTOM3.rgb, deep = body * _Deep;
        // The body, darker in a band on the undersides only.
        float ld = fwidth(lit) + 0.01;
        vec3 col = mix(body * 0.62, body * (0.85 + 0.25 * lit), smoothstep(0.33 - ld, 0.33 + ld, lit));
        vec3 world = mix(CG_LIGHTMAP(cg_Light), vec3(1.0), hot);
        col *= world;
        deep *= world;
        // An irregular core on each lobe, pushed toward the light; the small domes break its edge.
        float shape = i.lobe.x * (0.42 + 0.75 * lit) + (i.lobe.y - 0.5) * 0.18 + core * 0.15;
        float d = fwidth(shape) + 0.01;
        vec3 glow = coreColour * (0.88 + 0.25 * i.lobe.x) * (1.0 + (_Glow - 1.0) * core);
        col = mix(col, glow, smoothstep(_Core - d, _Core + d, shape));
        col *= mix(1.0, 0.65, life * life * (1.0 - hot));
        // Contours: wherever the surface turns away from the eye, a little wider in shadow.
        float facing = dot(n, toEye);
        float width = _Contour * (0.7 + 0.6 * (1.0 - lit));
        float fd = fwidth(facing) + 1.0e-3;
        col = mix(col, deep, 1.0 - smoothstep(width - fd, width + fd, facing));
        fragColor = vec4(col, 1.0);
    }
}
