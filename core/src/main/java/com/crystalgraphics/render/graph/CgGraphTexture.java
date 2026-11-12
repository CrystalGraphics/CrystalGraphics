package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.platform.rhi.CgExternalTarget;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;

import javax.annotation.Nullable;
import java.util.Arrays;
import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.gpu.resource.CgTextureDesc;

/**
 * A texture a frame graph orders work on: a pass writes it, a draw or a copy reads it. A handle at once, on any
 * thread, and no texture: its storage ({@link CgGraphStorage}) exists only while the passes using it execute, which
 * resolve it on the render thread (Unreal's {@code FRDGTexture}). A binding takes it as any {@link CgTexture}.
 *
 * <pre>{@code
 * CgGraphTexture main  = CgGraphTexture.imported("mc_main", CgExternalTarget.host(fbo, w, h));   // someone else's
 * CgGraphTexture layer = CgGraphTexture.transientTexture("layer", CgTextureDesc.of(w, h, CgFormat.RGBA8_UNORM));
 * CgGraphTexture view  = CgGraphTexture.requested("preview", desc);               // kept across frames
 * CgGraphTexture here  = CgGraphTexture.current();                                // the target the execution opened
 * CgGraphTexture grid  = CgGraphTexture.requested("grid", CgTextureDesc.volume(64, 64, 64, r16f));   // 3D
 * CgGraphTexture taa   = CgGraphTexture.history("taa", desc);                    // this frame's and the last
 * }</pre>
 *
 * <ul>
 *   <li><b>External</b>: a target someone else owns, used as it is: {@link #imported}'s, or {@link #current()}, the one
 *       {@code CgExecutor.into} opened round the execution.</li>
 *   <li><b>Transient</b>: storage from a pool for the passes between its first and last use in one frame; its
 *       contents do not survive the frame. Two that never live at once may share storage.</li>
 *   <li><b>Requested</b>: storage made on first use and kept until {@code recording.release(texture)}.</li>
 *   <li><b>History</b>: two storages kept until released, each raster pass into it drawing the next version
 *       ({@link #history}).</li>
 *   <li>A kernel binds one as a storage image (a {@code CgDispatch}'s {@code image}) where its format is the image's:
 *       a volume as a {@code 3d} image, any other as a {@code 2d} one. A draw samples a volume as a {@code sampler3D}.</li>
 *   <li>Sampled outside the passes that resolve it, it binds nothing.</li>
 * </ul>
 */
public final class CgGraphTexture extends CgGraphResource implements CgTexture {

    /** Where its storage comes from. */
    public enum Kind { EXTERNAL, TRANSIENT, REQUESTED, HISTORY }

    private static final CgGraphTexture CURRENT = new CgGraphTexture(Kind.EXTERNAL, "current", null, null, null, null);

    private final Kind kind;
    @Nullable
    private final CgTextureDesc desc;
    /** An imported texture's target; null for {@link #current()}, which the execution names. */
    @Nullable
    private final CgExternalTarget target;
    /** The texture whose depth every pass into this one draws with ({@link #transientTexture(String, CgTextureDesc, CgGraphTexture)}). */
    @Nullable
    private final CgGraphTexture depthOf;

    /** Render thread only: the storage while resolved. */
    @Nullable
    private CgGraphStorage storage;
    /** For {@link #previous()}'s view: the history it reads, else null. */
    @Nullable
    private final CgGraphTexture of;
    @Nullable
    private CgGraphTexture previous;
    /** Render thread only: a history's two storages, made at its first use, and which is the newest. */
    @Nullable
    private final CgGraphStorage[] versions;
    private int newest;

    /** Its {@link #level} and {@link #attachment} views, made at first ask. */
    private Level[] levelViews = new Level[0];
    @Nullable
    private Stencil stencilView;

    private CgGraphTexture(Kind kind, String name, @Nullable CgTextureDesc desc, @Nullable CgExternalTarget target,
                           @Nullable CgGraphTexture depthOf, @Nullable CgGraphTexture of) {
        super(name);
        if (desc != null && (desc.isCube() || desc.isVolume() && desc.levels() > 1)) {
            throw new IllegalArgumentException(name + ": the graph makes no cube, and a volume of one level: " + desc);
        }
        this.kind = kind;
        this.desc = desc;
        this.target = target;
        this.depthOf = depthOf;
        this.of = of;
        this.versions = kind == Kind.HISTORY && of == null ? new CgGraphStorage[2] : null;
    }

    /** A target someone else owns, used as it is: a made render target, a retained surface, a host's framebuffer. */
    public static CgGraphTexture imported(String name, CgExternalTarget target) {
        CgGraphTexture texture = new CgGraphTexture(Kind.EXTERNAL, name, null, target, null, null);
        texture.storage = CgGraphStorage.imported(target);
        return texture;
    }

