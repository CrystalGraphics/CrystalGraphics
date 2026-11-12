package com.crystalgraphics.material.parse;

import com.crystalgraphics.api.buffer.CgBufferField;
import com.crystalgraphics.api.buffer.CgBufferLayout;
import com.crystalgraphics.material.CgAttachedBuffer;
import com.crystalgraphics.api.vertex.CgAttribType;
import com.crystalgraphics.api.vertex.CgVertexAttribute;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gpu.resource.CgUniformBuffer;
import com.crystalgraphics.render.shader.CgBindingModel;

/**
 * Generates GLSL declaration blocks for user-attached buffers (SSBO, UBO), and the vertex fetch of a
 * {@link CgVertexFormat}: its attributes pulled from the mesh store's words.
 *
 * <p>No GL calls, no instance state. All core methods are package-private and take raw
 * {@code (CgBufferLayout, String)} parameters — no {@code CgShaderBuffer} required.
 * This is the test seam: unit tests call core methods directly without a GL context.</p>
 *
 * <p>Public facade methods delegate to the core methods and accept the typed wrapper objects
 * used at compile time.</p>
 */
public final class CgGlslEmitter {

    private CgGlslEmitter() {
        throw new AssertionError("CgGlslEmitter is not instantiable");
    }

    /** The storage block a vertex stage pulls its mesh's vertices from: the format's words, bound at {@code VERTICES}. */
    public static final String VERTEX_BLOCK = "CgVertexWords";

    /** The storage block a material's values are read from where they live in its shader's arena. */
    public static final String ARENA_BLOCK = "CgMaterialArena";

