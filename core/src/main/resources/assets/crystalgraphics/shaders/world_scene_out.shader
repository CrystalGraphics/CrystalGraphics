// The opaque firing's HDR scene encoded back into the host's 8-bit colour, so what the host draws before the
// transparent stage blends over our opaque draws as it did before; held at white, as the 8-bit target holds it. With
// DEPTH (a scene with a depth of its own: a host lending none) it writes the scene's depth into the host's too, so the
// host's later draws are hidden by ours. CgSceneTarget draws it; world_scene_in's KEEP reads what it wrote.
#domain global
#include "crystalgraphics:shaders/lib/post/composite.glsl"
Material { Media On }

Keywords {
    DEPTH ("DEPTH", switched)
}

RenderState {
    Blend OFF
    DepthTest ALWAYS
    DepthWrite OFF
    Cull OFF
}

Properties {
    _Scene ("The opaque firing's scene", sampler2D) = "black"
}

struct v2f { vec2 uv; };

void vertex(out v2f o) {
    vec2 p = vec2(float((CG_VERTEX_ID & 1) << 2) - 1.0, float((CG_VERTEX_ID & 2) << 1) - 1.0);
    o.uv = p * 0.5 + 0.5;
    gl_Position = vec4(p, 0.0, 1.0);
}

void fragment(in v2f i, out vec4 fragColor) {
    ivec2 at = ivec2(gl_FragCoord.xy);
    vec4 scene = texelFetch(_Scene, at, 0);
    // Snapped to the nearest code here, never left to the device's conversion: KEEP matches it within half a code
    vec3 code = floor(post_encode_srgb(min(scene.rgb, vec3(1.0))) * 255.0 + 0.5);
    fragColor = vec4(code * (1.0 / 255.0), scene.a);
#ifdef DEPTH
    gl_FragDepth = texelFetch(cg_DepthBuffer, at, 0).r;
#endif
}
