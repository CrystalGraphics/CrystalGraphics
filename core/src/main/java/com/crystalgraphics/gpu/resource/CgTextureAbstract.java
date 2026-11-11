package com.crystalgraphics.gpu.resource;

import com.crystalgraphics.platform.rhi.CgDeviceNames;
import com.crystalgraphics.render.draw.CgBoundResources;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;
import java.util.Set;
import java.util.EnumSet;
import com.crystalgraphics.platform.gl.format.GLFormats;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.asset.tree.CgBinaryAsset;
import com.crystalgraphics.gpu.CgDeferral;
import com.crystalgraphics.mc.CgReloadable;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.trace.CgTrace;
import javax.annotation.Nullable;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.variable.CgVar;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.crystalgraphics.api.texture.CgSampler;

/**
 * Shared base for all owned GL texture implementations.
 *
 * <p>Owns a single GL texture id (allocated by the subclass factory and
 * released via {@link #delete()}). Provides the full lifecycle boilerplate
 * that is identical across every texture type: id/spec storage, bind/unbind,
 * mipmap generation, and delete. Subclasses supply only:
 *
 * <ul>
 *   <li>{@link #getTarget()} — the GL texture target constant</li>
 *   <li>their own static factory methods</li>
 *   <li>any type-specific fields (e.g. {@code depth})</li>
 * </ul>
 *
 * <p>All its device work goes through {@link #gpu}, so a texture is made, filled and deleted from any thread: where
 * the device may not be driven, the work waits for the render thread. {@link #getId()} and {@link #bind} run it first
 * there.</p>
 *
 * <p>A texture from files is made {@link #loadOnFirstUse() unloaded}: its {@link #load()} decodes them at its first
 * use (its size, its id, a bind, a write) on the thread that uses it, so a server that names a texture never decodes
 * it. A subclass reads its own fields only after {@link #checkUsable()} or inside {@code load()}.</p>
 *
 * <p>Not part of the public API surface; lives in {@code gpu/resource/} alongside
 * the concrete implementations.</p>
 */
public abstract class CgTextureAbstract implements CgTexture, CgBinaryAsset, CgReloadable {

    // ── Upload-format GL constants (shared by all subclass factories) ─
    private static final int GL_RED = 0x1903;
    private static final int GL_RGB = 0x1907;
    protected static final int GL_RGBA = 0x1908;
    protected static final int GL_UNSIGNED_BYTE = 0x1401;

    // ── Instance state ──────────────────────────────────────────────
    /** GL texture object id: 0 until made, and again once deleted. Written only by {@link #gpu}'s work. */
    protected int textureId;
    /**
     * This texture's GL work, in order, each piece handing back the binding it moved. Spread over frames: {@code bind} and
     * {@code getId} land it first, so a burst of decoded textures lands within a frame's budget unless one is used.
     */
    protected final CgDeferral gpu = new CgDeferral().spreadOverFrames();
    /** Width of the texture at mip level 0, in pixels; 0 until a texture from files loads. */
    protected int width;
    /** Height of the texture at mip level 0, in pixels; 0 until a texture from files loads. */
    protected int height;
    /** Immutable spec: format, filter, wrap, and mipmap policy. */
    protected final Texels spec;
    /** How it samples where a binding names no sampler; null for the device's defaults. */
    @Nullable
    private CgSampler sampler;
    /** Whether the texture cache hands it to every caller of its path: its sampling is then its settings file's alone. */
    private boolean shared;
    /** False for a texture adopted from its owner: {@link #delete()} forgets it without deleting it. */
    private final boolean owned;
    /** Made on the installed device ({@code CgTexture2D.onDevice}): released through it. */
    @Nullable protected CgGpuTexture image;
    private boolean deleted;
    /** Set while a texture from files waits for its first use. */
    private volatile boolean unloaded;
    /** Whether its files would not load: it samples a stand-in. */
    protected boolean missing;

    /**
     * The RHI texture behind it on the installed device: its own where it was made on the device, else its name imported
     * through {@link CgDeviceNames} (RDG's external texture). Null while it has no storage. Render thread.
     */
    @Nullable
    public CgGpuTexture rhiTexture() {
        if (image != null) return image;
        if (textureId == 0) return null;
        return CgDeviceNames.texture(textureId);
    }

