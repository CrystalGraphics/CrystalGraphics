// The canvas's text: each layer of a text style on bitmap, MSDF and MTSDF glyphs (CgTextBatch), painted by a colour
// or a gradient. The layer itself is lib/text_layer.glsl's, which a Text-domain material calls with its own paint.

#type none
#pragma cg_use quad
#pragma cg_use clip
#pragma cg_use text

// MSDF_MODE covers MSDF and MTSDF; bitmap is off. The atlas is RGBA8 (CgGlyphAtlas), measured clean for the
// median of .rgb (CgMsdfFieldStorageTest). .a, the true distance, drives wide effects, where 8 bits may band: dither,
// narrow the range, or move the atlas back to RGBA16F, in that order.
#pragma cg_feature MSDF_MODE
// A retained world label's glyphs (CgWorldText), drawn as OBJECT instances: each record is in its label's own space,
// and its node is the label's object record, whose model matrix places it under the firing's view.
#pragma cg_feature WORLD_LABEL
#pragma cg_permutation MSDF_MODE WORLD_LABEL   // four fixed instances (CgTextMaterials), never toggled

#include "crystalgraphics:shaders/lib/text_layer.glsl"

Tags {
    "RenderType" = "Transparent"
}

// At or past 2500: no generated depth pass.
Queue = "Overlay"

Properties {
    _MainTex ("Atlas Texture", sampler2DArray) = "white"
    // Every gradient drawn, a row each (CgTextGradients); a quad's custom0 names its row and maps it.
    _Gradients ("Gradient ramps", sampler2D) = "white"
}

struct v2f {
    vec2 uv;
    vec4 color;
    float atlasLayer;
    // The unit quad's own parameter: a gradient's mapping and the analytic rect shadows read it.
    vec2 param;
    // text_gamma_terms of this glyph's colour under each CgTextGamma level; constant across the quad.
    vec4 gammaSmall;
    vec4 gammaLarge;
};

Pass {
    Tags {
        "LightMode" = "Forward"
        "Name"      = "Text"
    }

    RenderState {
        // Premultiplied, so an additive layer (CgTextMaterials, ONE ONE) draws from the same output.
        Blend ONE ONE_MINUS_SRC_ALPHA
        Cull OFF

        // Tested, never written: a draw's layers are coplanar and would depth-fight (TextMeshPro's ZWrite Off).
        DepthTest LEQUAL
        DepthWrite OFF
    }

    // The pose is baked into each record; a label's placement already holds the view. TextData (cg_use text) is
    // CgTextGamma's two levels and a depth pass's cut; gamma corrects a fill's coverage only.
    void vertex(out v2f o) {
#ifdef WORLD_LABEL
        int label = int(QUAD_DATA(CG_INSTANCE_ID).node);
        mat4 place = cg_Objects[label].modelMatrix;
        vec3 local = QUAD_DATA(CG_INSTANCE_ID).origin + CG_VERTEX_CORNER.x * QUAD_DATA(CG_INSTANCE_ID).right
                + CG_VERTEX_CORNER.y * QUAD_DATA(CG_INSTANCE_ID).up;
        gl_Position = cg_ProjMatrix * (place * vec4(local, 1.0));
#else
        gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(CG_QUAD_WORLD_POS, 1.0);
#endif
        o.uv         = CG_QUAD_UV;
        o.color      = CG_QUAD_COLOR;
        o.atlasLayer = CG_QUAD_ATLAS_LAYER;
        o.param      = CG_VERTEX_CORNER;
        o.gammaSmall = text_gamma_terms(CG_QUAD_COLOR.rgb, u_TextGammaSmall);
        o.gammaLarge = text_gamma_terms(CG_QUAD_COLOR.rgb, u_TextGammaLarge);
    }

    void fragment(in v2f i, out vec4 fragColor) {
        vec4 paint = i.color;
        // A runtime branch, not a keyword: a variant would break the batch.
        if (CG_QUAD_CUSTOM0.w > 0.0) {
            paint *= texture(_Gradients, vec2(cg_text_gradient_t(i.param), CG_QUAD_CUSTOM0.w));
        }
        fragColor = cg_text_layer(_MainTex, i.uv, i.atlasLayer, i.param, paint, i.gammaSmall, i.gammaLarge);
    }
}
