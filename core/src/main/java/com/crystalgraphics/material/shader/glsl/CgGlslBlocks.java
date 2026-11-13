package com.crystalgraphics.material.shader.glsl;

import com.crystalgraphics.api.buffer.CgBufferField;
import com.crystalgraphics.api.buffer.CgBufferLayout;
import com.crystalgraphics.api.vertex.CgAttribType;
import com.crystalgraphics.api.vertex.CgVertexAttribute;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.compute.source.CgBufferDecl;
import com.crystalgraphics.render.shader.CgBindingModel;

import javax.annotation.Nullable;

/**
 * GLSL declarations any generated program needs, from the engine's own descriptions: a storage or uniform block from a
 * {@link CgBufferLayout}, a material's values in its shader's arena, a vertex format pulled from the mesh store, a
 * material buffer's reader. A material's writer and a kernel's both declare through it.
 *
 * <pre>{@code
 * String ubo = CgGlslBlocks.uniformBlock(layout, "CgMaterialBlock", CgBindingModel.MATERIAL_BLOCK);
 * String ssbo = CgGlslBlocks.storageBlock(layout, "CgParticles", "PARTICLE_DATA", true, slot);   // PARTICLE_DATA(n)
 * String fetch = CgGlslBlocks.vertexFetch(CgVertexFormat.SPATIAL, null, null);                  // void cg_FetchVertex()
 * }</pre>
 *
 * <ul>
 *   <li>A slot of {@code null} declares no binding: the program is wired by name.</li>
 *   <li>No GL call, no state: each method returns text.</li>
 * </ul>
 */
public final class CgGlslBlocks {

    /** The storage block a vertex stage pulls its mesh's vertices from: the format's words, bound at {@code VERTICES}. */
    public static final String VERTEX_BLOCK = "CgVertexWords";

    /** The storage block a material's values are read from where they live in its shader's arena. */
    public static final String ARENA_BLOCK = "CgMaterialArena";

    private static final String SWIZZLE = "xyzw";

    private CgGlslBlocks() {}

    /**
     * {@code layout(std140) uniform name { fields };}: its fields in the block's scope, read by their own names.
     */
    public static String uniformBlock(CgBufferLayout format, String name, @Nullable CgBindingModel.Slot slot) {
        StringBuilder sb = new StringBuilder(128);
        if (slot != null) sb.append(slot.glsl());
        sb.append("layout(std140) uniform ").append(name).append(" {\n");
        appendFields(sb, format);
        return sb.append("};\n").toString();
    }

    /**
     * The element struct, a {@code std430} storage block holding an array of it, and {@code macro(n)} reading element
     * {@code n}: what an attached or engine buffer is declared as; writable where {@code readonly} is false (a kernel's).
     */
    public static String storageBlock(CgBufferLayout format, String name, String macro, boolean readonly,
                                      @Nullable CgBindingModel.Slot slot) {
        String struct = format.getGlslName();
        String array = "_cg_" + (struct.isEmpty() ? struct : Character.toLowerCase(struct.charAt(0)) + struct.substring(1)) + "Arr";
        StringBuilder sb = new StringBuilder(256);
        sb.append("struct ").append(struct).append(" {\n");
        appendFields(sb, format);
        sb.append("};\n\n");
        if (slot != null) sb.append(slot.glsl());
        sb.append(readonly ? "layout(std430) readonly buffer " : "layout(std430) buffer ").append(name).append(" {\n")
          .append("    ").append(struct).append(' ').append(array).append("[];\n};\n\n")
          .append("#define ").append(macro).append("(n) ").append(array).append("[n]\n");
        return sb.toString();
    }

    /**
     * A material's values where they live in its shader's arena: the block as a struct padded to the arena's
     * {@code stride}, an array of them at {@code slot}, and each value a global under its name, read from the draw's
     * slot by {@code _cg_material()}, which the generated main calls first. Defines {@code CG_MATERIAL_ARENA}.
     *
     * <ul>
     *   <li>The draw's slot is its id's where the whole arena is bound; a batch bound its own slot alone reads that one,
     *       told by the array's length.</li>
     *   <li>std140, so the struct's members sit where the uniform block's did.</li>
     * </ul>
     *
     * @throws IllegalArgumentException if the block is not a whole number of vec4s or is larger than {@code stride}
     */
    public static String arena(CgBufferLayout format, CgBindingModel.Slot slot, int stride) {
        int bytes = format.getFloatCount() * 4;
        if (bytes % 16 != 0 || bytes > stride) {
            throw new IllegalArgumentException("a block of " + bytes + " bytes in an arena of " + stride + " a slot");
        }
        StringBuilder sb = new StringBuilder(512);
        sb.append("#define CG_MATERIAL_ARENA 1\nstruct CgMaterialData {\n");
        appendFields(sb, format);
        if (stride > bytes) sb.append("    vec4 _cg_pad[").append((stride - bytes) / 16).append("];\n");
        sb.append("};\n").append(slot.glsl()).append("layout(std140) readonly buffer ").append(ARENA_BLOCK).append(" {\n")
          .append("    CgMaterialData cg_Materials[];\n};\n")
          .append("#define CG_MATERIAL_SLOT (cg_Materials.length() == 1 ? 0u")
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
        return sb.append("}\n").toString();
    }

