package com.crystalgraphics.gl.material.parse;

import com.crystalgraphics.api.buffer.CgBufferField;
import com.crystalgraphics.api.buffer.CgBufferFormat;
import com.crystalgraphics.api.material.CgAttachedBuffer;
import com.crystalgraphics.api.vertex.CgVertexAttribute;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.buffer.shader.CgUniformBuffer;
import com.crystalgraphics.render.shader.CgBindingModel;

/**
 * Generates GLSL declaration blocks for user-attached buffers (SSBO, UBO)
 * and vertex attribute input declarations for a given {@link CgVertexFormat}.
 *
 * <p>No GL calls, no instance state. All core methods are package-private and take raw
 * {@code (CgBufferFormat, String)} parameters — no {@code CgShaderBuffer} required.
 * This is the test seam: unit tests call core methods directly without a GL context.</p>
 *
 * <p>Public facade methods delegate to the core methods and accept the typed wrapper objects
 * used at compile time.</p>
 */
public final class CgGlslEmitter {

    private CgGlslEmitter() {
        throw new AssertionError("CgGlslEmitter is not instantiable");
    }

    /**
     * Generates {@code in <glslType> <name>;} declarations for all attributes in a vertex format.
     *
     * <p>Output shape:</p>
     * <pre>
     * // Vertex attributes (format: spatial)
     * in vec3 cg_Position;
     * in vec2 cg_TexCoord0;
     * in vec3 cg_Normal;
     * </pre>
     *
     * <p>Only called from {@code buildVertexSource()} in {@link CgMaterialShaderCompiler} —
     * never from the fragment stage, because vertex attributes are not visible there.</p>
     *
     * @param format the vertex format whose attributes to emit; must not be null
     * @return GLSL {@code in} declaration block string, terminated with a newline
     */
    static String emitVertexInputs(CgVertexFormat format) {
        StringBuilder sb = new StringBuilder();
        sb.append("// Vertex attributes (format: ").append(format.getKey()).append(")\n");
        for (int i = 0; i < format.getAttributeCount(); i++) {
            CgVertexAttribute attr = format.getAttribute(i);
            sb.append("in ").append(attr.getGlslType()).append(' ').append(attr.getName()).append(";\n");
        }
        return sb.toString();
    }

    /**
     * Generates the SSBO block declaration for an attached buffer, bound at {@code slot}.
     *
     * @param ab the attached buffer descriptor
     */
    public static String emitSsbo(CgAttachedBuffer ab, CgBindingModel.Slot slot) {
        return emitSsbo(ab.getBuffer().getFormat(), ab.getBuffer().getName(), ab.getMacroName(), true, slot);
    }

    /**
     * Generates a flat {@code layout(std140) uniform} block declaration for a UBO, bound at {@code slot}.
     *
     * @param ubo the uniform buffer
     */
    public static String emitUbo(CgUniformBuffer ubo, CgBindingModel.Slot slot) {
        return emitUbo(ubo.getFormat(), ubo.getName(), slot);
    }

    /**
     * Generates the SSBO block declaration for a buffer with the given format, GL buffer name,
     * and user macro name.
     *
     * @param format     the buffer's field layout (must be STD430)
     * @param bufferName the value of {@code CgShaderBuffer.getName()} — used as the GLSL block
     *                   interface name; must match exactly for {@code wireShader()} to succeed
     * @param macroName  the user-facing macro, e.g. {@code "FONT_METRICS"}
     */
    static String emitSsbo(CgBufferFormat format, String bufferName, String macroName) {
        return emitSsbo(format, bufferName, macroName, true, null);
    }

    /**
     * {@link #emitSsbo(CgBufferFormat, String, String)}, writable where {@code readonly} is false: what a kernel
     * declares an engine buffer as. Bound at {@code slot}, or where the program is wired with none.
     */
    public static String emitSsbo(CgBufferFormat format, String bufferName, String macroName, boolean readonly,
                                  CgBindingModel.Slot slot) {
        String structName = format.getGlslName();
        String arrayName  = "_cg_" + lowerFirst(structName) + "Arr";

        StringBuilder sb = new StringBuilder(256);
        appendStructDecl(sb, format, structName);
        sb.append('\n');
        if (slot != null) sb.append(slot.glsl());
        sb.append(readonly ? "layout(std430) readonly buffer " : "layout(std430) buffer ").append(bufferName).append(" {\n");
        sb.append("    ").append(structName).append(' ').append(arrayName).append("[];\n");
        sb.append("};\n");
        sb.append('\n');
        sb.append("#define ").append(macroName).append("(n) ").append(arrayName).append("[n]\n");
        return sb.toString();
    }

    /**
     * Generates a flat {@code layout(std140) uniform} block declaration.
     *
     * <p>No struct is emitted — fields land directly in the block's scope, accessible
     * in the shader as plain {@code fieldName} without any instance prefix.
     * No macro is generated — UBO data is a single instance, not an indexed array.</p>
     *
     * @param format    the UBO's field layout (should be STD140)
     * @param blockName the value of {@code CgUniformBuffer.getName()} — used as the GLSL
     *                  block name; must match exactly for {@code wireShader()} to succeed
     */
    public static String emitUbo(CgBufferFormat format, String blockName) {
        return emitUbo(format, blockName, null);
    }

    /** {@link #emitUbo(CgBufferFormat, String)} bound at {@code slot}, or where the program is wired with none. */
    public static String emitUbo(CgBufferFormat format, String blockName, CgBindingModel.Slot slot) {
        StringBuilder sb = new StringBuilder(128);
        if (slot != null) sb.append(slot.glsl());
        sb.append("layout(std140) uniform ").append(blockName).append(" {\n");
        for (int i = 0; i < format.getFieldCount(); i++) {
            CgBufferField field = format.getField(i);
            sb.append("    ").append(field.getType().getGlslName())
              .append(' ').append(field.getName()).append(";\n");
        }
        sb.append("};\n");
        return sb.toString();
    }

    /** Emits a {@code struct <structName> { fields };} declaration from the format. */
    private static void appendStructDecl(StringBuilder sb, CgBufferFormat format, String structName) {
        sb.append("struct ").append(structName).append(" {\n");
        for (int i = 0; i < format.getFieldCount(); i++) {
            CgBufferField field = format.getField(i);
            sb.append("    ").append(field.getType().getGlslName())
              .append(' ').append(field.getName()).append(";\n");
        }
        sb.append("};\n");
    }

    /** Lowercases the first character of {@code s}; returns {@code s} unchanged if empty. */
    static String lowerFirst(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
