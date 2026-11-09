// canvas/image.shader from a layer of a 2D array: the quad's atlasLayer picks it (a sheet of cells, glyph pages).

#type none
#pragma cg_use quad
#pragma cg_feature MASK
#pragma cg_feature SCREEN
#include "crystalgraphics:shaders/lib/canvas.glsl"
#include "crystalgraphics:shaders/lib/texel.glsl"

Tags { "RenderType" = "Transparent" }
Queue = "Transparent"

Properties {
    _MainTex ("Image layers", sampler2DArray) = "white"
}

struct v2f {
    vec2 uv;
    vec2 param;
    float layer;
    vec4 color;
};

Pass {
    Tags { "LightMode" = "Forward" }

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
        o.layer = CG_QUAD_ATLAS_LAYER;
        o.color = CG_QUAD_COLOR;
    }

    void fragment(in v2f i, out vec4 fragColor) {
#ifdef SCREEN
        vec3 uvw = vec3(CG_QUAD_EDGE_UV(i.param), i.layer);
        vec4 texel = CG_QUAD_EDGE_ROTATED
                ? cg_texel_aa_sample(_MainTex, uvw, CG_QUAD_UV_RECT, CG_QUAD_EDGE_FILTER)
                : texture(_MainTex, uvw);
        vec4 colour = vec4(i.color.rgb, i.color.a * CG_QUAD_EDGE_COVERAGE(i.param));
#else
        vec4 texel = texture(_MainTex, vec3(i.uv, i.layer));
        vec4 colour = i.color;
#endif
#ifdef MASK
        fragColor = cg_canvas_colour(texel, colour, true);
#else
        fragColor = cg_canvas_colour(texel, colour, false);
#endif
    }
}
