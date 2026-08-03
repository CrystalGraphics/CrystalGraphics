package com.crystalgraphics.api.framebuffer;

import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.platform.gl.CgCapabilities;
import lombok.Getter;

import java.util.Arrays;

/**
 * Immutable descriptor for a framebuffer's attachment layout.
 *
 * <p>Describes which color slots are populated (sparse — unused slots are {@code null}),
 * whether each slot uses a sampleable texture or a non-sampleable renderbuffer, and the
 * optional depth/stencil attachment.  Dimensions are NOT part of this descriptor —
 * they belong to {@code CgFrameBuffer} instances.</p>
 *
 * <p>This is pure data — no GL calls, no {@code CgCapabilities.detect()}.
 * Hardware slot-count validation happens in {@code CgFrameBuffer.create()}.</p>
 *
 * <h3>Pre-built constants</h3>
 * <ul>
 *   <li>{@link #RGBA8_WITH_DEPTH} — standard postprocessing: RGBA8 color + DEPTH24_STENCIL8</li>
 *   <li>{@link #HDR_WITH_DEPTH} — HDR postprocessing: RGBA16F color + DEPTH24_STENCIL8</li>
 *   <li>{@link #SHADOW_DEPTH} — shadow map rendering: depth texture only</li>
 *   <li>{@link #PING_PONG_HDR} — ping-pong blur: single RGBA16F color, no depth</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // Define once as a static constant
 * static final CgFrameBufferFormat GBUFFER = CgFrameBufferFormat.builder("gbuffer")
 *     .color(0, CgTextureType.RGBA8)              // albedo — sampleable texture
 *     .color(1, CgTextureType.RGB10_A2)           // normals — sampleable texture
 *     .colorRenderbuffer(2, CgTextureType.RGBA8)  // emission — renderbuffer (not sampled)
 *     .depth(CgTextureType.DEPTH24_STENCIL8)      // depth — sampleable (needed for SSAO)
 *     .build();
 *
 * CgFrameBuffer fbo = CgFrameBuffer.create("gbuffer_main", 1920, 1080, GBUFFER);
 * }</pre>
 */
public final class CgFrameBufferFormat {

    // ── Maximum color slot index safe for build-time validation ────────────────
    // Actual GPU limit is validated in CgFrameBuffer.create() where a GL context exists.
    private static final int MAX_COLOR_SLOTS = 8;

    // ── Pre-built constants ────────────────────────────────────────────────────

    /**
     * RGBA8 color texture + DEPTH24_STENCIL8 depth texture. Standard postprocessing.
     * Both attachments are sampleable textures.
     */
    public static final CgFrameBufferFormat RGBA8_WITH_DEPTH =
        CgFrameBufferFormat.builder("rgba8_with_depth")
            .color(0, CgTextureType.RGBA8)
            .depth(CgTextureType.DEPTH24_STENCIL8)
            .build();

    /**
     * RGBA16F color texture + DEPTH24_STENCIL8 depth texture. HDR postprocessing.
     * Both attachments are sampleable textures.
     */
    public static final CgFrameBufferFormat HDR_WITH_DEPTH =
        CgFrameBufferFormat.builder("hdr_with_depth")
            .color(0, CgTextureType.RGBA16F)
            .depth(CgTextureType.DEPTH24_STENCIL8)
            .build();

    /**
     * Depth texture only — no color attachment. For depth testing or depth snapshot blitting.
     * The depth attachment is a sampleable texture suitable for PCF lookup.
     */
    public static final CgFrameBufferFormat DEPTH =
        CgFrameBufferFormat.builder("depth")
            .depth(CgTextureType.DEPTH24)
            .build();
    /**
     * Depth texture only — no color attachment. Shadow map rendering.
     * The depth attachment is a sampleable texture suitable for PCF lookup.
     */
    public static final CgFrameBufferFormat SHADOW_DEPTH =
        CgFrameBufferFormat.builder("shadow_depth")
            .depth(CgTextureType.DEPTH24)
            .build();

    /**
     * Single RGBA16F color texture, no depth. Ping-pong blur / post effects.
     */
    public static final CgFrameBufferFormat PING_PONG_HDR =
        CgFrameBufferFormat.builder("ping_pong_hdr")
            .color(0, CgTextureType.RGBA16F)
            .build();

    // ── Instance fields ────────────────────────────────────────────────────────