    /** The storage block a material buffer ({@code Buffers { }}) is read through. */
    public static String readerBlock(CgBufferDecl b) {
        return "CgMaterialBuffer_" + b.name();
    }

    /** Material buffer {@code b}, the {@code n}th: its storage block, and {@code NAME(i)} reading element {@code i}. */
    public static String reader(CgBufferDecl b, int n) {
        String e = b.element();
        StringBuilder sb = new StringBuilder(160);
        sb.append(CgBindingModel.buffer(n).glsl()).append("layout(std430) readonly buffer ").append(readerBlock(b))
          .append(" { ").append(e).append(' ').append(b.array()).append("[]; };\n");
        for (String index : new String[]{"int", "uint"}) {
            sb.append(e).append(' ').append(b.name()).append('(').append(index).append(" i) { return ")
              .append(b.array()).append("[i]; }\n");
        }
        return sb.toString();
    }

    /**
     * A format's attributes as a vertex stage pulls them: the store's words at {@link CgBindingModel#VERTICES}, and a
     * global per attribute under its name, which {@link #vertexFetch} fills. Empty for a format with none.
     */
    public static String vertexDeclarations(CgVertexFormat format) {
        if (format.getAttributeCount() == 0) return "";
        StringBuilder sb = new StringBuilder(256);
        sb.append("// Vertex attributes (format: ").append(format.getKey()).append("), pulled\n")
          .append(CgBindingModel.VERTICES.glsl()).append("layout(std430) readonly buffer ").append(VERTEX_BLOCK)
          .append(" { uint cg_VertexWords[]; };\n");
        for (int i = 0; i < format.getAttributeCount(); i++) {
            CgVertexAttribute a = format.getAttribute(i);
            sb.append(a.getGlslType()).append(' ').append(a.getName()).append(";\n");
        }
        return sb.toString();
    }

    /**
     * {@code void cg_FetchVertex()}, filling {@link #vertexDeclarations}' globals from vertex {@code gl_VertexID}: each
     * attribute decoded from the words as a vertex input would have read it. Empty for a format with none.
     *
     * <pre>{@code
     * vertexFetch(CgVertexFormat.SPATIAL, null, null);
     * vertexFetch(CgVertexFormat.SKINNED, CgVertexFormat.SKINNED_WIDE, "CG_BONES_WIDE");   // either width, by a condition
     * }</pre>
     *
     * <ul>
     *   <li>{@code alternate}, read where {@code when} holds, names the same attributes with the same shader types.</li>
     *   <li>A stride, and every component, falls on its own size; a format that does not is refused.</li>
     * </ul>
     */
    public static String vertexFetch(CgVertexFormat format, @Nullable CgVertexFormat alternate, @Nullable String when) {
        if (format.getAttributeCount() == 0) return "";
        StringBuilder sb = new StringBuilder(512).append("void cg_FetchVertex() {\n");
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

    private static void appendFields(StringBuilder sb, CgBufferLayout format) {
        for (int i = 0; i < format.getFieldCount(); i++) {
            CgBufferField field = format.getField(i);
            sb.append("    ").append(field.getType().getGlslName()).append(' ').append(field.getName()).append(";\n");
        }
    }

    private static void appendFetch(StringBuilder sb, CgVertexFormat format, String indent) {
        if ((format.getStride() & 3) != 0) {
            throw new IllegalStateException(format.getKey() + ": a pulled vertex is whole words, not " + format.getStride() + " bytes");
        }
        sb.append(indent).append("uint v = uint(gl_VertexID) * ").append(format.getStride() >> 2).append("u;\n");
        for (int i = 0; i < format.getAttributeCount(); i++) {
            CgVertexAttribute a = format.getAttribute(i);
            int n = a.isInteger() ? a.getComponents() : a.getShaderComponents();
            sb.append(indent).append(a.getName()).append(" = ").append(a.getGlslType()).append('(');
            for (int c = 0; c < n; c++) sb.append(c > 0 ? ", " : "").append(component(a, c));
            sb.append(");\n");
        }
    }

    /** Component {@code c} of attribute {@code a} of the vertex whose first word is {@code v}, as GLSL. */
    private static String component(CgVertexAttribute a, int c) {
        CgAttribType type = a.getType();
        int size = type.getByteSize(), at = a.getOffset() + c * size, shift = (at & 3) * 8;
        if (at % size != 0) throw new IllegalStateException(a + ": component " + c + " starts at byte " + at + ", not on its size");
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
}
