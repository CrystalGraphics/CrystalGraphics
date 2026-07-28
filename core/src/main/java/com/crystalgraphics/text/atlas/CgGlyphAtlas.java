package com.crystalgraphics.text.atlas;

import com.crystalgraphics.api.font.CgGlyphKey;
import com.crystalgraphics.api.font.CgGlyphPlacement;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.gl.texture.CgTexture2DArray;
import com.crystalgraphics.text.atlas.packing.MaxRectsPacker;
import com.crystalgraphics.text.atlas.packing.CgPackingStrategy;
import com.crystalgraphics.util.profiling.CgProfiler;
import lombok.Getter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Multi-page atlas manager for glyph storage — owns one {@link CgTexture2DArray}
 * per atlas family (bitmap or distance-field), with each {@link CgGlyphAtlasPage}
 * a lightweight {@code (layerIndex, CgPackingStrategy)} view into one layer of
 * that shared array, rather than an independent GL texture. When a glyph does
 * not fit on the current page, the manager allocates a new page (layer) instead
 * of evicting existing glyphs. This ensures placement stability — once a glyph
 * is allocated, its position and page never change, until the whole page is
 * evicted (see below).
 *
 * <h3>Why an array, not independent textures per page</h3>
 * <p>Every page used to own its own standalone {@code GL_TEXTURE_2D}, so text
 * spanning N pages of one atlas family cost N draw calls purely because each
 * page was a different GL texture — even though every one of those glyphs is
 * otherwise mode/format-identical. Collapsing all pages of one family into one
 * array's layers turns the renderer's batch-break "which texture" dimension
 * from an unbounded per-page count into one fixed id per family, so a
 * multi-page HUD/paragraph's draw-call count stops scaling with page count.
 * See {@code docs_research/CGTEXTRENDERER_INSTANCING_FOUNDATIONS.md}, "Atlas
 * texture array."</p>
 *
 * <h3>Format</h3>
 * <p>Bitmap atlases allocate their array as {@code R8}. Distance-field atlases
 * (MSDF and MTSDF alike) allocate their array as {@code RGBA8} — MSDF simply
 * leaves the alpha channel unused/undefined at upload time (the shader only
 * ever reads {@code .rgb} in MSDF mode), avoiding a separate 3-component array
 * format with its own driver-alignment history for a channel count that's
 * different only by convention, not by anything the fragment shader needs.</p>
 *
 * <h3>Lookup vs. Allocation — two different costs</h3>
 * <p>Looking up an <em>already-placed</em> glyph ({@link #get(CgGlyphKey, long)}) is
 * {@code O(1)} — a single {@code glyphIndex} map lookup, not a per-page scan. Finding room
 * for a <em>new</em> glyph is a different concern and still scans pages (see "Page Allocation
 * Policy" below); that only happens on an actual cache miss, not on every lookup.</p>
 *
 * <h3>Page Allocation Policy</h3>
 * <ol>
 *   <li>Scan existing pages <strong>oldest first</strong>, placing the glyph in the first one
 *       that has room. Each page rejects in {@code O(1)} via
 *       {@link CgPackingStrategy#mayFit} when it provably cannot take the glyph, so this stays
 *       cheap even with dozens of pages.</li>
 *   <li>If no existing page fits, create a new page — a fresh layer, growing
 *       the backing array first if needed (see below), or — only for an atlas
 *       given an explicit {@link #maxPages} bound — a reused layer freed by
 *       evicting the coldest page once the array is already at that bound.</li>
 * </ol>
 *
 * <h3>Backing Array Growth — starts small, grows 1.5x on demand</h3>
 * <p>The array texture starts at {@link #INITIAL_PAGES} layer(s) and grows
 * by 1.5x via {@link CgTexture2DArray#growLayers(int)} each time
 * {@link #createPage()} needs a layer beyond current capacity — so an atlas
 * that only ever fills 2 pages only ever allocates 2 layers' worth of GPU
 * memory. Growth reallocates the array's GL storage in place (same texture
 * id, same {@code CgTexture2DArray} instance) and is a cold-path operation —
 * expected to happen a handful of times over an atlas's life, not per frame.</p>
 * <p><strong>Unbounded by default</strong> ({@link #UNBOUNDED_PAGES}, via
 * {@link #createForRegistry(int, int, Type)} /
 * {@link #createForRegistry(int, int, Type, int)}) — the
 * array simply keeps growing 1.5x forever and no page is ever evicted. This
 * matters for scripts with very large glyph inventories (CJK fonts routinely
 * need far more than a couple dozen pages at typical atlas sizes); evicting
 * pages there would mean constant re-rasterization churn instead of a one-time
 * cost. Callers with an actual memory ceiling to enforce can still opt into
 * the old bounded behavior via
 * {@link #createForRegistry(int, int, Type, int, int)} with
 * an explicit {@code maxPages} — once the array has grown to that bound and
 * is full, the atlas falls back to evicting the <strong>coldest whole
 * page</strong> — the one with the lowest
 * {@link CgGlyphAtlasPage#getLastTouchedFrame()} — and the new page
 * <strong>reuses that page's exact layer index</strong> (see
 * {@link #createPage()}). This preserves placement stability for every glyph
 * on every page that survives; a glyph is only ever displaced by losing its
 * entire page, never individually.</p>
 *
 * <h3>Packing Strategy</h3>
 * <p>Every production page uses {@link MaxRectsPacker} (see
 * {@link #createForRegistry(int, int, Type, int, int)});
 * {@link CgGuillotinePacker} remains available for tests and for callers wanting
 * msdf-atlas-gen output parity. Callers can supply a custom strategy factory via the
 * constructor.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>Not thread-safe. Must only be used from the GL context thread.</p>
 *
 * @see CgGlyphAtlasPage
 * @see CgPackingStrategy
 */
public class CgGlyphAtlas {

    private static final Logger LOGGER = Logger.getLogger(CgGlyphAtlas.class.getName());

    /** Discriminates bitmap (GL_R8), MSDF and MTSDF (both GL_RGBA8) atlas textures. */
    public enum Type {
        /** Single-channel bitmap atlas ({@code GL_R8}, {@code GL_UNSIGNED_BYTE}). */
        BITMAP,
        /** Three-channel MSDF atlas ({@code GL_RGB8F}, uploaded as {@code GL_FLOAT}). */
        MSDF,
        /** Four-channel MTSDF atlas ({@code GL_RGBA8}, uploaded as {@code GL_UNSIGNED_BYTE}). */
        MTSDF
    }

    /**
     * Factory interface for creating packing strategy instances per page.
     * Allows callers to customize the packing algorithm.
     */
    public interface PackerFactory {
        /**
         * Creates a new packing strategy for a page of the given dimensions.
         *
         * @param pageWidth  page width in pixels
         * @param pageHeight page height in pixels
         * @return a new packing strategy instance
         */
        CgPackingStrategy create(int pageWidth, int pageHeight, int spacingPx);
    }

    public static final PackerFactory MAX_RECTS_FACTORY = (pageWidth, pageHeight, spacingPx) -> new MaxRectsPacker(pageWidth, pageHeight);

    /**
     * A reasonable non-default page budget for callers of
     * {@link #createForRegistry(int, int, Type, int, int)}
     * who actually want eviction (see class javadoc "Backing Array Growth") —
     * NOT used automatically by any factory. {@link #UNBOUNDED_PAGES} is the
     * real default everywhere ({@link #createForTest} and the no-{@code maxPages}
     * {@link #createForRegistry} overloads alike).
     */
    public static final int DEFAULT_MAX_PAGES = 32;

    /** Sentinel meaning "no page budget — grow forever, never evict" (the default; see class javadoc). */
    public static final int UNBOUNDED_PAGES = 0;

    /**
     * Starting layer count for a real atlas's backing array — see class javadoc
     * "Backing Array Growth". Deliberately small: most atlases never need more
     * than a handful of pages, and growth is cheap/rare, so there's no benefit
     * to reserving more up front.
     */
    private static final int INITIAL_PAGES = 1;

    // ── Instance fields ────────────────────────────────────────────────

    @Getter
    private final int pageWidth;
    @Getter
    private final int pageHeight;
    @Getter
    private final Type type;
    private final PackerFactory packerFactory;
    private final boolean skipGlUpload;
    private final int spacingPx;
    @Getter
    private final int maxPages;

    /**
     * The array texture backing every page/layer of this atlas family.
     * {@code null} in test mode ({@link #createForTest}) — pages skip all GL
     * calls in that case, matching the pre-array-migration {@code skipGlUpload}
     * behavior.
     */
    private final CgTexture2DArray arrayTexture;

    /**
     * {@code arrayTexture}'s current layer depth — starts at {@link #INITIAL_PAGES}
     * and grows toward {@link #maxPages} via {@link #createPage()}; see class
     * javadoc "Backing Array Growth". Meaningless (never read) when
     * {@code arrayTexture} is {@code null}.
     */
    private int capacity;

    @Getter
    private final List<CgGlyphAtlasPage> pages;
    @Getter
    private boolean deleted;

    /**
     * Atlas-wide {@code CgGlyphKey → owning page} index, so {@link #get(CgGlyphKey, long)} is
     * {@code O(1)} instead of scanning every page's own slot map hot-page-first. Updated on
     * every successful {@link #allocateBitmap}/{@link #allocateMsdf}; a page's entries are
     * removed from here when that page is evicted (see {@link #evictColdestPageAndFreeLayer()})
     * — otherwise a stale entry would point at a layer index some other page now owns.
     * {@link #createPage}'s own page-selection scan (finding room for a <em>new</em> glyph) is
     * a different concern and is untouched by this — this index only accelerates looking up a
     * glyph that's already been placed somewhere.
     */
    private final Map<CgGlyphKey, CgGlyphAtlasPage> glyphIndex = new HashMap<>();

    /**
     * Glyphs known to have <strong>no renderable geometry</strong> — a space, an ideographic
     * space, a control character, or any glyph whose outline is empty and whose rasterized
     * bitmap comes back 0x0. They occupy no atlas pixels, so they can never live in
     * {@link #glyphIndex} (there is no page to point at), and {@link #get} would therefore
     * return {@code null} for them forever.
     *
     * <p>That {@code null} is indistinguishable from "not generated yet", so without this set
     * every resolve re-runs the full miss path for them — MSDF generation attempt, then a
     * FreeType rasterize that produces 0x0 and is thrown away — on every single frame that
     * misses the placement cache. Measured on a 1781-glyph CJK layout: <strong>542 futile
     * FreeType calls per resolve</strong>, forever, since the answer never changes.
     *
     * <p>Recording the verdict once turns those into an {@code O(1)} hit returning
     * {@link #EMPTY_PLACEMENT_MARKER}-shaped data (zero plane bounds, so
     * {@link CgGlyphPlacement#hasGeometry()} is {@code false} and every draw path already
     * skips it — see {@code CgTextRenderer#submitSortedQuads}'s {@code visibleGlyphCount}
     * filter, which is why a 1781-glyph layout only ever submitted 1242 quads).
     */
    private final Map<CgGlyphKey, CgGlyphPlacement> emptyGlyphs = new HashMap<>();

    /**
     * The page holding this atlas's reserved white texel (see {@link #reserveWhiteTexel()}),
     * or {@code null} if never reserved. Excluded from {@link #evictColdestPageAndFreeLayer()}'s
     * candidate scan so a bounded (eviction-enabled) atlas never displaces the one texel every
     * decoration-line draw depends on.
     */
    private CgGlyphAtlasPage whiteTexelPage;

    /**
     * Monotonic counter bumped every time this atlas gains content a previous lookup could not
     * have seen — a newly allocated bitmap/MSDF glyph, or a newly recorded empty glyph.
     *
     * <p>This is the <em>event</em> signal that replaces {@code CgGlyphPlacementCache}'s old
     * time-based {@code REFRESH_FRAMES} poll. A cached resolve that fell back to bitmap for
     * some glyphs is stale exactly when new content arrives (an MSDF upgrade may now be
     * available) — not "every 300 frames", which was simultaneously too slow to pick the
     * upgrade up promptly and expensive enough to cost a ~34ms full re-resolve hitch on a
     * large layout forever, even once fully converged. Mirrors
     * {@code CgMaterialShader#getRevisionNumber()}, which this codebase already uses for
     * exactly this "did the thing I cached against change?" question.</p>
     *
     *
     * -- GETTER --
     *
     @see #getEvictionGeneration()
     */
    @Getter
    private long contentGeneration;

    /**
     * Monotonic counter bumped every time a page is evicted, invalidating any placement that
     * referenced it (its layer index now belongs to a different page — see
     * {@link #glyphIndex}'s javadoc).
     *
     * <p>Tracked separately from {@link #contentGeneration} because the two invalidate
     * different things. New content only matters to a consumer still holding bitmap-fallback
     * placements it would like to upgrade; an eviction matters to <em>every</em> consumer,
     * including one whose placements are already uniformly distance-field. On the default
     * unbounded atlas nothing is ever evicted, so this stays {@code 0} for the process
     * lifetime and fully-converged cache entries never need re-resolving at all.</p>
     * -- GETTER --
     *

     */
    @Getter
    private long evictionGeneration;

    // ── Constructors ──────────────────────────────────────────────────
    //
    // No public constructors — every real (non-test) atlas must go through
    // createForPagedRegistry (which always supplies a positive maxPages, since
    // a real GL_TEXTURE_2D_ARRAY has a fixed depth), and every test atlas
    // through createForTest (which sets skipGlUpload=true). A bare public
    // constructor could not enforce either of those without duplicating the
    // validation already centralized in the 7-arg master below, so the surface
    // is factories-only.

    /**
     * Internal constructor with skip-GL-upload flag for testing. Unbounded page budget.
     * Real (non-test) callers reaching this overload get {@link #DEFAULT_MAX_PAGES}
     * instead of unbounded, since a real array must have a fixed depth — see the
     * 7-arg master constructor's validation.
     */
    CgGlyphAtlas(int pageWidth, int pageHeight, Type type,
                 PackerFactory packerFactory, boolean skipGlUpload, int spacingPx) {
        this(pageWidth, pageHeight, type, packerFactory, skipGlUpload, spacingPx, UNBOUNDED_PAGES);
    }

    /**
     * Internal constructor with skip-GL-upload flag and an explicit page budget.
     */
    CgGlyphAtlas(int pageWidth, int pageHeight, Type type, PackerFactory packerFactory, boolean skipGlUpload, int spacingPx,
                 int maxPages) {
        if (pageWidth <= 0 || pageHeight <= 0) 
            throw new IllegalArgumentException("Page dimensions must be positive, got: " + pageWidth + "x" + pageHeight);
        
        this.pageWidth = pageWidth;
        this.pageHeight = pageHeight;
        this.type = type;
        this.packerFactory = packerFactory;
        this.skipGlUpload = skipGlUpload;
        this.spacingPx = spacingPx;
        this.maxPages = maxPages;
        this.pages = new ArrayList<CgGlyphAtlasPage>();
        this.deleted = false;
        if (skipGlUpload) {
            this.arrayTexture = null;
        } else {
            this.capacity = INITIAL_PAGES;
            this.arrayTexture = CgTexture2DArray.allocateEmpty(pageWidth, pageHeight, capacity,
                    // MSDF and MTSDF both allocate RGBA8 — see class javadoc "Format".
                    type == Type.BITMAP ? CgTextureSpec.R8_NEAREST : CgTextureSpec.RGBA8_LINEAR);
        }
    }

    /**
     * Creates a test-mode paged atlas that skips all GL calls. Unbounded page budget.
     */
    public static CgGlyphAtlas createForTest(int pageWidth, int pageHeight, Type type) {
        // MaxRects, matching production. Tests used to build pages with a guillotine packer while
        // every real atlas used MaxRects, so nothing exercised the packer that actually ships.
        return new CgGlyphAtlas(pageWidth, pageHeight, type, MAX_RECTS_FACTORY, true, CgPackingStrategy.DEFAULT_SPACING_PX);
    }

    /**
     * Creates a test-mode paged atlas with an explicit page budget, so eviction
     * behavior can be exercised without allocating dozens of real pages.
     */
    public static CgGlyphAtlas createForTest(int pageWidth, int pageHeight, Type type, PackerFactory packerFactory, int maxPages) {
        return new CgGlyphAtlas(pageWidth, pageHeight, type, packerFactory, true, CgPackingStrategy.DEFAULT_SPACING_PX, maxPages);
    }

    /**
     * Creates a paged atlas for live registry use (with a real backing array
     * texture). Unbounded — see class javadoc "Backing Array Growth".
     */
    public static CgGlyphAtlas createForRegistry(int pageWidth, int pageHeight, Type type) {
        return createForRegistry(pageWidth, pageHeight, type, CgPackingStrategy.DEFAULT_SPACING_PX, UNBOUNDED_PAGES);
    }

    public static CgGlyphAtlas createForRegistry(int pageWidth, int pageHeight, Type type, int spacingPx) {
        return createForRegistry(pageWidth, pageHeight, type, spacingPx, UNBOUNDED_PAGES);
    }

    /**
     * Creates a paged atlas for live registry use with an explicit page budget.
     * Pass {@link #UNBOUNDED_PAGES} (the default used by the other overloads)
     * to grow forever and never evict, or a positive bound (e.g.
     * {@link #DEFAULT_MAX_PAGES}) to opt into eviction once the array grows to
     * that bound — see class javadoc "Backing Array Growth".
     */
    public static CgGlyphAtlas createForRegistry(int pageWidth, int pageHeight, Type type, int spacingPx, int maxPages) {
        // MaxRects for every atlas type, bitmap included.
        //
        // Bitmap used to get CgGuillotinePacker while only distance-field atlases got MaxRects.
        // That split was inherited from when guillotine was kept for msdf-atlas-gen output
        // parity; distance-field atlases have since moved to MaxRects, so the parity rationale
        // no longer applied to the one path still using it.
        //
        // Measured (M+ 1p, 720 bitmap glyphs at 48px, 1024px pages): the first page's fill went
        // from roughly 70% under guillotine to 87.6% under MaxRects — 491 glyphs on page 0
        // instead of ~390.
        //
        // Note what that does and does not buy, because the obvious metric is misleading here.
        // Total glyph area is a property of the glyph set (1,351,534 px), and page count was
        // already at its geometric minimum (2 pages = 2,097,152 px, and one page cannot hold
        // 1,351,534). So MEAN utilisation across pages is pinned at 64.4% for any packer that
        // fits in two pages, and does not move at all here — the two pages simply must sum to
        // 128.9% however the glyphs are distributed. What improves is headroom: a denser first
        // page means the glyph set can grow considerably further before a third page is needed.
        // Judge a packer by page count at the margin and by fill of non-final pages, never by
        // mean utilisation when page count is already minimal.
        return new CgGlyphAtlas(pageWidth, pageHeight, type, MAX_RECTS_FACTORY, false, spacingPx, maxPages);
    }

    // ── Core API ───────────────────────────────────────────────────────

    /**
     * Looks up a cached glyph placement — {@code O(1)} via {@link #glyphIndex}, not a
     * per-page scan. Also resolves glyphs previously recorded as empty via
     * {@link #markEmpty} (see {@link #emptyGlyphs}), so a space is answered from cache
     * instead of being re-rasterized on every miss.
     *
     * @param key          glyph key
     * @param currentFrame current frame for LRU tracking
     * @return the placement (possibly zero-geometry, for a known-empty glyph), or
     *         {@code null} if this glyph has not been generated yet
     */
    public CgGlyphPlacement get(CgGlyphKey key, long currentFrame) {
        CgGlyphAtlasPage page = glyphIndex.get(key);
        if (page != null) return page.get(key, currentFrame);
        // Checked second: a real placement always wins, and this map only ever holds keys
        // that genuinely cannot occupy pixels, so the two are disjoint in practice.
        return emptyGlyphs.get(key);
    }

    /**
     * Records that {@code key} has no renderable geometry, so subsequent {@link #get} calls
     * answer {@code O(1)} from cache rather than re-running generation/rasterization that is
     * guaranteed to produce nothing. See {@link #emptyGlyphs} for why this is worth doing.
     *
     * <p>Idempotent, and cheap to call speculatively — a key already known empty is not
     * re-recorded and does not bump {@link #getContentGeneration()}.</p>
     *
     * @return the zero-geometry placement now cached for {@code key}
     */
    public CgGlyphPlacement markEmpty(CgGlyphKey key) {
        checkNotDeleted();
        CgGlyphPlacement existing = emptyGlyphs.get(key);
        if (existing != null) return existing;

        CgGlyphPlacement empty = new CgGlyphPlacement(
                key, 0 /* no texture */, 0 /* no page */, type,
                0f, 0f, 0f, 0f,   // planeLeft/Bottom/Right/Top — all zero => hasGeometry() == false
                0, 0, 0, 0,       // atlasLeft/Bottom/Right/Top — occupies no texels
                0f, 0f, 0f, 0f,   // u0/v0/u1/v1
                0f);              // pxRange
        emptyGlyphs.put(key, empty);
        contentGeneration++;
        return empty;
    }

    /**
     * Allocates and uploads a bitmap glyph, creating a new page if needed.
     *
     * <p>Tries the hot page first, then scans earlier pages, then creates a
     * new page if no existing page has room.</p>
     *
     * @return the placement, or {@code null} if the glyph is larger than a page
     */
    public CgGlyphPlacement allocateBitmap(CgGlyphKey key, byte[] bitmapData,
                                            int width, int height,
                                            float bearingX, float bearingY,
                                            float metricsWidth, float metricsHeight,
                                            long currentFrame) {
        checkNotDeleted();

        // Fast path: check if already present
        CgGlyphPlacement existing = get(key, currentFrame);
        if (existing != null) {
            return existing;
        }

        // Try existing pages (hot page first)
        // Oldest page first. Newest-first (the previous order) meant that once a new page
        // existed with room, every subsequent glyph landed there and the gaps left in earlier
        // pages were never revisited -- so a 24-page atlas could be carrying 20+ usable holes
        // while still allocating page 25. Scanning oldest-first refills those holes; pages that
        // cannot possibly take the glyph reject in O(1) via CgPackingStrategy#mayFit, so this
        // does not become a free-list walk per page per glyph.
        for (int i = 0; i < pages.size(); i++) {
            CgGlyphAtlasPage page = pages.get(i);
            CgGlyphPlacement placement = page.allocateBitmap(
                    key, bitmapData, width, height,
                    bearingX, bearingY, metricsWidth, metricsHeight, currentFrame);
            if (placement != null) {
                glyphIndex.put(key, page);
                contentGeneration++;
                return placement;
            }
        }

        // Create a new page and try again
        CgGlyphAtlasPage newPage = createPage();
        CgGlyphPlacement placement = newPage.allocateBitmap(
                key, bitmapData, width, height,
                bearingX, bearingY, metricsWidth, metricsHeight, currentFrame);
        if (placement != null) {
            glyphIndex.put(key, newPage);
            contentGeneration++;
        }
        return placement;
    }

    /**
     * Allocates and uploads an MSDF glyph, creating a new page if needed.
     *
     * @return the placement, or {@code null} if the glyph is larger than a page
     */
    public CgGlyphPlacement allocateMsdf(CgGlyphKey key, float[] msdfData,
                                          int width, int height,
                                          float bearingX, float bearingY,
                                          float planeLeft, float planeBottom,
                                          float planeRight, float planeTop,
                                          float metricsWidth, float metricsHeight,
                                          float pxRange,
                                          long currentFrame) {
        checkNotDeleted();

        CgGlyphPlacement existing = get(key, currentFrame);
        if (existing != null) {
            return existing;
        }

        // Oldest page first -- see the matching comment in allocateBitmap.
        for (int i = 0; i < pages.size(); i++) {
            CgGlyphAtlasPage page = pages.get(i);
            CgGlyphPlacement placement = page.allocateMsdf(
                    key, msdfData, width, height,
                    bearingX, bearingY,
                    planeLeft, planeBottom, planeRight, planeTop,
                    metricsWidth, metricsHeight, pxRange, currentFrame);
            if (placement != null) {
                glyphIndex.put(key, page);
                contentGeneration++;
                return placement;
            }
        }

        CgGlyphAtlasPage newPage = createPage();
        CgGlyphPlacement placement = newPage.allocateMsdf(
                key, msdfData, width, height,
                bearingX, bearingY,
                planeLeft, planeBottom, planeRight, planeTop,
                metricsWidth, metricsHeight, pxRange, currentFrame);
        if (placement != null) {
            glyphIndex.put(key, newPage);
            contentGeneration++;
        }
        return placement;
    }

    /**
     * Returns this atlas's reserved opaque-white texel UV rect + page identity, allocating
     * it on first call (creating the first page if none exist yet). Used by
     * {@code CgResolvedGlyphs} to draw underline/strikethrough decoration quads through the
     * same material/page already bound for this atlas's glyphs — see
     * {@link CgGlyphAtlasPage#reserveWhiteTexel()}.
     *
     * <p>{@code pxRange} is the caller's atlas-generation pxRange (0 for a bitmap atlas, the
     * real {@code CgMsdfAtlasConfig.pxRange()} for an MSDF one) — carried on the returned
     * {@link WhiteTexel} purely so a decoration quad's batch key (mode/textureId/pxRange) comes
     * out byte-for-byte identical to a real glyph's from this same atlas. That's what lets a
     * decoration interleave into the exact same sorted batch as its font's glyphs with no extra
     * material transition, instead of forcing one just because pxRange didn't match (a flat
     * white-texel sample gives full opacity regardless of what pxRange is — see
     * {@code text.shader}'s MSDF branch — so this costs nothing to get right).</p>
     *
     * @return the reserved texel's page identity, UV rect, and batch-key fields
     */
    public WhiteTexel reserveWhiteTexel(float pxRange) {
        checkNotDeleted();
        boolean isDistanceField = type != Type.BITMAP;
        if (whiteTexelPage != null) {
            float[] uv = whiteTexelPage.reserveWhiteTexel();
            return new WhiteTexel(whiteTexelPage.getTextureId(), whiteTexelPage.getPageIndex(),
                    uv[0], uv[1], uv[2], uv[3], isDistanceField, pxRange);
        }
        CgGlyphAtlasPage page = pages.isEmpty() ? createPage() : pages.get(pages.size() - 1);
        float[] uv = page.reserveWhiteTexel();
        if (uv == null) {
            page = createPage();
            uv = page.reserveWhiteTexel();
        }
        whiteTexelPage = page;
        return new WhiteTexel(page.getTextureId(), page.getPageIndex(), uv[0], uv[1], uv[2], uv[3],
                isDistanceField, pxRange);
    }

    /**
     * Result of {@link #reserveWhiteTexel(float)} — a flat-fill sample point for decoration
     * quads, carrying its own {@code isDistanceField}/{@code pxRange} so it packs into the
     * exact same batch-sort-key shape a {@code CgGlyphPlacement} from this atlas would.
     */
    public record WhiteTexel(int atlasTextureId, int atlasPageIndex, float u0, float v0, float u1, float v1,
                              boolean isDistanceField, float pxRange) { }

    // ── Page queries ──────────────────────────────────────────────────

    /** Returns the number of pages currently allocated. */
    public int getPageCount() {
        return pages.size();
    }

    /**
     * Returns the first page with glyphs, or {@code null} if none exist.
     * For backward compatibility with single-page dump code.
     */
    public CgGlyphAtlasPage getFirstPopulatedPage() {
        for (CgGlyphAtlasPage page : pages) 
            if (page.getSlotCount() > 0) return page;
        
        return null;
    }

    /**
     * Returns the total number of glyphs across all pages.
     */
    public int getTotalSlotCount() {
        int total = 0;
        for (CgGlyphAtlasPage page : pages) 
            total += page.getSlotCount();
        
        return total;
    }

    // ── LRU tick ───────────────────────────────────────────────────────

    /**
     * Advances the LRU frame counter. Per-slot/per-page recency is tracked
     * directly by {@code allocate*}/{@code get} instead, so this remains a
     * no-op; page-budget eviction happens lazily in {@link #createPage()}
     * only when a new page is actually about to be allocated, not on every tick.
     */
    public void tickFrame(long frame) {
        // Intentionally a no-op — see javadoc.
    }

    // ── Deletion ──────────────────────────────────────────────────────

    /**
     * Deletes every page's bookkeeping and the shared array texture (if any).
     */
    public void delete() {
        if (deleted) return;

        for (CgGlyphAtlasPage page : pages)
            if (!page.isDeleted()) page.delete();

        pages.clear();
        glyphIndex.clear();
        emptyGlyphs.clear();
        if (arrayTexture != null && !arrayTexture.isDeleted()) arrayTexture.delete();
        deleted = true;
    }

    // ── Internal: page allocation ─────────────────────────────────────

    /**
     * Creates a new page. Three cases, checked in order:
     * <ol>
     *   <li>The array still has spare capacity ({@code pages.size() < capacity})
     *       — assign the next unused index via {@code pages.size()}, no GL work.</li>
     *   <li>The array is full but {@code maxPages} is {@link #UNBOUNDED_PAGES} or
     *       hasn't been reached yet — grow it (see {@link #growCapacity()}), then
     *       assign the next index same as above.</li>
     *   <li>The array is full <em>and</em> a positive {@code maxPages} has been
     *       reached — evict the coldest page and <strong>reuse its exact layer
     *       index</strong>. Unlike the old independent-texture model (where a
     *       brand-new page always got a fresh, never-reused index, since indices
     *       were just bookkeeping metadata), a layer index here is a scarce
     *       physical resource once the ceiling is reached, so eviction must free
     *       one for immediate reuse rather than retiring it.</li>
     * </ol>
     * Since eviction only ever happens exactly when already at a positive
     * {@code maxPages}, and always pairs one eviction with the one creation
     * that triggered it, every index in {@code [0, maxPages)} is held by
     * exactly one live page at all times once the array reaches full capacity.
     */
    private CgGlyphAtlasPage createPage() {
        if (arrayTexture != null && pages.size() >= capacity && (maxPages == UNBOUNDED_PAGES || capacity < maxPages)) 
            growCapacity();
        

        int layerIndex;
        if (maxPages > UNBOUNDED_PAGES && pages.size() >= maxPages) 
            layerIndex = evictColdestPageAndFreeLayer();
        else layerIndex = pages.size();
        

        CgPackingStrategy packer = packerFactory.create(pageWidth, pageHeight, spacingPx);

        CgGlyphAtlasPage page;
        if (skipGlUpload) page = CgGlyphAtlasPage.createForTest(pageWidth, pageHeight, type, layerIndex, packer);
        else              page = CgGlyphAtlasPage.create(pageWidth, pageHeight, type, layerIndex, arrayTexture, packer);
        

        pages.add(page);
        LOGGER.fine("[AtlasDiag] Allocated atlas page/layer " + layerIndex
                + " (" + pageWidth + "x" + pageHeight + " " + type + "), total pages now " + pages.size());
        return page;
    }

    /**
     * Grows {@link #capacity} by 1.5x — uncapped when {@link #maxPages} is
     * {@link #UNBOUNDED_PAGES} (the default), otherwise capped at {@code maxPages}
     * — and grows {@link #arrayTexture} to match; see class javadoc "Backing
     * Array Growth". {@code Math.max(capacity + 1, ...)} guards against integer
     * rounding stalling growth at small capacities (e.g. {@code ceil(1 * 1.5) = 2},
     * which the guard is not actually needed for, but keeps this correct by
     * construction rather than by coincidence of the current {@link #INITIAL_PAGES}).
     * Only called when {@code arrayTexture != null} and there is still room to
     * grow (unbounded, or {@code capacity < maxPages}).
     */
    /**
     * Layers added per growth once past {@link #GEOMETRIC_GROWTH_LIMIT_LAYERS} — a fixed step,
     * not a multiplier.
     *
     * <p>Geometric growth is the right default when elements are cheap, but an atlas layer is
     * 1-2 MB of GPU memory, so a 1.5x/2x factor makes the over-allocation <em>proportional to
     * atlas size</em>: a 24-layer atlas would jump to 36-48 and sit on 12-24 MB nobody asked
     * for, and a 250-layer one would waste hundreds of MB. An additive step bounds the waste to
     * a <strong>constant</strong> — at most {@code ADDITIVE_GROWTH_LAYERS} layers, regardless of
     * how large the atlas gets.</p>
     *
     * <p>The trade geometric growth normally buys is fewer (expensive) growth events. That
     * trade no longer applies: growth is now a GPU-side copy rather than a full CPU->GPU replay
     * of the entire atlas (see {@link CgTexture2DArray#growLayers}), so each event is cheap and
     * minimising wasted memory is the better use of the knob.</p>
     */
    private static final int ADDITIVE_GROWTH_LAYERS = 4;

    /**
     * Below this many layers, grow geometrically (1.5x) — at that size the absolute waste is
     * trivial (a handful of MB at most) and it gets a small atlas to its working size in fewer
     * steps. Past it, {@link #ADDITIVE_GROWTH_LAYERS} takes over.
     */
    private static final int GEOMETRIC_GROWTH_LIMIT_LAYERS = 8;

    private void growCapacity() {
        int grown = capacity < GEOMETRIC_GROWTH_LIMIT_LAYERS
                ? Math.max(capacity + 1, (capacity * 3 + 1) / 2)
                : capacity + ADDITIVE_GROWTH_LAYERS;
        int newCapacity = maxPages == UNBOUNDED_PAGES ? grown : Math.min(maxPages, grown);
        // Instrumented to test whether array growth is what's behind the isolated 30-66ms
        // single-glyph commit stalls: growLayers() reallocates the array and re-uploads every
        // existing layer from its CPU mirror, so its cost scales with current atlas size and
        // gets billed to whichever glyph happened to trigger it.
        try (CgProfiler.Scope ignored = CgProfiler.scope("atlas.growCapacity")) {
            CgProfiler.count("atlas.growCapacity.count");
            CgProfiler.sample("atlas.growCapacity.fromLayers", capacity);
            CgProfiler.sample("atlas.growCapacity.toLayers", newCapacity);
            arrayTexture.growLayers(newCapacity);
        }
        capacity = newCapacity;
    }

    /**
     * Evicts the page with the lowest {@link CgGlyphAtlasPage#getLastTouchedFrame()},
     * removes it from {@link #pages}, and returns its now-free layer index for
     * immediate reuse by the caller. Every glyph that was resident on it
     * becomes a cache miss on its next lookup and is re-rasterized/re-allocated
     * like any other uncached glyph — the same fallback path already used for a
     * glyph that was never cached, so no special invalidation signal is needed
     * by callers. The evicted layer's old pixel content is left in place in the
     * array texture (not cleared) — harmless, since nothing holds a
     * {@link CgGlyphPlacement} pointing at the evicted page's old UV rects
     * anymore, and the new page's uploads only ever read back what they
     * themselves wrote.
     *
     * @return the freed layer index
     */
    private int evictColdestPageAndFreeLayer() {
        int coldestIndex = -1;
        long coldestFrame = Long.MAX_VALUE;
        for (int i = 0; i < pages.size(); i++) {
            CgGlyphAtlasPage candidate = pages.get(i);
            if (candidate == whiteTexelPage) continue;
            
            long touched = candidate.getLastTouchedFrame();
            if (coldestIndex == -1 || touched < coldestFrame) {
                coldestFrame = touched;
                coldestIndex = i;
            }
        }
        if (coldestIndex == -1) {
            // Every page holds the reserved white texel (single-page atlas) — nothing
            // evictable without breaking decoration rendering; fall back to page 0.
            coldestIndex = 0;
        }

        CgGlyphAtlasPage evicted = pages.remove(coldestIndex);
        if (evicted == whiteTexelPage) whiteTexelPage = null;
        
        // Must happen before evicted.delete(), which clears the page's own key set —
        // otherwise glyphIndex would keep stale entries pointing at a page whose layer
        // index is about to be handed to a brand-new page with completely different glyphs.
        for (CgGlyphKey key : evicted.getGlyphKeys()) glyphIndex.remove(key);
        
        LOGGER.fine("[AtlasDiag] Evicting atlas page/layer " + evicted.getPageIndex()
                + " (last touched frame " + coldestFrame + ") — page budget " + maxPages + " reached");
        evicted.delete();
        // Any cached resolve holding a placement from this page is now dangling — its layer
        // index is about to be reused by a different page. See evictionGeneration's javadoc.
        evictionGeneration++;
        return evicted.getPageIndex();
    }

    private void checkNotDeleted() {
        if (deleted) throw new IllegalStateException("Paged atlas has been deleted");
        
    }
}
