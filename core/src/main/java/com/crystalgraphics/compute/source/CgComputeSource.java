package com.crystalgraphics.compute.source;

import com.crystalgraphics.gl.material.CgMaterialProperty;

import java.util.List;

/**
 * A parsed {@code .compute}: its kernels, what they share, and its code cut into the parts each kernel is emitted
 * from. Data only, no GL; {@code CgComputeParser} makes one and {@code CgKernelEmitter} reads it.
 *
 * <pre>{@code
 * CgComputeSource source = CgComputeParser.parse(text, "mymod:shaders/particles.compute");
 * CgKernelDecl simulate = source.kernel("Simulate");
 * }</pre>
 *
 * @param features      {@code #pragma cg_feature} keywords, in declaration order
 * @param engineBuffers {@code #pragma cg_use} tokens, with every token they require, each before its first user
 * @param extensions    the file's own {@code #extension} lines, which every kernel's header carries
 */
public record CgComputeSource(String path, List<CgKernelDecl> kernels, List<CgBufferDecl> buffers,
                              List<CgImageDecl> images, List<CgMaterialProperty> properties, List<String> features,
                              List<String> engineBuffers, List<String> extensions, List<CgSourcePart> parts) {

    /** The kernel of that name, or null. */
    public CgKernelDecl kernel(String name) {
        for (CgKernelDecl k : kernels) if (k.name().equals(name)) return k;
        return null;
    }

    public CgBufferDecl buffer(String name) {
        for (CgBufferDecl b : buffers) if (b.name().equals(name)) return b;
        return null;
    }

    public CgImageDecl image(String name) {
        for (CgImageDecl i : images) if (i.name().equals(name)) return i;
        return null;
    }
}