    /** {@link #rhiView}'s answer, for the device texture it was made over. */
    @Nullable
    private CgTextureView rhiView;

    @Override
    @Nullable
    public CgTextureView rhiView() {
        CgGpuTexture t = rhiTexture();
        if (t == null) return null;
        if (rhiView == null || rhiView.texture() != t) rhiView = CgTextureView.whole(t);
        return rhiView;
    }

    @Override
    public CgSampler sampler() {
        return sampler;
    }

    /**
     * Samples it by {@code sampler} where a binding names none: a texture you made. Any thread; the next draw reads it.
     *
     * <pre>{@code
     * CgTexture2D sheet = CgTextures.create("mymod.sheet", CgTextureDesc.of(64, 64, CgFormat.RGBA8_UNORM))
     *         .sampler(CgSampler.NEAREST_CLAMP);
     * }</pre>
     *
     * @throws IllegalStateException for a texture from a file, which every caller of its path shares: its settings file
     *                               says how it samples, and a binding may name another
     */
    protected void setSampler(CgSampler sampler) {
        if (shared) {
            throw new IllegalStateException(source() + " is shared by every caller of its path: say its sampling in its "
                    + "settings file, or name a sampler where you bind it");
        }
        this.sampler = java.util.Objects.requireNonNull(sampler, "sampler");
    }

    /** Marks it the cache's: shared, its sampling its settings file's. */
    final void share() {
        shared = true;
    }

    /** How the device sees it, for {@link #rhiTexture}'s import: a 2D texture unless a kind says otherwise. */
    protected CgGpuTexture.Desc rhiDesc() {
        return new CgGpuTexture.Desc(source(), CgGpuTexture.Kind.D2, GLFormats.of(spec.getGlInternalFormat()), width, height,
                1, mips(), 1, SAMPLED_ONLY);
    }

    /** Its mip count where nothing records one: the full chain under a mipmapped spec. */
    protected int mips() {
        return spec.mipmapped() ? CgTexture.fullChain(width, height) : 1;
    }

    protected static final Set<CgGpuTexture.Usage> SAMPLED_ONLY = EnumSet.of(CgGpuTexture.Usage.SAMPLED);

    protected CgTextureAbstract(int textureId, int width, int height, Texels spec) {
        this(textureId, width, height, spec, true);
    }

    protected CgTextureAbstract(int textureId, int width, int height, Texels spec, boolean owned) {
        this.textureId = textureId;
        this.width = width;
        this.height = height;
        this.spec = spec;
        this.sampler = spec == null ? null : spec.sampler();
        this.owned = owned;
    }

    // ── CgTexture — binding ─────────────────────────────────────────

    public void bind(int unit) {
        checkUsable();
        gpu.flush();
        CgBoundResources.texture(unit, this, -1);
    }

    // ── CgTexture — accessors ───────────────────────────────────────

    public int getId() {
        ensureLoaded();
        gpu.flush();
        return textureId;
    }

    @Override
    public int getWidth() {
        ensureLoaded();
        return width;
    }

    @Override
    public int getHeight() {
        ensureLoaded();
        return height;
    }

    /** The GL texture target it binds as: {@code GL_TEXTURE_2D}, {@code GL_TEXTURE_3D}, {@code GL_TEXTURE_2D_ARRAY}. */
    public abstract int getTarget();

    public boolean isDeleted() {return deleted;}

    public boolean isMissing() {
        ensureLoaded();
        return missing;
    }

