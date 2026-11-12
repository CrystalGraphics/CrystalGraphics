package com.crystalgraphics.render.draw;

import com.crystalgraphics.gpu.resource.CgTextureAbstract;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.platform.rhi.CgDeviceNames;
import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.gpu.resource.CgFrameRing;
import com.crystalgraphics.gpu.resource.CgFallbackTextures;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.crystalgraphics.gpu.resource.CgShaderBuffer;
import com.crystalgraphics.gpu.resource.CgShaderBufferRegistry;

/**
 * A recorded draw's or dispatch's bindings as the device's sets: each slot of its pipeline's layout filled from the
 * pass's parameters (the caller's, by binding: its records, instances, tables and scene copies), then from its
 * {@link CgFrameBindings} snapshot by slot, then from the engine buffer made for that slot; interned by content across
 * frames, as Unreal keeps a material's uniform buffer until its contents change.
 *
 * <pre>{@code
 * table.upload(ring);                                                 // the ring blocks' places first
 * sets.prepare(table);                                               // textures loaded, outside a device section
 * CgBindingSet pass = sets.set(rhi, table, id, layout, CgBindingModel.PASS, passParameters);
 * rp.setBindingSet(CgBindingModel.PASS, pass, sets.offsets(), sets.offsetCount());   // read before the next set()
 * }</pre>
 *
 * <ul>
 *   <li>A slot nothing fills takes a default (white for a texture, a zeroed-at-birth buffer), counted as
 *       {@code bindings.slots-defaulted}: a draw that reads one draws wrongly, so a scene asserts none.</li>
 *   <li>A buffer in a dynamic slot ({@link CgDeviceStates} marks them) is keyed without its offset, which
 *       {@link #offsets} gives for the bind: a set holding a buffer the frame ring moves each frame lasts. One past the
 *       device's dynamic limit is keyed by its offset, its set that frame's.</li>
 *   <li>A block in a {@link CgMaterialArena} fills the storage slot it maps to: the whole arena for draws whose ids
 *       carry its slot, else its slot alone, as the caller says.</li>
 *   <li>A set no call has asked for in {@link #KEPT_FRAMES} frames is released (the device keeps it until its frames
 *       retire): a frame's own sets go with it, as Unreal resets a command buffer's pools.</li>
 *   <li>Render thread.</li>
 * </ul>
 */
public final class CgDeviceSets {

    private static final int MADE = CgTrace.name("bindings.sets-made"), REUSED = CgTrace.name("bindings.sets-reused"),
            DEFAULTED = CgTrace.name("bindings.slots-defaulted"), RELEASED = CgTrace.name("bindings.sets-released");
    private static final int DEFAULT_BYTES = 4096;
    /** Dynamic slots a pipeline's sets hold at most, whatever the device allows. */
    public static final int MAX_DYNAMIC = 16;
    /** Frames a set lasts unasked. */
    static final int KEPT_FRAMES = 16;

    private final Map<Key, Entry> sets = new HashMap<>();
    /** The same entries, by index: the sweep walks them with no iterator. */
    private final ArrayList<Entry> live = new ArrayList<>();
    private long swept = -1;
    private final Key probe = new Key();
    private final CgBindings contents = new CgBindings();
    /** The last set's dynamic offsets, in binding order; and each content entry's place among them, or -1. */
    private final int[] offsets = new int[MAX_DYNAMIC];
    private int offsetCount;
    private int[] entryDynamic = new int[16];
    @Nullable
    private CgRHI device;
    @Nullable
    private CgGpuBuffer defaultBuffer;
    private long made, reused, defaulted;
    /** The last slot {@link #fill} left to a default, for a caller naming it. */
    private String lastDefaulted = "";
    /** The frame the set being filled is for. */
    private long frame;
    /** The set being filled binds an arena block as the whole arena, else as its slot alone. */
    private boolean wholeArena;

    /**
     * Set {@code set} of {@code layout} for snapshot {@code id}: made, or the one made for equal contents before.
     *
     * @param extras the pass's parameters for this set, by binding, ahead of the snapshot; null for none
     */
    public CgBindingSet set(CgRHI rhi, CgFrameBindings table, int id, CgPipelineLayout layout, int set,
                            @Nullable CgBindings extras) {
        return set(rhi, table, id, layout, set, extras, true);
    }

