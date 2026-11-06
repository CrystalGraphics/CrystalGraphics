package com.crystalgraphics.render.draw;

import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgColorMask;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.gl.material.parse.CgMaterialShaderCompiler;
import com.crystalgraphics.gl.material.parse.CgParsedShader;

/**
 * A way a pipeline draws its pass other than plainly: how its vertices are placed ({@link #SKINNED}) or what its
 * fragment writes (overdraw counts, a glow folded in, depth alone ...). A pipeline holds a set of them, each compiled
 * in as its keyword; {@link CgPipeline#as} adds one, and the table below is the one place that says which combine, as
 * Unreal's mesh passes filter a shader's outputs and vertex factory in {@code ShouldCompilePermutation}.
 *
 * <pre>{@code
 * CgPipeline forward = material.pipeline(CgInstanceKind.OBJECT);
 * CgPipeline prepass = forward.as(CgPipelineForm.DEPTH);              // null for a material with no prepass
 * if (prepass != null) chunks.draw(prepass, bindings, mesh);
 *
 * // Forms combine where the table allows: a folded glow, marched as a volume
 * CgPipeline folded = forward.as(CgPipelineForm.FOLD);
 * CgPipeline marching = (folded != null ? folded : forward).as(CgPipelineForm.VOLUME);
 * }</pre>
 *
 * <ul>
 *   <li>{@link CgPipeline#as} answers null where a form does not apply: another pass, an MRT output where it writes
 *       one target, a form it excludes already held, or the shader's own refusal ({@link #admits}).
 *       {@link #SKINNED} and {@link #OVERDRAW} always apply.</li>
 *   <li>A new form is a constant here, its row in the table, and its keyword's handling in
 *       {@code CgMaterialShaderCompiler}.</li>
 * </ul>
 */
public enum CgPipelineForm {

    /** Vertices placed by their bones from the draw's palette (Unreal's GPU skin vertex factory). */
    SKINNED(CgMaterialShaderCompiler.SKINNED, false, false),
    /** A count of 1 in red into a target with no depth, with the pass's depth test and discard: the overdraw view. */
    OVERDRAW(CgMaterialShaderCompiler.DEBUG_OVERDRAW, false, false) {
        @Override
        CgRenderState state(CgRenderState own) {
            return CgRenderState.builder().blend(CgBlendState.SUM).depth(CgDepthState.NONE).cull(own.getCull())
                    .colorMask(CgColorMask.ALL).build();
        }
    },
    /** Its Emissive pass drawn in the same draw, into the HDR scene, under its own blend. */
    FOLD(CgMaterialShaderCompiler.SCENE_FOLD, true, false) {
        @Override
        boolean admits(CgParsedShader parsed, CgRenderState own) {
            return CgMaterialShaderCompiler.sceneFolds(parsed);
        }
    },
    /**
     * Depth alone where it is solid: a {@code "Depth" = "Prepass"} material's prepass, its fragment run up to
     * {@code cg_Clip}. Both programs declare {@code invariant gl_Position}, so its colour draw passes its depth test
     * exactly where this wrote.
     */
    DEPTH(CgMaterialShaderCompiler.DEPTH_PREPASS, true, false) {
        @Override
        boolean admits(CgParsedShader parsed, CgRenderState own) {
            return parsed.depthMode() == CgParsedShader.DepthMode.PREPASS;
        }

        @Override
        CgRenderState state(CgRenderState own) {
            CgDepthState depth = own.getDepth();
            return CgRenderState.builder().blend(CgBlendState.DISABLED)
                    .depth(depth != null && depth.test() ? new CgDepthState(true, true, depth.compareFunc()) : CgDepthState.TEST_WRITE)
                    .cull(own.getCull()).colorMask(CgColorMask.NONE).build();
        }
    },
    /** Beside its colour, at location 1, the depth {@code CG_VOLUME_DEPTH(eye)} names: what a temporal resolve reprojects by. */
    VOLUME(CgMaterialShaderCompiler.VOLUME_TARGET, true, true),
    /** Beside its colour, at location 1, the vertex normal: what the world lights read for our surfaces. */
    NORMALS(CgMaterialShaderCompiler.NORMAL_TARGET, true, true),
    /** In place of its colour, the custom data and shading model its fragment names, for a post material reading them. */
    CUSTOM_DATA(CgMaterialShaderCompiler.CUSTOM_DATA_TARGET, true, true),
    /**
     * Black at its colour's alpha, under its blend, hidden by the scene's depth: a surface glowing nothing, covering the
     * glows behind it where glows are drawn apart from the scene. Refused where it adds ({@code dst} factor ONE), since
     * it would cover nothing.
     */
    COVER(CgMaterialShaderCompiler.EMISSION_COVER, true, true) {
        @Override
        boolean admits(CgParsedShader parsed, CgRenderState own) {
            CgBlendState blend = own.getBlend();
            return blend == null || !blend.adds();
        }

        @Override
        CgRenderState state(CgRenderState own) {
            // Unset is the transparent pass's alpha blend; the glow pass's own is ONE ONE.
            CgBlendState blend = own.getBlend();
            return CgRenderState.builder().blend(blend != null ? blend : CgBlendState.ALPHA)
                    .depth(CgDepthState.NONE).cull(own.getCull()).colorMask(CgColorMask.ALL).build();
        }
    },
    /** Full coverage, alpha 1, whatever its fragment wrote: an opaque draw redrawn into a graded layer. */
    SOLID(CgMaterialShaderCompiler.SOLID_COVER, true, true);

    static {
        // The table: a form, then the forms a pipeline holding any of them cannot take it with.
        FOLD.excludes(OVERDRAW, COVER);
        DEPTH.excludes(OVERDRAW, COVER, FOLD);
        VOLUME.excludes(OVERDRAW, COVER, DEPTH);
        NORMALS.excludes(OVERDRAW, COVER, FOLD, DEPTH, VOLUME);
        CUSTOM_DATA.excludes(OVERDRAW, COVER, FOLD, DEPTH, VOLUME, NORMALS);
        COVER.excludes(OVERDRAW);
        SOLID.excludes(OVERDRAW, COVER, FOLD, DEPTH, VOLUME, NORMALS);
        // Overdraw counts what is drawn, so it takes any pipeline, dropping the forms that would change what that is.
        OVERDRAW.drops(COVER, FOLD);
    }

    static final CgPipelineForm[] ALL = values();

    final String keyword;
    /** Only a Forward pass takes it. */
    final boolean forwardOnly;
    /** Only a pass writing one target takes it: it writes another, or writes in place of that one. */
    final boolean singleOutput;
    int excludes, drops;

    CgPipelineForm(String keyword, boolean forwardOnly, boolean singleOutput) {
        this.keyword = keyword;
        this.forwardOnly = forwardOnly;
        this.singleOutput = singleOutput;
    }

    /** Its bit in a pipeline's set of forms. */
    int bit() {
        return 1 << ordinal();
    }

    /** Whether a shader takes it, beyond the table; {@code parsed} is never null. */
    boolean admits(CgParsedShader parsed, CgRenderState own) {
        return true;
    }

    /** The render state it draws under, from the pipeline's own. */
    CgRenderState state(CgRenderState own) {
        return own;
    }

    private void excludes(CgPipelineForm... forms) {
        for (CgPipelineForm f : forms) excludes |= f.bit();
    }

    private void drops(CgPipelineForm... forms) {
        for (CgPipelineForm f : forms) drops |= f.bit();
    }
}
