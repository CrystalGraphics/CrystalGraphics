package com.crystalgraphics.render.draw;

import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.gl.texture.CgFallbackTextures;
import com.crystalgraphics.platform.gl.CgGLImports;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.binding.CgBindingLayout;
import com.crystalgraphics.platform.rhi.binding.CgBindingSet;
import com.crystalgraphics.platform.rhi.binding.CgBindings;
import com.crystalgraphics.platform.rhi.pipeline.CgPipelineLayout;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;
import com.crystalgraphics.platform.rhi.resource.CgGpuSampler;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;
import com.crystalgraphics.render.shader.CgBindingModel;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A recorded draw's bindings as the device's sets: each set of its pipeline's layout filled from the draw's
 * {@link CgBindingTable} snapshot through {@link CgBindingModel}'s slots, from what the caller supplies for the slots
 * a snapshot does not hold, then from what the executor has bound there ({@link CgBoundResources}: the pass's records
 * and tables), and interned by content across frames, as Unreal keeps a material's
 * uniform buffer until its contents change.
 *
 * <pre>{@code
 * table.upload(ring);                                                 // the ring blocks' places first
 * sets.prepare(table);                                               // textures loaded, outside a device section
 * CgBindingSet material = sets.set(rhi, table, id, layout, CgBindingModel.MATERIAL, null);
 * CgBindingSet pass = sets.set(rhi, table, id, layout, CgBindingModel.PASS, passResources);
 * }</pre>
 *
 * <ul>
 *   <li>A slot nothing fills takes a default (white for a texture, a zeroed-at-birth buffer), counted as
 *       {@code bindings.slots-defaulted}: a draw that reads one draws wrongly, so a scene asserts none.</li>
 *   <li>A set holding a ring block is that frame's: the block's place moves each frame. A material's own block is a
 *       {@link CgBlockVersion}, so its set lasts.</li>
 *   <li>Render thread; the sets it made live until {@link #release}.</li>
 * </ul>
 */
public final class CgDeviceSets {

    private static final int MADE = CgTrace.name("bindings.sets-made"), REUSED = CgTrace.name("bindings.sets-reused"),
            DEFAULTED = CgTrace.name("bindings.slots-defaulted");
    private static final int DEFAULT_BYTES = 4096;

    private final Map<Key, CgBindingSet> sets = new HashMap<>();
    private final Key probe = new Key();
    private final CgBindings contents = new CgBindings();
    @Nullable
    private CgRHI device;
    @Nullable
    private CgGpuBuffer defaultBuffer;
    private long made, reused, defaulted;
    /** The last slot {@link #fill} left to a default, for a caller naming it. */
    private String lastDefaulted = "";

    /**
     * Set {@code set} of {@code layout} for snapshot {@code id}: made, or the one made for equal contents before.
     *
     * @param extras what fills a slot the snapshot does not hold, by binding; null for none
     */
    public CgBindingSet set(CgRHI rhi, CgBindingTable table, int id, CgPipelineLayout layout, int set,
                            @Nullable CgBindings extras) {
        if (device != rhi) {
            release();
            device = rhi;
        }
        CgBindingLayout bl = layout.sets().get(set);
        contents.clear();
        fill(rhi, table, id, set, bl, extras);
        probe.of(bl, contents);
        CgBindingSet found = sets.get(probe);
        if (found != null) {
            reused++;
            CgTrace.add(CgChannels.GL, REUSED, 1);
            return found;
        }
        CgBindingSet made = rhi.createBindingSet("set " + set, bl, contents);
        sets.put(probe.copy(), made);
        this.made++;
        CgTrace.add(CgChannels.GL, MADE, 1);
        return made;
    }

    /**
     * Loads every texture {@code table}'s snapshots record and lands its pending uploads: outside a device section, as
     * those go through {@code CgGL}. Before the first {@link #set} of a frame.
     */
    public void prepare(CgBindingTable table) {
        for (int id = 0; id < table.size(); id++) {
            for (int t = 0; t < table.textures(id); t++) table.texture(id, t).getId();
        }
    }

    /** Sets made, reused and slots defaulted since it was created. */
    public long made() {
        return made;
    }

    public long reused() {
        return reused;
    }

    public long defaulted() {
        return defaulted;
    }

    /** The last slot a default filled: {@code "set 2 binding 16 SAMPLED_TEXTURE"}. */
    public String lastDefaulted() {
        return lastDefaulted;
    }

    /** Releases every set it made and its default buffer; the next {@link #set} starts again. */
    public void release() {
        if (device != null) {
            for (CgBindingSet s : sets.values()) device.release(s);
            if (defaultBuffer != null) device.release(defaultBuffer);
        }
        sets.clear();
        defaultBuffer = null;
        device = null;
    }

    private void fill(CgRHI rhi, CgBindingTable table, int id, int set, CgBindingLayout layout, @Nullable CgBindings extras) {
        List<CgBindingLayout.Slot> slots = layout.slots();
        for (int i = 0; i < slots.size(); i++) {
            CgBindingLayout.Slot slot = slots.get(i);
            int packed = CgBindingModel.pack(set, slot.binding());
            if (fromTable(rhi, table, id, packed, slot)) continue;
            int at = extras == null ? -1 : extras.indexOf(slot.binding());
            if (at >= 0) {
                copy(extras, at);
                continue;
            }
            fallback(rhi, slot);
            defaulted++;
            lastDefaulted = "set " + set + " binding " + slot.binding() + " " + slot.type();
            CgTrace.add(CgChannels.GL, DEFAULTED, 1);
        }
    }

    /** Whether the snapshot holds {@code slot}, added to {@link #contents} if so. */
    private boolean fromTable(CgRHI rhi, CgBindingTable table, int id, int packed, CgBindingLayout.Slot slot) {
        switch (slot.type()) {
            case SAMPLED_TEXTURE -> {
                if (boundTexture(rhi, slot, packed)) return true;
                for (int t = 0; t < table.textures(id); t++) {
                    if (CgBindingModel.ofTexture(table.textureUnit(id, t)) != packed) continue;
                    CgTexture texture = table.texture(id, t);
                    CgTextureView view = texture.rhiView();
                    if (view == null) return false;
                    contents.texture(slot.binding(), view, CgDeviceSamplers.of(rhi, table.sampler(id, t), texture));
                    return true;
                }
                return false;
            }
            case UNIFORM_BUFFER -> {
                for (int k = 0; k < table.blocks(id); k++) {
                    if (CgBindingModel.ofBlock(table.blockBinding(id, k)) != packed) continue;
                    CgBlockVersion version = table.blockVersion(id, k);
                    int name = version != null ? version.buffer : table.uploadedBuffer();
                    long at = version != null ? version.offset : table.ringOffset(id, k);
                    if (name == 0) return false;
                    CgGLImports imports = CgGLImports.get();
                    long bytes = Math.max(16, (table.blockFloatCount(id, k) * 4L + 15) & ~15L);
                    contents.buffer(slot.binding(), slot.type(), imports.buffer(name), imports.bufferOffset(name) + at, bytes);
                    return true;
                }
                return bound(slot, packed);
            }
            case STORAGE_BUFFER -> {
                for (int s = 0; s < table.storages(id); s++) {
                    if (CgBindingModel.ofStorage(table.storagePoint(id, s)) != packed) continue;
                    return whole(slot, table.storage(id, s).bufferId());
                }
                return bound(slot, packed);
            }
            default -> {
                return false;
            }
        }
    }

    /** The texture bound for {@code packed} ({@link CgBoundResources}): over the snapshot's, as a GL program samples. */
    private boolean boundTexture(CgRHI rhi, CgBindingLayout.Slot slot, int packed) {
        CgTexture texture = CgBoundResources.texture(packed);
        if (texture == null || texture.isDeleted()) return false;
        CgTextureView view = texture.rhiView();
        if (view == null) return false;
        int level = CgBoundResources.level(packed);
        if (level >= 0) view = new CgTextureView(view.texture(), level, 1, view.baseLayer(), view.layers());
        contents.texture(slot.binding(), view, CgDeviceSamplers.of(rhi, CgBoundResources.sampler(packed), texture));
        return true;
    }

    /** What the executor has bound for {@code packed} ({@link CgBoundResources}): a snapshot's buffers, the pass's. */
    private boolean bound(CgBindingLayout.Slot slot, int packed) {
        int name = CgBoundResources.name(packed);
        if (name == 0) return false;
        CgGLImports imports = CgGLImports.get();
        CgGpuBuffer buffer = imports.buffer(name);
        long offset = imports.bufferOffset(name) + CgBoundResources.offset(packed);
        long size = CgBoundResources.size(packed);
        contents.buffer(slot.binding(), slot.type(), buffer, offset, size != 0 ? size : buffer.size() - offset);
        return true;
    }

    /** {@code name}'s whole storage at {@code slot}. */
    private boolean whole(CgBindingLayout.Slot slot, int name) {
        if (name == 0) return false;
        CgGLImports imports = CgGLImports.get();
        CgGpuBuffer buffer = imports.buffer(name);
        long offset = imports.bufferOffset(name);
        contents.buffer(slot.binding(), slot.type(), buffer, offset, buffer.size() - offset);
        return true;
    }

    private void copy(CgBindings from, int i) {
        switch (from.type(i)) {
            case SAMPLED_TEXTURE -> contents.texture(from.binding(i), from.view(i), from.sampler(i));
            case STORAGE_IMAGE -> contents.image(from.binding(i), from.view(i));
            case TEXEL_BUFFER -> contents.texel(from.binding(i), from.buffer(i), from.offset(i), from.size(i), from.texelFormat(i));
            default -> contents.buffer(from.binding(i), from.type(i), from.buffer(i), from.offset(i), from.size(i));
        }
    }

    private void fallback(CgRHI rhi, CgBindingLayout.Slot slot) {
        switch (slot.type()) {
            case SAMPLED_TEXTURE -> {
                CgTextureView white = CgFallbackTextures.WHITE_1x1 == null ? null : CgFallbackTextures.WHITE_1x1.rhiView();
                if (white == null) throw new IllegalStateException("no default texture for " + slot);
                contents.texture(slot.binding(), white,
                        CgDeviceSamplers.of(rhi, CgSampler.NEAREST_CLAMP, CgFallbackTextures.WHITE_1x1));
            }
            case UNIFORM_BUFFER, STORAGE_BUFFER -> {
                if (defaultBuffer == null) {
                    defaultBuffer = rhi.createBuffer(new CgGpuBuffer.Desc("default binding", DEFAULT_BYTES,
                            CgGpuBuffer.Usage.ALL, false));
                }
                contents.buffer(slot.binding(), slot.type(), defaultBuffer, 0, DEFAULT_BYTES);
            }
            default -> throw new IllegalStateException("no default for " + slot);
        }
    }

    /** A set's layout and contents by identity and number: what makes two sets the same set. */
    private static final class Key {
        CgBindingLayout layout;
        Object[] refs = new Object[16];
        long[] nums = new long[16];
        int count, hash;

        void of(CgBindingLayout layout, CgBindings c) {
            this.layout = layout;
            count = c.count();
            if (refs.length < count * 2) refs = new Object[count * 2];
            if (nums.length < count * 3) nums = new long[count * 3];
            int h = System.identityHashCode(layout);
            for (int i = 0; i < count; i++) {
                CgTextureView view = c.view(i);
                Object resource = view != null ? view.texture() : c.buffer(i);
                CgGpuSampler sampler = c.sampler(i);
                refs[i * 2] = resource;
                refs[i * 2 + 1] = sampler;
                nums[i * 3] = c.binding(i);
                nums[i * 3 + 1] = view != null ? (long) view.baseMip() << 32 | view.baseLayer() : c.offset(i);
                nums[i * 3 + 2] = view != null ? (long) view.mips() << 32 | view.layers() : c.size(i);
                h = h * 31 + System.identityHashCode(resource);
                h = h * 31 + System.identityHashCode(sampler);
                h = h * 31 + Long.hashCode(nums[i * 3 + 1]) + c.binding(i);
            }
            hash = h;
        }

        Key copy() {
            Key k = new Key();
            k.layout = layout;
            k.refs = Arrays.copyOf(refs, count * 2);
            k.nums = Arrays.copyOf(nums, count * 3);
            k.count = count;
            k.hash = hash;
            return k;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key k) || k.layout != layout || k.count != count) return false;
            for (int i = 0; i < count * 2; i++) if (k.refs[i] != refs[i]) return false;
            for (int i = 0; i < count * 3; i++) if (k.nums[i] != nums[i]) return false;
            return true;
        }
    }
}
