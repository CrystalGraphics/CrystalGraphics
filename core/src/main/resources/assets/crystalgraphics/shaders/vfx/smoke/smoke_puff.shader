// A billow of smoke: one lit puff on a camera-facing quad (CgVfxFrame.billboard), shaded as a cauliflower-edged ball. Its
// normal comes from the ball and the bumps on it; a key light from above leaves its top bright and its underside dark;
// the fire it rose from glows into it from below while it is hot. Its edge is soft, and it fades where the scene is
// nearer than the ball's own surface, so it meets the ground in a curve rather than along the quad's flat cut; that
// comparison is its depth test. Overlapping puffs, sorted back to front by the world renderer, make the cloud.
// CG_OBJECT_CUSTOM1: x its life 0..1, y its seed, z its opacity, w how hot it still is 0..1. Colour A is the smoke,
// colour B the fire's light in it, A's alpha a strength.
#type spatial
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_depth.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

Properties {
    _Light  ("Key light, toward it; w unused", vec4) = (0.55, 0.75, 0.3, 0.0)
    _Shadow ("Underside, share of the lit colour", float) = 0.12
    _Bumps  ("Billows on the ball, share of its radius", float) = 0.45
    _Spin   ("Turns over its life", float) = 0.15
    _Soft   ("Fade into the scene, share of its radius", float) = 0.35
}

struct v2f { vec3 world; vec2 quad; vec3 right; vec3 up; vec3 back; float size; };

Pass {
    Tags { "LightMode" = "Forward" }
    RenderState {
        Blend ONE ONE_MINUS_SRC_ALPHA
        DepthTest ALWAYS
        DepthWrite OFF
        Cull OFF
    }

    // The puff's surface over the quad at b (1 at its edge): the ball, and billows on it that churn as it ages.
    float puffHeight(vec2 b, float life, float seed) {
        float ball = sqrt(max(1.0 - dot(b, b), 0.0));
        float billows = fx_value_fbm(vec3(b * 2.4, ball * 1.5) + vec3(0.0, life * 0.9, life * 0.4) + seed * 7.0, 3);
        return ball + _Bumps * billows * ball;
    }

    void vertex(out v2f o) {
        float life = CG_OBJECT_CUSTOM1.x, seed = CG_OBJECT_CUSTOM1.y;
        vec3 right = vec3(cg_ViewMatrix[0][0], cg_ViewMatrix[1][0], cg_ViewMatrix[2][0]);
        vec3 up = vec3(cg_ViewMatrix[0][1], cg_ViewMatrix[1][1], cg_ViewMatrix[2][1]);
        // Each puff turned its own way, and turning slowly, so no two read alike.
        float a = seed * 6.28318531 + life * _Spin * 6.28318531 * (seed > 0.5 ? 1.0 : -1.0);
        vec3 r = cos(a) * right + sin(a) * up, u = -sin(a) * right + cos(a) * up;
        vec2 corner = cg_TexCoord0 * 2.0 - 1.0;
        float size = length(CG_OBJECT_TO_WORLD[0].xyz);
        vec3 world = CG_OBJECT_TO_WORLD[3].xyz + (r * corner.x + u * corner.y) * size;
        o.world = world;
        o.quad = corner;
        o.right = r;
        o.up = u;
        o.back = vec3(cg_ViewMatrix[0][2], cg_ViewMatrix[1][2], cg_ViewMatrix[2][2]);
        o.size = size;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(world, 1.0);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        float life = CG_OBJECT_CUSTOM1.x, seed = CG_OBJECT_CUSTOM1.y, hot = CG_OBJECT_CUSTOM1.w;
        vec2 q = i.quad;
        float r = length(q);
        // A cauliflower outline: the edge wanders with the angle and churns as it ages.
        vec2 around = q / max(r, 1.0e-4);
        float edge = 0.6 + 0.34 * fx_value_fbm(vec3(around * 2.2, life * 1.2) + seed * 17.0, 4);
        if (r > edge) discard;
        vec2 b = q / edge;
        float h = puffHeight(b, life, seed);
        const float E = 0.04;
        vec2 slope = vec2(puffHeight(b + vec2(E, 0.0), life, seed) - h, puffHeight(b + vec2(0.0, E), life, seed) - h) / E;
        vec3 n = normalize(vec3(-slope, 1.0));
        vec3 normal = normalize(i.right * n.x + i.up * n.y + i.back * n.z);
        // Lit from above, wrapped so the terminator is soft; deep folds darker.
        float lit = clamp(dot(normal, normalize(_Light.xyz)) * 0.7 + 0.3, 0.0, 1.0);
        lit *= lit;
        float fold = mix(0.55, 1.0, clamp(h / (1.0 + _Bumps), 0.0, 1.0));
        vec3 smoke = CG_OBJECT_CUSTOM2.rgb;
        vec3 col = mix(smoke * _Shadow, smoke, lit) * fold;
        // The fire beneath lights its underside and its thick heart while it is hot.
        float under = clamp(0.5 - 0.5 * normal.y, 0.0, 1.0);
        col += CG_OBJECT_CUSTOM3.rgb * hot * hot * (0.3 + 0.9 * under) * (0.4 + 0.6 * h) * 1.6;
        // Opaque in the middle, thinning to a soft rim, and faded where it meets the scene.
        float alpha = (1.0 - smoothstep(edge - 0.07, edge, r)) * mix(0.7, 1.0, smoothstep(0.0, 0.5, h));
        vec3 eye = FX_CAMERA;
        vec3 ray = normalize(i.world - eye);
        float surface = distance(i.world, eye) - min(h, 1.0) * i.size * edge;
        alpha *= clamp((FX_SCENE_DISTANCE(ray) - surface) / (_Soft * i.size), 0.0, 1.0);
        alpha *= CG_OBJECT_CUSTOM1.z * CG_OBJECT_CUSTOM2.a;
        fragColor = vec4(col * alpha, alpha);
    }
}
