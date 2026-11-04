package com.crystalgraphics.gl.material.parse;

import com.crystalgraphics.compute.source.CgBufferDecl;

/**
 * GLSL reading a buffer a material declares in {@code Buffers { }}: {@code NAME(i)} for an {@code int} or {@code uint}
 * {@code i}, over a storage block. The compiler emits it; the material binds the block by the name here. (A buffer
 * texture reader ran on the TBO path, unhooked: {@code docs/TBO_PATH.md}.)
 *
 * <pre>{@code
 * sb.append(shader.bufferStructs());                                   // the element structs first
 * for (CgBufferDecl b : shader.buffers()) CgBufferReaders.reader(sb, b);
 * int index = glGetProgramResourceIndex(program, GL_SHADER_STORAGE_BLOCK, CgBufferReaders.readerBlock(b));
 * }</pre>
 */
public final class CgBufferReaders {

    private CgBufferReaders() {}

    /** The storage block {@link #reader} declares. */
    public static String readerBlock(CgBufferDecl b) { return "CgMaterialBuffer_" + b.name(); }

    /** {@code NAME(i)} over the storage block {@link #readerBlock}. The element's struct is the caller's to declare first. */
    public static void reader(StringBuilder sb, CgBufferDecl b) {
        String e = b.element();
        sb.append("layout(std430) readonly buffer ").append(readerBlock(b)).append(" { ").append(e).append(' ')
          .append(b.array()).append("[]; };\n");
        for (String index : new String[]{"int", "uint"}) {
            sb.append(e).append(' ').append(b.name()).append('(').append(index).append(" i) { return ")
              .append(b.array()).append("[i]; }\n");
        }
    }
}
