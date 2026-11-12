package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.gpu.resource.CgTexelConvert;
import com.crystalgraphics.platform.rhi.CgDeviceNames;
import com.crystalgraphics.platform.rhi.CgDeviceSection;
import com.crystalgraphics.platform.rhi.CgExternalTarget;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.command.CgAccess;
import com.crystalgraphics.platform.rhi.command.CgCommandList;
import com.crystalgraphics.platform.rhi.command.CgPassDesc;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.platform.rhi.resource.CgTextureRegion;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.CgBufferUtils;
import com.crystalgraphics.util.trace.CgChannels;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.gpu.resource.CgTextureDesc;

/**
 * A graph texture's storage on the device while it is resolved (Unreal's pooled render target behind an
 * {@code FRDGTexture}): the one texture the graph makes from a {@link CgTextureDesc}, at colour slot 0 or as its depth by
 * its format, or an {@link CgExternalTarget}'s colour and depth imported as they are.
 *
 * <pre>{@code
 * CgGraphStorage storage = texture.storage();          // render thread, inside an execution that resolves it
 * ByteBuffer rgba = storage.readColor(0, 0, w, h);     // a test's check: waits for the GPU, rows bottom first
 * }</pre>
 *
 * <ul>
 *   <li>Known to the executor's states by its first texture: two storages over the same images (a target imported
 *       twice) are one resource, and two that share none are two.</li>
 *   <li>An imported one owns nothing; a made one is the graph's to release.</li>
 *   <li>Render thread.</li>
 * </ul>
 */
public final class CgGraphStorage {

    private final String name;
    private final int width, height, depthSlices, levels, layers;
    /** Colour textures by slot, an attach-only one's too; null where a slot is unused. */
    private final CgGpuTexture[] colors;
    @Nullable
    private final CgGpuTexture depth;
    /** The level and layer an imported target's attachments are drawn at: 0 for storage of the graph's. */
    private final int colorLevel, colorLayer, depthLayer;
    @Nullable
    private final CgExternalTarget external;
    private final boolean owned;
    private final boolean volume;
    private boolean released;

    private final Sampled[] sampled;
    @Nullable
    private Sampled sampledDepth;
    /** What a callback drawing into it takes as its target ({@link #asTarget}), made at first ask. */
    @Nullable
    private CgExternalTarget asTarget;

    private CgGraphStorage(String name, int width, int height, int depthSlices, int levels,
                           int layers, CgGpuTexture[] colors, @Nullable CgGpuTexture depth, int colorLevel, int colorLayer,
                           int depthLayer, @Nullable CgExternalTarget external, boolean owned, boolean volume) {
        this.name = name;
        this.width = width;
        this.height = height;
        this.depthSlices = depthSlices;
        this.levels = levels;
        this.layers = layers;
        this.colors = colors;
        this.depth = depth;
        this.colorLevel = colorLevel;
        this.colorLayer = colorLayer;
        this.depthLayer = depthLayer;
        this.external = external;
        this.owned = owned;
        this.volume = volume;
        this.sampled = new Sampled[colors.length];
    }

    // ── Made by the graph ──────────────────────────────────────────────────────

    /** Makes the texture slot of a storage: the transient heaps' placement, or the device's own. */
    @FunctionalInterface
    interface Images {
        CgGpuTexture make(CgGpuTexture.Desc desc);
    }

    /** Storage for {@code desc}, its texture made by the device. */
    static CgGraphStorage make(String name, CgTextureDesc desc) {
        CgRHI rhi = CgRHI.get();
        return make(name, desc, rhi::createTexture);
    }

