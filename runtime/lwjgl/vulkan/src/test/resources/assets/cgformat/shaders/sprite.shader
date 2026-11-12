// The engine's vfx/particle/sprite in the material format: a flipbook frame on a faced quad, lit where it is.
#domain surface
#include "crystalgraphics:shaders/lib/vfx/fx_common.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_ribbon.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_particle.glsl"
#include "crystalgraphics:shaders/lib/vfx/fx_draw.glsl"
Material { BlendMode AlphaComposite  Faces Both  VertexFactory Sprites  PerDrawValues On }
Properties {
    _Color ("Tints the sheet; its alpha a strength", color) = (1, 1, 1, 1)
    _Sheet ("The flipbook sheet, straight alpha, frame 0 at the top left", sampler2D) = "white"
    _SoftDistance ("Fades into what is behind it over this many blocks; 0 for none", float) = 0.5
}
struct v2f { vec4 uv; float blend; float opacity; vec2 light; vec3 tint; };
void vertex(inout CgVertex v, out v2f o) {
    o.uv = vec4(v.uv0, v.uv1);
    o.blend = cg_Particle.subUVLerp;
    o.opacity = CG_PARTICLE_OPACITY(cg_Particle.record);
    o.light = CG_PARTICLE_LIGHT(cg_Particle.record);
    o.tint = cg_Particle.color.rgb;
}
void surface(in v2f i, inout CgSurface s) {
    s.light = i.light;
    vec4 texel = mix(texture(_Sheet, i.uv.xy), texture(_Sheet, i.uv.zw), i.blend);
    float soft = fx_particle_soft(CG_SCENE_EYE_DEPTH(gl_FragCoord.xy / CG_RESOLUTION),
            cg_LinearEyeDepth(gl_FragCoord.z), _SoftDistance);
    float alpha = texel.a * i.opacity * _Color.a * FX_PRESENCE * soft;
    s.baseColor = texel.rgb * _Color.rgb * i.tint * alpha;
    s.opacity = alpha;
}
