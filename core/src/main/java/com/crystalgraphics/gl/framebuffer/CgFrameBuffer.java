package com.crystalgraphics.gl.framebuffer;

import com.crystalgraphics.platform.rhi.CgHostImports;
import com.crystalgraphics.platform.gl.driver.GLC;
import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.gl.texture.CgTexture2DArray;
import com.crystalgraphics.gl.texture.CgTexture3D;
import com.crystalgraphics.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.format.GLFormats;
import com.crystalgraphics.platform.rhi.CgDeviceSection;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.command.CgAccess;
import com.crystalgraphics.platform.rhi.command.CgCommandList;
import com.crystalgraphics.platform.rhi.command.CgPassDesc;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.platform.rhi.resource.CgGpuSampler;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.platform.rhi.resource.CgTextureRegion;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.CgBufferUtils;
import com.crystalgraphics.util.trace.CgChannels;
import lombok.Getter;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A set of device textures a pass draws into: colour slots and a depth, as a {@link CgFrameBufferFormat} lays them out.
 * An owned one is the engine's: its textures, known by a name ({@link #getId()}) the engine gives it, never a GL
 * framebuffer. A wrapped one is a host's framebuffer ({@link #wrap}), its attachments imported.
 *
 * <pre>{@code
 * CgFrameBuffer target = CgFrameBuffer.createOwned("preview", w, h, format);
 * target.clearAll(0f, 0f, 0f, 1f);
 * try (CgExecutor.Into ignored = CgExecutor.into(target)) {   // what executions draw into, nothing bound
 *     stage.fire();
 * }
 * ByteBuffer rgba = target.readColor(0, 0, w, h);            // rows bottom first
 * target.delete();
 * }</pre>
 *
 * <ul>
 *   <li>{@link #create} caches by name ({@link CgFrameBufferRegistry}); {@link #createOwned} is the caller's to resize
 *       and delete.</li>
 *   <li>A multisampled or renderbuffer slot is a device texture a pass attaches and never samples.</li>
 *   <li>Render thread.</li>
 * </ul>
 */
public class CgFrameBuffer {

    /** The first name an owned framebuffer is given: above any a GL driver hands out for the host's. */
    private static final int FIRST_NAME = 0x40000000;
    private static int nextName = FIRST_NAME;
    /** Owned framebuffers by name, until deleted. */
    /** Owned framebuffers by {@code id - FIRST_NAME}; null once deleted. Looked up per pass, so never boxed. */
    private static final ArrayList<CgFrameBuffer> OWNED = new ArrayList<>();

    /** Human-readable instance name (e.g. "gbuffer_main", "shadow:directional"). */
    protected final String name;

    /** The format descriptor used to create it; null for a wrapped one. */
    @Nullable
    protected final CgFrameBufferFormat format;

    /** The name it is known by: the engine's for an owned one, the host's framebuffer for a wrapped one. */
    protected final int id;

    @Getter
    protected int width;

    @Getter
    protected int height;

    /** Whether the engine made it and may delete it. */
    @Getter
    protected final boolean owned;

    @Getter
    protected boolean deleted;

    /** Whether {@link CgFrameBufferRegistry#acquireScreenSized} made it; set by the registry. */
    @Getter
    boolean screenSized = false;

    /** Colour attachments by slot, only those populated. */
    protected final TreeMap<Integer, Attachment> colorAttachments = new TreeMap<>();

    /** Depth/stencil attachment, or {@code null} if the format has no depth. */
    @Nullable
    protected Attachment depthAttachment;

    /** Mip levels of each colour texture, at most its full chain at the current size. */
    @Getter
    private int colorLevels = 1;
    /** What makes its texture slots ({@link #createOnDevice}); null for the device's own textures. */
    @Nullable
    private Images images;

    /** Whether it is a volume's storage ({@link #createVolume}): a 3D colour texture nothing draws into. */
    @Getter
    private boolean volume;

    /** An array's layers ({@link #createArray}); 0 for any other framebuffer. */
    private int arrayLayers;

    private static Integer maxColorAttachments;

    static int getMaxColorAttachments() {
        if (maxColorAttachments == null) maxColorAttachments = CgCapabilities.detect().getMaxDrawBuffers();
        return maxColorAttachments;
    }

    /** An owned framebuffer, given its name now. */
    CgFrameBuffer(String name, CgFrameBufferFormat format, int width, int height) {
        this.name = name;
        this.format = format;
        this.width = width;
        this.height = height;
        this.owned = true;
        this.id = nextName++;
        OWNED.add(this);
    }

    /** A host's framebuffer {@code fbo}. */
    private CgFrameBuffer(String name, int fbo, int width, int height) {
        this.name = name;
        this.format = null;
        this.id = fbo;
        this.width = width;
        this.height = height;
        this.owned = false;
    }

    // ── Factories ──────────────────────────────────────────────────────────────

    /** Returns (or lazily creates) a framebuffer for the given name, dimensions, and format ({@link CgFrameBufferRegistry}). */
    public static CgFrameBuffer create(String name, int width, int height, CgFrameBufferFormat format) {
        return CgFrameBufferRegistry.get().getOrCreate(name, width, height, format);
    }

    /** A new owned framebuffer: the registry's maker. */
    static CgFrameBuffer createInternal(String name, int width, int height, CgFrameBufferFormat format) {
        return createInternal(name, width, height, format, 1, null);
    }

    private static CgFrameBuffer createInternal(String name, int width, int height, CgFrameBufferFormat format,
                                                int colorLevels, @Nullable Images images) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Framebuffer dimensions must be positive: " + width + "x" + height);
        }
        if (format == null) throw new IllegalArgumentException("Format must not be null");
        int maxSlots = getMaxColorAttachments();
        for (int slot : format.getActiveColorSlotIds()) {
            if (slot >= maxSlots) {
                throw new UnsupportedOperationException("CgFrameBuffer '" + name + "': color slot " + slot
                        + " exceeds GPU max draw buffers (" + maxSlots + ")");
            }
        }
        if (colorLevels < 1) throw new IllegalArgumentException("a colour texture of " + colorLevels + " levels");
        if (colorLevels > 1 && format.isMultisampled()) {
            throw new IllegalArgumentException("CgFrameBuffer '" + name + "': a multisampled colour slot has no mip levels");
        }
        CgFrameBuffer fbo = new CgFrameBuffer(name, format, width, height);
        fbo.colorLevels = colorLevels;
        fbo.images = images;
        fbo.makeAttachments(width, height, format);
        return fbo;
    }

    /**
     * A new framebuffer the caller owns outright, outside the registry's name cache: it resizes and deletes it.
     *
     * <pre>{@code
     * CgFrameBuffer layer = CgFrameBuffer.createOwned("ui.layer", w, h, format);
     * }</pre>
     */
    public static CgFrameBuffer createOwned(String name, int width, int height, CgFrameBufferFormat format) {
        return createInternal(name, width, height, format, 1, null);
    }

    /**
     * {@link #createOwned(String, int, int, CgFrameBufferFormat)} with {@code colorLevels} mip levels in every colour
     * texture; a pass or a kernel attached to a level fills it.
     *
     * <pre>{@code
     * CgFrameBuffer bloom = CgFrameBuffer.createOwned("bloom", w, h, format, CgTexture.fullChain(w, h));
     * }</pre>
     */
    public static CgFrameBuffer createOwned(String name, int width, int height, CgFrameBufferFormat format,
                                            int colorLevels) {
        return createInternal(name, width, height, format, colorLevels, null);
    }

    /** {@link #createOwned(String, int, int, CgFrameBufferFormat, int)}: the frame graph's name for it. */
    public static CgFrameBuffer createOnDevice(String name, int width, int height, CgFrameBufferFormat format,
                                               int colorLevels) {
        return createInternal(name, width, height, format, colorLevels, null);
    }

    /**
     * {@link #createOnDevice(String, int, int, CgFrameBufferFormat, int)} with each texture slot's image made by
     * {@code images}, asked in {@link #deviceDescs}' order: the frame graph's transients, placed in heaps.
     *
     * <pre>{@code
     * List<CgGpuTexture.Desc> descs = CgFrameBuffer.deviceDescs(name, w, h, format, 1);
     * CgFrameBuffer target = CgFrameBuffer.createOnDevice(name, w, h, format, 1, desc -> memory.place(heap, at(desc), desc));
     * }</pre>
     *
     * <ul>
     *   <li>A {@link #resize} asks {@code images} again, for the new size.</li>
     *   <li>Renderbuffer slots are made by the device itself ({@link #allOnDevice}).</li>
     * </ul>
     */
    public static CgFrameBuffer createOnDevice(String name, int width, int height, CgFrameBufferFormat format,
                                               int colorLevels, Images images) {
        return createInternal(name, width, height, format, colorLevels, images);
    }

    /** Makes a framebuffer's texture slot on the device, from what the slot needs. */
    @FunctionalInterface
    public interface Images {
        CgGpuTexture make(CgGpuTexture.Desc desc);
    }

    /**
     * The device textures {@link #createOnDevice} makes for {@code format} at this size, in the order it makes them:
     * each colour texture slot by slot, then a depth texture.
     */
    public static List<CgGpuTexture.Desc> deviceDescs(String name, int width, int height, CgFrameBufferFormat format,
                                                      int colorLevels) {
        List<CgGpuTexture.Desc> descs = new ArrayList<>();
        if (format.isMultisampled()) return descs;
        for (int slot : format.getActiveColorSlotIds()) {
            if (format.isColorRenderbuffer(slot)) continue;
            descs.add(CgTexture2D.deviceDesc(colourLabel(name, slot), width, height,
                    format.getColorSlot(slot).toTextureSpec(), Math.min(colorLevels, CgTexture.fullChain(width, height))));
        }
        if (format.hasDepth() && !format.isDepthRenderbuffer()) {
            descs.add(CgTexture2D.deviceDesc(depthLabel(name), width, height, format.getDepthType().toTextureSpec(), 1));
        }
        return descs;
    }

    /** Whether {@link #createOnDevice}'s {@code images} makes every attachment of {@code format}: no renderbuffer. */
    public static boolean allOnDevice(CgFrameBufferFormat format) {
        if (format.isMultisampled() || format.hasDepth() && format.isDepthRenderbuffer()) return false;
        for (int slot : format.getActiveColorSlotIds()) if (format.isColorRenderbuffer(slot)) return false;
        return true;
    }

    private static String colourLabel(String name, int slot) {
        return name + " colour " + slot;
    }

    private static String depthLabel(String name) {
        return name + " depth";
    }

    /**
     * Storage for a 3D texture: {@code format}'s slot 0 as a {@link CgTexture3D} of {@code depth} slices, which nothing
     * draws into: a kernel writes it as a {@code 3d} image and a draw samples it.
     *
     * <pre>{@code
     * CgFrameBuffer field = CgFrameBuffer.createVolume("wind", 32, 32, 32, rgba16f);
     * CgGraphTexture wind = CgGraphTexture.imported("wind", field);   // a kernel's 3d image, a material's sampler3D
     * }</pre>
     *
     * <ul>
     *   <li>A clear, a copy or a resize throws.</li>
     * </ul>
     */
    public static CgFrameBuffer createVolume(String name, int width, int height, int depth, CgFrameBufferFormat format) {
        if (width <= 0 || height <= 0 || depth <= 0) {
            throw new IllegalArgumentException("CgFrameBuffer '" + name + "': a volume of " + width + "x" + height + "x" + depth);
        }
        CgTextureType type = format.getColorSlot(0);
        if (format.colorSlotCount() != 1 || type == null || format.isColorRenderbuffer(0) || format.hasDepth()
                || format.isMultisampled()) {
            throw new IllegalArgumentException("CgFrameBuffer '" + name + "': a volume is one single-sampled colour "
                    + "texture at slot 0, not " + format);
        }
        return adopt(name, format, CgTexture3D.createEmpty(width, height, depth, type.toTextureSpec()));
    }

    /**
     * Storage around a volume made by the caller, with its own sampling and mip levels: a kernel writes each level as a
     * {@code 3d} image and a draw samples it as made. The framebuffer owns it from here.
     *
     * <pre>{@code
     * CgTexture3D made = CgTexture3D.createEmpty(64, 64, 64, repeatingRgba16f, CgTexture3D.fullChain(64, 64, 64));
     * CgFrameBuffer noise = CgFrameBuffer.createVolume("noise", made);
     * pass.dispatch(fill, 64, 64, 64).image("OUT", CgGraphTexture.imported("noise", noise), 0, -1);
     * noise.delete();   // deletes made
     * }</pre>
     */
    public static CgFrameBuffer createVolume(String name, CgTexture3D volume) {
        CgTextureType type = volume.spec().getType();
        return adopt(name, CgFrameBufferFormat.builder(name).color(0, type).build(), volume);
    }

    private static CgFrameBuffer adopt(String name, CgFrameBufferFormat format, CgTexture3D volume) {
        CgFrameBuffer fbo = new CgFrameBuffer(name, format, volume.getWidth(), volume.getHeight());
        fbo.volume = true;
        fbo.colorLevels = volume.getLevels();
        Attachment a = new Attachment(0, format.getColorSlot(0), false);
        a.texture = volume;
        fbo.colorAttachments.put(0, a);
        return fbo;
    }

    /**
     * Storage for a 2D array: {@code format}'s slot 0 as a {@link CgTexture2DArray} of {@code layers} layers; a pass
     * attached to a layer draws into it.
     *
     * <pre>{@code
     * CgFrameBuffer field = CgFrameBuffer.createArray("field", w, h, 5, rgba16f);
     * CgGraphTexture fields = CgGraphTexture.imported("field", field); // a material's sampler2DArray
     * }</pre>
     *
     * <ul>
     *   <li>A clear addresses every layer; a resize keeps the layer count.</li>
     *   <li>One level, one colour attachment, no depth.</li>
     * </ul>
     */
    public static CgFrameBuffer createArray(String name, int width, int height, int layers, CgFrameBufferFormat format) {
        if (width <= 0 || height <= 0 || layers <= 0) {
            throw new IllegalArgumentException("CgFrameBuffer '" + name + "': an array of " + width + "x" + height + ", " + layers + " layers");
        }
        CgTextureType type = format.getColorSlot(0);
        if (format.colorSlotCount() != 1 || type == null || format.isColorRenderbuffer(0) || format.hasDepth()
                || format.isMultisampled()) {
            throw new IllegalArgumentException("CgFrameBuffer '" + name + "': an array is one single-sampled colour "
                    + "texture at slot 0, not " + format);
        }
        CgFrameBuffer fbo = new CgFrameBuffer(name, format, width, height);
        fbo.arrayLayers = layers;
        fbo.makeAttachments(width, height, format);
        return fbo;
    }

    /** Whether it is an array's storage ({@link #createArray}). */
    public boolean isArray() {
        return arrayLayers > 0;
    }

    /** An array's layers; 1 for any other framebuffer. */
    public int getLayers() {
        return Math.max(arrayLayers, 1);
    }

    /** A volume's slices; 1 for any other framebuffer. */
    public int getDepth() {
        return volume && getColorTexture(0) instanceof CgTexture3D texture ? texture.getDepth() : 1;
    }

    private void requireTarget(String what) {
        if (volume) throw new IllegalStateException("FBO '" + name + "' is a volume, with nothing to " + what);
    }

    /**
     * Returns (or lazily creates) a screen-sized framebuffer managed by {@link CgFrameBufferRegistry}, resized in place
     * at every window resize ({@link CgGraphicsLifecycle#onResize}).
     */
    public static CgFrameBuffer createScreenSized(String name, CgFrameBufferFormat format) {
        return CgFrameBufferRegistry.get().acquireScreenSized(name, format);
    }

    /**
     * A host's framebuffer {@code fbo}, its attachments imported: framebuffer 0 is the window's. Owns nothing;
     * {@link #delete()} does nothing.
     *
     * <pre>{@code
     * CgFrameBuffer window = CgFrameBuffer.wrap("window", 0, w, h);
     * offscreen.copyColorTo(0);   // or window.readColor(0, 0, w, h)
     * }</pre>
     */
    public static CgFrameBuffer wrap(String name, int fbo, int width, int height) {
        return new CgFrameBuffer(name, fbo, width, height);
    }

    /** The owned framebuffer known as {@code id}; null for a host's name or a deleted one. */
    @Nullable
    public static CgFrameBuffer owned(int id) {
        int at = id - FIRST_NAME;
        return id >= FIRST_NAME && at < OWNED.size() ? OWNED.get(at) : null;
    }

    /**
     * The colour attachment 0 (or the depth) of the framebuffer named {@code fbo} as a device texture view: an owned
     * one's own, else the host's imported at {@code width} x {@code height}. Null where it has none.
     */
    @Nullable
    public static CgTextureView attachment(int fbo, boolean depth, int width, int height) {
        CgFrameBuffer ours = owned(fbo);
        if (ours != null) {
            CgGpuTexture t = depth ? ours.rhiDepth() : ours.rhiColor(0);
            return t == null ? null : CgTextureView.attachment(t, 0, 0);
        }
        return CgHostImports.get().attachment(fbo, depth, width, height);
    }

    /**
     * The depth type a framebuffer's depth attachment has: what a copy of it holds, since a depth blit refuses formats
     * that differ.
     *
     * <pre>{@code
     * CgTextureType type = CgFrameBuffer.depthTypeOf(mainTarget, w, h);   // DEPTH32F on 26.2, DEPTH24 before
     * }</pre>
     *
     * @return {@code null} when the framebuffer has no depth attachment
     */
    @Nullable
    public static CgTextureType depthTypeOf(int fbo, int width, int height) {
        CgTextureView depth = attachment(fbo, true, width, height);
        if (depth == null) return null;
        switch (depth.texture().desc().format()) {
            case DEPTH16_UNORM: return CgTextureType.DEPTH16;
            case DEPTH24_PLUS: return CgTextureType.DEPTH24;
            case DEPTH32_FLOAT: return CgTextureType.DEPTH32F;
            case DEPTH24_PLUS_STENCIL8: return CgTextureType.DEPTH24_STENCIL8;
            case DEPTH32_FLOAT_STENCIL8: return CgTextureType.DEPTH32F_STENCIL8;
            default: return null;
        }
    }

    // ── Attachments ────────────────────────────────────────────────────────────

    private void makeAttachments(int w, int h, CgFrameBufferFormat fmt) {
        try (CgTrace.Zone zone = CgTrace.zone(CgChannels.GL, "framebuffer.create")) {
            if (arrayLayers > 0) {
                CgTextureType type = fmt.getColorSlot(0);
                Attachment a = new Attachment(0, type, false);
                a.texture = CgTexture2DArray.allocateEmpty(w, h, arrayLayers, type.toTextureSpec());
                colorAttachments.put(0, a);
                return;
            }
            for (int slot : fmt.getActiveColorSlotIds()) {
                CgTextureType type = fmt.getColorSlot(slot);
                boolean attachOnly = fmt.isColorRenderbuffer(slot);
                if (!attachOnly && fmt.isMultisampled()) {
                    // A multisampled slot sampled as a texture needs a sampler2DMS: refused rather than built single-sampled.
                    throw new IllegalArgumentException("CgFrameBufferFormat '" + fmt + "': colour slot " + slot
                            + " is multisampled but declared as a sampleable texture. Use colorRenderbuffer(...).");
                }
                Attachment a = new Attachment(slot, type, attachOnly);
                if (attachOnly) a.image = attachOnly(colourLabel(name, slot), type, w, h, fmt.getSamples());
                else a.texture = texture(colourLabel(name, slot), type, w, h, Math.min(colorLevels, CgTexture.fullChain(w, h)));
                colorAttachments.put(slot, a);
            }
            if (fmt.hasDepth()) {
                CgTextureType type = fmt.getDepthType();
                boolean attachOnly = fmt.isDepthRenderbuffer();
                if (!attachOnly && fmt.isMultisampled()) {
                    throw new IllegalArgumentException("CgFrameBufferFormat '" + fmt + "': depth is multisampled but "
                            + "declared as a sampleable texture. Use depthRenderbuffer(...).");
                }
                Attachment a = new Attachment(-1, type, attachOnly);
                if (attachOnly) a.image = attachOnly(depthLabel(name), type, w, h, fmt.getSamples());
                else a.texture = texture(depthLabel(name), type, w, h, 1);
                depthAttachment = a;
            }
        } catch (RuntimeException e) {
            freeAttachments();
            throw e;
        }
    }

    private CgTexture2D texture(String label, CgTextureType type, int w, int h, int levels) {
        return images != null ? CgTexture2D.onDevice(images.make(CgTexture2D.deviceDesc(label, w, h, type.toTextureSpec(),
                levels)), type.toTextureSpec()) : CgTexture2D.createEmpty(w, h, type.toTextureSpec(), levels);
    }

    /** A slot a pass attaches and never samples: what a renderbuffer was, multisampled where the format is. */
    private static CgGpuTexture attachOnly(String label, CgTextureType type, int w, int h, int samples) {
        return CgRHI.get().createTexture(new CgGpuTexture.Desc(label, CgGpuTexture.Kind.D2,
                GLFormats.of(type.glInternalFormat), w, h, 1, 1, Math.max(1, samples),
                EnumSet.of(CgGpuTexture.Usage.ATTACHMENT, CgGpuTexture.Usage.COPY_SRC)));
    }

    /** The name it is known by: an owned one's, given at creation; a wrapped one's host framebuffer. */
    public int getId() {
        return id;
    }

    /** The format descriptor used to create it (null for a wrapped one). */
    @Nullable
    public CgFrameBufferFormat getFormat() {
        return format;
    }

    /** Level {@code level}'s width: {@code max(1, width >> level)}. */
    public int levelWidth(int level) {
        return Math.max(1, width >> level);
    }

    /** Level {@code level}'s height: {@code max(1, height >> level)}. */
    public int levelHeight(int level) {
        return Math.max(1, height >> level);
    }

    /** The texture at colour slot {@code slot}; null where the slot is unused or attach-only. */
    @Nullable
    public CgTexture getColorTexture(int slot) {
        Attachment a = colorAttachments.get(slot);
        return a != null ? a.texture : null;
    }

    /** The depth texture; null with no depth or an attach-only one. */
    @Nullable
    public CgTexture getDepthTexture() {
        return depthAttachment != null ? depthAttachment.texture : null;
    }

    /**
     * The device texture at colour slot {@code slot}, an attach-only one's too: what a render pass draws into. A
     * wrapped framebuffer's slot 0 is the host's, imported. Null where the slot is unused.
     *
     * <pre>{@code
     * CgTextureView view = CgTextureView.attachment(fbo.rhiColor(0), 0, 0);
     * }</pre>
     */
    @Nullable
    public CgGpuTexture rhiColor(int slot) {
        if (!owned) return slot == 0 ? imported(false) : null;
        Attachment a = colorAttachments.get(slot);
        return a != null ? a.rhiTexture() : null;
    }

    /** As {@link #rhiColor}, for the depth/stencil attachment. */
    @Nullable
    public CgGpuTexture rhiDepth() {
        if (!owned) return imported(true);
        return depthAttachment != null ? depthAttachment.rhiTexture() : null;
    }

    /** A wrapped framebuffer's attachment: the host's imported, or an owned one's where it names one of ours. */
    @Nullable
    private CgGpuTexture imported(boolean depth) {
        CgTextureView view = attachment(id, depth, width, height);
        return view == null ? null : view.texture();
    }

    // ── Reading and copying ─────────────────────────────────────────────────────

    /**
     * Its colour slot 0's pixels over {@code (x, y, w, h)}, read now, waiting for the GPU: RGBA8, rows bottom first.
     * For a tool or a test's check, never a frame's work. Render thread.
     *
     * <pre>{@code
     * ByteBuffer rgba = target.readColor(0, 0, target.getWidth(), target.getHeight());
     * int red = rgba.get((y * target.getWidth() + x) * 4) & 0xFF;   // y counted from the bottom
     * }</pre>
     */
    public ByteBuffer readColor(int x, int y, int w, int h) {
        return readColor(x, y, w, h, CgBufferUtils.createByteBuffer(w * h * 4));
    }

    /** {@link #readColor(int, int, int, int)} into {@code into}, which holds at least {@code w * h * 4} bytes. */
    public ByteBuffer readColor(int x, int y, int w, int h, ByteBuffer into) {
        CgGpuTexture color = rhiColor(0);
        if (color == null) throw new IllegalStateException("FBO '" + name + "' has no colour attachment 0 to read");
        into.clear();
        return read(color, new CgTextureRegion(0, x, y, 0, w, h, 1), false, into);
    }

    /**
     * {@code texture}'s level 0 (an array's layer {@code layer}), read now and waiting for the GPU, as RGBA8: rows from
     * the texture's row 0, which is how it was uploaded. A single-channel texture answers its red in R. Render thread.
     *
     * <pre>{@code
     * ByteBuffer page = CgFrameBuffer.readTexture(atlas, 2);   // layer 2 of an array
     * }</pre>
     */
    public static ByteBuffer readTexture(CgTexture texture, int layer) {
        return readTexture(texture, layer, 0, false);
    }

    /**
     * {@code texture}'s mip {@code level}, read now and waiting for the GPU, as RGBA32F in native order: a check of a
     * float target's exact values. Rows as {@link #readTexture}'s. Render thread.
     *
     * <pre>{@code
     * FloatBuffer texels = CgFrameBuffer.readTextureFloats(chain.getColorTexture(0), 2).asFloatBuffer();
     * }</pre>
     */
    public static ByteBuffer readTextureFloats(CgTexture texture, int level) {
        return readTexture(texture, 0, level, true);
    }

    private static ByteBuffer readTexture(CgTexture texture, int layer, int level, boolean floats) {
        CgTextureView view = texture.rhiView();
        if (view == null) throw new IllegalStateException(texture + " has no device texture to read");
        int w = Math.max(1, texture.getWidth() >> level), h = Math.max(1, texture.getHeight() >> level);
        return read(view.texture(), new CgTextureRegion(level, 0, 0, layer, w, h, 1), floats,
                CgBufferUtils.createByteBuffer(w * h * (floats ? 16 : 4)));
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

    /**
     * Copies its colour slot 0 into framebuffer {@code fbo} at the same size, unfiltered: a host's main target into the
     * window ({@code 0}) at the frame's end.
     */
    public void copyColorTo(int fbo) {
        copyTo(fbo, false);
    }

    /** {@link #copyColorTo} with its depth too: both framebuffers' depth formats must match. */
    public void copyColorAndDepthTo(int fbo) {
        copyTo(fbo, true);
    }

    private void copyTo(int fbo, boolean depth) {
        requireTarget("copy from");
        CgTextureView to = attachment(fbo, false, width, height);
        CgTextureView toDepth = depth ? attachment(fbo, true, width, height) : null;
        if (to == null || depth && toDepth == null) {
            throw new IllegalStateException("framebuffer " + fbo + " has nothing to copy FBO '" + name + "' into");
        }
        CgDeviceSection.begin();
        try {
            CgRHI.get().encoder().blit(CgTextureView.attachment(rhiColor(0), 0, 0), 0, 0, width, height,
                    to, 0, 0, width, height, CgGpuSampler.Filter.NEAREST);
            if (depth) {
                CgRHI.get().encoder().blit(CgTextureView.attachment(rhiDepth(), 0, 0), 0, 0, width, height,
                        toDepth, 0, 0, width, height, CgGpuSampler.Filter.NEAREST);
            }
        } finally {
            CgDeviceSection.end();
        }
    }

    // ── Clear ──────────────────────────────────────────────────────────────────

    /**
     * Clears what {@code mask} names ({@code GL_COLOR_BUFFER_BIT}, {@code GL_DEPTH_BUFFER_BIT},
     * {@code GL_STENCIL_BUFFER_BIT}) to the values given, whole: every colour slot, an array's every layer. A pass on the
     * device of its own. Depth is GL's convention, far at 1, mirrored for a host whose depth runs far to near.
     */
    public void clear(int mask, float r, float g, float b, float a, double depthValue, int stencilValue) {
        requireTarget("clear");
        boolean color = (mask & GLC.GL_COLOR_BUFFER_BIT) != 0, depth = (mask & GLC.GL_DEPTH_BUFFER_BIT) != 0,
                stencil = (mask & GLC.GL_STENCIL_BUFFER_BIT) != 0;
        float clearDepth = (float) (CgGraphicsLifecycle.hostDepthReversed() ? 1.0 - depthValue : depthValue);
        CgDeviceSection.begin();
        try {
            for (int layer = 0; layer < getLayers(); layer++) {
                List<CgPassDesc.Color> colors = new ArrayList<>();
                if (color) {
                    for (int slot : owned ? colorAttachments.keySet() : Collections.singleton(0)) {
                        CgGpuTexture t = rhiColor(slot);
                        if (t != null) colors.add(new CgPassDesc.Color(CgTextureView.attachment(t, 0, layer),
                                CgPassDesc.LoadOp.CLEAR, r, g, b, a, CgPassDesc.StoreOp.STORE));
                    }
                }
                CgGpuTexture d = depth || stencil ? rhiDepth() : null;
                CgPassDesc.Depth depthDesc = d == null ? null : new CgPassDesc.Depth(CgTextureView.attachment(d, 0, 0),
                        depth ? CgPassDesc.LoadOp.CLEAR : CgPassDesc.LoadOp.LOAD, clearDepth,
                        stencil ? CgPassDesc.LoadOp.CLEAR : CgPassDesc.LoadOp.LOAD, stencilValue,
                        CgPassDesc.StoreOp.STORE, false);
                if (colors.isEmpty() && depthDesc == null) return;
                // From rest and back, as every use outside a graph leaves a texture (CgAccess.RESTING)
                CgCommandList list = CgRHI.get().encoder();
                for (CgPassDesc.Color c : colors) list.imageBarrier(c.view().texture(), CgAccess.RESTING, CgAccess.COLOR_WRITE);
                if (d != null) list.imageBarrier(d, CgAccess.RESTING, CgAccess.DEPTH_WRITE);
                list.beginPass(new CgPassDesc("clear " + name, colors, depthDesc, width, height)).end();
                for (CgPassDesc.Color c : colors) list.imageBarrier(c.view().texture(), CgAccess.COLOR_WRITE, CgAccess.RESTING);
                if (d != null) list.imageBarrier(d, CgAccess.DEPTH_WRITE, CgAccess.RESTING);
            }
        } finally {
            CgDeviceSection.end();
        }
    }

    /** Clears only the colour buffer to the given RGBA colour. */
    public void clearColor(float r, float g, float b, float a) {
        clear(GLC.GL_COLOR_BUFFER_BIT, r, g, b, a, 1.0, 0);
    }

    /** Clears only the depth buffer to 1.0 (far plane). */
    public void clearDepth() {
        clear(GLC.GL_DEPTH_BUFFER_BIT, 0, 0, 0, 0, 1.0, 0);
    }

    /** Clears only the depth buffer to {@code value}. */
    public void clearDepth(double value) {
        clear(GLC.GL_DEPTH_BUFFER_BIT, 0, 0, 0, 0, value, 0);
    }

    /** Clears only the stencil buffer to 0. */
    public void clearStencil() {
        clear(GLC.GL_STENCIL_BUFFER_BIT, 0, 0, 0, 0, 1.0, 0);
    }

    /** Clears only the stencil buffer to {@code value}. */
    public void clearStencil(int value) {
        clear(GLC.GL_STENCIL_BUFFER_BIT, 0, 0, 0, 0, 1.0, value);
    }

    /** Clears depth and stencil buffers. */
    public void clearDepthStencil() {
        clear(GLC.GL_DEPTH_BUFFER_BIT | GLC.GL_STENCIL_BUFFER_BIT, 0, 0, 0, 0, 1.0, 0);
    }

    /** Clears depth and stencil buffers. */
    public void clearDepthStencil(double depthValue, int stencilValue) {
        clear(GLC.GL_DEPTH_BUFFER_BIT | GLC.GL_STENCIL_BUFFER_BIT, 0, 0, 0, 0, depthValue, stencilValue);
    }

    /** Clears colour, depth, and stencil in one call. Depth resets to 1.0, stencil to 0. */
    public void clearAll(float r, float g, float b, float a) {
        clear(GLC.GL_COLOR_BUFFER_BIT | GLC.GL_DEPTH_BUFFER_BIT | GLC.GL_STENCIL_BUFFER_BIT, r, g, b, a, 1.0, 0);
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    /** Remakes its attachments at the new size; the object and its name stay. */
    public void resize(int newWidth, int newHeight) {
        requireTarget("resize");
        if (!owned) {
            width = newWidth;
            height = newHeight;
            return;
        }
        freeAttachments();
        this.width = newWidth;
        this.height = newHeight;
        makeAttachments(newWidth, newHeight, format);
    }

    /** Releases its textures; a wrapped one owns nothing. Later calls do nothing. */
    public void delete() {
        if (!owned || deleted) return;
        freeAttachments();
        OWNED.set(id - FIRST_NAME, null);
        deleted = true;
    }

    private void freeAttachments() {
        for (Attachment a : colorAttachments.values()) a.delete();
        colorAttachments.clear();
        if (depthAttachment != null) {
            depthAttachment.delete();
            depthAttachment = null;
        }
    }

    /** One attachment slot: a texture a pass may sample, or an attach-only device image. */
    public static final class Attachment {

        /** Colour index (0..N-1), or -1 for the depth attachment. */
        @Getter
        private final int slot;

        @Getter
        private final CgTextureType type;

        /** Whether it is attach-only, never sampled: what a renderbuffer was. */
        @Getter
        private final boolean renderbuffer;

        @Nullable
        private CgTexture texture;
        @Nullable
        private CgGpuTexture image;

        Attachment(int slot, CgTextureType type, boolean renderbuffer) {
            this.slot = slot;
            this.type = type;
            this.renderbuffer = renderbuffer;
        }

        /** The texture, or {@code null} for an attach-only slot. */
        @Nullable
        public CgTexture getTexture() {
            return texture;
        }

        @Nullable
        CgGpuTexture rhiTexture() {
            if (image != null) return image;
            CgTextureView view = texture == null ? null : texture.rhiView();
            return view == null ? null : view.texture();
        }

        void delete() {
            if (texture != null) texture.delete();
            texture = null;
            if (image != null) CgRHI.get().release(image);
            image = null;
        }
    }
}
