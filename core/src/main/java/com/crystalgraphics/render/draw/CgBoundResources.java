package com.crystalgraphics.render.draw;

import com.crystalgraphics.gpu.resource.CgTextureAbstract;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.platform.rhi.CgDeviceNames;
import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;
import com.crystalgraphics.render.shader.CgBindingModel;

import javax.annotation.Nullable;

/**
 * What is bound at each engine buffer point and texture unit, by the slot it fills: what {@link CgDeviceSets} reads, as
 * the GL program would (the pass's records and tables, an engine buffer's ring range, a scene copy the executor bound
 * over the snapshot's). Records only while {@link #recording}, so a GL-only frame pays nothing. Goes when the executor
 * states its sets itself.
 *
 * <pre>{@code
 * CgBoundResources.recording = true;                       // device sets on
 * CgBoundResources.storage(point, buffer, offset, bytes); // beside each storage bind; 0 bytes for the rest of it
 * CgBoundResources.texture(unit, texture, -1);            // beside each texture bind; a level for one level alone
 * int v = CgBoundResources.version(CgBindingModel.PASS);   // moves when anything of that set's changes
 * }</pre>
 *
 * <p>Render thread.</p>
 */
public final class CgBoundResources {

    /** Whether binds are recorded. */
    public static boolean recording;

    private static final int SETS = 3, BINDINGS = 64;
    private static final CgGpuBuffer[] BUFFER = new CgGpuBuffer[SETS * BINDINGS];
    private static final long[] OFFSET = new long[SETS * BINDINGS], SIZE = new long[SETS * BINDINGS];

    private static final CgTexture[] TEXTURE = new CgTexture[SETS * BINDINGS];
    private static final int[] LEVEL = new int[SETS * BINDINGS];
    /** The view each texture resolved to when bound: a graph texture or a copy is one object over storage that moves. */
    private static final CgTextureView[] VIEW = new CgTextureView[SETS * BINDINGS];
    private static final CgSampler[] SAMPLER = new CgSampler[SETS * BINDINGS];
    private static final int[] VERSION = new int[SETS];

    private CgBoundResources() {}

    /**
     * Binds {@code texture} at {@code unit}: an engine texture as it binds itself (loading it first), any other (a graph
     * texture's, a target copy's) as its storage stands now.
     */
    public static void bind(int unit, CgTexture texture) {
        if (texture instanceof CgTextureAbstract engine) engine.bind(unit);
        else texture(unit, texture, -1);
    }

    /**
     * {@code texture} bound at engine unit {@code unit}: level {@code level} alone, or every level for -1. The same object
     * resolving to another view since (a graph texture given new storage) is a new binding.
     */
    public static void texture(int unit, CgTexture texture, int level) {
        if (!recording) return;
        int i = index(CgBindingModel.ofTexture(unit));
        if (i < 0) return;
        CgTextureView view = texture == null ? null : texture.rhiView();
        if (TEXTURE[i] == texture && LEVEL[i] == level && VIEW[i] == view) return;
        TEXTURE[i] = texture;
        LEVEL[i] = level;
        VIEW[i] = view;
        VERSION[i / BINDINGS]++;
    }

    /** The sampler bound at {@code unit}; null for the texture's own sampling. */
    public static void sampler(int unit, @Nullable CgSampler sampler) {
        if (!recording) return;
        int i = index(CgBindingModel.ofTexture(unit));
        if (i < 0 || SAMPLER[i] == sampler) return;
        SAMPLER[i] = sampler;
        VERSION[i / BINDINGS]++;
    }

    /** Forgets the textures noted for {@code set}: a pass on the device takes them from its snapshots. */
    public static void forgetTextures(int set) {
        if (set >= SETS) return;
        for (int b = 0; b < BINDINGS; b++) {
            TEXTURE[set * BINDINGS + b] = null;
            VIEW[set * BINDINGS + b] = null;
            SAMPLER[set * BINDINGS + b] = null;
        }
        VERSION[set]++;
    }

    /** A number that moves whenever anything recorded for {@code set} changes; 0 for a set it records nothing of. */
    public static int version(int set) {
        return set < SETS ? VERSION[set] : 0;
    }

    /** The texture bound for {@code packed}; null for none. */
    @Nullable
    static CgTexture texture(int packed) {
        int i = index(packed);
        return i < 0 ? null : TEXTURE[i];
    }

    static int level(int packed) {
        return LEVEL[index(packed)];
    }

    @Nullable
    static CgSampler sampler(int packed) {
        return SAMPLER[index(packed)];
    }

    /**
     * A storage buffer bound at engine point {@code point}: {@code buffer}'s {@code bytes} from {@code offset}, 0 for the
     * rest of it; null for none.
     */
    public static void storage(int point, @Nullable CgGpuBuffer buffer, long offset, long bytes) {
        if (recording) put(CgBindingModel.ofStorage(point), buffer, offset, bytes);
    }

    /** {@link #storage(int, CgGpuBuffer, long, long)} for a buffer known by its device name, 0 for none. */
    public static void storage(int point, int name, long offset, long bytes) {
        if (!recording) return;
        if (name == 0) {
            put(CgBindingModel.ofStorage(point), null, 0, 0);
            return;
        }
        put(CgBindingModel.ofStorage(point), CgDeviceNames.buffer(name), offset, bytes);
    }

    /** A uniform buffer bound at engine point {@code point}. */
    public static void block(int point, @Nullable CgGpuBuffer buffer, long offset, long bytes) {
        if (recording) put(CgBindingModel.ofBlock(point), buffer, offset, bytes);
    }

    private static void put(int packed, @Nullable CgGpuBuffer buffer, long offset, long bytes) {
        int i = index(packed);
        if (i < 0 || BUFFER[i] == buffer && OFFSET[i] == offset && SIZE[i] == bytes) return;
        BUFFER[i] = buffer;
        OFFSET[i] = offset;
        SIZE[i] = bytes;
        VERSION[i / BINDINGS]++;
    }

    /** The buffer bound for {@code packed}; null for none. */
    @Nullable
    static CgGpuBuffer buffer(int packed) {
        int i = index(packed);
        return i < 0 ? null : BUFFER[i];
    }

    static long offset(int packed) {
        return OFFSET[index(packed)];
    }

    /** Its bytes; 0 for the rest of the buffer from {@link #offset}. */
    static long size(int packed) {
        return SIZE[index(packed)];
    }

    private static int index(int packed) {
        if (packed == CgBindingModel.NONE) return -1;
        int set = CgBindingModel.setOf(packed), binding = CgBindingModel.bindingOf(packed);
        return set < SETS && binding < BINDINGS ? set * BINDINGS + binding : -1;
    }
}
