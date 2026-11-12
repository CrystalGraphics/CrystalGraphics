#type spatial
Tags { "RenderType" = "Transparent" "Lighting" = "Unlit" "Fog" = "Off" }
Queue = "Transparent"
Properties {
    _Strength ("Bend at full intensity, share of the screen", float) = 0.04
    _Noise ("Noise", sampler2D) = "white"
}
struct v2f { vec3 world; };
Pass {
    Tags { "LightMode" = "Forward" }
    RenderState { Blend SRC_ALPHA ONE_MINUS_SRC_ALPHA  DepthTest ALWAYS  DepthWrite OFF  Cull OFF  ColorMask 0 }
    void vertex(out v2f o) {
        vec4 w = CG_OBJECT_TO_WORLD * vec4(cg_Position, 1.0);
        o.world = w.xyz;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * w;
    }
    void fragment(in v2f i, out vec4 fragColor) { fragColor = vec4(1.0); }
}
Pass {
    Tags { "LightMode" = "Distortion" }
    RenderState { DepthTest ALWAYS  Cull OFF }
    void fragment(in v2f i, out vec4 offset) {
        vec2 n = texture(_Noise, i.world.xz * 0.3 + vec2(0.0, CG_TIME * 0.5)).rg - 0.5;
        offset = CG_DISTORTION(n * _Strength, 0.3, cg_LinearEyeDepth(gl_FragCoord.z));
    }
}
