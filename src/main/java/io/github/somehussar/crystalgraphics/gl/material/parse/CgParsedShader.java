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
 * @param vertexBody
 * Content of the {@code void vertex(out v2f o) \{ \}} block (body only, no braces).
 * @param fragmentBody
 * Content of the {@code void fragment(in v2f i, out vec4 fragColor) \{ \}} block
 * (body only, no braces).
 * @param renderState
 * Composite render state parsed from the optional {@code RenderState { }} block.
 * Defaults to {@link CgRenderState#DEFAULT} when the block is absent.
 * Real parsing of the block is implemented in T9; this field carries the placeholder
 * default until then.
 * @param renderQueue
 * Numeric render queue priority parsed from the optional {@code RenderQueue} keyword.
 * Defaults to {@link CgRenderQueue#GEOMETRY} value (2000) when absent.
 * Real parsing is implemented in T9.
 */
@Desugar
public record CgParsedShader(String shaderType, List<CgMaterialProperty> properties, String v2fStructBody,
                             String globalDecls, String vertexBody, String fragmentBody,
                             CgRenderState renderState, int renderQueue) {

}
