package io.github.somehussar.crystalgraphics.api.material;

import io.github.somehussar.crystalgraphics.api.buffer.CgBufferFormat;
import io.github.somehussar.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import io.github.somehussar.crystalgraphics.gl.buffer.shader.CgUniformBuffer;
import lombok.Getter;

/**
 * Immutable descriptor for a user-defined auxiliary buffer (SSBO, TBO, or UBO) attached to a
 * {@link CgMaterial} for GLSL auto-injection.
 *
 * <h3>SSBO/TBO path ({@code of(CgShaderBuffer, macroName)})</h3>
 * <p>Per-material auxiliary structured data that your shader needs beyond what the engine's
 * {@code cg_env.glsl} provides: font glyph metrics, per-light data, bindless texture handle
 * pools (via {@code uvec2} or {@code uint64_t} fields), tile material properties, animation
 * curve tables, etc. Access via {@code MACRO_NAME(n).fieldName}.</p>
 *
 * <h3>UBO path ({@code of(CgUniformBuffer)})</h3>
 * <p>Single-instance uniform data: scene parameters, per-pass constants, camera extras, light
 * properties — anything the same for all instances in a draw. Fields land in direct shader
 * scope (no macro, no index). Use {@link #isUbo()} to distinguish from SSBO/TBO entries.</p>
 *
 * <h3>What they are NOT</h3>
 * <p>Engine pipeline buffers — {@code CgObjectDataBuffer}, {@code CgFrameBlock} — are owned
 * by {@code CgMaterialPipeline} and declared in {@code cg_env.glsl}. Do NOT pass them here;
 * doing so produces duplicate GLSL declarations that fail to compile.</p>
 *
 * <h3>TBO path limitations</h3>
 * <p>The TBO float path uses {@code samplerBuffer} + {@code GL_RGBA32F}: only float-family
 * types are valid ({@link io.github.somehussar.crystalgraphics.api.buffer.CgGpuType#isTboCompatible()}
 * must return {@code true} for every field). INT, UINT, BOOL, IVEC*, UVEC*, INT64, UINT64 are
 * SSBO-only. The format stride must also be a multiple of 16 bytes (one TBO texel = 16 bytes).
 * These constraints are validated at compile time (not at attach time).</p>
 *
 * <h3>Naming contract</h3>
 * <p>{@code buffer.getName()} is used directly as the GLSL block interface name (SSBO path) or
 * TBO {@code samplerBuffer} uniform name or UBO block name. This is required for the existing
 * {@link CgShaderBuffer#wireShader} implementations to locate the block/sampler in the linked
 * program via {@code glGetProgramResourceIndex} / {@code glGetUniformLocation}. Any mismatch
 * will cause silent GL wiring failure at runtime.</p>
 */
public final class CgAttachedBuffer {

    /** The user's buffer. Never an engine pipeline buffer. */
    @Getter private final CgShaderBuffer buffer;

    /** User-facing macro name, e.g. {@code "FONT_METRICS"} → macro {@code FONT_METRICS(n)} in GLSL. */
    @Getter private final String macroName;

    /** GLSL struct type name, equal to {@code format.getGlslName()}, e.g. {@code "FontMetrics"}. */
    @Getter private final String structName;

    /**
     * Internal SSBO array instance name, derived as {@code "_cg_" + lowerFirst(structName) + "Arr"}.
     * Not user-visible; referenced only in the macro expansion.
     */
    @Getter private final String ssboArrayName;

    /**
     * Internal TBO getter function name, derived as {@code "_cg_get" + structName}.
     * Not user-visible; referenced only in the macro expansion.
     */
    @Getter private final String tboGetterName;

    /**
     * -- GETTER --
     *  Returns 
     *  if this entry wraps a UBO (created via 
     * ).
     *  UBO entries have no macroName, structName, or accessor names.
     */
    @Getter private final boolean isUbo;

    private CgAttachedBuffer(CgShaderBuffer buffer, String macroName,
                              String structName, String ssboArrayName, String tboGetterName, boolean isUbo) {
        this.buffer        = buffer;
        this.macroName     = macroName;
        this.structName    = structName;
        this.ssboArrayName = ssboArrayName;
        this.tboGetterName = tboGetterName;
        this.isUbo = isUbo;
    }

    /**
     * Creates and validates a {@code CgAttachedBuffer}.
     *
     * @param buffer    the buffer to attach; format must use {@code STD430}
     * @param macroName uppercase GLSL-style identifier, e.g. {@code "FONT_METRICS"}
     * @throws IllegalArgumentException if any validation rule is violated
     */
    public static CgAttachedBuffer of(CgShaderBuffer buffer, String macroName) {
        if (buffer == null) {
            throw new IllegalArgumentException("buffer must not be null");
        }

        if (macroName == null || !macroName.matches("^[A-Z][A-Z0-9_]*$")) {
            throw new IllegalArgumentException(
                "macroName must be an uppercase GLSL-style identifier (e.g. \"FONT_METRICS\"); got: " + macroName);
        }

        CgBufferFormat format = buffer.getFormat();
        if (format.getMemoryLayout() != CgBufferFormat.MemoryLayout.STD430) {
            throw new IllegalArgumentException(
                "attach() requires a STD430 buffer (SSBO/TBO). UBOs use STD140 and cannot be auto-injected. " +
                "Use CgShaderBindings.ubo() instead.");
        }

        String structName = format.getGlslName();
        if (structName == null || !structName.matches("^[A-Za-z_][A-Za-z0-9_]*$")) {
            throw new IllegalArgumentException(
                "CgBufferFormat glslName \"" + structName + "\" is not a valid GLSL identifier");
        }
        if (structName.startsWith("gl_")) {
            throw new IllegalArgumentException(
                "CgBufferFormat glslName \"" + structName + "\" is not a valid GLSL identifier");
        }

        String ssboArrayName = "_cg_" + lowerFirst(structName) + "Arr";
        String tboGetterName = "_cg_get" + structName;

        return new CgAttachedBuffer(buffer, macroName, structName, ssboArrayName, tboGetterName, false);
    }

    /**
     * Creates a {@code CgAttachedBuffer} wrapping a UBO for flat-block GLSL injection.
     *
     * @param buffer the UBO to attach; format must use {@code STD140}
     * @throws IllegalArgumentException if buffer is null, format is not STD140, or block name
     *                                   is not a valid GLSL identifier or starts with {@code gl_}
     */
    public static CgAttachedBuffer of(CgUniformBuffer buffer) {
        if (buffer == null) {
            throw new IllegalArgumentException("buffer must not be null");
        }
        if (buffer.getFormat().getMemoryLayout() != CgBufferFormat.MemoryLayout.STD140) {
            throw new IllegalArgumentException(
                "of(CgUniformBuffer) requires a STD140 buffer. Got: "
                + buffer.getFormat().getMemoryLayout());
        }
        String blockName = buffer.getName();
        if (blockName == null || !blockName.matches("^[A-Za-z_][A-Za-z0-9_]*$")) {
            throw new IllegalArgumentException(
                "buffer.getName() \"" + blockName + "\" is not a valid GLSL identifier");
        }
        if (blockName.startsWith("gl_")) {
            throw new IllegalArgumentException(
                "buffer.getName() \"" + blockName + "\" must not start with gl_");
        }
        return new CgAttachedBuffer(buffer, null, null, null, null, true);
    }

    /** Lowercases the first character of {@code s}; returns {@code s} unchanged if empty. */
    static String lowerFirst(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
