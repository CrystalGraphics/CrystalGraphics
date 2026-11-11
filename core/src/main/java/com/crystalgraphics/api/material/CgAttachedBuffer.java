package com.crystalgraphics.api.material;

import com.crystalgraphics.api.buffer.CgBufferFormat;
import com.crystalgraphics.gpu.resource.CgShaderBuffer;
import com.crystalgraphics.gpu.resource.CgUniformBuffer;
import lombok.Getter;

/**
 * Immutable descriptor for a user-defined auxiliary buffer (SSBO or UBO) attached to a
 * {@link CgMaterial} for GLSL auto-injection.
 *
 * <h3>SSBO path ({@code of(CgShaderBuffer, macroName)})</h3>
 * <p>Per-material auxiliary structured data that your shader needs beyond what the engine's
 * {@code cg_env.glsl} provides: font glyph metrics, per-light data, bindless texture handle
 * pools (via {@code uvec2} or {@code uint64_t} fields), tile material properties, animation
 * curve tables, etc. Access via {@code MACRO_NAME(n).fieldName}.</p>
 *
 * <h3>UBO path ({@code of(CgUniformBuffer)})</h3>
 * <p>Single-instance uniform data: scene parameters, per-pass constants, camera extras, light
 * properties — anything the same for all instances in a draw. Fields land in direct shader
 * scope (no macro, no index). Use {@link #isUbo()} to distinguish from SSBO entries.</p>
 *
 * <h3>What they are NOT</h3>
 * <p>Engine pipeline buffers — {@code CgObjectDataBuffer}, {@code CgViewBlock} — are owned
 * by the engine and declared in {@code cg_env.glsl}. Do NOT pass them here;
 * doing so produces duplicate GLSL declarations that fail to compile.</p>
 *
 * <h3>Naming contract</h3>
 * <p>{@code buffer.getName()} is the GLSL block interface name (SSBO) or UBO block name the material
 * compiler declares at the buffer's binding.</p>
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
     * -- GETTER --
     *  Returns 
     *  if this entry wraps a UBO (created via 
     * ).
     *  UBO entries have no macroName, structName, or accessor names.
     */
    @Getter private final boolean isUbo;

    private CgAttachedBuffer(CgShaderBuffer buffer, String macroName,
                              String structName, String ssboArrayName, boolean isUbo) {
        this.buffer        = buffer;
        this.macroName     = macroName;
        this.structName    = structName;
        this.ssboArrayName = ssboArrayName;
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
                "An engine token's storage buffer is STD430 (an SSBO); a uniform block takes CgAttachedBuffer.of(CgUniformBuffer).");
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
        return new CgAttachedBuffer(buffer, macroName, structName, ssboArrayName, false);
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
        return new CgAttachedBuffer(buffer, null, null, null, true);
    }

    /** Lowercases the first character of {@code s}; returns {@code s} unchanged if empty. */
    static String lowerFirst(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
