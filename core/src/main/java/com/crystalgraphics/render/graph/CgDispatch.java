package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.compute.CgKernel;
import com.crystalgraphics.compute.emit.CgPropertyBlock;
import com.crystalgraphics.compute.source.CgBufferAccess;
import com.crystalgraphics.compute.source.CgBufferAccessor;
import com.crystalgraphics.compute.source.CgBufferDecl;
import com.crystalgraphics.compute.source.CgComputeSource;
import com.crystalgraphics.compute.source.CgImageAccessor;
import com.crystalgraphics.compute.source.CgImageDecl;
import com.crystalgraphics.compute.source.CgImageDimension;
import com.crystalgraphics.compute.source.CgKernelDecl;
import com.crystalgraphics.platform.device.command.CgAccess;

import com.crystalgraphics.platform.gl.CgCapabilities;

import javax.annotation.Nullable;

/**
 * One kernel run in a {@link CgComputePass}: what it is bound to, its property values and how many it runs. Made by the
 * pass's {@code dispatch} methods; complete by the pass's {@link CgComputePass#end()}.
 *
 * <pre>{@code
 * pass.dispatch(simulate, count)
 *     .bind("STATE_IN", state)                  // a history: read, its newest version
 *     .bind("STATE_OUT", state)                 // ... and written, its next
 *     .bind("SPAWNS", spawns).counter("SPAWNS", spawnCount, 0)
 *     .image("DENSITY", density)
 *     .texture("_Noise", noise)
 *     .set("_Drag", 0.1f);
 * }</pre>
 *
 * <ul>
 *   <li>What a binding is read or written as comes from what the kernel uses — {@code STATE(i)} reads, {@code STATE_WRITE}
 *       writes — so the graph orders and fences exactly that. Every buffer and image the kernel uses must be bound.</li>
 *   <li>Values are copied when set; a texture is bound as it is when the pass executes.</li>
 * </ul>
 */
public final class CgDispatch {

    /** How the work is sized. */
    enum Form { ELEMENTS, GROUPS, INDIRECT }

    final CgComputePass pass;
    final CgKernel kernel;
    final CgKernelDecl decl;
    final CgComputeSource source;
    final Form form;
    final int x, y, z;
    @Nullable
    final CgGraphBuffer args;
    final long argsOffset;

    /** Per declared buffer: the buffer bound, its range (size 0 for the whole), and the kernel's access to it. */
    final CgGraphBuffer[] buffers;
    final long[] offsets, sizes;
    final int[] bufferAccess;
    /** Per declared buffer: an append buffer's count, its offset, and the access to it; null where none. */
    final CgGraphBuffer[] counters;
    final long[] counterOffsets;
    final int[] counterAccess;
    /** Per declared image: the texture, its level and layer (-1 for every layer), and the access to it. */
    final CgGraphTexture[] images;
    final int[] levels, layers, imageAccess;
    /** Per sampler property, by unit. */
    final CgTexture[] samplers;
    @Nullable
    final float[] values;
    /** The recording's snapshot of its blocks and samplers, made when the pass ends. */
    int bindings = -1;

    CgDispatch(CgComputePass pass, CgKernel kernel, Form form, int x, int y, int z, @Nullable CgGraphBuffer args,
               long argsOffset) {
        this.pass = pass;
        this.kernel = kernel;
        this.decl = kernel.decl();
        this.source = kernel.compute().source();
        this.form = form;
        this.x = x;
        this.y = y;
        this.z = z;
        this.args = args;
        this.argsOffset = argsOffset;
        // A kernel some tier cannot run fails here, at the caller's line, on any machine; one this context cannot run
        // too, not in the frame that executes it.
        kernel.check();
        if (CgCapabilities.detected() != null) kernel.form();
        int n = source.buffers().size(), m = source.images().size();
        buffers = new CgGraphBuffer[n];
        offsets = new long[n];
        sizes = new long[n];
        bufferAccess = new int[n];
        counters = new CgGraphBuffer[n];
        counterOffsets = new long[n];
        counterAccess = new int[n];
        images = new CgGraphTexture[m];
        levels = new int[m];
        layers = new int[m];
        imageAccess = new int[m];
        CgPropertyBlock block = kernel.compute().properties();
        samplers = new CgTexture[block.samplers().size()];
        values = block.hasValues() ? block.defaults() : null;
        if (args != null) {
            if (args.desc() != null && !args.desc().has(CgBufferUsage.INDIRECT)) {
                throw new IllegalArgumentException(args + " has no INDIRECT use, so it cannot size a dispatch");
            }
            if ((argsOffset & 3) != 0) throw new IllegalArgumentException("an indirect dispatch's arguments sit at a multiple of 4");
            pass.recording.read(pass, args, CgAccess.INDIRECT);
        }
    }

