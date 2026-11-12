#type none
Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" }
Queue = "Transparent"
struct Spark { vec4 positionLife; vec4 velocitySeed; };
Buffers {
    SPARKS ("Sparks", Spark, readonly)
    LIVE ("Live", uint, readonly)
}
struct v2f { float life; };
Pass {
    Tags { "LightMode" = "Forward" }
    RenderState { Blend ONE ONE  DepthWrite OFF  ColorMask 0 }
    void vertex(out v2f o) {
        Spark p = SPARKS(LIVE(CG_VERTEX_ID >> 2));
        vec3 local = p.positionLife.xyz + vec3(CG_VERTEX_CORNER - 0.5, 0.0) * 0.1;
        o.life = p.positionLife.w;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * (CG_OBJECT_TO_WORLD * vec4(local, 1.0));
    }
    void fragment(in v2f i, out vec4 fragColor) {
        CG_GLOW(vec3(1.0, 0.6, 0.2) * clamp(i.life, 0.0, 1.0));
        fragColor = vec4(1.0);
    }
}
Pass { Tags { "LightMode" = "Emissive" } RenderState { Blend ONE ONE  DepthWrite OFF } }
