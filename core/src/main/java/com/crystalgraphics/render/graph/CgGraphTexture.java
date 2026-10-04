package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.gl.texture.CgTexture2D;

import javax.annotation.Nullable;
import java.util.Arrays;

/**
 * A texture a frame graph orders work on: a pass writes it, a draw or a copy reads it. A handle at once, on any
 * thread; its storage is the executor's to resolve on the render thread. It is a {@link CgTexture}, so a binding
 * snapshot takes it like any other.
 *
 * <pre>{@code
 * CgGraphTexture main  = CgGraphTexture.imported("mc_main", mainTarget);          // the host's, as it is
 * CgGraphTexture layer = CgGraphTexture.transientTexture("layer", new CgTextureDesc(w, h, CgTextureDesc.RGBA8));
 * CgGraphTexture view  = CgGraphTexture.requested("preview", desc);               // kept across frames
 * CgGraphTexture here  = CgGraphTexture.current();                                // what is bound when execution begins
 * }</pre>
 *
 * <ul>
 *   <li><b>Transient</b>: storage from a pool for the passes between its first and last use in one frame; its
 *       contents do not survive the frame. Two that never live at once may share storage.</li>
 *   <li><b>Requested</b>: storage made on first use and kept until {@code recording.release(texture)}.</li>
 *   <li>A kernel binds one as a storage image (a {@code CgDispatch}'s {@code image}) where its format is the image's.</li>
 *   <li>Binding one outside the passes that resolve it binds nothing. {@link #delete()} refuses: the graph owns it.</li>
 * </ul>
 */
public final class CgGraphTexture extends CgGraphResource implements CgTexture {

    /** Where its storage comes from. */
    public enum Kind { IMPORTED, CURRENT, TRANSIENT, REQUESTED }

    private static final CgGraphTexture CURRENT = new CgGraphTexture(Kind.CURRENT, "current", null, null);

    private final Kind kind;
    @Nullable
    private final CgTextureDesc desc;

    /** Render thread only: the storage while resolved. */
    @Nullable
    private CgFrameBuffer framebuffer;

    /** Its {@link #level} and {@link #attachment} views, made at first ask. */
    private Level[] levelViews = new Level[0];
    private Attachment[] attachmentViews = new Attachment[0];

    private CgGraphTexture(Kind kind, String name, @Nullable CgTextureDesc desc, @Nullable CgFrameBuffer framebuffer) {
        super(name);
        this.kind = kind;
        this.desc = desc;
        this.framebuffer = framebuffer;
    }

    /** A framebuffer someone else owns, used as it is: the host's main target, a retained surface. */
    public static CgGraphTexture imported(String name, CgFrameBuffer framebuffer) {
        return new CgGraphTexture(Kind.IMPORTED, name, null, framebuffer);
    }

    /** The framebuffer and viewport bound when the frame's execution began, kept across passes into other targets. */
    public static CgGraphTexture current() {
        return CURRENT;
    }

    /** Storage for this frame only, from the pool. */
    public static CgGraphTexture transientTexture(String name, CgTextureDesc desc) {
        return new CgGraphTexture(Kind.TRANSIENT, name, desc, null);
    }

    /** Storage made on first use and kept until released. */
    public static CgGraphTexture requested(String name, CgTextureDesc desc) {
        return new CgGraphTexture(Kind.REQUESTED, name, desc, null);
    }

    public Kind kind() {
        return kind;
    }

    /** What the graph allocates for it; null for an imported or current texture. */
    @Nullable
    public CgTextureDesc desc() {
        return desc;
    }

