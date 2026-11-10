#pragma once
// The canvas's default image materials (CgCanvasMaterials): a texel and the item's colour, as the pass's blend wants
// them. Unreal's simple element shader, one function per blend mode (SimpleElementPixelShader.usf).

#if !defined(CG_VERTEX_STAGE) && !defined(CG_COMPUTE_STAGE)
// `mask`: the texel's alpha masks the colour, its rgb unused (a silhouette tinted by the item's colour).
vec4 cg_canvas_colour(vec4 texel, vec4 colour, bool mask) {
    vec4 c = mask ? vec4(colour.rgb, colour.a * texel.a) : texel * colour;
#if CG_FOG_MODE == 1
    // Premultiplied: the texel is premultiplied already, the colour is not.
    c = mask ? vec4(colour.rgb * c.a, c.a) : texel * vec4(colour.rgb * colour.a, colour.a);
#elif CG_FOG_MODE == 2
    c = vec4(c.rgb * c.a, 0.0);                  // added: weighed by its own alpha
#elif CG_FOG_MODE == 3
    c = vec4(mix(vec3(1.0), c.rgb, c.a), 1.0);   // multiplied: transparent leaves what is behind
#endif
    return c;
}
#endif