    /**
     * {@link #set(CgRHI, CgFrameBindings, int, CgPipelineLayout, int, CgBindings)}, an arena block bound as the whole arena
     * where {@code wholeArena}, for draws whose ids carry its slot, else as its slot's range alone.
     */
    public CgBindingSet set(CgRHI rhi, CgFrameBindings table, int id, CgPipelineLayout layout, int set,
                            @Nullable CgBindings extras, boolean wholeArena) {
        this.wholeArena = wholeArena;
        if (device != rhi) {
            release();
            device = rhi;
        }
        long frame = CgFrameRing.frame();
        if (frame != swept) sweep(frame);
        CgBindingLayout bl = layout.sets().get(set);
        contents.clear();
        fill(rhi, table, id, set, bl, extras);
        dynamicOffsets(bl);
        probe.of(bl, contents, entryDynamic);
        Entry found = sets.get(probe);
        if (found != null) {
            found.asked = frame;
            reused++;
            CgTrace.add(CgChannels.GL, REUSED, 1);
            return found.set;
        }
        CgBindingSet made = rhi.createBindingSet("set " + set, bl, contents);
        Entry entry = new Entry(made, frame, probe.copy());
        sets.put(entry.key, entry);
        live.add(entry);
        this.made++;
        CgTrace.add(CgChannels.GL, MADE, 1);
        return made;
    }

    /** {@link #contents}' dynamic entries: their places noted, their offsets into {@link #offsets}. */
    private void dynamicOffsets(CgBindingLayout layout) {
        int n = contents.count();
        if (entryDynamic.length < n) entryDynamic = new int[Math.max(n, entryDynamic.length * 2)];
        offsetCount = layout.dynamicCount();
        if (offsetCount > MAX_DYNAMIC) throw new IllegalStateException(layout.label() + " has " + offsetCount + " dynamic slots");
        for (int i = 0; i < n; i++) {
            int at = offsetCount == 0 ? -1 : CgBindingLayout.dynamicIndex(layout.slots(), contents.binding(i));
            entryDynamic[i] = at;
            if (at >= 0) offsets[at] = Math.toIntExact(contents.offset(i));
        }
    }

    /** The dynamic offsets of the set {@link #set} last answered, in binding order: read before the next call. */
    public int[] offsets() {
        return offsets;
    }

    /** How many of {@link #offsets} the last set takes. */
    public int offsetCount() {
        return offsetCount;
    }

    /** Releases the sets no call asked for in {@link #KEPT_FRAMES} frames; once a frame. */
    private void sweep(long frame) {
        swept = frame;
        for (int i = live.size() - 1; i >= 0; i--) {
            Entry e = live.get(i);
            if (frame - e.asked <= KEPT_FRAMES) continue;
            device.release(e.set);
            sets.remove(e.key);
            int last = live.size() - 1;
            live.set(i, live.get(last));
            live.remove(last);
            CgTrace.add(CgChannels.GL, RELEASED, 1);
        }
    }

    /**
     * Loads every texture {@code table}'s snapshots record and lands its buffers' pending uploads: outside any render
     * pass, as an upload is a copy. Before the first {@link #set} of a frame.
     */
    public void prepare(CgFrameBindings table) {
        for (int id = 0; id < table.size(); id++) {
            for (int t = 0; t < table.textures(id); t++) {
                if (table.texture(id, t) instanceof CgTextureAbstract engine) engine.getId();
            }
            for (int b = 0; b < table.buffers(id); b++) table.buffer(id, b).flush();
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
            for (int i = 0; i < live.size(); i++) device.release(live.get(i).set);
            if (defaultBuffer != null) device.release(defaultBuffer);
        }
        sets.clear();
        live.clear();
        defaultBuffer = null;
        device = null;
    }

    private void fill(CgRHI rhi, CgFrameBindings table, int id, int set, CgBindingLayout layout, @Nullable CgBindings extras) {
        frame = CgFrameRing.frame();
        List<CgBindingLayout.Slot> slots = layout.slots();
        for (int i = 0; i < slots.size(); i++) {
            CgBindingLayout.Slot slot = slots.get(i);
            int at = extras == null ? -1 : extras.indexOf(slot.binding());
            if (at >= 0) {
                copy(extras, at);
                continue;
            }
            int packed = CgBindingModel.pack(set, slot.binding());
            if (fromTable(rhi, table, id, packed, slot)) continue;
            if (slot.type() != CgBindingLayout.Type.SAMPLED_TEXTURE && owned(packed, slot)) continue;
            fallback(rhi, slot);
            defaulted++;
            lastDefaulted = "set " + set + " binding " + slot.binding() + " " + slot.type();
            CgTrace.add(CgChannels.GL, DEFAULTED, 1);
        }
    }

