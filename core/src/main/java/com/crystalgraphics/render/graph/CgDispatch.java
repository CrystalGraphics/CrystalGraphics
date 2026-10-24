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
import com.crystalgraphics.platform.gl.CgGL;

import javax.annotation.Nullable;
import java.util.Arrays;

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
    // Not final: its pass refills it after a reset (reuse).
    Form form;
    int x, y, z;
    @Nullable
    CgGraphBuffer args;
    long argsOffset;

    /** Per declared buffer: the buffer bound, its range (size 0 for the whole), and the kernel's access to it. */
    final CgGraphBuffer[] buffers;
    final long[] offsets, sizes;
    final int[] bufferAccess;
    /**
     * Per declared buffer, and per append buffer's count: 1 + the group whose other skipping accesses in this pass it
     * waits for none of, 0 where it waits as usual.
     */
    final int[] skipGroup, countSkipGroup;
    /** Per declared buffer and count: the executing pass whose start barriered it, so binding it issues none. */
    final int[] hoisted, counterHoisted;
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
    /** The kernel's, per declared buffer and image: a bit per accessor it uses. Shared; never written. */
    private final int[] bufferUse, imageUse;
    /** Per declared buffer: what the kernel does to its elements, as {@link CgAccess} bits. */
    private final int[] accessOf;
    /**
     * Per value and buffer call since the dispatch started, in order: the name it gave and what that resolved to. A
     * shell reused for its kernel makes the same calls each frame, so a lookup is an identity test; a different name
     * at a call looks it up again.
     */
    private String[] valueNames = new String[0], bufferNames = new String[0];
    private int[] valueShapes = new int[0], valueOffsets = new int[0];
    private CgBufferDecl[] bufferDecls = new CgBufferDecl[0];
    private int valueCalls, bufferCalls;

    private static final CgBufferAccessor[] BUFFER_ACCESSORS = CgBufferAccessor.values();
    private static final CgImageAccessor[] IMAGE_ACCESSORS = CgImageAccessor.values();
    private static final int COUNT = 1 << CgBufferAccessor.COUNT.ordinal(), APPEND = 1 << CgBufferAccessor.APPEND.ordinal();

    CgDispatch(CgComputePass pass, CgKernel kernel, Form form, int x, int y, int z, @Nullable CgGraphBuffer args,
               long argsOffset) {
        this.pass = pass;
        this.kernel = kernel;
        this.decl = kernel.decl();
        this.source = kernel.compute().source();
        bufferUse = kernel.bufferAccessors();
        imageUse = kernel.imageAccessors();
        int n = source.buffers().size(), m = source.images().size();
        buffers = new CgGraphBuffer[n];
        offsets = new long[n];
        sizes = new long[n];
        bufferAccess = new int[n];
        accessOf = new int[n];
        for (int i = 0; i < n; i++) accessOf[i] = access(bufferUse[i]);
        skipGroup = new int[n];
        countSkipGroup = new int[n];
        hoisted = new int[n];
        counterHoisted = new int[n];
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
        start(form, x, y, z, args, argsOffset);
    }

    /** Whether this dispatch's arrays fit {@code kernel} as it stands: the same kernel, its file not reloaded since. */
    boolean reusableFor(CgKernel kernel) {
        return kernel == this.kernel && kernel.compute().source() == source && kernel.decl() == decl;
    }

    /** Makes it a new dispatch of its kernel, every binding cleared and its values back to the file's defaults. */
    void reuse(Form form, int x, int y, int z, @Nullable CgGraphBuffer args, long argsOffset) {
        Arrays.fill(buffers, null);
        Arrays.fill(counters, null);
        Arrays.fill(images, null);
        Arrays.fill(samplers, null);
        Arrays.fill(offsets, 0L);
        Arrays.fill(sizes, 0L);
        Arrays.fill(bufferAccess, 0);
        Arrays.fill(skipGroup, 0);
        Arrays.fill(countSkipGroup, 0);
        Arrays.fill(counterAccess, 0);
        Arrays.fill(imageAccess, 0);
        if (values != null) kernel.compute().properties().defaultsInto(values);
        bindings = -1;
        start(form, x, y, z, args, argsOffset);
    }

    /** Drops what it names once its recording resets. */
    void clearReferences() {
        Arrays.fill(buffers, null);
        Arrays.fill(counters, null);
        Arrays.fill(images, null);
        Arrays.fill(samplers, null);
        args = null;
    }

    private void start(Form form, int x, int y, int z, @Nullable CgGraphBuffer args, long argsOffset) {
        this.form = form;
        this.x = x;
        this.y = y;
        this.z = z;
        this.args = args;
        this.argsOffset = argsOffset;
        valueCalls = bufferCalls = 0;
        // A kernel some tier cannot run fails here, at the caller's line, on any machine; one this context cannot run
        // too, not in the frame that executes it.
        kernel.check();
        if (CgCapabilities.detected() != null) kernel.form();
        if (args != null) {
            if (!args.allows(CgBufferUsage.INDIRECT)) {
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
        checkHistory(buffer, access, name, "");
        if (!buffer.allows(CgBufferUsage.STORAGE)) {
            throw new IllegalArgumentException(buffer + " has no STORAGE use, so a kernel cannot bind it as " + name);
        }
        buffers[b.index()] = buffer;
        offsets[b.index()] = offset;
        sizes[b.index()] = size;
        bufferAccess[b.index()] = access;
        log(buffer, access);
        return this;
    }

    /**
     * Lets buffer {@code name}'s accesses skip the barrier against this pass's other skipping accesses to the same
     * storage: Unreal RDG's {@code SkipBarrier}, Niagara's UAV overlap. For dispatches that each touch their own slice of
     * one buffer, which would otherwise wait for each other one by one.
     *
     * <pre>{@code
     * for (Pool p : pools) {
     *     pass.dispatch(key, p.size()).bind("KEYS", keys, p.first() * 4L, p.size() * 4L).skipBarrier("KEYS");
     * }
     * pass.dispatch(sort, all).bind("KEYS", keys);   // waits for every slice
     * }</pre>
     *
     * <ul>
     *   <li>The caller promises the accesses touch disjoint words, and that none reads what another wrote: nothing
     *       checks it, and a race is silent. Synchronization validation reports overlapping words, so keep it clean.</li>
     *   <li>Bind first: it names a binding already made. An access without it waits as usual, and so does the first
     *       skipping access of each pass.</li>
     * </ul>
     */
    public CgDispatch skipBarrier(String name) {
        return skipBarrier(name, 0);
    }

    /**
     * {@link #skipBarrier(String)} within group {@code group} (0 to 7) of the pass: a stage of many dispatches whose
     * accesses overlap among themselves, and wait once for the stage before, of another group.
     *
     * <pre>{@code
     * for (Pool p : pools) step(pass, p).bind("LIVE", counts, p.at(), 16).skipBarrier("LIVE", 1).skipCountBarrier("OUT", 1);
     * for (Pool p : pools) look(pass, p).bind("LIVE", counts, p.at(), 16);   // waits once, for every step
     * }</pre>
     */
    public CgDispatch skipBarrier(String name, int group) {
        pass.requireOpen();
        CgBufferDecl b = bufferDecl(name);
        if (buffers[b.index()] == null) throw new IllegalStateException(name + " is not bound yet");
        skipGroup[b.index()] = 1 + group(group);
        return this;
    }

    /**
     * {@link #skipBarrier(String, int)} for append buffer {@code name}'s count: only where every dispatch of the group
     * appends to a word of its own, as each pool's count in one buffer. One count shared by them is never skipped.
     */
    public CgDispatch skipCountBarrier(String name, int group) {
        pass.requireOpen();
        CgBufferDecl b = bufferDecl(name);
        if (counters[b.index()] == null) throw new IllegalStateException(name + "'s count is not bound yet");
        countSkipGroup[b.index()] = 1 + group(group);
        return this;
    }

    private static int group(int group) {
        if (group < 0 || group > 7) throw new IllegalArgumentException("skip group " + group + ": 0 to 7");
        return group;
    }

    /** Append buffer {@code name}'s count: the {@code uint} at {@code offset} in {@code counter}, which the caller zeroes. */
    public CgDispatch counter(String name, CgGraphBuffer counter, long offset) {
        pass.requireOpen();
        CgBufferDecl b = bufferDecl(name);
        if (b.access() != CgBufferAccess.APPEND) throw new IllegalArgumentException(name + " is no append buffer");
        int use = bufferUse[b.index()];
        int access = ((use & APPEND) != 0 ? CgAccess.COMPUTE_READ | CgAccess.COMPUTE_WRITE : 0)
                | ((use & COUNT) != 0 ? CgAccess.COMPUTE_READ : 0);
        checkHistory(counter, access, name, "'s count");
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
        if (texture.isArray()) throw new IllegalArgumentException(name + " binds " + texture + ", an array: raster passes draw arrays");
        if ((image.dimension() == CgImageDimension.D3) != texture.isVolume()) {
            throw new IllegalArgumentException(name + " is a " + image.dimension().token + " image, and " + texture
                    + (texture.isVolume() ? " is a volume: declare the image 3d" : " is 2D: bind a CgTextureDesc.volume"));
        }
        int access = 0, use = imageUse[image.index()];
        for (CgImageAccessor a : IMAGE_ACCESSORS) {
            if ((use & 1 << a.ordinal()) == 0) continue;
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
        CgGraphTexture graph = CgGraphTexture.sampled(texture);
        if (graph != null && graph.isArray()) {
            throw new IllegalArgumentException("'" + name + "' binds " + texture + ", an array: materials sample arrays, kernels do not");
        }
        boolean volume = graph != null ? graph.isVolume() : texture.getTarget() == CgGL.GL_TEXTURE_3D;
        if (volume != kernel.compute().properties().isVolume(name)) {
            throw new IllegalArgumentException("'" + name + "' samples " + (volume ? "2D, and " + texture + " is a volume"
                    : "a sampler3D, and " + texture + " is no 3D texture"));
        }
        samplers[unit] = texture;
        if (graph != null) pass.recording.read(pass, graph, CgAccess.SAMPLED_READ);
        return this;
    }

    public CgDispatch set(String name, float value) {
        values()[offset(name, 1, false)] = value;
        return this;
    }

    public CgDispatch set(String name, float x, float y) {
        int at = offset(name, 2, false);
        float[] v = values();
        v[at] = x;
        v[at + 1] = y;
        return this;
    }

    public CgDispatch set(String name, float x, float y, float z, float w) {
        int at = offset(name, 4, false);
        float[] v = values();
        v[at] = x;
        v[at + 1] = y;
        v[at + 2] = z;
        v[at + 3] = w;
        return this;
    }

    /** An int or bool property: 0 is false. */
    public CgDispatch set(String name, int value) {
        values()[offset(name, 1, true)] = Float.intBitsToFloat(value);
        return this;
    }

    public CgKernel kernel() {
        return kernel;
    }

    /** Every buffer, count and image the kernel uses is bound. At the pass's end. */
    void requireBound() {
        for (int i = 0; i < bufferUse.length; i++) {
            if ((bufferUse[i] & ~COUNT) != 0 && buffers[i] == null) throw unbound(source.buffers().get(i).name());
            if ((bufferUse[i] & (APPEND | COUNT)) != 0 && counters[i] == null) {
                throw unbound(source.buffers().get(i).name() + "'s count (counter)");
            }
        }
        for (int i = 0; i < imageUse.length; i++) {
            if (imageUse[i] != 0 && images[i] == null) throw unbound(source.images().get(i).name());
        }
    }

    private IllegalStateException unbound(String what) {
        return new IllegalStateException(pass.name + ": kernel " + decl.name() + " uses " + what + ", which is not bound");
    }

    private CgBufferDecl bufferDecl(String name) {
        int k = bufferCalls++;
        if (k < bufferNames.length && bufferNames[k] == name) return bufferDecls[k];
        CgBufferDecl b = source.buffer(name);
        if (b == null) throw new IllegalArgumentException(source.path() + " declares no buffer '" + name + "'");
        if (k >= bufferNames.length) {
            bufferNames = Arrays.copyOf(bufferNames, Math.max(8, k * 2));
            bufferDecls = Arrays.copyOf(bufferDecls, bufferNames.length);
        }
        bufferNames[k] = name;
        bufferDecls[k] = b;
        return b;
    }

    private int bufferAccess(CgBufferDecl b) {
        return accessOf[b.index()];
    }

    /** What the kernel does to elements through accessor bits {@code use}. */
    private static int access(int use) {
        int access = 0;
        for (CgBufferAccessor a : BUFFER_ACCESSORS) {
            if ((use & 1 << a.ordinal()) == 0) continue;
            access |= switch (a) {
                case READ -> CgAccess.COMPUTE_READ;
                case LENGTH, COUNT -> 0;
                case WRITE, STORE, APPEND -> CgAccess.COMPUTE_WRITE;
                case ADD, MIN, MAX, INC, DATA -> CgAccess.COMPUTE_READ | CgAccess.COMPUTE_WRITE;
            };
        }
        return access;
    }

    /** {@code binding} and {@code suffix} name what it checks, joined only to throw. */
    private void checkHistory(CgGraphBuffer buffer, int access, String binding, String suffix) {
        boolean writes = (access & CgAccess.COMPUTE_WRITE) != 0;
        if (buffer.isPreviousVersion() && writes) {
            throw new IllegalArgumentException(binding + suffix + " writes " + buffer + ": a previous version is read-only");
        }
        if (buffer.kind() == CgGraphBuffer.Kind.HISTORY && writes && (access & CgAccess.COMPUTE_READ) != 0) {
            throw new IllegalArgumentException(binding + suffix + " reads and writes history " + buffer.name()
                    + ": read it through one binding and write it through another");
        }
    }

    private void log(CgGraphResource resource, int access) {
        if (access == 0) return;
        if ((access & ~CgAccess.COMPUTE_WRITE) != 0) pass.recording.read(pass, resource, access & ~CgAccess.COMPUTE_WRITE);
        if ((access & CgAccess.COMPUTE_WRITE) != 0) pass.recording.write(pass, resource, CgAccess.COMPUTE_WRITE);
    }

    /** Where value {@code name} starts, checked {@code components} wide and, with {@code integer}, an int or bool. */
    private int offset(String name, int components, boolean integer) {
        pass.requireOpen();
        int k = valueCalls++, shape = integer ? -components : components;
        if (k < valueNames.length && valueNames[k] == name && valueShapes[k] == shape) return valueOffsets[k];
        CgPropertyBlock properties = kernel.compute().properties();
        if (integer && !properties.isInteger(name)) throw new IllegalArgumentException("'" + name + "' is no int or bool");
        int at;
        try {
            at = properties.offset(name, components);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(source.path() + ": " + e.getMessage(), e);
        }
        if (k >= valueNames.length) {
            valueNames = Arrays.copyOf(valueNames, Math.max(16, k * 2));
            valueShapes = Arrays.copyOf(valueShapes, valueNames.length);
            valueOffsets = Arrays.copyOf(valueOffsets, valueNames.length);
        }
        valueNames[k] = name;
        valueShapes[k] = shape;
        valueOffsets[k] = at;
        return at;
    }

    private float[] values() {
        if (values == null) throw new IllegalArgumentException(source.path() + " declares no value properties");
        return values;
    }
}
