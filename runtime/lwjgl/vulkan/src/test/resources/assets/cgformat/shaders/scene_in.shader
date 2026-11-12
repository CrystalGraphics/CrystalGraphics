// The engine's world_scene_in.shader as a #domain global
#domain global
#include "crystalgraphics:shaders/lib/post/composite.glsl"
Material { SceneColorMargin 0.001 }
Keywords {
    DEPTH ("Writes the host's depth into the scene's own")
    KEEP ("Keeps the opaque firing's scene where the host drew nothing since")
}
RenderState { Blend OFF  DepthTest ALWAYS  DepthWrite OFF  Cull OFF }
Properties { _Kept ("The opaque firing's scene", sampler2D) = "black" }
struct v2f { vec2 uv; };
void vertex(out v2f o) {
    vec2 p = vec2(float((CG_VERTEX_ID & 1) << 2) - 1.0, float((CG_VERTEX_ID & 2) << 1) - 1.0);
    o.uv = p * 0.5 + 0.5;
    gl_Position = vec4(p, 0.0, 1.0);
}
void fragment(in v2f i, out vec4 fragColor) {
    ivec2 at = ivec2(gl_FragCoord.xy);
    vec4 host = texelFetch(cg_SceneColor, at, 0);
    fragColor = vec4(post_decode_srgb(host.rgb), host.a);
#ifdef KEEP
    vec4 kept = texelFetch(_Kept, at, 0);
    vec3 written = post_encode_srgb(min(kept.rgb, vec3(1.0))) * 255.0;
    if (all(lessThanEqual(abs(host.rgb * 255.0 - written), vec3(0.51)))) fragColor = kept;
#endif
#ifdef DEPTH
    gl_FragDepth = texelFetch(cg_DepthBuffer, at, 0).r;
#endif
}
