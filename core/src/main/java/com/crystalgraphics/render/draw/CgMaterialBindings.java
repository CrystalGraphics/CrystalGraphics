package com.crystalgraphics.render.draw;

import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.gpu.resource.CgShaderBuffer;
import com.crystalgraphics.render.shader.CgBindingModel;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Objects;

/**
 * What a draw of a material binds besides its pass's parameters, as of one version of the material: its values' block,
 * its textures and their samplers, its engine blocks and buffers, and the storage buffers bound to it. Its material's
 * proxy makes one when any of them moves and answers the same object until then (Unreal's material uniform buffer,
 * cached on {@code FMaterialRenderProxy}); a draw names it rather than copying what it holds.
 *
 * <pre>{@code
 * CgMaterialBindings b = CgMaterialProxy.of(material).bindings();   // the same object while nothing moved
 * chunks.draw(pipeline, b, mesh);
 * chunks.draw(pipeline, b.with(0, atlasPage), mesh);               // a draw's own texture over its material's first
 * }</pre>
 *
 * <p>Made by hand, for a draw with no material of its own:</p>
 *
 * <pre>{@code
 * CgMaterialBindings copy = CgMaterialBindings.builder().texture(0, source, CgSampler.NEAREST_CLAMP).build();
 * }</pre>
 *
 * <ul>
 *   <li>Immutable: a value written after a draw names it reaches only draws naming the next version.</li>
 *   <li>A texture is held, not its storage: one reallocated before the draw executes binds its new storage, which a
 *       growing glyph atlas needs. Its content is ordered by the frame graph's versions.</li>
 *   <li>{@link #with} is kept per texture, so a draw repeating one allocates nothing.</li>
 *   <li>At most {@value #MAX_TEXTURES} textures, {@value #MAX_BLOCKS} blocks, {@value #MAX_BUFFERS} buffers and
 *       {@value #MAX_STORAGE} storage buffers.</li>
 * </ul>
 */
public final class CgMaterialBindings {

    /** A version's cap; a material binds at most {@link CgBindingModel#MATERIAL_TEXTURES}, checked at compile. */
    public static final int MAX_TEXTURES = 12;
    public static final int MAX_BLOCKS = 4;
    public static final int MAX_BUFFERS = 4;
    public static final int MAX_STORAGE = 4;
    /** {@link #with}'s kept versions per base: the textures one material is drawn with in turn. */
    private static final int KEPT_OVERS = 8;

    /** Binds nothing: a draw reading only its pass's parameters. */
    public static final CgMaterialBindings NONE = builder().build();

    final int[] textureSlots;
    final CgTexture[] textures;
    final CgSampler[] samplers;
    final int[] blockSlots;
    final CgBlockVersion[] blocks;
    final CgShaderBuffer[] buffers;
    final int[] storageSlots;
    final CgBufferHandle[] storage;

    /** {@link #with}'s versions, each under its unit, texture and sampler; replaced in turn past {@link #KEPT_OVERS}. */
    @Nullable
    private Over[] overs;
    private int oversNext;

    private record Over(int slot, CgTexture texture, @Nullable CgSampler sampler, CgMaterialBindings made) {
    }