    /** Decodes its files again and uploads them in place; nothing before its first use, or for a texture of none. */
    public final void reload() {
        if (deleted || unloaded) return;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "texture.load")) {
            load();
        }
    }

    /** Reloads it in place from its source images: what a cached texture does when one of them changes. */
    @Override
    public void refresh() {
        reload();
    }

    // ── Files ───────────────────────────────────────────────────────

    /**
     * Decodes its files and queues their upload, at its first use and at each reload. A file that will not load is
     * logged; at the first load it leaves a stand-in and sets {@link #missing}, at a reload it keeps what it had.
     * Nothing for a texture made without files.
     */
    protected void load() {}

    /** Marks it to {@link #load()} at its first use rather than now. */
    protected final void loadOnFirstUse() {
        unloaded = true;
    }

    private static final int LOADS = CgTrace.name("texture.loads");
    private static final Logger LOGGER = LogManager.getLogger(CgTextureAbstract.class);
    private static final CgVar.Bool LOG_FIRST_USE = CgVar.bool("crystalgraphics.texture.logFirstUse", false, CgVar.DEV,
            "Log each texture from files loaded at its first use: its source, the thread that paid for it and how long.");

    /** What it was made from, for a log line: its file where it has one. */
    protected String source() {
        return getClass().getSimpleName();
    }
    private static volatile long loads;

    /**
     * Textures from files loaded so far, at their first use or ahead of it: a play that should load none reads it
     * before and after. Also the trace counter {@code texture.loads}.
     */
    public static long loads() {
        return loads;
    }

    /** Loads it now if it waits for its first use. Any thread. */
    protected final void ensureLoaded() {
        if (!unloaded) return;
        synchronized (this) {
            if (!unloaded) return;
            unloaded = false;
            if (deleted) return;
            long started = LOG_FIRST_USE.get() ? System.nanoTime() : 0L;
            CgTrace.marker(CgChannels.GL, "texture.first-use", source());   // which, on which frame
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "texture.load")) {
                load();
            }
            if (started != 0L) {
                LOGGER.info("texture loaded at first use on {}: {} ({} ms)", Thread.currentThread().getName(), source(),
                        (System.nanoTime() - started) / 1_000_000L);
            }
            synchronized (CgTextureAbstract.class) {
                loads++;
            }
            CgTrace.add(CgChannels.GL, LOADS, 1);
        }
    }

    // ── CgTexture — lifecycle ───────────────────────────────────────

    /** Any thread. What was queued is dropped: a texture about to go needs none of it. */
    public void delete() {
        if (deleted) return;
        deleted = true;
        unloaded = false;
        gpu.clear();
        gpu.run(() -> {
            if (image != null) {
                CgDeviceNames.forget(image);
                CgRHI.get().release(image);
                image = null;
            }
            textureId = 0;
        });
    }

    // ── Device storage ──────────────────────────────────────────────

    /**
     * Render thread: storage on the device of {@code kind} and {@code format}, {@code width x height x depthOrLayers},
     * {@code chain} levels, made where it has none of that shape, its device name taken with it. Answers the storage
     * it replaced, which the caller releases (once it has copied out of it) through {@link CgDeviceTextures#release}; null
     * where nothing changed or it had none.
     */
    @Nullable
    protected final CgGpuTexture storeOnDevice(CgGpuTexture.Kind kind, CgFormat format, int width, int height,
                                               int depthOrLayers, int chain) {
        if (CgDeviceTextures.fits(image, format, width, height, depthOrLayers, chain)) return null;
        CgGpuTexture old = image;
        image = CgDeviceTextures.create(source(), kind, format, width, height, depthOrLayers, chain);
        textureId = CgDeviceNames.name(image);
        return old;
    }

    /** Render thread: its device storage, for a write into part of it. */
    protected final CgGpuTexture storage() {
        if (image == null) {
            throw new IllegalStateException(owned ? source() + " has no storage yet: upload it whole first"
                    : "A wrapped " + getClass().getSimpleName() + " belongs to its host");
        }
        return image;
    }

    // ── Internal ────────────────────────────────────────────────────

    /** Guards every bind and write: throws once it is deleted, and loads a texture from files first. */
    protected void checkUsable() {
        if (deleted) throw new IllegalStateException(getClass().getSimpleName() + " has been deleted");
        ensureLoaded();
    }

    /** Guards every write: an adopted texture's storage and parameters are its owner's. */
    protected void checkOwned() {
        if (!owned) throw new IllegalStateException("A wrapped " + getClass().getSimpleName() + " belongs to its host");
    }

    /** Whether the driver converts {@code (pixelFormat, pixelType)} into the spec's storage as it copies. */
    protected boolean converts(int pixelFormat, int pixelType) {
        return pixelFormat != spec.getGlBaseFormat() || pixelType != spec.getGlType();
    }

    protected static int pixelFormatForChannels(int channels) {
        switch (channels) {
            case 1: return GL_RED;
            case 3: return GL_RGB;
            default: return GL_RGBA;
        }
    }
}