    /** Human-readable name for this format (e.g. "gbuffer", "shadow_depth"). 
     * -- GETTER --
     * Returns the format name (e.g. "gbuffer"). 
     */
    @Getter
    private final String name;

    /**
     * Sparse color slot array (length = MAX_COLOR_SLOTS).
     * {@code null} entries indicate unused slots.
     */
    private final CgTextureType[] colorSlots;

    /**
     * Parallel to {@code colorSlots}: {@code true} = renderbuffer (not sampleable),
     * {@code false} = texture (sampleable, default).
     */
    private final boolean[] colorRenderbuffer;

    /** Depth/stencil format, or {@code null} if there is no depth attachment. 
     * -- GETTER --
     *  Returns the depth/stencil format, or 
     *  if there is no depth attachment.
     */
    @Getter
    private final CgTextureType depthType;

    /** {@code true} = depth is a renderbuffer; {@code false} = depth is a texture (default). 
     * -- GETTER --
     *  Returns 
     *  if the depth attachment is a renderbuffer (non-sampleable).
     *  Returns 
     *  if it is a texture (sampleable, the default).
     */
    @Getter
    private final boolean depthRenderbuffer;

    /**
     * Samples per pixel. 1 means no multisampling.
     *
     * <p>Part of the format, and therefore part of {@link #equals} — a 4× target and a 1× target of
     * otherwise identical shape are <b>not interchangeable</b>, and {@code CgFrameBufferRegistry} keys
     * its cache on the format. Leaving it out would let a registry lookup hand back a single-sampled FBO
     * to a caller that asked for MSAA, which fails as "antialiasing silently does nothing".</p>
     */
    @Getter
    private final int samples;
    
    @Getter
    private final boolean maxSamples;
    
    /** Pre-computed sorted array of slot indices that have a non-null color type. 
     * -- GETTER --
     *  Returns a pre-computed sorted array of active color slot indices.
     *  The returned array must not be modified.
     */
    @Getter
    private final int[] activeColorSlotIds;

    // ── Constructor (private — use builder) ───────────────────────────────────

    private CgFrameBufferFormat(String name,
                                 CgTextureType[] colorSlots,
                                 boolean[] colorRenderbuffer,
                                 CgTextureType depthType,
                                 boolean depthRenderbuffer,
                                 int samples, boolean maxSamples) {
        this.name              = name;
        this.colorSlots        = colorSlots.clone();
        this.colorRenderbuffer = colorRenderbuffer.clone();
        this.depthType         = depthType;
        this.depthRenderbuffer = depthRenderbuffer;
        this.samples           = samples;
        this.maxSamples        = maxSamples;

        // Pre-compute active slot ids
        int count = 0;
        for (int i = 0; i < MAX_COLOR_SLOTS; i++) 
            if (colorSlots[i] != null) count++;
        
        int[] ids = new int[count];
        int pos = 0;
        for (int i = 0; i < MAX_COLOR_SLOTS; i++) 
            if (colorSlots[i] != null) ids[pos++] = i;
        
        this.activeColorSlotIds = ids;
    }

    // ── Getters ────────────────────────────────────────────────────────────────

    /**
     * Returns the {@link CgTextureType} for the given color slot,
     * or {@code null} if the slot is unused.
     */
    public CgTextureType getColorSlot(int slot) {
        if (slot < 0 || slot >= MAX_COLOR_SLOTS) return null;
        return colorSlots[slot];
    }

    /**
     * Returns {@code true} if the given color slot is backed by a renderbuffer
     * (non-sampleable) rather than a texture.
     */
    public boolean isColorRenderbuffer(int slot) {
        if (slot < 0 || slot >= MAX_COLOR_SLOTS) return false;
        return colorRenderbuffer[slot];
    }

    /** Returns {@code true} if this format has a depth or depth+stencil attachment. */
    public boolean hasDepth() { return depthType != null; }

    /**
     * Returns {@code true} if this format has no color attachments — only a depth attachment.
     * Depth-only FBOs require {@code GL_NONE} draw/read buffers (see {@code CgFrameBuffer.create()}).
     */
    public boolean isDepthOnly() { return activeColorSlotIds.length == 0 && hasDepth(); }

    /** Returns the number of active (non-null) color slots. */
    public int colorSlotCount() { return activeColorSlotIds.length; }

    /** Samples per pixel; 1 when this format is not multisampled. */
    public int getSamples() { return  maxSamples ? CgCapabilities.detect().getMaxSamples() : samples; }