    /** Storage for {@code desc}, its texture ({@link #deviceDesc}) made by {@code images}. */
    static CgGraphStorage make(String name, CgTextureDesc desc, Images images) {
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "graph.storage.create")) {
            CgGpuTexture made = images.make(deviceDesc(name, desc));
            boolean depth = desc.isDepth();
            return new CgGraphStorage(name, desc.width(), desc.height(), desc.depth(), desc.levels(), desc.layers(),
                    depth ? new CgGpuTexture[0] : new CgGpuTexture[]{made}, depth ? made : null, 0, 0, 0, null, true,
                    desc.isVolume());
        }
    }

    /** The device texture {@link #make(String, CgTextureDesc, Images)} asks {@code images} for. */
    static CgGpuTexture.Desc deviceDesc(String name, CgTextureDesc desc) {
        CgFormat format = desc.format();
        if (desc.isMultisampled()) {
            // Drawn into and resolved from: nothing samples it
            return new CgGpuTexture.Desc(desc.isDepth() ? depthLabel(name) : colourLabel(name, 0), CgGpuTexture.Kind.D2,
                    format, desc.width(), desc.height(), 1, 1, desc.samples(),
                    EnumSet.of(CgGpuTexture.Usage.ATTACHMENT, CgGpuTexture.Usage.COPY_SRC));
        }
        if (desc.isDepth()) {
            return new CgGpuTexture.Desc(depthLabel(name), CgGpuTexture.Kind.D2, format, desc.width(), desc.height(), 1, 1, 1,
                    EnumSet.of(CgGpuTexture.Usage.ATTACHMENT, CgGpuTexture.Usage.SAMPLED, CgGpuTexture.Usage.COPY_SRC,
                            CgGpuTexture.Usage.COPY_DST));
        }
        CgRHI rhi = CgRHI.get();
        EnumSet<CgGpuTexture.Usage> usage = rhi.supports(format, CgGpuTexture.Usage.STORAGE)
                ? EnumSet.allOf(CgGpuTexture.Usage.class) : EnumSet.complementOf(EnumSet.of(CgGpuTexture.Usage.STORAGE));
        String label = colourLabel(name, 0);
        if (desc.isVolume()) {
            usage.remove(CgGpuTexture.Usage.ATTACHMENT);
            return new CgGpuTexture.Desc(label, CgGpuTexture.Kind.D3, format, desc.width(), desc.height(), desc.depth(),
                    1, 1, usage);
        }
        if (desc.isArray()) {
            return new CgGpuTexture.Desc(label, CgGpuTexture.Kind.D2_ARRAY, format, desc.width(), desc.height(),
                    desc.layers(), 1, 1, usage);
        }
        return new CgGpuTexture.Desc(label, CgGpuTexture.Kind.D2, format, desc.width(), desc.height(), 1,
                Math.min(desc.levels(), CgTexture.fullChain(desc.width(), desc.height())), 1, usage);
    }

    private static String colourLabel(String name, int slot) {
        return name + " colour " + slot;
    }

    private static String depthLabel(String name) {
        return name + " depth";
    }

    // ── Imported ───────────────────────────────────────────────────────────────

    /**
     * {@code target}'s images as storage the graph draws into and samples, owning nothing: its colour at slot 0 and its
     * depth.
     */
    static CgGraphStorage imported(CgExternalTarget target) {
        CgTextureView color = target.color(), depth = target.depth();
        CgGpuTexture.Desc of = (color != null ? color : depth).texture().desc();
        boolean volume = of.kind() == CgGpuTexture.Kind.D3;
        int layers = of.kind() == CgGpuTexture.Kind.D2_ARRAY ? of.depthOrLayers() : 1;
        // An attachment at a level above 0 draws that level alone: the storage is its size, one level
        boolean whole = color == null || color.baseMip() == 0 && color.baseLayer() == 0;
        return new CgGraphStorage(target.label(), target.width(), target.height(),
                volume ? of.depthOrLayers() : 1, whole ? of.mips() : 1, whole ? layers : 1,
                color == null ? new CgGpuTexture[0] : new CgGpuTexture[]{color.texture()}, depth == null ? null : depth.texture(),
                color == null ? 0 : color.baseMip(), color == null ? 0 : color.baseLayer(),
                depth == null ? 0 : depth.baseLayer(), target, false, volume);
    }

    // ── What it holds ──────────────────────────────────────────────────────────

    public String name() {
        return name;
    }

    /** The format of colour slot {@code slot}; null where it has none. */
    @Nullable
    public CgFormat colorFormat(int slot) {
        CgGpuTexture t = color(slot);
        return t == null ? null : t.desc().format();
    }

    /** Its depth's format; null where it has none. */
    @Nullable
    public CgFormat depthFormat() {
        return depth == null ? null : depth.desc().format();
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Level {@code level}'s width: {@code max(1, width >> level)}. */
    public int levelWidth(int level) {
        return Math.max(1, width >> level);
    }

    /** Level {@code level}'s height. */
    public int levelHeight(int level) {
        return Math.max(1, height >> level);
    }

    /** Its colour's mip levels. */
    public int levels() {
        return levels;
    }

    /** An array's layers; 1 for any other. */
    public int layers() {
        return layers;
    }

    /** A volume's slices; 1 for any other. */
    public int depthSlices() {
        return depthSlices;
    }

    public boolean isVolume() {
        return volume;
    }

    public boolean isArray() {
        return layers > 1;
    }

    /** The device texture at colour slot {@code slot}, an attach-only one's too; null where the slot is unused. */
    @Nullable
    public CgGpuTexture color(int slot) {
        return slot < colors.length ? colors[slot] : null;
    }

    /** Colour slots it may hold: one past the highest used. */
    public int colorSlots() {
        return colors.length;
    }

    /** Its depth texture; null for none. */
    @Nullable
    public CgGpuTexture depth() {
        return depth;
    }

    /** The level its colour is drawn at: an imported attachment's, else 0. */
    int colorLevel() {
        return colorLevel;
    }

    /** The layer its colour is drawn at: an imported attachment's, else 0. */
    int colorLayer() {
        return colorLayer;
    }

    /** The layer its depth is drawn at: an imported attachment's, else 0. */
    int depthLayer() {
        return depthLayer;
    }

    /** Whether the graph made it, and releases it. */
    boolean owned() {
        return owned;
    }

    /** The target it imports; null for storage of the graph's. */
    @Nullable
    CgExternalTarget external() {
        return external;
    }

    /** Whether a pass may draw beside its depth or colour: an imported target's say, always for the graph's own. */
    public boolean lendsAttachments() {
        return external == null || external.lendsAttachments();
    }

    /** Its states' key, its textures moved as one: by its first texture, render thread. */
    long key() {
        if (key == 0) key = CgResourceStates.storage(CgDeviceNames.name(colors.length > 0 && colors[0] != null ? colors[0] : depth));
        return key;
    }

    private long key;

    /** A texture's key, moved by subresource. */

    static long key(CgGpuTexture image) {
        return CgResourceStates.texture(CgDeviceNames.name(image));
    }

    /**
     * It as a target: what a callback drawing into it takes as {@code CgExecutor.into}'s, with {@code depth}'s depth
     * in place of its own where not null.
     */
    CgExternalTarget asTarget(@Nullable CgGraphStorage depth) {
        if (external != null && depth == null) return external;
        if (depth != null && depth != this) {
            if (besideOf != depth.depth) {
                beside = CgExternalTarget.of(name + " beside " + depth.name, color(0), depth.depth);
                besideOf = depth.depth;
            }
            return beside;
        }
        if (asTarget == null) asTarget = CgExternalTarget.of(name, color(0), this.depth);
        return asTarget;
    }

    /** Its colour beside another's depth, as a callback's target, kept while that depth is the same. */
    @Nullable
    private CgExternalTarget beside;
    @Nullable
    private CgGpuTexture besideOf;

    /** Every colour slot of a made storage cleared to 0, as it rests before and after: a history's first state. */
    void clearColor() {
        CgDeviceSection.begin();
        try {
            CgCommandList list = CgRHI.get().encoder();
            for (int layer = 0; layer < layers; layer++) {
                List<CgPassDesc.Color> attached = new ArrayList<>();
                for (CgGpuTexture t : colors) {
                    if (t == null) continue;
                    attached.add(new CgPassDesc.Color(CgTextureView.attachment(t, 0, layer), CgPassDesc.LoadOp.CLEAR,
                            0f, 0f, 0f, 0f, CgPassDesc.StoreOp.STORE));
                }
                if (attached.isEmpty()) return;
                for (CgPassDesc.Color c : attached) list.imageBarrier(c.view().texture(), CgAccess.RESTING, CgAccess.COLOR_WRITE);
                list.beginPass(new CgPassDesc("clear " + name, attached, null, width, height)).end();
                for (CgPassDesc.Color c : attached) list.imageBarrier(c.view().texture(), CgAccess.COLOR_WRITE, CgAccess.RESTING);
            }
        } finally {
            CgDeviceSection.end();
        }
    }

    /** Colour slot {@code slot}, sampled: every level and layer. Throws for a slot it lacks or only attaches. */
    public CgTexture sampled(int slot) {
        CgGpuTexture t = color(slot);
        if (t == null || !t.desc().usage().contains(CgGpuTexture.Usage.SAMPLED)) {
            throw new IllegalStateException(this + " has no sampled colour " + slot);
        }
        Sampled s = sampled[slot];
        if (s == null) sampled[slot] = s = new Sampled(t);
        return s;
    }

    /** Its depth, sampled; null where it has none or only attaches it. */
    @Nullable
    public CgTexture sampledDepth() {
        if (depth == null || !depth.desc().usage().contains(CgGpuTexture.Usage.SAMPLED)) return null;
        if (sampledDepth == null) sampledDepth = new Sampled(depth);
        return sampledDepth;
    }

    // ── Reading ────────────────────────────────────────────────────────────────

    /**
     * Its colour slot 0's pixels over {@code (x, y, w, h)}, read now, waiting for the GPU: RGBA8, rows bottom first.
     * For a tool or a test's check, never a frame's work.
     *
     * <pre>{@code
     * ByteBuffer rgba = storage.readColor(0, 0, storage.width(), storage.height());
     * }</pre>
     */
    public ByteBuffer readColor(int x, int y, int w, int h) {
        return readColor(x, y, w, h, CgBufferUtils.createByteBuffer(w * h * 4));
    }

    /** {@link #readColor(int, int, int, int)} into {@code into}, which holds at least {@code w * h * 4} bytes. */
    public ByteBuffer readColor(int x, int y, int w, int h, ByteBuffer into) {
        CgGpuTexture t = color(0);
        if (t == null) throw new IllegalStateException(this + " has no colour 0 to read");
        into.clear();
        return read(t, new CgTextureRegion(colorLevel, x, y, colorLayer, w, h, 1), false, into);
    }

    /** Colour slot 0's level {@code level}, read now as RGBA32F in native order: a float target's exact values. */
    public ByteBuffer readColorFloats(int level) {
        CgGpuTexture t = color(0);
        if (t == null) throw new IllegalStateException(this + " has no colour 0 to read");
        int w = levelWidth(level), h = levelHeight(level);
        return read(t, new CgTextureRegion(level, 0, 0, 0, w, h, 1), true, CgBufferUtils.createByteBuffer(w * h * 16));
    }

    private static ByteBuffer read(CgGpuTexture texture, CgTextureRegion region, boolean floats, ByteBuffer out) {
        CgFormat format = texture.desc().format();
        int texels = region.width() * region.height();
        ByteBuffer raw = CgBufferUtils.createByteBuffer((int) format.bytes(region.width(), region.height(), 1));
        CgDeviceSection.begin();
        try {
            CgRHI.get().encoder().readTexture(texture, region, raw);
        } finally {
            CgDeviceSection.end();
        }
        if (floats) CgTexelConvert.toRgba32f(raw, format, texels, out);
        else CgTexelConvert.toRgba8(raw, format, texels, out);
        return out;
    }

    // ── Lifetime ───────────────────────────────────────────────────────────────

    /** Releases a made storage's textures, at the device's pace; an imported one owns nothing. Later calls do nothing. */
    void release() {
        if (!owned || released) return;
        released = true;
        CgRHI rhi = CgRHI.get();
        for (int i = 0; i < colors.length; i++) {
            if (colors[i] == null) continue;
            CgDeviceNames.forget(colors[i]);
            rhi.release(colors[i]);
            colors[i] = null;
        }
        if (depth != null) {
            CgDeviceNames.forget(depth);
            rhi.release(depth);
        }
    }

    boolean released() {
        return released;
    }

    @Override
    public String toString() {
        return "CgGraphStorage(" + name + " " + width + "x" + height + ")";
    }

    /**
     * One of its textures, sampled whole: an integer format nearest, any other trilinear across whatever levels it
     * has; an imported one with the defaults, as a host's always has, its filtering its owner's to say.
     */
    private final class Sampled implements CgTexture {
        private final CgTextureView view;
        @Nullable
        private final CgSampler sampler;

        Sampled(CgGpuTexture texture) {
            this.view = CgTextureView.whole(texture);
            this.sampler = external != null ? null
                    : texture.desc().format().numeric() == CgFormat.Numeric.INT ? CgSampler.NEAREST_CLAMP : CgSampler.TRILINEAR_CLAMP;
        }

        @Override
        public CgTextureView rhiView() {
            return released ? null : view;
        }

        @Override
        @Nullable
        public CgSampler sampler() {
            return sampler;
        }

        @Override
        public int getWidth() {
            return width;
        }

        @Override
        public int getHeight() {
            return height;
        }

        @Override
        public int getLevels() {
            return view.mips();
        }
    }
}
