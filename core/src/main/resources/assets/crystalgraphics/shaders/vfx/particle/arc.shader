// An ink streak, as anime draws an explosion's shock: a dark tapered stroke, an arc of the circle round the emitter's
// source through its particle, in the eye's plane, so it flies out as the particle does and thins as its size curve
// falls. A CgVfxRibbons stroke placed from its particle record (CgVfxFrame.particles, ARCS); the draw is centred on the
// source, so CG_OBJECT_TO_WORLD[3] is the source. _Color is the ink, its alpha a strength. Solid ink: it writes depth
// wherever it covers half a pixel, so what is behind it is hidden.
#domain surface
#include "crystalgraphics:shaders/lib/vfx/common.glsl"
#include "crystalgraphics:shaders/lib/vfx/ribbon.glsl"
#include "crystalgraphics:shaders/lib/vfx/particle.glsl"
Material { BlendMode AlphaComposite  ShadingModel Unlit  Faces Both  ClipDepth On  VertexFactory Sprites  PerDrawValues On }

Properties {
    _Color ("The ink; its alpha a strength", color) = (0.02, 0.02, 0.03, 1)
    _Span ("Arc a stroke spans, radians, before its seed varies it", float) = 0.9
}

// its side -1..1 and its record's opacity; its colour
struct v2f { vec2 stroke; vec3 tint; };

void vertex(inout CgVertex v, out v2f o) {
    int n = fx_particle_index(FX_RIBBON_INDEX, CG_OBJECT_CUSTOM0.x, CG_OBJECT_CUSTOM0.y);
    float along = FX_RIBBON_ALONG, side = FX_RIBBON_SIDE;
    vec3 source = CG_OBJECT_TO_WORLD[3].xyz;
    vec3 origin = source - CG_OBJECT_CUSTOM1.xyz;
    vec3 right = vec3(cg_ViewMatrix[0][0], cg_ViewMatrix[1][0], cg_ViewMatrix[2][0]);
    vec3 up = vec3(cg_ViewMatrix[0][1], cg_ViewMatrix[1][1], cg_ViewMatrix[2][1]);
    int i = max(n, 0);
    vec3 offset = origin + CG_PARTICLE_POSITION(i) - source;
    // The circle round the source through the particle, in the eye's plane.
    float radius = length(vec2(dot(offset, right), dot(offset, up)));
    float centreAngle = atan(dot(offset, up), dot(offset, right) + 1.0e-6);
    float span = _Span * (0.4 + 1.2 * CG_PARTICLE_SEED(i));
    float a = centreAngle + span * (along - 0.5);
    vec3 p = source + (right * cos(a) + up * sin(a)) * radius;
    vec3 tangent = right * -sin(a) + up * cos(a);
    float taper = pow(sin(3.14159265 * along), 0.8);
    float width = n < 0 ? 0.0 : CG_PARTICLE_SIZE(i) * CG_PARTICLE_SCALE(i).x * CG_OBJECT_CUSTOM0.z * taper;
    v.world = fx_ribbon_vertex(p, tangent, CG_CAMERA_POSITION, width, side);
    o.stroke = vec2(side, CG_PARTICLE_OPACITY(i));
    o.tint = CG_PARTICLE_COLOR(i).rgb;
}

void surface(in v2f i, inout CgSurface s) {
    float across = abs(i.stroke.x);
    float aa = fwidth(across) + 1.0e-3;
    float cover = 1.0 - smoothstep(1.0 - 2.0 * aa, 1.0, across);
    s.opacityMask = cover;
    float alpha = cover * i.stroke.y * _Color.a;
    s.baseColor = _Color.rgb * i.tint * alpha;   // premultiplied
    s.opacity = alpha;
}
