// The post chain's velocity debug view (Unreal's Velocity buffer visualization): where the velocity pass wrote, the
// motion in texels a frame as 0.5 + motion / 32 in red and green (up is +y), blue 1; where it wrote nothing, blue half
// where a blended draw covered (the responsive target, Responsive AA), else black.
// CgPostDebug draws it (-Dcrystalgraphics.post.debug=velocity); nothing else should.
#domain global
#include "crystalgraphics:shaders/lib/velocity.glsl"
Material { Media On }

RenderState {
    Blend OFF
    DepthTest ALWAYS
    DepthWrite OFF
    Cull OFF
}

Properties {
    _Source ("What the velocity pass wrote", sampler2D) = "black"
    _Responsive ("1 where a blended draw covered", sampler2D) = "black"
}

struct v2f { vec2 uv; };

void vertex(out v2f o) {
    vec2 p = vec2(float((CG_VERTEX_ID & 1) << 2) - 1.0, float((CG_VERTEX_ID & 2) << 1) - 1.0);
    o.uv = p * 0.5 + 0.5;
    gl_Position = vec4(p, 0.0, 1.0);
}

void fragment(in v2f i, out vec4 fragColor) {
    ivec2 at = ivec2(gl_FragCoord.xy);
    vec2 written = texelFetch(_Source, at, 0).xy;
    if (written.x <= 0.0) {
        fragColor = vec4(0.0, 0.0, cg_MarkResponsive(texelFetch(_Responsive, at, 0).r) ? 0.5 : 0.0, 1.0);
        return;
    }
    vec2 texels = cg_DecodeVelocity(written) * 0.5 * vec2(textureSize(_Source, 0));
    fragColor = vec4(clamp(0.5 + texels / 32.0, 0.0, 1.0), 1.0, 1.0);
}
