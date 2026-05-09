package io.github.somehussar.crystalgraphics.gl.material;

import io.github.somehussar.crystalgraphics.api.buffer.CgBufferField;
import io.github.somehussar.crystalgraphics.api.buffer.CgBufferFormat;
import io.github.somehussar.crystalgraphics.api.buffer.CgGpuType;
import io.github.somehussar.crystalgraphics.api.material.CgAttachedBuffer;
import io.github.somehussar.crystalgraphics.api.shader.CgPreprocessorException;
import io.github.somehussar.crystalgraphics.gl.buffer.shader.CgUniformBuffer;

/**
 * Generates GLSL declaration blocks for user-attached buffers (SSBO, TBO, UBO).
 *
 * <p>No GL calls, no instance state. All core methods are package-private and take raw
 * {@code (CgBufferFormat, String)} parameters — no {@code CgShaderBuffer} required.
 * This is the test seam: unit tests call core methods directly without a GL context.</p>
 *
 * <p>Public facade methods delegate to the core methods and accept the typed wrapper objects
 * used at compile time.</p>
 */
final class CgBufferGlslEmitter {

    private CgBufferGlslEmitter() {
        throw new AssertionError("CgBufferGlslEmitter is not instantiable");
    }

    /**
     * Generates the SSBO block declaration for an attached buffer.
     *
     * @param ab the attached buffer descriptor
     */
    public static String emitSsbo(CgAttachedBuffer ab) {
        return emitSsbo(ab.getBuffer().getFormat(), ab.getBuffer().getName(), ab.getMacroName());
    }

    /**
     * Generates the TBO sampler + getter function for an attached buffer.
     * Validates TBO compatibility first.
     *
     * @param ab the attached buffer descriptor
     * @throws CgPreprocessorException if any field type is not TBO-compatible, or if
     *                                  {@code format.getStride() % 16 != 0}
     */
    public static String emitTbo(CgAttachedBuffer ab) throws CgPreprocessorException {
        return emitTbo(ab.getBuffer().getFormat(), ab.getBuffer().getName(), ab.getMacroName());
    }

    /**
     * Generates a flat {@code layout(std140) uniform} block declaration for a UBO.
     *
     * @param ubo the uniform buffer
     */
    public static String emitUbo(CgUniformBuffer ubo) {
        return emitUbo(ubo.getFormat(), ubo.getName());
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
        String structName = format.getGlslName();
        String arrayName  = "_cg_" + lowerFirst(structName) + "Arr";

        StringBuilder sb = new StringBuilder(256);
        appendStructDecl(sb, format, structName);
        sb.append('\n');
        sb.append("layout(std430) readonly buffer ").append(bufferName).append(" {\n");
        sb.append("    ").append(structName).append(' ').append(arrayName).append("[];\n");
        sb.append("};\n");
        sb.append('\n');
        sb.append("#define ").append(macroName).append("(n) ").append(arrayName).append("[n]\n");
        return sb.toString();
    }