    /**
     * Level {@code level} alone, as a texture a draw or a kernel samples: what a pass drawing into another level of it
     * reads, with no feedback loop on any device. A shader reads it at its base, LOD 0, and its size is the level's.
     *
     * <pre>{@code
     * for (int k = 1; k < bloom.getLevels(); k++) {         // each level drawn from the one above
     *     CgRasterPass down = rec.raster(bloom, k, CgLoad.load(), constants, null, CgOrder.LOOKBACK);
     *     int reads = rec.bindings().withTexture(downsample.captureBindings(rec.bindings()), 0, bloom.level(k - 1));
     *     CgChunkBuilder c = rec.chunks().begin();
     *     c.draw(downsample.pipeline(CgInstanceKind.OBJECT), reads, CgMesh.quads(1));   // a fullscreen #type none quad
     *     c.instance();
     *     down.add(c.end());
     *     down.end();
     * }
     * }</pre>
     *
     * <ul>
     *   <li>Read as the whole texture for ordering: a pass sampling a level runs after every pass writing the texture.</li>
     *   <li>The pin lasts until the texture is next bound whole, and the executor unpins it after the pass.</li>
     * </ul>
     */
    public Level level(int level) {
        if (level < 0 || level >= getLevels()) throw new IllegalArgumentException(this + " has no level " + level);
        if (levelViews.length <= level) levelViews = Arrays.copyOf(levelViews, getLevels());
        Level view = levelViews[level];
        if (view == null) levelViews[level] = view = new Level(this, level);
        return view;
    }

    /**
     * Its colour attachment {@code slot}, as a texture a draw samples: what a pass that drew into every attachment of
     * a multi-target format ({@code CgFrameBufferFormat.color(1, ...)}) hands a later reader. Slot 0 is the texture
     * itself.
     *
     * <pre>{@code
     * CgRasterPass both = rec.raster(gbuffer, CgLoad.clear(0, 0, 0, 0), constants, null, CgOrder.SORTED);   // writes RT0 and RT1
     * int reads = rec.bindings().withTexture(material.captureBindings(rec.bindings()), 0, gbuffer.attachment(1));
     * }</pre>
     *
     * <ul>
     *   <li>Read as the whole texture for ordering: a pass sampling it runs after every pass writing the texture.</li>
     *   <li>Its size is the attachment's; a slot the format lacks binds nothing.</li>
     * </ul>
     */
    public Attachment attachment(int slot) {
        if (slot < 0) throw new IllegalArgumentException(this + " has no colour attachment " + slot);
        if (attachmentViews.length <= slot) attachmentViews = Arrays.copyOf(attachmentViews, slot + 1);
        Attachment view = attachmentViews[slot];
        if (view == null) attachmentViews[slot] = view = new Attachment(this, slot);
        return view;
    }

    /** The graph texture binding {@code texture} samples: itself, or a level or attachment view's; null for any other. */
    @Nullable
    static CgGraphTexture sampled(@Nullable CgTexture texture) {
        if (texture instanceof CgGraphTexture graph) return graph;
        if (texture instanceof Level view) return view.texture;
        return texture instanceof Attachment view ? view.texture : null;
    }

    /** Render thread: samples every level again, if a {@link #level} view pinned one. */
    void unpinLevels() {
        if (color() instanceof CgTexture2D color) color.unpinLevels();
    }

    @Override
    boolean isTransient() {
        return kind == Kind.TRANSIENT;
    }

    @Override
    boolean outlivesFrame() {
        return kind != Kind.TRANSIENT;
    }

    /** Render thread: its storage now, or null outside the passes that resolve it. */
    @Nullable
    public CgFrameBuffer framebuffer() {
        return framebuffer;
    }

    void resolve(@Nullable CgFrameBuffer storage) {
        this.framebuffer = storage;
    }

    @Nullable
    private CgTexture color() {
        return framebuffer == null ? null : framebuffer.getColorTexture(0);
    }

    @Override
    public void bind() {
        CgTexture color = color();
        if (color != null) color.bind();
    }

    @Override
    public void bind(int unit) {
        CgTexture color = color();
        if (color != null) color.bind(unit);
    }

    @Override
    public int getId() {
        CgTexture color = color();
        return color == null ? 0 : color.getId();
    }