    /**
     * The target the execution draws into: the one {@code CgExecutor.into} opened round it, imported once per
     * execution. A pass's default target, and what a stage's draws land in unless it is retargeted.
     */
    public static CgGraphTexture current() {
        return CURRENT;
    }

    /** Whether it is {@link #current()}. */
    public boolean isCurrent() {
        return this == CURRENT;
    }

    /** Storage for this frame only, from the pool. */
    public static CgGraphTexture transientTexture(String name, CgTextureDesc desc) {
        return new CgGraphTexture(Kind.TRANSIENT, name, desc, null, null, null);
    }

    /**
     * Storage for this frame only, colour alone, every pass into it drawing with {@code depthOf}'s depth: a linear
     * scene drawn in place of the host's colour, tested against and writing the host's depth
     * ({@code depthOf} {@link #current()}).
     *
     * <pre>{@code
     * if (stage.host().target().lendsAttachments()) {
     *     scene = CgGraphTexture.transientTexture("scene", new CgTextureDesc(w, h, rgba16f), CgGraphTexture.current());
     * }
     * }</pre>
     *
     * <ul>
     *   <li>{@code desc} is one level of colour alone, {@code depthOf}'s size.</li>
     *   <li>A target that lends no attachments (the window's, a multisampled one) draws it without depth, once, and logs
     *       why: ask {@code lendsAttachments()} first and give the scene a depth of its own there.</li>
     *   <li>A pass into it is ordered as a write of {@code depthOf} too, and may clear that depth only where it is a
     *       texture of the graph's: an external target's is its owner's.</li>
     * </ul>
     */
    public static CgGraphTexture transientTexture(String name, CgTextureDesc desc, CgGraphTexture depthOf) {
        return new CgGraphTexture(Kind.TRANSIENT, name, colourAlone(name, desc), null, depthOf, null);
    }

    /** Storage made on first use and kept until released. */
    public static CgGraphTexture requested(String name, CgTextureDesc desc) {
        return new CgGraphTexture(Kind.REQUESTED, name, desc, null, null, null);
    }

    /**
     * As {@link #transientTexture(String, CgTextureDesc, CgGraphTexture)}, its storage kept until released: a scene
     * drawn in one stage's firing and read in a later one.
     *
     * <pre>{@code
     * CgGraphTexture opaque = CgGraphTexture.requested("scene", new CgTextureDesc(w, h, rgba16f), CgGraphTexture.current());
     * recording.release(opaque);   // at a resize, before a new one is made
     * }</pre>
     */
    public static CgGraphTexture requested(String name, CgTextureDesc desc, CgGraphTexture depthOf) {
        return new CgGraphTexture(Kind.REQUESTED, name, colourAlone(name, desc), null, depthOf, null);
    }

    private static CgTextureDesc colourAlone(String name, CgTextureDesc desc) {
        if (desc.isDepth() || desc.levels() != 1 || desc.isArray() || desc.isVolume()) {
            throw new IllegalArgumentException(name + ": a texture drawn with another's depth is one level of colour alone");
        }
        return desc;
    }

    /**
     * The newest two versions of a picture drawn every frame, kept until released: what a temporal effect blends its
     * new frame into. Each raster pass into it draws the next version, which becomes the newest; {@link #previous()}
     * samples the one before. Sampled itself, it is the newest.
     *
     * <pre>{@code
     * CgGraphTexture smoke = CgGraphTexture.history("smoke", new CgTextureDesc(w, h, rgba16f));
     * resolve.applyProperties(b -> b.sampler("_Now", 0, current).sampler("_Last", 1, smoke.previous()));
     * CgRasterPass pass = rec.raster("graphTexture.smoke").color(smoke, CgLoad.load()).constants(constants);   // draws the next version
     * // ... the resolve's draw: this frame blended over the last, by a validity the caller keeps ...
     * pass.end();
     * composite.applyProperties(b -> b.sampler("_Smoke", 0, smoke));   // a later pass: what that one drew
     * }</pre>
     *
     * <pre>{@code
     * rec.release(smoke);          // both versions, once nothing draws it: unused for a while, or resized
     * }</pre>
     *
     * <ul>
     *   <li>Both versions start cleared to 0. Whether the previous one holds a frame of yours is yours to keep: blend by
     *       a validity false at its first frame and after a cut, and select rather than multiply by it, since a frame of
     *       another view is no zero.</li>
     *   <li>Raster passes write it, level 0 and layer 0: never a kernel's image or a copy.</li>
     *   <li>A frame executed again draws into it once: its passes are taken once, as a simulation's are.</li>
     *   <li>Never transient, never aliased, never culled; its storage is made at its first use, a read included.</li>
     *   <li>A history of one view a firing: a second firing of another camera drawing into it blends the two.</li>
     * </ul>
     */
    public static CgGraphTexture history(String name, CgTextureDesc desc) {
        if (desc.levels() != 1 || desc.isArray() || desc.isVolume()) {
            throw new IllegalArgumentException(name + ": a history is one level of a 2D texture");
        }
        return new CgGraphTexture(Kind.HISTORY, name, desc, null, null, null);
    }

