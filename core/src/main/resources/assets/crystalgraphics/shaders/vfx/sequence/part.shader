// A prefab part as CC2's particle-played clips draw one: its texture through a UV window, tinted, faded by its node's
// opacity and alpha-tested by _Clip, _Glare of it glowing. On a SEQUENCE particle its node places it and the clip's
// material scalars override the material's (lib/vfx/sequence.glsl); on a mesh slot it draws as the prefab instance places it.
// Premultiplied over what is behind it.
#domain surface
#include "crystalgraphics:shaders/lib/vfx/common.glsl"
Material { BlendMode AlphaComposite  ShadingModel Unlit  Faces Both  VertexFactory VfxMesh, Sequence  PerDrawValues On }

Properties {
    _MainTex    ("Its texture", sampler2D) = "white"
    _Color      ("Its tint; its alpha a strength", color) = (1, 1, 1, 1)
    _U0_LocX    ("Where its UV window starts across", float) = 0.0
    _V0_LocY    ("Where it starts up", float) = 0.0
    _U0_ScaleX  ("Its UV window's width", float) = 1.0
    _V0_ScaleY  ("Its height", float) = 1.0
    _Glare      ("How much of it glows, 0..1", float) = 0.0
    _Clip       ("The alpha it must pass to draw, 0..1", float) = 0.0
}

Buffers { FX_CLIPS ("Baked clips", vec4, readonly) }

struct v2f { vec2 uv; float opacity; float glare; float clip; };

void vertex(inout CgVertex v, out v2f o) {
#ifdef FX_ON_SEQUENCE
    // The node's opacity times the particle's over its life.
    o.opacity = FX_SEQUENCE_OPACITY * CG_OBJECT_CUSTOM1.z;
    vec4 window = vec4(FX_SEQ_SCALAR(0, _U0_LocX), FX_SEQ_SCALAR(1, _V0_LocY), FX_SEQ_SCALAR(4, _U0_ScaleX),
            FX_SEQ_SCALAR(5, _V0_ScaleY));
    o.glare = FX_SEQ_SCALAR(10, _Glare);
    o.clip = FX_SEQ_SCALAR(11, _Clip);
#else
    o.opacity = 1.0;
    vec4 window = vec4(_U0_LocX, _V0_LocY, _U0_ScaleX, _V0_ScaleY);
    o.glare = _Glare;
    o.clip = _Clip;
#endif
    o.uv = window.xy + v.uv0 * window.zw;
}

void surface(in v2f i, inout CgSurface s) {
    vec4 texel = texture(_MainTex, i.uv);
    float alpha = texel.a * _Color.a * i.opacity;
    if (alpha * cg_Vfx.presence <= i.clip || alpha <= 0.0) discard;
    vec3 colour = texel.rgb * _Color.rgb;
    s.baseColor = colour * alpha;   // premultiplied
    s.opacity = alpha;
    s.emissive = colour * alpha * i.glare;
}
