package com.crystalgraphics.gl.material.parse;

import com.github.bsideup.jabel.Desugar;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.state.CgRenderState;

/**
 * Immutable structural representation of a single {@code Pass { }} block parsed from a
 * CrystalShader {@code .shader} file.
 *
 * <p>Each {@code Pass} block owns its own render state, v2f struct, vertex body, fragment body,
 * and fragment output descriptor. Produced by {@link CgShaderParser} from each
 * {@code Pass { }} block in the source and consumed by {@link CgMaterialShaderCompiler}
 * to generate per-pass GLSL source strings.</p>
 *
 * <p>All fields are non-null by contract:</p>
 * <ul>
 *   <li>{@link #lightMode()} — one of {@link #LIGHT_MODE_FORWARD}, {@link #LIGHT_MODE_SHADOW_CASTER},
 *       or {@link #LIGHT_MODE_DEPTH}. Auto-defaulted to {@code "Forward"} by the parser if absent
 *       or unknown.</li>
 *   <li>{@link #name()} — auto-assigned by the parser if absent:
 *       Forward passes → {@code "Pass0"}, {@code "Pass1"}, …;
 *       ShadowCaster → {@code "ShadowCaster"}; Depth → {@code "Depth"}.</li>
 *   <li>{@link #renderState()} — defaults to {@link CgRenderState#DEFAULT} when the
 *       {@code RenderState { }} block is absent from the pass.</li>
 *   <li>{@link #v2fStructBody()}, {@link #globalDecls()}, {@link #vertexBody()},
 *       {@link #fragmentBody()} — may be empty strings but never {@code null}.</li>
 *   <li>{@link #fragOutput()} — never {@code null}; single-output path when no MRT struct.</li>
 * </ul>
 *
 * @param lightMode
 *     LightMode tag value: one of {@link #LIGHT_MODE_FORWARD}, {@link #LIGHT_MODE_SHADOW_CASTER},
 *     or {@link #LIGHT_MODE_DEPTH}. Never null.
 * @param name
 *     User-assigned {@code "Name"} tag value, or auto-assigned name. Never null.
 * @param renderState
 *     Render state parsed from the optional {@code RenderState { }} block in this pass.
 *     Defaults to {@link CgRenderState#DEFAULT} when the block is absent. Never null.
 * @param v2fStructBody
 *     Raw content between {@code struct v2f \{} and its matching {@code \}} in this pass body,
 *     or the shared v2f body inherited from the top-level if the pass omits its own.
 *     Empty string if neither the pass nor the top level declares one. Never null.
 * @param globalDecls
 *     Everything between the closing {@code \};} of {@code struct v2f} and the start of
 *     {@code void vertex(} in this pass body. May be empty but never null.
 *     Includes any preamble {@code #}-directives from within the pass.
 * @param vertexBody
 *     Content of the {@code void vertex(out v2f o) \{ \}} block in this pass (body only,
 *     no braces). Never null.
 * @param fragmentBody
 *     Content of the {@code void fragment(...) \{ \}} block in this pass (body only,
 *     no braces). Never null.
 * @param fragOutput
 *     Fragment output descriptor produced by {@link CgFragOutputParser} for this pass.
 *     Holds the output param name, struct type name (MRT only), field names,
 *     resolved layout locations, and the annotation-free struct body. Never null.
 */
@Desugar
public record CgParsedPass(String lightMode, String name, CgRenderState renderState,
                            String v2fStructBody, String globalDecls,
                            String vertexBody, String fragmentBody,
                            CgFragOutputParser.FragOutput fragOutput) {

    // ── LightMode string constants ─────────────────────────────────────────────
    // All lightMode comparisons across this package use these constants — never scattered
    // raw string literals for LightMode values.

    /** LightMode tag value for standard forward-lit passes. */
    static final String LIGHT_MODE_FORWARD = CgRenderPassVariant.FORWARD.lightModeName();

    /** LightMode tag value for shadow-caster passes (depth from light). */
    static final String LIGHT_MODE_SHADOW_CASTER = CgRenderPassVariant.SHADOW.lightModeName();

    /** LightMode tag value for depth-only pre-pass (v2 — not executed in MVP). */
    static final String LIGHT_MODE_DEPTH = CgRenderPassVariant.DEPTH.lightModeName();
}
