package com.crystalgraphics.gl.material.parse;

import com.crystalgraphics.compute.source.CgBufferDecl;
import com.crystalgraphics.compute.source.CgElementField;
import com.crystalgraphics.platform.gl.CgGL;

/**
 * GLSL reading a buffer a material declares in {@code Buffers { }}: {@code NAME(i)} for an {@code int} or {@code uint}
 * {@code i}, over a storage block, or over a buffer texture where the context reads buffers that way. The compiler
 * emits it; the material binds the block or the texture by the names here.
 *
 * <pre>{@code
 * sb.append(shader.bufferStructs());                                   // the element structs first
 * for (CgBufferDecl b : shader.buffers()) CgBufferReaders.reader(sb, b, useSsbo);
 * int index = glGetProgramResourceIndex(program, GL_SHADER_STORAGE_BLOCK, CgBufferReaders.readerBlock(b));
 * }</pre>
 */
public final class CgBufferReaders {

    private CgBufferReaders() {}

    /** The buffer texture {@link #reader} samples where it does not read a storage block. */
    public static String tbo(CgBufferDecl b) { return "_cg_tbo_" + b.name(); }

    /** The storage block {@link #reader} declares. */
    public static String readerBlock(CgBufferDecl b) { return "CgMaterialBuffer_" + b.name(); }

    /** Words in one buffer-texture texel of {@code b}: 1, 2 or 4. */
    public static int texelWords(CgBufferDecl b) {
        return b.struct() ? 4 : b.stride() / 4;
    }

    /** {@code GL_R32UI}, {@code GL_RG32UI} or {@code GL_RGBA32UI}: the buffer texture's format. */
    public static int texelFormat(CgBufferDecl b) {
        int words = texelWords(b);
        return words == 1 ? CgGL.GL_R32UI : words == 2 ? CgGL.GL_RG32UI : CgGL.GL_RGBA32UI;
    }

    /**
     * {@code NAME(i)} over the storage block {@link #readerBlock} when {@code storage}, else over the buffer texture
     * {@link #tbo}, its texels {@link #texelFormat}. The element's struct is the caller's to declare first.
     */
    public static void reader(StringBuilder sb, CgBufferDecl b, boolean storage) {
        String e = b.element();
        if (storage) {
            sb.append("layout(std430) readonly buffer ").append(readerBlock(b)).append(" { ").append(e).append(' ')
              .append(b.array()).append("[]; };\n");
            indexed(sb, e + " " + b.name(), "return " + b.array() + "[i];");
            return;
        }
        sb.append("uniform usamplerBuffer ").append(tbo(b)).append(";\n");
        load(sb, b);
        indexed(sb, e + " " + b.name(), "return _cg_load_" + b.name() + "(int(i));");
    }

    /** {@code _cg_load_NAME(i)}: element {@code i} of the buffer texture, from its words. */
    private static void load(StringBuilder sb, CgBufferDecl b) {
        String e = b.element();
        int k = b.struct() ? b.fields().size() : 1;
        sb.append(e).append(" _cg_load_").append(b.name()).append("(int i) {\n    int t = i * ").append(k).append(";\n");
        if (!b.struct()) {
            sb.append("    return ").append(unpack(e, fetch(b, "t"))).append(";\n}\n");
            return;
        }
        sb.append("    ").append(e).append(" v;\n");
        for (int j = 0; j < b.fields().size(); j++) {
            CgElementField f = b.fields().get(j);
            sb.append("    v.").append(f.name()).append(" = ").append(unpack(f.type(), fetch(b, "t + " + j))).append(";\n");
        }
        sb.append("    return v;\n}\n");
    }

    private static String fetch(CgBufferDecl b, String t) {
        int words = texelWords(b);
        return "texelFetch(" + tbo(b) + ", " + t + ")" + (words == 1 ? ".r" : words == 2 ? ".rg" : "");
    }

    /** A value of {@code type} from its bits. */
    private static String unpack(String type, String bits) {
        if (type.equals("float") || type.startsWith("vec")) return "uintBitsToFloat(" + bits + ")";
        if (type.equals("int") || type.startsWith("ivec")) return type + "(" + bits + ")";
        return bits;
    }

    /** A function taking an element index, once for {@code int} and once for {@code uint}. */
    private static void indexed(StringBuilder sb, String signature, String body) {
        for (String index : new String[]{"int", "uint"}) {
            sb.append(signature).append("(").append(index).append(" i) { ").append(body).append(" }\n");
        }
    }
}
