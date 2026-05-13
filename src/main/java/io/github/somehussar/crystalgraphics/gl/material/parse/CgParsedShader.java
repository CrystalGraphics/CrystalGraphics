package io.github.somehussar.crystalgraphics.gl.material.parse;

import com.github.bsideup.jabel.Desugar;
import io.github.somehussar.crystalgraphics.api.material.CgRenderPassVariant;
import io.github.somehussar.crystalgraphics.api.material.CgRenderQueue;
import io.github.somehussar.crystalgraphics.gl.material.CgMaterialProperty;

import java.util.List;

/**
 * Immutable structural representation of a parsed {@code .shader} file.
 *
 * <p>Produced by {@link CgShaderParser#parse(String)} and consumed by
 * {@link CgMaterialShaderCompiler} to generate per-pass GLSL source strings.</p>
 *
 * <p>Per-pass data (v2f struct, global declarations, vertex body, fragment body,
 * render state, fragment output) lives on each {@link CgParsedPass} inside
 * {@link #passes()}. This record holds only material-level data that applies to
 * the shader as a whole.</p>
 *
 * @param shaderType
 *     Shader type as written after {@code #type} (e.g. {@code "spatial"}).
 *     Only {@code "spatial"} is valid in MVP.
 * @param properties
 *     Ordered list of material properties from the {@code Properties { }} block.
 *     Each entry holds the declaration, parsed default value, and current value.
 *     May be empty but never null.
 * @param featureNames
 *     Ordered feature flag names from {@code #pragma cg_feature} declarations.
 *     Always {@link java.util.Collections#emptyList()} if none declared.
 * @param renderQueue
 *     Numeric render queue priority parsed from the optional {@code Queue = "..."} keyword.
 *     Defaults to {@link CgRenderQueue#GEOMETRY} value (2000) when absent.
 * @param renderType
 *     Material-level {@code "RenderType"} tag value (e.g. {@code "Opaque"},
 *     {@code "Transparent"}). Defaults to {@code "Opaque"} when absent or unknown.
 * @param castShadows
 *     {@code false} when the material-level {@code Tags} block contains
 *     {@code "CastShadows" = "Off"}; {@code true} in all other cases (including
 *     when the tag is absent). Controls whether the shadow auto-generation ladder
 *     runs during {@code recompile()}.
 * @param passes
 *     Ordered, unmodifiable list of parsed {@link CgParsedPass} records.
 *     Contains at least one entry — the parser throws
 *     {@link CgShaderParseException} when no {@code Pass { }} blocks are found.
 *     Duplicate Depth/ShadowCaster passes are silently removed (first kept).
 */
@Desugar
public record CgParsedShader(String shaderType, List<CgMaterialProperty> properties,
                              List<String> featureNames, int renderQueue,
                              String renderType, boolean castShadows,
                              List<CgParsedPass> passes) {

    /**
     * Returns the first pass whose {@code name()} equals {@code name},
     * or {@code null} if no such pass exists.
     *
     * @param name the authored or auto-assigned pass name to look up
     * @return matching {@link CgParsedPass}, or {@code null}
     */
    public CgParsedPass getPassByName(String name) {
        for (CgParsedPass pass : passes) {
            if (pass.name().equals(name)) return pass;
        }
        return null;
    }

    /**
     * Returns the first pass whose {@code lightMode()} equals {@code lightMode},
     * or {@code null} if no such pass exists.
     *
     * <p>Use the {@code LIGHT_MODE_*} string constants from {@link CgParsedPass}
     * (package-private) or {@link CgRenderPassVariant#lightModeName()}
     * for the lookup value.</p>
     *
     * @param lightMode the LightMode tag value to look up (e.g. {@code "Forward"})
     * @return matching {@link CgParsedPass}, or {@code null}
     */
    public CgParsedPass getPassByLightMode(String lightMode) {
        for (CgParsedPass pass : passes) {
            if (pass.lightMode().equals(lightMode)) return pass;
        }
        return null;
    }
}
