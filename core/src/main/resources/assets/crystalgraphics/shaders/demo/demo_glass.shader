// The lights demo's glass (CgLightsDemo): a tinted pane blended over what is behind it, lit by the world where each of
// its pixels is (cg_Lit, the world lights' translucency volume), with a darker frame round its edge. CG_OBJECT_CUSTOM0 is
// its tint and alpha.
#domain surface
Material { BlendMode Translucent  Faces Both }

struct v2f { vec3 local; };

void vertex(inout CgVertex v, out v2f o) {
    o.local = cg_Position;
    v.world = (CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0)).xyz;
}

void surface(in v2f i, inout CgSurface s) {
    vec4 fragColor = vec4(0.0);
    // The unit cube's faces, x and y across a pane: a frame where either nears its edge.
    vec2 edge = 0.5 - abs(i.local.xy);
    float frame = 1.0 - smoothstep(0.02, 0.035, min(edge.x, edge.y));
    vec4 tint = CG_OBJECT_CUSTOM0;
    fragColor = vec4(mix(tint.rgb, tint.rgb * 0.35, frame), mix(tint.a, 0.95, frame));
    s.baseColor = fragColor.rgb;
    s.opacity = fragColor.a;
}