    /**
     * A material's values where they live in its shader's arena ({@code CgMaterialArena}): the block as a struct padded
     * to the arena's {@code stride}, an array of them at {@code slot}, and each value a global under its name, read from
     * the draw's slot by {@code _cg_material()}, which the generated main calls first. Defines {@code CG_MATERIAL_ARENA}.
     *
     * <pre>{@code
     * emitArena(format, CgBindingModel.MATERIAL_BLOCK, CgMaterialArena.stride(format.getFloatCount()));
     * // struct CgMaterialData { vec4 _Color; vec4 _cg_pad[15]; };
     * // CG_BIND(2, 0) layout(std140) readonly buffer CgMaterialArena { CgMaterialData cg_Materials[]; };
     * // vec4 _Color;
     * // void _cg_material() { uint s = CG_MATERIAL_SLOT; _Color = cg_Materials[s]._Color; }
     * }</pre>
     *
     * <ul>
     *   <li>The draw's slot is its id's where the whole arena is bound; a batch bound its own slot alone reads that one,
     *       told by the array's length, and never reads an id.</li>
     *   <li>std140, so the struct's members sit where the uniform block's did and the material's writer is unchanged.</li>
     * </ul>
     *
     * @throws IllegalArgumentException if the block is not a whole number of vec4s or is larger than {@code stride}
     */
    public static String emitArena(CgBufferLayout format, CgBindingModel.Slot slot, int stride) {
        int bytes = format.getFloatCount() * 4;
        if (bytes % 16 != 0 || bytes > stride) {
            throw new IllegalArgumentException("a block of " + bytes + " bytes in an arena of " + stride + " a slot");
        }
        StringBuilder sb = new StringBuilder(512);
        sb.append("#define CG_MATERIAL_ARENA 1\nstruct CgMaterialData {\n");
        for (int i = 0; i < format.getFieldCount(); i++) {
            CgBufferField field = format.getField(i);
            sb.append("    ").append(field.getType().getGlslName()).append(' ').append(field.getName()).append(";\n");
        }
        if (stride > bytes) sb.append("    vec4 _cg_pad[").append((stride - bytes) / 16).append("];\n");
        sb.append("};\n");
        sb.append(slot.glsl()).append("layout(std140) readonly buffer ").append(ARENA_BLOCK).append(" {\n")
                .append("    CgMaterialData cg_Materials[];\n};\n");
        sb.append("#define CG_MATERIAL_SLOT (cg_Materials.length() == 1 ? 0u")
                .append(" : min(cg_ObjectIds[CG_INSTANCE_ID].y, uint(cg_Materials.length()) - 1u))\n");
        for (int i = 0; i < format.getFieldCount(); i++) {
            CgBufferField field = format.getField(i);
            sb.append(field.getType().getGlslName()).append(' ').append(field.getName()).append(";\n");
        }
        sb.append("void _cg_material() {\n    uint s = CG_MATERIAL_SLOT;\n");
        for (int i = 0; i < format.getFieldCount(); i++) {
            String name = format.getField(i).getName();
            sb.append("    ").append(name).append(" = cg_Materials[s].").append(name).append(";\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * A format's attributes as a vertex stage pulls them (Unreal's manual vertex fetch): the store's words at
     * {@link CgBindingModel#VERTICES}, and a global per attribute under its name, which {@link #emitVertexFetch} fills.
     * Empty for a format with no attributes. The vertex stage only.
     *
     * <pre>
     * CG_BIND(1, 4) layout(std430) readonly buffer CgVertexWords { uint cg_VertexWords[]; };
     * vec3 cg_Position;
     * vec3 cg_Normal;
     * ...
     * </pre>
     */
    static String emitVertexDeclarations(CgVertexFormat format) {
        if (format.getAttributeCount() == 0) return "";
        StringBuilder sb = new StringBuilder(256);
        sb.append("// Vertex attributes (format: ").append(format.getKey()).append("), pulled\n");
        sb.append(CgBindingModel.VERTICES.glsl()).append("layout(std430) readonly buffer ").append(VERTEX_BLOCK)
          .append(" { uint cg_VertexWords[]; };\n");
        for (int i = 0; i < format.getAttributeCount(); i++) {
            CgVertexAttribute a = format.getAttribute(i);
            sb.append(a.getGlslType()).append(' ').append(a.getName()).append(";\n");
        }
        return sb.toString();
    }

    /**
     * {@code void cg_FetchVertex()}, which fills {@link #emitVertexDeclarations}' globals from vertex {@code gl_VertexID}
     * (the draw's base vertex included, on GL and Vulkan alike): each attribute decoded from the words as a vertex
     * input would have read it, halves widened and normalised integers scaled. Empty for a format with no attributes.
     *
     * <pre>{@code
     * emitVertexFetch(CgVertexFormat.SPATIAL, null, null);
     * // the skinned factory: a draw of either width, picked by a condition uniform over the draw
     * emitVertexFetch(CgVertexFormat.SKINNED, CgVertexFormat.SKINNED_WIDE, "CG_BONES_WIDE");
     * }</pre>
     *
     * <ul>
     *   <li>{@code alternate}, read where {@code when} holds, must name the same attributes with the same shader types.</li>
     *   <li>A stride, and every component, must fall on its own size; a format that does not is refused here.</li>
     * </ul>
     */
    static String emitVertexFetch(CgVertexFormat format, CgVertexFormat alternate, String when) {
        if (format.getAttributeCount() == 0) return "";
        StringBuilder sb = new StringBuilder(512);
        sb.append("void cg_FetchVertex() {\n");
        if (alternate == null) {
            appendFetch(sb, format, "  ");
        } else {
            sb.append("  if (").append(when).append(") {\n");
            appendFetch(sb, alternate, "    ");
            sb.append("  } else {\n");
            appendFetch(sb, format, "    ");
            sb.append("  }\n");
        }
        return sb.append("}\n").toString();
    }

    private static final String SWIZZLE = "xyzw";

    private static void appendFetch(StringBuilder sb, CgVertexFormat format, String indent) {
        if ((format.getStride() & 3) != 0) {
            throw new IllegalStateException(format.getKey() + ": a pulled vertex is whole words, not " + format.getStride() + " bytes");
        }
        sb.append(indent).append("uint v = uint(gl_VertexID) * ").append(format.getStride() >> 2).append("u;\n");
        for (int i = 0; i < format.getAttributeCount(); i++) {
            CgVertexAttribute a = format.getAttribute(i);
            int n = a.isInteger() ? a.getComponents() : a.getShaderComponents();
            sb.append(indent).append(a.getName()).append(" = ").append(a.getGlslType()).append('(');
            for (int c = 0; c < n; c++) {
                if (c > 0) sb.append(", ");
                sb.append(component(a, c));
            }
            sb.append(");\n");
        }
    }

    /** Component {@code c} of attribute {@code a} of the vertex whose first word is {@code v}, as GLSL. */
    private static String component(CgVertexAttribute a, int c) {
        CgAttribType type = a.getType();
        int size = type.getByteSize(), at = a.getOffset() + c * size, shift = (at & 3) * 8;
        if (at % size != 0) {
            throw new IllegalStateException(a + ": component " + c + " starts at byte " + at + ", not on its size");
        }
        boolean norm = a.isNormalized();
        if (norm && size == 4) throw new IllegalStateException(a + ": no normalised 32-bit integers");
        String word = "cg_VertexWords[v + " + (at >> 2) + "u]";
        return switch (type) {
            case FLOAT -> "uintBitsToFloat(" + word + ")";
            case UNSIGNED_INT -> word;
            case INT -> "int(" + word + ")";
            case HALF_FLOAT -> "unpackHalf2x16(" + word + ")." + SWIZZLE.charAt(shift / 16);
            case UNSIGNED_SHORT -> norm ? "unpackUnorm2x16(" + word + ")." + SWIZZLE.charAt(shift / 16)
                    : "bitfieldExtract(" + word + ", " + shift + ", 16)";
            case SHORT -> norm ? "unpackSnorm2x16(" + word + ")." + SWIZZLE.charAt(shift / 16)
                    : "bitfieldExtract(int(" + word + "), " + shift + ", 16)";
            case UNSIGNED_BYTE -> norm ? "unpackUnorm4x8(" + word + ")." + SWIZZLE.charAt(shift / 8)
                    : "bitfieldExtract(" + word + ", " + shift + ", 8)";
            case BYTE -> norm ? "unpackSnorm4x8(" + word + ")." + SWIZZLE.charAt(shift / 8)
                    : "bitfieldExtract(int(" + word + "), " + shift + ", 8)";
        };
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
    static String emitSsbo(CgBufferLayout format, String bufferName, String macroName) {
        return emitSsbo(format, bufferName, macroName, true, null);
    }

    /**
     * {@link #emitSsbo(CgBufferLayout, String, String)}, writable where {@code readonly} is false: what a kernel
     * declares an engine buffer as. Bound at {@code slot}, or where the program is wired with none.
     */
    public static String emitSsbo(CgBufferLayout format, String bufferName, String macroName, boolean readonly,
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
    public static String emitUbo(CgBufferLayout format, String blockName) {
        return emitUbo(format, blockName, null);
    }

    /** {@link #emitUbo(CgBufferLayout, String)} bound at {@code slot}, or where the program is wired with none. */
    public static String emitUbo(CgBufferLayout format, String blockName, CgBindingModel.Slot slot) {
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
    private static void appendStructDecl(StringBuilder sb, CgBufferLayout format, String structName) {
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
