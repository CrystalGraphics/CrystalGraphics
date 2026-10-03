package com.crystalgraphics.compute.cpu;

import com.crystalgraphics.compute.emit.CgPropertyBlock;
import com.crystalgraphics.compute.source.CgBufferAccess;
import com.crystalgraphics.compute.source.CgBufferDecl;
import com.crystalgraphics.compute.source.CgComputeSource;
import com.crystalgraphics.compute.source.CgImageDecl;
import com.crystalgraphics.compute.source.CgKernelDecl;

import javax.annotation.Nullable;
import java.util.Set;

/**
 * What a {@link CgCpuBody} runs over: a range of the dispatch's elements, and its buffers, images, property values and
 * keywords by the names the {@code .compute} declares. The engine hands one to each range it runs.
 *
 * <pre>{@code
 * kernel.cpu(d -> {
 *     CgCpuBuffer in = d.buffer("IN"), out = d.buffer("OUT");
 *     float drag = d.property("_Drag");
 *     for (int e = d.first(); e < d.end(); e++) out.setFloat(e, in.getFloat(e) * (1f - drag));
 * });
 *
 * kernel.cpu(d -> {                                    // an append kernel
 *     CgCpuBuffer spawned = d.appended("SPAWNS");
 *     for (int e = d.first(); e < d.end(); e++) {
 *         int at = d.append("SPAWNS");
 *         spawned.setFloat(at, 0, e);
 *     }
 * });
 * }</pre>
 *
 * <ul>
 *   <li>Elements are numbered as {@code CG_ELEMENT}, x fastest; {@link #x}, {@link #y} and {@link #z} take one apart.</li>
 *   <li>An append lands after what the buffer's counter held, in element order, and the counter grows by every append
 *       made, even past the buffer's end, as on the GPU.</li>
 *   <li>Sampler properties are not reachable here: a texture's texels are the GPU's.</li>
 *   <li>Keep nothing from it past the body's return: the engine reuses it for the next range.</li>
 * </ul>
 */
public final class CgCpuDispatch {

    final CgComputeSource source;
    final CgCpuBuffer[] buffers;
    /** Per append buffer: what this range appended. */
    final CgCpuBuffer[] appended;
    /** Per append buffer: the counter's value when the dispatch began. */
    final int[] counts;
    final CgCpuImage[] images;
    private CgKernelDecl kernel;
    private Set<String> keywords;
    @Nullable
    private float[] values;
    @Nullable
    private float[] constants;
    private CgPropertyBlock block;
    private int countX, countY, countZ;
    private int first, end;

    /** A range's view of {@code source}'s dispatches, sharing its buffers, counts and images with the others. */
    CgCpuDispatch(CgComputeSource source, CgCpuBuffer[] buffers, int[] counts, CgCpuImage[] images) {
        this.source = source;
        this.buffers = buffers;
        this.counts = counts;
        this.images = images;
        this.appended = new CgCpuBuffer[buffers.length];
        for (CgBufferDecl b : source.buffers()) {
            if (b.access() == CgBufferAccess.APPEND) appended[b.index()] = CgCpuBuffer.staging(b);
        }
    }

    /** Points this view at one dispatch's range {@code [first, end)}, its appends emptied. */
    void set(CgKernelDecl kernel, Set<String> keywords, @Nullable float[] values, @Nullable float[] constants,
             CgPropertyBlock block, int countX, int countY, int countZ, int first, int end) {
        this.kernel = kernel;
        this.keywords = keywords;
        this.values = values;
        this.constants = constants;
        this.block = block;
        this.countX = countX;
        this.countY = countY;
        this.countZ = countZ;
        this.first = first;
        this.end = end;
        for (CgCpuBuffer a : appended) if (a != null) a.length = 0;
    }

    /** The first element of this range. */
    public int first() { return first; }

    /** One past the last element of this range. */
    public int end() { return end; }

    /** The elements asked for along {@code axis}, 0 to 2: {@code CG_DISPATCH_COUNT}. */
    public int count(int axis) {
        return axis == 0 ? countX : axis == 1 ? countY : countZ;
    }

    /** {@code CG_DISPATCH_ID.x} of element {@code e}. */
    public int x(int e) { return e % countX; }

    public int y(int e) { return (e / countX) % countY; }

    public int z(int e) { return e / (countX * countY); }

    /** The kernel this body runs for. */
    public CgKernelDecl kernel() { return kernel; }

    public boolean keyword(String name) {
        return keywords.contains(name);
    }

    /** {@code cg_Time.y}: the pass's time in seconds, 0 for a pass with none. */
    public float time() {
        return constants == null ? 0f : constants[33];
    }

    /** Buffer {@code name} as bound to the dispatch. */
    public CgCpuBuffer buffer(String name) {
        CgCpuBuffer b = buffers[decl(name).index()];
        if (b == null || b.words == null) throw new IllegalStateException(kernel.name() + ": buffer " + name + " is not bound");
        return b;
    }

    /** What this range appends to append buffer {@code name}: write an element at the index {@link #append} answers. */
    public CgCpuBuffer appended(String name) {
        CgCpuBuffer b = appended[decl(name).index()];
        if (b == null) throw new IllegalArgumentException(name + " is no append buffer");
        return b;
    }

    /** Appends an element to {@code name}, zeroed, answering its index in {@link #appended}. */
    public int append(String name) {
        return appended(name).add();
    }

    /** {@code NAME_COUNT()}: elements the append buffer held when the dispatch began, at most its length. */
    public int appendCount(String name) {
        CgBufferDecl b = decl(name);
        return (int) Math.min(Integer.toUnsignedLong(counts[b.index()]), buffer(name).length());
    }

    public CgCpuImage image(String name) {
        CgImageDecl decl = source.image(name);
        if (decl == null) throw new IllegalArgumentException(source.path() + " declares no image '" + name + "'");
        CgCpuImage image = images[decl.index()];
        if (image == null) throw new IllegalStateException(kernel.name() + ": image " + name + " is not bound");
        return image;
    }

    /** A float property's value. */
    public float property(String name) {
        return property(name, 0);
    }

    /** Component {@code component} of a vector property. */
    public float property(String name, int component) {
        if (values == null) throw new IllegalArgumentException(source.path() + " declares no value properties");
        return values[block.offset(name) + component];
    }

    /** An int or bool property. */
    public int propertyInt(String name) {
        return Float.floatToRawIntBits(property(name, 0));
    }

    private CgBufferDecl decl(String name) {
        CgBufferDecl b = source.buffer(name);
        if (b == null) throw new IllegalArgumentException(source.path() + " declares no buffer '" + name + "'");
        return b;
    }
}