    @Override
    public int getWidth() {
        return framebuffer != null ? framebuffer.getWidth() : desc != null ? desc.width() : 0;
    }

    @Override
    public int getHeight() {
        return framebuffer != null ? framebuffer.getHeight() : desc != null ? desc.height() : 0;
    }

    /** Its colour's mip levels: the description's, or an imported framebuffer's. */
    @Override
    public int getLevels() {
        return desc != null ? desc.levels() : framebuffer != null ? framebuffer.getColorLevels() : 1;
    }

    @Override
    public int getTarget() {
        CgTexture color = color();
        return color == null ? 0 : color.getTarget();
    }

    @Override
    public boolean isDeleted() {
        return false;
    }

    /** Refused: a graph texture is the graph's. A requested one is released through its recording. */
    @Override
    public void delete() {
        throw new UnsupportedOperationException("'" + name + "' belongs to the frame graph: release it through a recording");
    }

    @Override
    public String toString() {
        return "CgGraphTexture(" + kind + " " + name + ")";
    }

    /** One level of a graph texture, sampled alone: {@link CgGraphTexture#level}. */
    public static final class Level implements CgTexture {

        private final CgGraphTexture texture;
        private final int level;

        private Level(CgGraphTexture texture, int level) {
            this.texture = texture;
            this.level = level;
        }

        public CgGraphTexture texture() {
            return texture;
        }

        public int level() {
            return level;
        }

        @Override
        public void bind() {
            if (texture.color() instanceof CgTexture2D color) color.bindLevel(level);
        }

        @Override
        public void bind(int unit) {
            if (texture.color() instanceof CgTexture2D color) color.bindLevel(unit, level);
        }

        @Override
        public int getId() {
            return texture.getId();
        }

        @Override
        public int getWidth() {
            return Math.max(1, texture.getWidth() >> level);
        }

        @Override
        public int getHeight() {
            return Math.max(1, texture.getHeight() >> level);
        }

        @Override
        public int getTarget() {
            return texture.getTarget();
        }

        @Override
        public boolean isDeleted() {
            return false;
        }

        /** Refused, as for its texture. */
        @Override
        public void delete() {
            texture.delete();
        }

        @Override
        public String toString() {
            return texture + " level " + level;
        }
    }

    /** One colour attachment of a graph texture, sampled: {@link CgGraphTexture#attachment}. */
    public static final class Attachment implements CgTexture {

        private final CgGraphTexture texture;
        private final int slot;

        private Attachment(CgGraphTexture texture, int slot) {
            this.texture = texture;
            this.slot = slot;
        }

        public CgGraphTexture texture() {
            return texture;
        }

        public int slot() {
            return slot;
        }

        @Nullable
        private CgTexture color() {
            CgFrameBuffer framebuffer = texture.framebuffer;
            return framebuffer == null ? null : framebuffer.getColorTexture(slot);
        }

        @Override
        public void bind() {
            CgTexture color = color();
            if (color != null) color.bind();
        }

        @Override
        public void bind(int unit) {
            CgTexture color = color();
            if (color != null) color.bind(unit);
        }

        @Override
        public int getId() {
            CgTexture color = color();
            return color == null ? 0 : color.getId();
        }

        @Override
        public int getWidth() {
            CgTexture color = color();
            return color == null ? texture.getWidth() : color.getWidth();
        }

        @Override
        public int getHeight() {
            CgTexture color = color();
            return color == null ? texture.getHeight() : color.getHeight();
        }

        @Override
        public int getTarget() {
            CgTexture color = color();
            return color == null ? 0 : color.getTarget();
        }

        @Override
        public boolean isDeleted() {
            return false;
        }

        /** Refused, as for its texture. */
        @Override
        public void delete() {
            texture.delete();
        }

        @Override
        public String toString() {
            return texture + " attachment " + slot;
        }
    }
}
