// The canvas's default image: a quad's texture times its colour, under whatever blend its material state gives.
// Drawn through CgCanvasMaterials, never loaded by path: the blend and the depth test are the instance's.
// MASK: the texture's alpha masks the colour instead. No texture bound draws the colour alone.
// SCREEN: a rotated quad antialiases its edges and filters its texels, as the box does (env/buffer/quad.glsl).

#type none
#pragma cg_use quad
#pragma cg_feature MASK
#pragma cg_feature SCREEN
#include "crystalgraphics:shaders/lib/canvas.glsl"
#include "crystalgraphics:shaders/lib/texel.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

Properties {
    _MainTex ("Image", sampler2D) = "white"
}

struct v2f {
    vec2 uv;
    vec2 param;
    vec4 color;
};

Pass {
    Tags { "LightMode" = "Forward" }

    // No Blend: CgMaterialState gives it. Tested against the scene in the world; a screen instance turns that off.
    RenderState {
        Cull OFF
        DepthTest LEQUAL
        DepthWrite OFF
    }

    void vertex(out v2f o) {
#ifdef SCREEN
        o.param = CG_QUAD_EDGE_PARAM;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(CG_QUAD_EDGE_WORLD_POS(o.param), 1.0);
#else
        o.param = CG_VERTEX_CORNER;
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(CG_QUAD_WORLD_POS, 1.0);
#endif
        o.uv = CG_QUAD_UV;
        o.color = CG_QUAD_COLOR;
    }

    void fragment(in v2f i, out vec4 fragColor) {
#ifdef SCREEN
        // Coverage scales the colour's alpha, which every blend mode weighs by.
        vec2 uv = CG_QUAD_EDGE_UV(i.param);
        vec4 texel = CG_QUAD_EDGE_ROTATED
                ? cg_texel_aa_sample(_MainTex, uv, CG_QUAD_UV_RECT, CG_QUAD_EDGE_FILTER)
                : texture(_MainTex, uv);
        vec4 colour = vec4(i.color.rgb, i.color.a * CG_QUAD_EDGE_COVERAGE(i.param) * CG_QUAD_OPACITY);
#else
        vec4 texel = texture(_MainTex, i.uv);
        vec4 colour = vec4(i.color.rgb, i.color.a * CG_QUAD_OPACITY);
#endif
#ifdef MASK
        fragColor = cg_canvas_colour(texel, colour, true);
#else
        fragColor = cg_canvas_colour(texel, colour, false);
#endif
    }
}