    private CgMaterialBindings(Builder b) {
        textureSlots = Arrays.copyOf(b.textureSlots, b.textureCount);
        textures = Arrays.copyOf(b.textures, b.textureCount);
        samplers = Arrays.copyOf(b.samplers, b.textureCount);
        blockSlots = Arrays.copyOf(b.blockSlots, b.blockCount);
        blocks = Arrays.copyOf(b.blocks, b.blockCount);
        buffers = Arrays.copyOf(b.buffers, b.bufferCount);
        storageSlots = Arrays.copyOf(b.storageSlots, b.storageCount);
        storage = Arrays.copyOf(b.storage, b.storageCount);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** These bindings with {@code texture} as the material's texture {@code index}, sampled as its own. */
    public CgMaterialBindings with(int index, CgTexture texture) {
        return with(index, texture, null);
    }

    /**
     * These bindings with {@code texture} as the material's texture {@code index}, in place of what it had there, sampled
     * by {@code sampler} (null for its own sampling): a canvas item's image, a label's atlas page, a copy's source.
     */
    public synchronized CgMaterialBindings with(int index, CgTexture texture, @Nullable CgSampler sampler) {
        int slot = CgBindingModel.packedTexture(index);
        if (overs != null) {
            for (Over o : overs) {
                if (o != null && o.slot == slot && o.texture == texture && Objects.equals(o.sampler, sampler)) return o.made;
            }
        } else {
            overs = new Over[KEPT_OVERS];
        }
        Builder b = builder();
        for (int t = 0; t < textures.length; t++) if (textureSlots[t] != slot) b.put(textureSlots[t], textures[t], samplers[t]);
        b.put(slot, texture, sampler);
        b.rest(this);
        CgMaterialBindings made = b.build();
        overs[oversNext] = new Over(slot, texture, sampler, made);
        oversNext = (oversNext + 1) % KEPT_OVERS;
        return made;
    }

    public int textures() {
        return textures.length;
    }

    /** The slot ({@link CgBindingModel#pack packed}) texture {@code i} binds to. */
    public int textureSlot(int i) {
        return textureSlots[i];
    }

    public CgTexture texture(int i) {
        return textures[i];
    }

    /** The sampler texture {@code i} is sampled by; null for its own. */
    @Nullable
    public CgSampler sampler(int i) {
        return samplers[i];
    }

    public int blocks() {
        return blocks.length;
    }

    /** The slot ({@link CgBindingModel#pack packed}) block {@code k} binds to. */
    public int blockSlot(int k) {
        return blockSlots[k];
    }

    public CgBlockVersion block(int k) {
        return blocks[k];
    }

    public int buffers() {
        return buffers.length;
    }

    /** An engine buffer read at its own slot, its range as it is when the draw executes. */
    public CgShaderBuffer buffer(int i) {
        return buffers[i];
    }

    public int storages() {
        return storage.length;
    }

    /** The slot ({@link CgBindingModel#pack packed}) storage buffer {@code i} binds at. */
    public int storageSlot(int i) {
        return storageSlots[i];
    }

    public CgBufferHandle storage(int i) {
        return storage[i];
    }

    /** Whether {@code other} binds the same things to the same slots: a version a proxy can keep in its place. */
    public boolean sameAs(CgMaterialBindings other) {
        if (other == this) return true;
        return Arrays.equals(textureSlots, other.textureSlots) && same(textures, other.textures)
                && Arrays.equals(samplers, other.samplers) && Arrays.equals(blockSlots, other.blockSlots)
                && same(blocks, other.blocks) && same(buffers, other.buffers)
                && Arrays.equals(storageSlots, other.storageSlots) && same(storage, other.storage);
    }

    private static boolean same(Object[] a, Object[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }

    @Override
    public String toString() {
        return "CgMaterialBindings[" + textures.length + " textures, " + blocks.length + " blocks, " + buffers.length
                + " buffers, " + storage.length + " storage]";
    }

    /** Fills a version by slot; {@link #build} once. */
    public static final class Builder {
        private final int[] textureSlots = new int[MAX_TEXTURES];
        private final CgTexture[] textures = new CgTexture[MAX_TEXTURES];
        private final CgSampler[] samplers = new CgSampler[MAX_TEXTURES];
        private int textureCount;
        private final int[] blockSlots = new int[MAX_BLOCKS];
        private final CgBlockVersion[] blocks = new CgBlockVersion[MAX_BLOCKS];
        private int blockCount;
        private final CgShaderBuffer[] buffers = new CgShaderBuffer[MAX_BUFFERS];
        private int bufferCount;
        private final int[] storageSlots = new int[MAX_STORAGE];
        private final CgBufferHandle[] storage = new CgBufferHandle[MAX_STORAGE];
        private int storageCount;

        private Builder() {
        }

        /** {@code texture} as the material's texture {@code index}, sampled by {@code sampler}; null for its own. */
        public Builder texture(int index, CgTexture texture, @Nullable CgSampler sampler) {
            return put(CgBindingModel.packedTexture(index), texture, sampler);
        }

        public Builder texture(int index, CgTexture texture) {
            return texture(index, texture, null);
        }

        private Builder put(int slot, CgTexture texture, @Nullable CgSampler sampler) {
            if (textureCount == MAX_TEXTURES) throw new IllegalStateException("more than " + MAX_TEXTURES + " textures");
            textureSlots[textureCount] = slot;
            textures[textureCount] = Objects.requireNonNull(texture, "texture");
            samplers[textureCount++] = sampler;
            return this;
        }

        /** A block at {@code slot} from {@code version}'s persistent memory. */
        public Builder block(CgBindingModel.Slot slot, CgBlockVersion version) {
            if (blockCount == MAX_BLOCKS) throw new IllegalStateException("more than " + MAX_BLOCKS + " blocks");
            blockSlots[blockCount] = slot.packed();
            blocks[blockCount++] = version;
            return this;
        }

        /** An engine buffer the draw reads at its own slot. */
        public Builder buffer(CgShaderBuffer buffer) {
            if (bufferCount == MAX_BUFFERS) throw new IllegalStateException("more than " + MAX_BUFFERS + " buffers");
            buffers[bufferCount++] = buffer;
            return this;
        }

        /** {@code buffer} as the storage block at {@code slot}, resolved when the draw executes. */
        public Builder storage(CgBindingModel.Slot slot, CgBufferHandle buffer) {
            if (storageCount == MAX_STORAGE) throw new IllegalStateException("more than " + MAX_STORAGE + " storage buffers");
            storageSlots[storageCount] = slot.packed();
            storage[storageCount++] = buffer;
            return this;
        }

        /** Empties it for another version: a builder kept by its owner fills each without allocating. */
        public Builder reset() {
            Arrays.fill(textures, 0, textureCount, null);
            Arrays.fill(samplers, 0, textureCount, null);
            Arrays.fill(blocks, 0, blockCount, null);
            Arrays.fill(buffers, 0, bufferCount, null);
            Arrays.fill(storage, 0, storageCount, null);
            textureCount = blockCount = bufferCount = storageCount = 0;
            return this;
        }

        /** Whether it holds what {@code made} binds: a version its owner keeps rather than {@link #build}ing another. */
        public boolean sameAs(CgMaterialBindings made) {
            if (made.textures.length != textureCount || made.blocks.length != blockCount
                    || made.buffers.length != bufferCount || made.storage.length != storageCount) return false;
            for (int t = 0; t < textureCount; t++) {
                if (made.textureSlots[t] != textureSlots[t] || made.textures[t] != textures[t]
                        || !Objects.equals(made.samplers[t], samplers[t])) return false;
            }
            for (int k = 0; k < blockCount; k++) {
                if (made.blockSlots[k] != blockSlots[k] || made.blocks[k] != blocks[k]) return false;
            }
            for (int b = 0; b < bufferCount; b++) if (made.buffers[b] != buffers[b]) return false;
            for (int s = 0; s < storageCount; s++) {
                if (made.storageSlots[s] != storageSlots[s] || made.storage[s] != storage[s]) return false;
            }
            return true;
        }

        /** {@code from}'s blocks, buffers and storage: what {@link #with} keeps beside a new texture. */
        private void rest(CgMaterialBindings from) {
            for (int k = 0; k < from.blocks.length; k++) {
                blockSlots[blockCount] = from.blockSlots[k];
                blocks[blockCount++] = from.blocks[k];
            }
            for (CgShaderBuffer b : from.buffers) buffers[bufferCount++] = b;
            for (int s = 0; s < from.storage.length; s++) {
                storageSlots[storageCount] = from.storageSlots[s];
                storage[storageCount++] = from.storage[s];
            }
        }

        public CgMaterialBindings build() {
            return new CgMaterialBindings(this);
        }
    }
}