    /** Whether this format requests multisampling. */
    public boolean isMultisampled() { return samples > 1 || maxSamples; }

    // ── equals / hashCode / toString ──────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CgFrameBufferFormat)) return false;
        CgFrameBufferFormat that = (CgFrameBufferFormat) o;
        return depthRenderbuffer == that.depthRenderbuffer
                && samples == that.samples
                && name.equals(that.name)
                && Arrays.equals(colorSlots, that.colorSlots)
                && Arrays.equals(colorRenderbuffer, that.colorRenderbuffer)
                && (depthType == that.depthType);
    }

    @Override
    public int hashCode() {
        int h = name.hashCode();
        h = 31 * h + Arrays.hashCode(colorSlots);
        h = 31 * h + Arrays.hashCode(colorRenderbuffer);
        h = 31 * h + (depthType != null ? depthType.hashCode() : 0);
        h = 31 * h + (depthRenderbuffer ? 1 : 0);
        h = 31 * h + samples;
        return h;
    }

    @Override
    public String toString() {
        return "CgFrameBufferFormat(" + name + ")";
    }

    // ── Static factory ─────────────────────────────────────────────────────────

    /**
     * Returns a new builder for this format.
     * The inner {@link Builder} constructor is private — this is the only entry point.
     *
     * @param name human-readable format name (e.g. "gbuffer")
     * @return a fresh builder
     */
    public static Builder builder(String name) {
        if (name == null || name.isEmpty()) 
            throw new IllegalArgumentException("CgFrameBufferFormat name must not be null or empty");
        
        return new Builder(name);
    }

    // ── Builder ────────────────────────────────────────────────────────────────

    /**
     * Builder for {@link CgFrameBufferFormat}. The constructor is private;
     * use {@link CgFrameBufferFormat#builder(String)} to obtain an instance.
     */
    public static final class Builder {

        private final String name;
        private final CgTextureType[] colorSlots        = new CgTextureType[MAX_COLOR_SLOTS];
        private final boolean[]       colorRenderbuffer = new boolean[MAX_COLOR_SLOTS];
        private int                   samples           = 1;
        private boolean               maxSamples        = false;
        private CgTextureType depthType        = null;
        private boolean       depthRenderbuffer = false;

        /** Private — use {@link CgFrameBufferFormat#builder(String)}. */
        private Builder(String name) {
            this.name = name;
        }

        /**
         * Adds a color slot backed by a sampleable texture (default).
         *
         * @param slot slot index in [0, 8)
         * @param type a color {@link CgTextureType} (must have {@code isColor() == true})
         * @return this builder
         */
        /**
         * Requests multisampling at {@code samples} samples per pixel. 1 (the default) disables it.
         *
         * <h4>What this costs you, stated plainly</h4>
         * <p>A multisampled attachment <b>cannot be sampled by an ordinary {@code sampler2D}</b>. Colour
         * slots declared with {@link #color} become {@code GL_TEXTURE_2D_MULTISAMPLE} textures, readable
         * only through {@code sampler2DMS}; slots declared with {@link #colorRenderbuffer} cannot be read
         * at all. To use the result as a normal texture, render into a multisampled FBO and
         * {@code blitFrom} it into a matching single-sampled one — that blit <em>is</em> the resolve.</p>
         *
         * <p>The count is a request. Drivers clamp to {@code GL_MAX_SAMPLES}, so asking for 16 where 4 is
         * supported yields 4 rather than an error, and this builder does not pre-validate it: the maximum
         * is a property of the live context, which a format outlives.</p>
         */
        public Builder samples(int samples) {
            if (samples < 1) {
                throw new IllegalArgumentException(
                        "CgFrameBufferFormat '" + name + "': samples must be >= 1, got " + samples);
            }
            this.samples = samples;
            return this;
        }

        /**
         * Requests multisampling at the DRIVER'S maximum supported sample count instead of a specific
         * one — {@code true} means "as much MSAA as this machine actually has, or none at all if it has
         * none," resolved once a live GL context exists to ask ({@code CgGL.maxSamples()}), rather than a
         * count the caller has to pick and clamp themselves. {@code false} is equivalent to {@link
         * #samples(int) samples(1)} — no multisampling.
         *
         * <p>The point of this overload: a caller that just wants "the best antialiasing this hardware
         * offers, or a plain single-sampled attachment where it offers none" should not need its own
         * {@code CgCapabilities}/{@code CgGL.maxSamples()} probe and its own zero-or-one-sample special
         * case — that logic already has to exist once, here, for every format that wants it.</p>
         */
        public Builder maxSamples() {
            this.maxSamples = true;
            return this;
        }

        public Builder color(int slot, CgTextureType type) {
            validateColorSlot(slot, type, false);
            colorSlots[slot]        = type;
            colorRenderbuffer[slot] = false;
            return this;
        }

        /**
         * Adds a color slot backed by a non-sampleable renderbuffer.
         * Use this for MSAA or opaque-only passes where the attachment will not be sampled.
         *
         * @param slot slot index in [0, 8)
         * @param type a color {@link CgTextureType} (must have {@code isColor() == true})
         * @return this builder
         */
        public Builder colorRenderbuffer(int slot, CgTextureType type) {
            validateColorSlot(slot, type, true);
            colorSlots[slot]        = type;
            colorRenderbuffer[slot] = true;
            return this;
        }

        /**
         * Adds a depth/stencil attachment backed by a sampleable texture (default).
         * Use this when the depth attachment must be sampled (e.g. SSAO, shadow maps).
         *
         * @param type a depth/stencil {@link CgTextureType} (must have
         *             {@code isDepth || isStencil})
         * @return this builder
         */
        public Builder depth(CgTextureType type) {
            validateDepthType(type);
            this.depthType        = type;
            this.depthRenderbuffer = false;
            return this;
        }

        /**
         * Adds a depth/stencil attachment backed by a non-sampleable renderbuffer.
         * Faster on some hardware; the depth value cannot be sampled by shaders.
         *
         * @param type a depth/stencil {@link CgTextureType} (must have
         *             {@code isDepth || isStencil})
         * @return this builder
         */
        public Builder depthRenderbuffer(CgTextureType type) {
            validateDepthType(type);
            this.depthType        = type;
            this.depthRenderbuffer = true;
            return this;
        }

        /**
         * Builds the immutable {@link CgFrameBufferFormat}.
         *
         * <p>Validates structural constraints:</p>
         * <ul>
         *   <li>At least one attachment (color or depth) must be present.</li>
         *   <li>Color slot indices must be in {@code [0, 8)} — actual GPU limit is
         *       checked at FBO creation time in {@code CgFrameBuffer.create()}.</li>
         *   <li>Color types must pass {@code isColor()}.</li>
         *   <li>Depth types must have {@code isDepth || isStencil}.</li>
         * </ul>
         *
         * @return a new immutable {@link CgFrameBufferFormat}
         * @throws IllegalArgumentException if no attachments are defined
         */
        public CgFrameBufferFormat build() {
            boolean hasAny = (depthType != null);
            for (int i = 0; i < MAX_COLOR_SLOTS && !hasAny; i++) 
                if (colorSlots[i] != null) hasAny = true;
            
            if (!hasAny) {
                throw new IllegalArgumentException(
                        "CgFrameBufferFormat '" + name + "': at least one attachment "
                        + "(color or depth) must be defined");
            }
            return new CgFrameBufferFormat(name, colorSlots, colorRenderbuffer, depthType,
                    depthRenderbuffer, samples, maxSamples);
        }

        // ── Validation helpers ─────────────────────────────────────────────────

        private void validateColorSlot(int slot, CgTextureType type, boolean renderbuffer) {
            if (slot < 0 || slot >= MAX_COLOR_SLOTS) {
                throw new IllegalArgumentException(
                        "CgFrameBufferFormat '" + name + "': color slot " + slot
                        + " is out of range [0, " + MAX_COLOR_SLOTS + ")");
            }
            if (type == null) {
                throw new IllegalArgumentException(
                        "CgFrameBufferFormat '" + name + "': color type at slot "
                        + slot + " must not be null");
            }
            if (!type.isColor()) {
                throw new IllegalArgumentException(
                        "CgFrameBufferFormat '" + name + "': type " + type
                        + " at color slot " + slot
                        + " is not a color format (isDepth=" + type.isDepth
                        + ", isStencil=" + type.isStencil + ")");
            }
        }

        private void validateDepthType(CgTextureType type) {
            if (type == null) 
                throw new IllegalArgumentException("CgFrameBufferFormat '" + name + "': depth type must not be null");
            
            if (!type.isDepth && !type.isStencil) 
                throw new IllegalArgumentException("CgFrameBufferFormat '" + name + "': depth type " + type + " is not a depth/stencil format");
        }
    }
}