    /** Whether the snapshot holds {@code slot}, added to {@link #contents} if so. */
    private boolean fromTable(CgRHI rhi, CgFrameBindings table, int id, int packed, CgBindingLayout.Slot slot) {
        switch (slot.type()) {
            case SAMPLED_TEXTURE -> {
                for (int t = 0; t < table.textures(id); t++) {
                    if (table.textureUnit(id, t) != packed) continue;
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
                    CgBlockVersion version = table.blockVersion(id, k);
                    if (version != null && version.arena != null) continue;   // a storage block
                    if (table.blockBinding(id, k) != packed) continue;
                    CgGpuBuffer buffer = version != null ? version.buffer : table.uploadedBuffer();
                    long at = version != null ? version.offset : table.ringOffset(id, k);
                    if (buffer == null) return false;
                    // Read this frame: its range is not freed until the frame retires
                    if (version != null) version.lastUse = frame;
                    long bytes = Math.max(16, (table.blockFloatCount(id, k) * 4L + 15) & ~15L);
                    contents.buffer(slot.binding(), slot.type(), buffer, at, bytes);
                    return true;
                }
                return buffer(table, id, slot, packed);
            }
            case STORAGE_BUFFER -> {
                for (int k = 0; k < table.blocks(id); k++) {
                    CgBlockVersion version = table.blockVersion(id, k);
                    if (version == null || version.arena == null) continue;
                    if (table.blockBinding(id, k) != packed) continue;
                    CgMaterialArena arena = version.arena;
                    if (arena.buffer == null) return false;
                    long at = wholeArena ? 0 : (long) version.slot * arena.stride();
                    long bytes = wholeArena ? (long) arena.capacity * arena.stride() : arena.stride();
                    contents.buffer(slot.binding(), slot.type(), arena.buffer, at, bytes);
                    return true;
                }
                for (int s = 0; s < table.storages(id); s++) {
                    if (table.storagePoint(id, s) != packed) continue;
                    return whole(slot, table.storage(id, s).boundId());
                }
                return buffer(table, id, slot, packed);
            }
            default -> {
                return false;
            }
        }
    }

    /** An engine buffer the snapshot names whose slot is {@code packed}, at its latest range. */
    private boolean buffer(CgFrameBindings table, int id, CgBindingLayout.Slot slot, int packed) {
        for (int b = 0; b < table.buffers(id); b++) {
            CgShaderBuffer buffer = table.buffer(id, b);
            if (buffer.slot().packed() == packed) return buffer.describe(contents, slot.binding());
        }
        return false;
    }

    /** The engine buffer made for {@code packed} ({@link CgShaderBufferRegistry#ownerAt}), at its latest range. */
    private boolean owned(int packed, CgBindingLayout.Slot slot) {
        CgShaderBuffer owner = CgShaderBufferRegistry.get().ownerAt(packed);
        return owner != null && !owner.isDeleted() && owner.describe(contents, slot.binding());
    }

    /** {@code name}'s whole storage at {@code slot}. */
    private boolean whole(CgBindingLayout.Slot slot, int name) {
        if (name == 0) return false;
        CgGpuBuffer buffer = CgDeviceNames.buffer(name);
        long offset = 0L;
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

    private static final class Entry {
        final CgBindingSet set;
        final Key key;
        long asked;

        Entry(CgBindingSet set, long asked, Key key) {
            this.set = set;
            this.asked = asked;
            this.key = key;
        }
    }

    /**
     * A set's layout and contents by identity and number, each image's sampled layout too: what makes two sets the same
     * set. A dynamic slot's offset is the bind's, so not in it.
     */
    private static final class Key {
        CgBindingLayout layout;
        Object[] refs = new Object[16];
        long[] nums = new long[16];
        int count, hash;

        void of(CgBindingLayout layout, CgBindings c, int[] dynamic) {
            this.layout = layout;
            count = c.count();
            if (refs.length < count * 2) refs = new Object[count * 2];
            if (nums.length < count * 4) nums = new long[count * 4];
            int h = System.identityHashCode(layout);
            for (int i = 0; i < count; i++) {
                CgTextureView view = c.view(i);
                Object resource = view != null ? view.texture() : c.buffer(i);
                CgGpuSampler sampler = c.sampler(i);
                refs[i * 2] = resource;
                refs[i * 2 + 1] = sampler;
                nums[i * 4] = c.binding(i);
                nums[i * 4 + 1] = view != null ? (long) view.baseMip() << 32 | view.baseLayer() : dynamic[i] >= 0 ? 0 : c.offset(i);
                nums[i * 4 + 2] = view != null ? (long) view.mips() << 32 | view.layers() : c.size(i);
                nums[i * 4 + 3] = view != null ? view.texture().sampledLayout(view) : 0;
                h = h * 31 + System.identityHashCode(resource);
                h = h * 31 + System.identityHashCode(sampler);
                h = h * 31 + Long.hashCode(nums[i * 4 + 1]) + c.binding(i) + (int) nums[i * 4 + 3];
            }
            hash = h;
        }

        Key copy() {
            Key k = new Key();
            k.layout = layout;
            k.refs = Arrays.copyOf(refs, count * 2);
            k.nums = Arrays.copyOf(nums, count * 4);
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
            for (int i = 0; i < count * 4; i++) if (k.nums[i] != nums[i]) return false;
            return true;
        }
    }
}