    /** {@code buffer}, whole, as declared buffer {@code name}. */
    public CgDispatch bind(String name, CgGraphBuffer buffer) {
        return bind(name, buffer, 0, 0);
    }

    /** {@code size} bytes of {@code buffer} from {@code offset} as declared buffer {@code name}; size 0 for the rest. */
    public CgDispatch bind(String name, CgGraphBuffer buffer, long offset, long size) {
        pass.requireOpen();
        CgBufferDecl b = bufferDecl(name);
        int access = bufferAccess(b);
        checkHistory(buffer, access, name);
        if (buffer.desc() != null && !buffer.desc().has(CgBufferUsage.STORAGE)) {
            throw new IllegalArgumentException(buffer + " has no STORAGE use, so a kernel cannot bind it as " + name);
        }
        buffers[b.index()] = buffer;
        offsets[b.index()] = offset;
        sizes[b.index()] = size;
        bufferAccess[b.index()] = access;
        log(buffer, access);
        return this;
    }

    /** Append buffer {@code name}'s count: the {@code uint} at {@code offset} in {@code counter}, which the caller zeroes. */
    public CgDispatch counter(String name, CgGraphBuffer counter, long offset) {
        pass.requireOpen();
        CgBufferDecl b = bufferDecl(name);
        if (b.access() != CgBufferAccess.APPEND) throw new IllegalArgumentException(name + " is no append buffer");
        int access = (uses(b.name() + CgBufferAccessor.APPEND.suffix) ? CgAccess.COMPUTE_READ | CgAccess.COMPUTE_WRITE : 0)
                | (uses(b.name() + CgBufferAccessor.COUNT.suffix) ? CgAccess.COMPUTE_READ : 0);
        checkHistory(counter, access, name + "'s count");
        counters[b.index()] = counter;
        counterOffsets[b.index()] = offset;
        counterAccess[b.index()] = access;
        log(counter, access);
        return this;
    }

    /** Mip 0 of {@code texture}, every layer, as declared image {@code name}. */
    public CgDispatch image(String name, CgGraphTexture texture) {
        return image(name, texture, 0, -1);
    }

    /** Mip {@code level} of {@code texture} as image {@code name}: one {@code layer} of an array, or -1 for all. */
    public CgDispatch image(String name, CgGraphTexture texture, int level, int layer) {
        pass.requireOpen();
        CgImageDecl image = source.image(name);
        if (image == null) throw new IllegalArgumentException(source.path() + " declares no image '" + name + "'");
        if (texture.kind() == CgGraphTexture.Kind.CURRENT) {
            throw new IllegalArgumentException("a kernel binds named storage; the current target has none");
        }
        if (level < 0 || level >= texture.getLevels()) {
            throw new IllegalArgumentException(name + " binds level " + level + " of " + texture + ", which has "
                    + texture.getLevels());
        }
        if (layer >= 0 && image.dimension() != CgImageDimension.D2) {
            throw new IllegalArgumentException(name + " is " + image.dimension().token + ": it binds every layer");
        }
        int access = 0;
        for (CgImageAccessor a : CgImageAccessor.values()) {
            if (!uses(name + a.suffix)) continue;
            access |= switch (a) {
                case LOAD -> CgAccess.COMPUTE_READ;
                case SIZE -> 0;
                case WRITE, STORE -> CgAccess.COMPUTE_WRITE;
                case ADD, MIN, MAX -> CgAccess.COMPUTE_READ | CgAccess.COMPUTE_WRITE;
            };
        }
        images[image.index()] = texture;
        levels[image.index()] = level;
        layers[image.index()] = layer;
        imageAccess[image.index()] = access;
        log(texture, access);
        return this;
    }

    /** Sampler property {@code name}'s texture. A graph texture is read as of this call. */
    public CgDispatch texture(String name, CgTexture texture) {
        pass.requireOpen();
        int unit = kernel.compute().properties().samplerUnit(name);
        if (unit < 0) throw new IllegalArgumentException(source.path() + " has no sampler property '" + name + "'");
        samplers[unit] = texture;
        CgGraphTexture graph = CgGraphTexture.sampled(texture);
        if (graph != null) pass.recording.read(pass, graph, CgAccess.SAMPLED_READ);
        return this;
    }

