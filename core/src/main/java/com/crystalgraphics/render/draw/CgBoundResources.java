package com.crystalgraphics.render.draw;

import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.api.texture.CgTexture;
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
 * CgBoundResources.storage(point, name, offset, bytes);  // beside each storage bind; 0 bytes for the whole buffer
 * CgBoundResources.texture(unit, texture, -1);            // beside each texture bind; a level for one level alone
 * }</pre>
 *
 * <p>Render thread.</p>
 */
public final class CgBoundResources {

    /** Whether binds are recorded. */
    public static boolean recording;

    private static final int SETS = 3, BINDINGS = 64;
    private static final int[] NAME = new int[SETS * BINDINGS];
    private static final long[] OFFSET = new long[SETS * BINDINGS], SIZE = new long[SETS * BINDINGS];

    private static final CgTexture[] TEXTURE = new CgTexture[SETS * BINDINGS];
    private static final int[] LEVEL = new int[SETS * BINDINGS];
    private static final CgSampler[] SAMPLER = new CgSampler[SETS * BINDINGS];

    private CgBoundResources() {}

    /** {@code texture} bound at engine unit {@code unit}: level {@code level} alone, or every level for -1. */
    public static void texture(int unit, CgTexture texture, int level) {
        if (!recording) return;
        int i = index(CgBindingModel.ofTexture(unit));
        if (i < 0) return;
        TEXTURE[i] = texture;
        LEVEL[i] = level;
    }

    /** The sampler bound at {@code unit}; null for the texture's own sampling. */
    public static void sampler(int unit, @Nullable CgSampler sampler) {
        if (!recording) return;
        int i = index(CgBindingModel.ofTexture(unit));
        if (i >= 0) SAMPLER[i] = sampler;
    }

    /** Forgets the textures noted for {@code set}: a pass on the device takes them from its snapshots. */
    public static void forgetTextures(int set) {
        if (set >= SETS) return;
        for (int b = 0; b < BINDINGS; b++) {
            TEXTURE[set * BINDINGS + b] = null;
            SAMPLER[set * BINDINGS + b] = null;
        }
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

    /** A storage buffer bound at engine point {@code point}: {@code name}'s {@code bytes} from {@code offset}, 0 for all. */
    public static void storage(int point, int name, long offset, long bytes) {
        if (recording) put(CgBindingModel.ofStorage(point), name, offset, bytes);
    }

    /** A uniform buffer bound at engine point {@code point}. */
    public static void block(int point, int name, long offset, long bytes) {
        if (recording) put(CgBindingModel.ofBlock(point), name, offset, bytes);
    }

    private static void put(int packed, int name, long offset, long bytes) {
        int i = index(packed);
        if (i < 0) return;
        NAME[i] = name;
        OFFSET[i] = offset;
        SIZE[i] = bytes;
    }

    /** The buffer name bound for {@code packed}; 0 for none. */
    static int name(int packed) {
        int i = index(packed);
        return i < 0 ? 0 : NAME[i];
    }

    static long offset(int packed) {
        return OFFSET[index(packed)];
    }

    /** Its bytes; 0 for the whole buffer from {@link #offset}. */
    static long size(int packed) {
        return SIZE[index(packed)];
    }

    private static int index(int packed) {
        if (packed == CgBindingModel.NONE) return -1;
        int set = CgBindingModel.setOf(packed), binding = CgBindingModel.bindingOf(packed);
        return set < SETS && binding < BINDINGS ? set * BINDINGS + binding : -1;
    }
}