    /** A history's version before its newest: what this frame blends over. Sampled only. */
    public CgGraphTexture previous() {
        if (kind != Kind.HISTORY || of != null) throw new IllegalStateException("only a history has a previous version: " + this);
        if (previous == null) previous = new CgGraphTexture(Kind.HISTORY, name + ".previous", desc, null, null, this);
        return previous;
    }

    /** Whether this is a history's {@link #previous()} view. */
    public boolean isPreviousVersion() {
        return of != null;
    }

    /** The resource its reads and writes are ordered on: a previous view's history. */
    CgGraphTexture resource() {
        return of != null ? of : this;
    }

    /** Render thread: a history's storages, made by the executor at its first use; null for any other. */
    @Nullable
    CgGraphStorage[] versions() {
        return of != null ? of.versions : versions;
    }

    /** Render thread: the next version becomes the newest, before a pass draws it. */
    void advance() {
        newest = 1 - newest;
    }

    /** The texture whose depth passes into this one draw with; null where they draw with its own. */
    @Nullable
    public CgGraphTexture depthOf() {
        return depthOf;
    }

    public Kind kind() {
        return kind;
    }

    /** An imported texture's target; null for any other, and for {@link #current()}. */
    @Nullable
    public CgExternalTarget target() {
        return target;
    }

    /**
     * Its texture's format: its description's, or an imported target's colour (its depth where it has none); null for
     * {@link #current()} outside an execution.
     */
    @Nullable
    public CgFormat format() {
        if (desc != null) return desc.format();
        CgGraphStorage now = storage();
        if (now == null) return null;
        return now.color(0) != null ? now.colorFormat(0) : now.depthFormat();
    }

    /** What the graph allocates for it; null for an external one. */
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
     *     CgRasterPass down = rec.raster("graphTexture.bloom").color(bloom, k, 0, CgLoad.load()).constants(constants).order(CgOrder.LOOKBACK);
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
     * Its stencil, as a texture a draw samples: Unreal's CustomStencil, an SRV of the depth-stencil itself. Read as an
     * unsigned integer through a {@code usampler2D}, nearest.
     *
     * <pre>{@code
     * CgGraphTexture custom = stage.resources().get(CgFrameKeys.CUSTOM_DEPTH);
     * int reads = rec.bindings().withTexture(probe.captureBindings(rec.bindings()), 0, custom.stencil());
     * // in the shader: usampler2D _Stencil; uint bits = texelFetch(_Stencil, ivec2(gl_FragCoord.xy), 0).r;
     * }</pre>
     *
     * <ul>
     *   <li>Read as the whole texture for ordering: a pass sampling it runs after every pass writing the texture.</li>
     *   <li>Throws for a texture whose format has no stencil, here where the format is known.</li>
     * </ul>
     */
    public Stencil stencil() {
        if (desc != null && !desc.format().hasStencil()) {
            throw new IllegalArgumentException(this + " has no stencil");
        }
        if (stencilView == null) stencilView = new Stencil(this);
        return stencilView;
    }

    /** The graph texture binding {@code texture} samples: itself, or a level, attachment or stencil view's; null for any other. */
    @Nullable
    static CgGraphTexture sampled(@Nullable CgTexture texture) {
        if (texture instanceof CgGraphTexture graph) return graph;
        if (texture instanceof Level view) return view.texture;
        return texture instanceof Stencil view ? view.texture : null;
    }

    @Override
    boolean isTransient() {
        return kind == Kind.TRANSIENT;
    }

    @Override
    boolean outlivesFrame() {
        return kind != Kind.TRANSIENT;
    }

    /**
     * Render thread: its storage now, or null outside the passes that resolve it: a history's newest, or its previous.
     *
     * <pre>{@code
     * recording.callback("check", null, () -> pixels = picture.storage().readColor(0, 0, w, h), picture);
     * }</pre>
     */
    @Nullable
    public CgGraphStorage storage() {
        if (of != null) return of.versions[1 - of.newest];
        if (versions != null) return versions[newest];
        return storage;
    }

