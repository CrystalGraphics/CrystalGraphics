// A glowing spark or ember: a white-hot disc in a soft bloom of its colour, drawn out along its motion the faster it
// goes, as bright as it is hot, so an ember dims as it cools. A CgVfxQuads quad placed from its particle record
// (CgVfxFrame.particles, QUADS), stretched here rather than by the renderer's alignment. _Color is the bloom, its alpha
// a strength; _Hot the centre. Its light blooms, as it adds.
#domain surface
#include "crystalgraphics:shaders/lib/vfx/common.glsl"
#include "crystalgraphics:shaders/lib/vfx/ribbon.glsl"
#include "crystalgraphics:shaders/lib/vfx/particle.glsl"
Material { BlendMode Additive  ShadingModel Unlit  Faces Both  VertexFactory Sprites  PerDrawValues On }

Properties {
    _Color ("The bloom; its alpha a strength", color) = (1, 0.5, 0.15, 1)
    _Hot ("The white-hot centre", color) = (1, 0.95, 0.8, 1)
    _Stretch ("How much longer a spark draws per block a second it moves", float) = 0.12
    _MaxStretch ("Longest a streak gets, in its widths", float) = 4.0
    _CameraOffset ("Drawn this many of its sizes toward the eye, so one resting on a surface is not cut by it", float) = 1
}

// where on it, its glow; its colour
struct v2f { vec3 spark; vec3 tint; };

void vertex(inout CgVertex v, out v2f o) {
    int n = fx_particle_index(FX_QUAD_INDEX, CG_OBJECT_CUSTOM0.x, CG_OBJECT_CUSTOM0.y);
    vec2 corner = FX_QUAD_CORNER;
    vec3 origin = CG_OBJECT_TO_WORLD[3].xyz - CG_OBJECT_CUSTOM1.xyz;
    vec3 right = vec3(cg_ViewMatrix[0][0], cg_ViewMatrix[1][0], cg_ViewMatrix[2][0]);
    vec3 up = vec3(cg_ViewMatrix[0][1], cg_ViewMatrix[1][1], cg_ViewMatrix[2][1]);
    int i = max(n, 0);
    vec3 velocity = CG_PARTICLE_VELOCITY(i);
    float size = n < 0 ? 0.0 : CG_PARTICLE_SIZE(i) * CG_OBJECT_CUSTOM0.z, keep;
    vec3 centre = fx_particle_toward_eye(origin + CG_PARTICLE_POSITION(i), CG_CAMERA_POSITION, _CameraOffset * size, keep);
    size *= keep;
    // Drawn out along its motion in the eye's plane, the faster the longer.
    float onScreen = length(vec2(dot(velocity, right), dot(velocity, up)));
    float streak = min(1.0 + onScreen * _Stretch, _MaxStretch);
    vec2 extent = size * CG_PARTICLE_SCALE(i).xy * vec2(streak, 1.0);
    float angle = fx_particle_heading(velocity, right, up);
    v.world = fx_particle_corner(centre, corner, extent, angle, right, up);
    o.spark = vec3(corner, (0.35 + 0.65 * CG_PARTICLE_HEAT(i)) * CG_PARTICLE_OPACITY(i));
    o.tint = CG_PARTICLE_COLOR(i).rgb;
}

void surface(in v2f i, inout CgSurface s) {
    vec2 q = i.spark.xy;
    float r = length(q);
    float aa = fwidth(r) + 1.0e-3;
    // A round disc, white-hot in the middle, in a wide soft bloom.
    float disc = 1.0 - smoothstep(0.26 - aa, 0.26 + aa, r);
    float bloom = exp(-r * r * 9.0) * (1.0 - smoothstep(0.7, 1.0, r));
    vec3 col = mix(_Color.rgb, _Hot.rgb, exp(-r * r * 60.0)) * disc * 1.8 + _Color.rgb * bloom * 0.9;
    s.baseColor = col * i.tint * i.spark.z * _Color.a;
    s.emissive = s.baseColor;
}