    /**
     * Generates the TBO sampler + getter function for a buffer with the given format.
     * Validates TBO compatibility first.
     *
     * @param format     the buffer's field layout
     * @param bufferName the value of {@code CgShaderBuffer.getName()} — used as the GLSL
     *                   {@code uniform samplerBuffer} name; must match for {@code wireShader()}
     * @param macroName  the user-facing macro
     * @throws CgPreprocessorException if any field type is not TBO-compatible, or if
     *                                  {@code format.getStride() % 16 != 0}
     */
    static String emitTbo(CgBufferFormat format, String bufferName, String macroName)
            throws CgPreprocessorException {
        // 1. Field type compatibility check
        for (int i = 0; i < format.getFieldCount(); i++) {
            CgBufferField field = format.getField(i);
            if (!field.getType().isTboCompatible()) {
                throw new CgPreprocessorException(
                    "Attached buffer \"" + bufferName + "\": field \"" + field.getName() +
                    "\" has type " + field.getType() + " which is not TBO-compatible. " +
                    "TBO path requires float-family types only (FLOAT, VEC2, VEC3, VEC4, MAT3, MAT4). " +
                    "Use SSBO path or change the field type.",
                    "<attached-buffer:" + bufferName + ">", 0);
            }
        }

        // 2. Stride alignment check — one TBO texel = 16 bytes
        if (format.getStride() % 16 != 0) {
            throw new CgPreprocessorException(
                "Attached buffer \"" + bufferName + "\": format stride " + format.getStride() +
                " bytes is not a multiple of 16 (one TBO texel = 16 bytes). " +
                "Add a vec4 field or pad to a 16-byte boundary.",
                "<attached-buffer:" + bufferName + ">", 0);
        }

        String structName  = format.getGlslName();
        String getterName  = "_cg_get" + structName;
        int    texelsPerRecord = format.getStride() / 16;

        StringBuilder sb = new StringBuilder(512);
        appendStructDecl(sb, format, structName);
        sb.append('\n');
        sb.append("uniform samplerBuffer ").append(bufferName).append(";\n");
        sb.append('\n');
        sb.append(structName).append(' ').append(getterName).append("(int n) {\n");
        sb.append("    int _base = n * ").append(texelsPerRecord).append(";\n");
        sb.append("    ").append(structName).append(" _r;\n");

        // Emit texelFetch lines for each field.
        // Duplicate texelFetch calls (FLOAT/VEC2 packing into one texel) are CSE-eliminated by drivers.
        for (int i = 0; i < format.getFieldCount(); i++) {
            CgBufferField field = format.getField(i);
            appendTboFieldFetch(sb, bufferName, field);
        }

        sb.append("    return _r;\n");
        sb.append("}\n");
        sb.append('\n');
        sb.append("#define ").append(macroName).append("(n) ").append(getterName).append("(n)\n");
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
    static String emitUbo(CgBufferFormat format, String blockName) {
        StringBuilder sb = new StringBuilder(128);
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

    /**
     * Emits one or more {@code _r.field = texelFetch(...)} lines for a single field.
     *
     * <p>TBO texel arithmetic: texel = 16 bytes = 4 float32s.
     * {@code texelIndex = byteOffset / 16}, {@code componentOffset = (byteOffset % 16) / 4}.</p>
     *
     * <p>MAT4 and MAT3 produce multiple texelFetch lines (one per column).
     * All other types produce a single line with the appropriate swizzle.</p>
     */
    private static void appendTboFieldFetch(StringBuilder sb, String samplerName, CgBufferField field) {
        int byteOffset       = field.getByteOffset();
        int texelIndex       = byteOffset / 16;
        int componentOffset  = (byteOffset % 16) / 4;
        String fieldRef      = "    _r." + field.getName();
        CgGpuType type       = field.getType();

        switch (type) {
            case MAT4:
                // 4 consecutive full texels — column assignment
                for (int col = 0; col < 4; col++) {
                    sb.append(fieldRef).append('[').append(col).append(']')
                      .append(" = texelFetch(").append(samplerName)
                      .append(", _base + ").append(texelIndex + col).append(");\n");
                }
                break;

            case MAT3:
                // 3 consecutive texels, each .xyz (vec4-aligned columns)
                for (int col = 0; col < 3; col++) {
                    sb.append(fieldRef).append('[').append(col).append(']')
                      .append(" = texelFetch(").append(samplerName)
                      .append(", _base + ").append(texelIndex + col).append(").xyz;\n");
                }
                break;

            case VEC4:
                // Full texel, no swizzle
                sb.append(fieldRef)
                  .append(" = texelFetch(").append(samplerName)
                  .append(", _base + ").append(texelIndex).append(");\n");
                break;

            case VEC3:
                // Always at alignment 16, so componentOffset == 0; read .xyz
                sb.append(fieldRef)
                  .append(" = texelFetch(").append(samplerName)
                  .append(", _base + ").append(texelIndex).append(").xyz;\n");
                break;

            case VEC2: {
                // Packs within a texel; swizzle depends on component offset
                String swizzle = (componentOffset == 0) ? ".xy" : ".zw";
                sb.append(fieldRef)
                  .append(" = texelFetch(").append(samplerName)
                  .append(", _base + ").append(texelIndex).append(')').append(swizzle).append(";\n");
                break;
            }

            case FLOAT: {
                // Single component, swizzle by component offset
                String swizzle = componentSwizzle(componentOffset);
                sb.append(fieldRef)
                  .append(" = texelFetch(").append(samplerName)
                  .append(", _base + ").append(texelIndex).append(')').append(swizzle).append(";\n");
                break;
            }

            default:
                // Non-TBO-compatible types are rejected in validation above; this is unreachable.
                throw new IllegalStateException("Unexpected TBO type: " + type);
        }
    }

    /** Maps component offset (0–3) to a GLSL single-component swizzle (.x/.y/.z/.w). */
    private static String componentSwizzle(int offset) {
        switch (offset) {
            case 0: return ".x";
            case 1: return ".y";
            case 2: return ".z";
            case 3: return ".w";
            default: throw new IllegalArgumentException("Invalid component offset: " + offset);
        }
    }

    /** Lowercases the first character of {@code s}; returns {@code s} unchanged if empty. */
    static String lowerFirst(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }
}
