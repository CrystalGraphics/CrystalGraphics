package io.github.somehussar.crystalgraphics.gl.material.parse;

import com.github.bsideup.jabel.Desugar;
import io.github.somehussar.crystalgraphics.api.material.CgRenderQueue;
import io.github.somehussar.crystalgraphics.api.state.CgRenderState;
import io.github.somehussar.crystalgraphics.gl.material.CgMaterialProperty;

import java.util.List;

/**
 * Immutable structural representation of a parsed {@code .shader} file.
 *
 * <p>Produced by {@link CgShaderParser#parse(String)} and consumed by
 * {@link CgMaterialShaderCompiler} to generate the complete GLSL
 * vertex and fragment source strings.</p>
 *
 * @param shaderType
 * Shader type as written after {@code #type} (e.g. {@code "spatial"}).
 * Only {@code "spatial"} is valid in MVP.
 * @param properties
 * Ordered list of material properties from the {@code Properties { }} block.
 * Each entry holds the declaration, parsed default value, and current value.
 * May be empty but never null.
 * @param v2fStructBody
 * Raw content between {@code struct v2f \{} and its matching {@code \}}.
 * Does not include the braces themselves.
 * @param globalDecls
 * Everything between the closing {@code \};} of {@code struct v2f} and
 * the start of {@code void vertex(}. May be empty but never null.
 * For MRT shaders, this already contains the <em>clean</em> struct body
 * (with {@code : RTN} annotations stripped) — populated by
 * {@link CgFragOutputParser}.
 * @param vertexBody
 * Content of the {@code void vertex(out v2f o) \{ \}} block (body only, no braces).
 * @param fragmentBody
 * Content of the {@code void fragment(in v2f i, out vec4 fragColor) \{ \}} block
 * (body only, no braces).
 * @param renderState
 * Composite render state parsed from the optional {@code RenderState { }} block.
 * Defaults to {@link CgRenderState#DEFAULT} when the block is absent.
 * @param renderQueue
 * Numeric render queue priority parsed from the optional {@code RenderQueue} keyword.
 * Defaults to {@link CgRenderQueue#GEOMETRY} value (2000) when absent.
 * @param fragOutput
 * Fragment output descriptor produced by {@link CgFragOutputParser}.
 * Holds the output param name, struct type name (MRT only), field names,
 * resolved layout locations, and the annotation-free struct body.
 * Never null; use {@link CgFragOutputParser.FragOutput#isMrt()} to distinguish MRT from single-output.
 * @param featureNames
 * Ordered feature flag names from {@code #pragma cg_feature} declarations. Always
 * {@link java.util.Collections#emptyList()} in Wave A (Wave E replaces with real parsing).
 */
@Desugar
public record CgParsedShader(String shaderType, List<CgMaterialProperty> properties, String v2fStructBody,
                               String globalDecls, String vertexBody, String fragmentBody,
                               CgRenderState renderState, int renderQueue,
                               CgFragOutputParser.FragOutput fragOutput, List<String> featureNames) {

}
