// A billow of smoke: one lit puff on a camera-facing quad (CgVfxFrame.billboard), shaded as a cauliflower-edged ball. Its
// normal comes from the ball and the bumps on it; the sun (or the moon) leaves its sunward side bright and the far side
// dark, and the world's light where it is tints it, as Minecraft lights its blocks; the fire it rose from glows into it
// from below while it is hot, lit by nothing. Its edge is soft, and it fades where the scene is nearer than the ball's
// own surface, so it meets the ground in a curve rather than along the quad's flat cut; that comparison is its depth
// test. Overlapping puffs, sorted back to front by the world renderer, make the cloud. cg_Billboard.values: x its life
// 0..1, y its seed, z its opacity, w how hot it still is 0..1. _Color is the smoke, its alpha a strength; _Hot the
// fire's light in it.
#domain surface
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_depth.glsl"
Material { BlendMode AlphaComposite  ShadingModel Unlit  Faces Both  DepthTest Off  VertexFactory Billboard  PerDrawValues On }

Properties {
    _Color ("The smoke; its alpha a strength", color) = (0.3, 0.3, 0.32, 1)
    _Hot ("The fire's light in it", color) = (1, 0.5, 0.15, 1)
    _Shadow ("Underside, share of the lit colour", float) = 0.12
    _Bumps  ("Billows on the ball, share of its radius", float) = 0.45
    _Spin   ("Turns over its life", float) = 0.15
    _Soft   ("Fade into the scene, share of its radius", float) = 0.35
    _ValueNoise ("Value noise", sampler3D) = "cg_value_noise"
}

struct v2f { vec3 world; vec2 quad; vec3 right; vec3 up; };

// The puff's surface over the quad at b (1 at its edge): the ball, and billows on it that churn as it ages.
float puffHeight(vec2 b, float life, float seed) {
    float ball = sqrt(max(1.0 - dot(b, b), 0.0));
    float billows = fx_value_fbm(vec3(b * 2.4, ball * 1.5) + vec3(0.0, life * 0.9, life * 0.4) + seed * 7.0, 3);
    return ball + _Bumps * billows * ball;
}

void vertex(inout CgVertex v, out v2f o) {
    CgBillboard b = cg_Billboard;
    float life = b.values.x, seed = b.values.y;
    // Each puff turned its own way, and turning slowly, so no two read alike.
    float a = seed * 6.28318531 + life * _Spin * 6.28318531 * (seed > 0.5 ? 1.0 : -1.0);
    vec3 r = cos(a) * b.right + sin(a) * b.up, u = -sin(a) * b.right + cos(a) * b.up;
    v.world = CG_OBJECT_TO_WORLD[3].xyz + (r * v.position.x + u * v.position.y) * b.size;
    o.world = v.world;
    o.quad = v.position.xy;
    o.right = r;
    o.up = u;
}

void surface(in v2f i, inout CgSurface s) {
    CgBillboard bb = cg_Billboard;
    float life = bb.values.x, seed = bb.values.y, hot = bb.values.w;
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
    vec3 back = vec3(cg_ViewMatrix[0][2], cg_ViewMatrix[1][2], cg_ViewMatrix[2][2]);
    vec3 normal = normalize(i.right * n.x + i.up * n.y + back * n.z);
    // Lit by the sun, wrapped so the terminator is soft; deep folds darker.
    float lit = clamp(dot(normal, CG_SUN_DIRECTION) * 0.7 + 0.3, 0.0, 1.0);
    lit *= lit;
    float fold = mix(0.55, 1.0, clamp(h / (1.0 + _Bumps), 0.0, 1.0));
    vec3 smoke = _Color.rgb;
    vec3 col = mix(smoke * _Shadow, smoke, lit) * fold * CG_LIGHTMAP(cg_Light);
    // The fire beneath lights its underside and its thick heart while it is hot.
    float under = clamp(0.5 - 0.5 * normal.y, 0.0, 1.0);
    col += _Hot.rgb * hot * hot * (0.3 + 0.9 * under) * (0.4 + 0.6 * h) * 1.6;
    // Opaque in the middle, thinning to a soft rim, and faded where it meets the scene.
    float alpha = (1.0 - smoothstep(edge - 0.07, edge, r)) * mix(0.7, 1.0, smoothstep(0.0, 0.5, h));
    vec3 eye = CG_CAMERA_POSITION;
    vec3 ray = normalize(i.world - eye);
    float front = distance(i.world, eye) - min(h, 1.0) * bb.size * edge;
    alpha *= clamp((FX_SCENE_DISTANCE(ray) - front) / (_Soft * bb.size), 0.0, 1.0);
    alpha *= bb.values.z * _Color.a;
    s.baseColor = col * alpha;   // premultiplied
    s.opacity = alpha;
}
