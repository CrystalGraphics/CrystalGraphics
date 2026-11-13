// -----------------------------------------------------------------------------
// demo_render.shader -- a matte shape lit as Minecraft lights a block: the world's
// lightmap at its light, times Minecraft's face shading (top 1.0, north and south
// 0.8, east and west 0.6, bottom 0.5), blended by the normal for a curved surface.
// The sun's direction comes from the world's shadows alone (CgShadowsDemo).
//
// Colour is read from CG_OBJECT_CUSTOM0.rgb (set by each draw's custom(0, ...)).
// -----------------------------------------------------------------------------
#domain surface

struct v2f { vec3 normalWs; };

void vertex(inout CgVertex v, out v2f o) {
    v.world = (CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0)).xyz;
    o.normalWs  = CG_NORMAL_MATRIX * cg_Normal;
}

void surface(in v2f i, inout CgSurface s) {
    vec4 fragColor = vec4(0.0);
    vec3  n     = normalize(i.normalWs);
    vec3  w     = n * n;
    float shade = w.x * 0.6 + w.z * 0.8 + w.y * (n.y > 0.0 ? 1.0 : 0.5);
    vec3  col   = CG_OBJECT_CUSTOM0.rgb * shade;
    fragColor   = vec4(col, 1.0);
    s.baseColor = fragColor.rgb;
}
