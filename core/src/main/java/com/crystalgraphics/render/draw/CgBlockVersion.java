package com.crystalgraphics.render.draw;

import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;

import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.api.texture.CgTexture;

import javax.annotation.Nullable;
import java.nio.FloatBuffer;
import java.util.Arrays;
import com.crystalgraphics.render.shader.CgBindingModel;

/**
 * One version of a uniform block that outlives frames: a material's parameters as they were when a property last
 * moved. Written once into persistent memory the first time a frame binding it executes, and bound from there by
 * every draw after, so an unchanged material uploads nothing (Unreal's cached material uniform buffer).
 *
 * <pre>{@code
 * CgBlockVersion v = CgBlockVersion.of(packed, floats);       // when a value moved
 * CgMaterialBindings b = CgMaterialBindings.builder().block(CgBindingModel.MATERIAL_BLOCK, v).build();
 * v.supersede();                                              // replaced: freed once its frames retire
 *
 * CgBlockVersion w = CgBlockVersion.in(arena, packed, floats); // a slot of its shader's arena instead
 *
 * // a material's textures, as indices into the bindless heap written as the version is placed
 * CgBlockVersion t = CgBlockVersion.of(packed, floats, new CgBlockVersion.Members(offsets, textures, samplers));
 * CgBlockVersion over = t.withTexture(0, atlasPage, null);    // a draw's own texture in place of the first
 * }</pre>
 *
 * <ul>
 *   <li>A value: its floats never change. A changed block is a new version; a draw keeps the one it named.</li>
 *   <li>{@link #supersede} once nothing will capture it again, or its memory is kept for the session.</li>
 *   <li>Made on any thread; its placement is the render thread's ({@code CgFrameBindings}), which writes its texture
 *       members' heap indices then (Unreal's {@code UpdateUniformBufferConstants}) on the bindless path.</li>
 *   <li>One naming a frame graph texture is placed again every frame, in the frame's ring, with that frame's indices:
 *       a graph texture's storage may differ every frame. It never takes an arena slot.</li>
 * </ul>
 */
public final class CgBlockVersion {

    final float[] floats;
    /** Its texture members; null for none. */
    @Nullable
    final Members members;
    /** It names a frame graph texture: placed in each frame's ring with that frame's indices, never kept. */
    final boolean perFrame;
    /** The arena whose slot it takes ({@link #in}); null for a range of a page, and for a per-frame one. */
    @Nullable
    final CgMaterialArena arena;
    /**
     * Made for an arena and per frame: the arena's slot size, its copy in each frame's ring bound alone as a storage
     * block of one slot, which its shader reads as slot 0. 0 otherwise.
     */
    final int storageBytes;
    /** The arena it was made for, whether it takes a slot of it or not. */
    @Nullable
    private final CgMaterialArena madeFor;
    /** Where it was written: a page's buffer, its range's node and its byte offset; buffer null until written. */
    CgGpuBuffer buffer;
    int node = -1, offset;
    CgFrameBindings.BlockStore.Page page;
    /** In an arena, under its lock: its slot, -1 for none; the frames built naming it and not yet uploaded. */
    int slot = -1, pins;
    /** In an arena: whether its slot holds its floats. */
    boolean written;
    /** The last frame a draw bound it in. */
    long lastUse;
    volatile boolean superseded;

    private CgBlockVersion(float[] floats, @Nullable CgMaterialArena arena, @Nullable Members members) {
        this.floats = floats;
        this.members = members;
        this.perFrame = members != null && members.perFrame();
        // A graph texture's index changes every frame and an arena slot is written once: such a version draws alone
        this.arena = perFrame ? null : arena;
        this.storageBytes = perFrame && arena != null ? arena.stride() : 0;
        this.madeFor = arena;
    }

    /**
     * A block's textures: where each one's heap index sits among its floats, and the texture and sampler (null for its
     * own sampling) it reads, by the material's texture index.
     */
    public static final class Members {
        final int[] offsets;
        final CgTexture[] textures;
        final CgSampler[] samplers;