    public CgDispatch set(String name, float value) {
        values()[offset(name, 1)] = value;
        return this;
    }

    public CgDispatch set(String name, float x, float y) {
        int at = offset(name, 2);
        float[] v = values();
        v[at] = x;
        v[at + 1] = y;
        return this;
    }

    public CgDispatch set(String name, float x, float y, float z, float w) {
        int at = offset(name, 4);
        float[] v = values();
        v[at] = x;
        v[at + 1] = y;
        v[at + 2] = z;
        v[at + 3] = w;
        return this;
    }

    /** An int or bool property: 0 is false. */
    public CgDispatch set(String name, int value) {
        if (!kernel.compute().properties().isInteger(name)) throw new IllegalArgumentException("'" + name + "' is no int or bool");
        values()[offset(name, 1)] = Float.intBitsToFloat(value);
        return this;
    }

    public CgKernel kernel() {
        return kernel;
    }

    /** Every buffer, count and image the kernel uses is bound. At the pass's end. */
    void requireBound() {
        for (CgBufferDecl b : source.buffers()) {
            boolean used = false;
            for (CgBufferAccessor a : CgBufferAccessor.values()) {
                if (a != CgBufferAccessor.COUNT && uses(b.name() + a.suffix)) used = true;
            }
            if (used && buffers[b.index()] == null) throw unbound(b.name());
            boolean counts = uses(b.name() + CgBufferAccessor.APPEND.suffix) || uses(b.name() + CgBufferAccessor.COUNT.suffix);
            if (counts && counters[b.index()] == null) throw unbound(b.name() + "'s count (counter)");
        }
        for (CgImageDecl image : source.images()) {
            for (CgImageAccessor a : CgImageAccessor.values()) {
                if (uses(image.name() + a.suffix) && images[image.index()] == null) throw unbound(image.name());
            }
        }
    }

    private IllegalStateException unbound(String what) {
        return new IllegalStateException(pass.name + ": kernel " + decl.name() + " uses " + what + ", which is not bound");
    }

    private CgBufferDecl bufferDecl(String name) {
        CgBufferDecl b = source.buffer(name);
        if (b == null) throw new IllegalArgumentException(source.path() + " declares no buffer '" + name + "'");
        return b;
    }

    /** What the kernel does to buffer {@code b}'s elements, from the accessors it uses. */
    private int bufferAccess(CgBufferDecl b) {
        int access = 0;
        for (CgBufferAccessor a : CgBufferAccessor.values()) {
            if (!uses(b.name() + a.suffix)) continue;
            access |= switch (a) {
                case READ -> CgAccess.COMPUTE_READ;
                case LENGTH, COUNT -> 0;
                case WRITE, STORE, APPEND -> CgAccess.COMPUTE_WRITE;
                case ADD, MIN, MAX, INC, DATA -> CgAccess.COMPUTE_READ | CgAccess.COMPUTE_WRITE;
            };
        }
        return access;
    }

    private boolean uses(String accessor) {
        return decl.accessors().contains(accessor);
    }

    private void checkHistory(CgGraphBuffer buffer, int access, String binding) {
        boolean writes = (access & CgAccess.COMPUTE_WRITE) != 0;
        if (buffer.isPreviousVersion() && writes) {
            throw new IllegalArgumentException(binding + " writes " + buffer + ": a previous version is read-only");
        }
        if (buffer.kind() == CgGraphBuffer.Kind.HISTORY && writes && (access & CgAccess.COMPUTE_READ) != 0) {
            throw new IllegalArgumentException(binding + " reads and writes history " + buffer.name()
                    + ": read it through one binding and write it through another");
        }
    }

    private void log(CgGraphResource resource, int access) {
        if (access == 0) return;
        if ((access & ~CgAccess.COMPUTE_WRITE) != 0) pass.recording.read(pass, resource, access & ~CgAccess.COMPUTE_WRITE);
        if ((access & CgAccess.COMPUTE_WRITE) != 0) pass.recording.write(pass, resource, CgAccess.COMPUTE_WRITE);
    }

    private int offset(String name, int components) {
        pass.requireOpen();
        try {
            return kernel.compute().properties().offset(name, components);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(source.path() + ": " + e.getMessage(), e);
        }
    }

    private float[] values() {
        if (values == null) throw new IllegalArgumentException(source.path() + " declares no value properties");
        return values;
    }
}
