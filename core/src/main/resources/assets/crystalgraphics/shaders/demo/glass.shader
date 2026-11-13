// The lights demo's glass (CgLightsDemo): a tinted pane blended over what is behind it, lit by the world where each of
// its pixels is (cg_Lit, the world lights' translucency volume), with a darker frame round its edge. CG_OBJECT_CUSTOM0 is
// its tint and alpha.
#domain surface
Material { BlendMode Translucent  Faces Both }

struct v2f { vec3 local; };

void vertex(inout CgVertex v, out v2f o) {
    o.local = cg_Position;
}

void surface(in v2f i, inout CgSurface s) {
    // The unit cube's faces, x and y across a pane: a frame where either nears its edge.
    vec2 edge = 0.5 - abs(i.local.xy);
    float frame = 1.0 - smoothstep(0.02, 0.035, min(edge.x, edge.y));
    vec4 tint = CG_OBJECT_CUSTOM0;
    s.baseColor = mix(tint.rgb, tint.rgb * 0.35, frame);
    s.opacity = mix(tint.a, 0.95, frame);
}
