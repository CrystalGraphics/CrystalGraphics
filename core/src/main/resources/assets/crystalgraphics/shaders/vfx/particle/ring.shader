// A shock front's foot glowing as it runs out from the blast: a bright line where its wall meets the floor, or anything
// else in the scene, under a faint sheet of light fading up the wall, brightest where it is seen edge-on, in the blast's
// light, breaking up round its length and gone by _FlashFor of its life; past that the dust it lifts shows it. Its air is
// shock_ring.shader on the same particle. A mesh particle (CgVfxFrame.particles, MESHES) whose sphere vertex() bends
// into the wall (lib/vfx/ring.glsl), its radius the particle's drawn size; the line is where the wall comes within _Width of
// the scene behind it, so it finds a floor anywhere within _Below under the centre. _Color is the glow, its alpha a
// strength; _Hot its middle. Its light blooms.
#domain surface
#include "crystalgraphics:shaders/lib/vfx/common.glsl"
#include "crystalgraphics:shaders/lib/vfx/depth.glsl"
#include "crystalgraphics:shaders/lib/vfx/ring.glsl"
Material { BlendMode Additive  ShadingModel Unlit  Faces Both  VertexFactory MeshParticles  PerDrawValues On }

Properties {
    _Color ("The glow; its alpha a strength", color) = (1, 0.55, 0.2, 1)
    _Hot ("The line's hot middle", color) = (1, 1, 1, 1)
    _Below  ("Blocks the wall reaches under its centre", float) = 1.5
    _Height ("Blocks it stands over its centre", float) = 2.5
    _Width  ("Blocks of wall in front of the scene the line lights", float) = 0.5
    _Breaks ("How many times it breaks round", float) = 7.0
    _Bright ("Its light", float) = 2.6
    _Veil   ("The sheet's light, share of the line's", float) = 0.2
    _FlashFor ("Share of its life it glows for: full to 40% of that, then fading out", float) = 0.65
    _VeilHeight ("Blocks up the wall the sheet's light falls to a third", float) = 0.9
    _ValueNoise ("Value noise", sampler3D) = "cg_value_noise"
}

struct v2f { vec3 world; vec3 outward; };

void vertex(inout CgVertex v, out v2f o) {
    vec3 centre = CG_OBJECT_TO_WORLD[3].xyz;
    float radius = length(CG_OBJECT_TO_WORLD[0].xyz), rise;
    v.world = fx_ring_wall(centre, radius, _Below, _Height, 0.0, v.uv0, o.outward, rise);
    o.world = v.world;
}

void surface(in v2f i, inout CgSurface s) {
    float life = cg_Particle.relativeTime, seed = cg_Particle.random;
    vec3 eye = CG_CAMERA_POSITION;
    vec3 ray = normalize(i.world - eye);
    float behind = FX_SCENE_DISTANCE(ray) - distance(eye, i.world);
    // Never thinner than about a pixel, so a far line does not flicker.
    float width = _Width + 1.5 * fwidth(behind);
    float contact = 1.0 - smoothstep(0.0, width, behind);
    vec2 dir = normalize(i.outward.xz + 1.0e-5);
    // Gaps opening round it as it spreads.
    float breaks = fx_value_noise(vec3(dir * _Breaks, seed * 7.0 + 2.0));
    float whole = smoothstep(0.55 * life, 0.55 * life + 0.25, breaks);
    float over = i.world.y - CG_OBJECT_TO_WORLD[3].y;
    float edgeOn = 1.0 - abs(dot(normalize(i.outward), ray));
    float veil = _Veil * exp(-max(over, 0.0) / _VeilHeight) * mix(0.25, 1.0, edgeOn * edgeOn);
    float flash = 1.0 - smoothstep(0.4 * _FlashFor, _FlashFor, life);
    float glow = (contact + veil) * whole * flash * cg_Particle.color.a * _Color.a;
    if (glow < 0.003) discard;
    vec3 col = mix(_Color.rgb, _Hot.rgb, contact * contact * contact) * cg_Particle.color.rgb * _Bright;
    s.baseColor = col * glow;
    s.emissive = s.baseColor;
}