        public Members(int[] offsets, CgTexture[] textures, CgSampler[] samplers) {
            if (offsets.length != textures.length || samplers.length != textures.length) {
                throw new IllegalArgumentException(offsets.length + " offsets for " + textures.length + " textures");
            }
            this.offsets = offsets;
            this.textures = textures;
            this.samplers = samplers;
        }

        public int count() {
            return textures.length;
        }

        boolean perFrame() {
            for (CgTexture t : textures) if (t != null && !t.keepsStorage()) return true;
            return false;
        }
    }

    /** A version holding {@code data[0, count)}, copied. */
    public static CgBlockVersion of(float[] data, int count) {
        return of(data, count, null);
    }

    /** A version holding {@code data[0, count)}, copied, its texture {@code members} indexed as it is placed. */
    public static CgBlockVersion of(float[] data, int count, @Nullable Members members) {
        return new CgBlockVersion(Arrays.copyOf(data, count), null, members);
    }

    /**
     * This version reading {@code texture} through {@code sampler} as its texture {@code index}: an override's block.
     *
     * @throws IllegalArgumentException where it has no texture {@code index}, or an arena version would read a frame
     *                                  graph texture
     */
    public CgBlockVersion withTexture(int index, CgTexture texture, @Nullable CgSampler sampler) {
        if (members == null || index >= members.count()) {
            throw new IllegalArgumentException("no texture " + index + " in a block of " + (members == null ? 0 : members.count()));
        }
        CgTexture[] textures = members.textures.clone();
        CgSampler[] samplers = members.samplers.clone();
        textures[index] = texture;
        samplers[index] = sampler;
        return new CgBlockVersion(floats, arena, new Members(members.offsets, textures, samplers));
    }

    /** Its floats into {@code into} at its position, each texture member's heap index in place where {@code bindless}. */
    void put(FloatBuffer into, boolean bindless) {
        int at = into.position();
        into.put(floats);
        if (!bindless || members == null) return;
        for (int i = 0; i < members.count(); i++) {
            // None before the engine's own textures exist; a graph texture is indexed as its pass executes
            if (members.textures[i] == null || !members.textures[i].keepsStorage()) continue;
            int index = CgBindlessTextures.index(members.textures[i], members.samplers[i]);
            into.put(at + members.offsets[i], Float.intBitsToFloat(index));
        }
    }

    /**
     * A version holding {@code data[0, count)} in a slot of {@code arena}: bound as the arena, its draws' ids naming
     * the slot, so draws of other versions of the arena join with its.
     *
     * @throws IllegalArgumentException if {@code count} floats are more than the arena's slot holds
     */
    public static CgBlockVersion in(CgMaterialArena arena, float[] data, int count) {
        return in(arena, data, count, null);
    }

    /**
     * {@link #in(CgMaterialArena, float[], int)} with texture {@code members}, indexed as it is placed. One naming a
     * frame graph texture takes no slot: it is copied into each frame's ring and its draws do not join others'.
     */
    public static CgBlockVersion in(CgMaterialArena arena, float[] data, int count, @Nullable Members members) {
        if (count * 4 > arena.stride()) throw new IllegalArgumentException("a block of " + count + " floats in " + arena);
        return new CgBlockVersion(Arrays.copyOf(data, count), arena, members);
    }

    /** The arena it was made for ({@link #in}), whether or not it takes a slot of it; null for a version of its own. */
    @Nullable
    public CgMaterialArena arena() {
        return madeFor;
    }

    /** Its floats' count. */
    public int floatCount() {
        return floats.length;
    }

    /** A copy of its floats. */
    public float[] floats() {
        return floats.clone();
    }

    /** Whether it holds {@code data[0, count)}: a writer's floats a new version would only repeat. */
    public boolean holds(float[] data, int count) {
        if (floats.length != count) return false;
        for (int i = 0; i < count; i++) {
            if (Float.floatToRawIntBits(floats[i]) != Float.floatToRawIntBits(data[i])) return false;
        }
        return true;
    }

    /** Never captured again: its memory is freed once the frames that drew it retire. */
    public void supersede() {
        superseded = true;
    }
}