    void resolve(@Nullable CgGraphStorage storage) {
        this.storage = storage;
    }

    @Override
    @Nullable
    public CgTextureView rhiView() {
        if (CgGraphCheck.on()) CgGraphCheck.bound(this);   // a binding's: the executor reads storage()
        CgGraphStorage now = storage();
        return now == null || now.color(0) == null ? null : now.sampled(0).rhiView();
    }

    @Override
    @Nullable
    public CgSampler sampler() {
        CgGraphStorage now = storage();
        return now == null || now.color(0) == null ? null : now.sampled(0).sampler();
    }

    @Override
    public int getWidth() {
        CgGraphStorage now = storage();
        return now != null ? now.width() : desc != null ? desc.width() : target != null ? target.width() : 0;
    }

    @Override
    public int getHeight() {
        CgGraphStorage now = storage();
        return now != null ? now.height() : desc != null ? desc.height() : target != null ? target.height() : 0;
    }

    /** Whether it is a 3D texture: its description says so, or an imported target's images are one. */
    public boolean isVolume() {
        if (desc != null) return desc.isVolume();
        CgGpuTexture.Desc image = imageDesc();
        return image != null && image.kind() == CgGpuTexture.Kind.D3;
    }

    /** A volume's slices; 1 for a 2D texture. */
    public int getDepth() {
        if (desc != null) return desc.depth();
        CgGpuTexture.Desc image = imageDesc();
        return image != null && image.kind() == CgGpuTexture.Kind.D3 ? image.depthOrLayers() : 1;
    }

    /** Whether it is a 2D array: its description says so, or an imported target's images are one. */
    public boolean isArray() {
        return getLayers() > 1;
    }

    /** An array's layers; 1 for any other texture. */
    public int getLayers() {
        if (desc != null) return desc.layers();
        CgGpuTexture.Desc image = imageDesc();
        return image != null && image.kind() == CgGpuTexture.Kind.D2_ARRAY ? image.depthOrLayers() : 1;
    }

    /** Its colour's mip levels: the description's, or an imported target's colour's. */
    @Override
    public int getLevels() {
        if (desc != null) return desc.levels();
        CgGpuTexture.Desc image = imageDesc();
        return image != null ? image.mips() : 1;
    }

    /** An imported target's colour image, else its depth; null for any other. */
    @Nullable
    private CgGpuTexture.Desc imageDesc() {
        if (target == null) return null;
        CgTextureView view = target.color() != null ? target.color() : target.depth();
        return view == null ? null : view.texture().desc();
    }

    @Override
    public String toString() {
        return "CgGraphTexture(" + (isCurrent() ? "current" : kind + " " + name) + ")";
    }

    /** One level of a graph texture, sampled alone: {@link CgGraphTexture#level}. */
    public static final class Level implements CgTexture {

        private final CgGraphTexture texture;
        private final int level;
        /** Its level alone, kept while the storage's texture is the same. */
        @Nullable
        private CgTextureView view;

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
        @Nullable
        public CgTextureView rhiView() {
            CgTextureView whole = texture.rhiView();
            if (whole == null) return null;
            if (whole.texture().desc().kind() != CgGpuTexture.Kind.D2) return whole;
            if (view == null || view.texture() != whole.texture()) {
                view = new CgTextureView(whole.texture(), level, 1, 0, whole.layers());
            }
            return view;
        }

        @Override
        @Nullable
        public CgSampler sampler() {
            return texture.sampler();
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
        public String toString() {
            return texture + " level " + level;
        }
    }

    /** The stencil of a graph texture, sampled: {@link CgGraphTexture#stencil}. */
    public static final class Stencil implements CgTexture {


        private final CgGraphTexture texture;
        @Nullable
        private CgTextureView view;

        private Stencil(CgGraphTexture texture) {
            this.texture = texture;
        }

        public CgGraphTexture texture() {
            return texture;
        }

        @Override
        @Nullable
        public CgTextureView rhiView() {
            if (CgGraphCheck.on()) CgGraphCheck.bound(texture);
            CgGraphStorage storage = texture.storage();
            CgGpuTexture depth = storage == null ? null : storage.depth();
            if (depth == null) return null;
            if (view == null || view.texture() != depth) view = CgTextureView.stencil(depth);
            return view;
        }

        /** Integers are never filtered: a linear filter over one leaves the texture incomplete. */
        @Override
        public CgSampler sampler() {
            return CgSampler.NEAREST_CLAMP;
        }

        @Override
        public int getWidth() {
            return texture.getWidth();
        }

        @Override
        public int getHeight() {
            return texture.getHeight();
        }

        @Override
        public String toString() {
            return texture + " stencil";
        }
    }
}
