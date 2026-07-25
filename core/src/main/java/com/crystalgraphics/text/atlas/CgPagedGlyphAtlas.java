package com.crystalgraphics.text.atlas;

import com.crystalgraphics.api.font.CgGlyphKey;
import com.crystalgraphics.api.font.CgGlyphPlacement;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.gl.texture.CgTexture2DArray;
import com.crystalgraphics.text.atlas.packing.CgGuillotinePacker;
import com.crystalgraphics.text.atlas.packing.MaxRectsPacker;
import com.crystalgraphics.text.atlas.packing.CgPackingStrategy;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
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
 * (MSDF and MTSDF alike) allocate their array as {@code RGBA16F} — MSDF simply
 * leaves the alpha channel unused/undefined at upload time (the shader only
 * ever reads {@code .rgb} in MSDF mode), avoiding a separate 3-component array
 * format with its own driver-alignment history for a channel count that's
 * different only by convention, not by anything the fragment shader needs.</p>
 *
 * <h3>Page Allocation Policy</h3>
 * <ol>
 *   <li>Try the most recently allocated page (hot page).</li>
 *   <li>If the glyph does not fit, scan earlier pages for available space.</li>
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
 * {@link #createForPagedRegistry(int, int, CgGlyphAtlas.Type)} /
 * {@link #createForPagedRegistry(int, int, CgGlyphAtlas.Type, int)}) — the
 * array simply keeps growing 1.5x forever and no page is ever evicted. This
 * matters for scripts with very large glyph inventories (CJK fonts routinely
 * need far more than a couple dozen pages at typical atlas sizes); evicting
 * pages there would mean constant re-rasterization churn instead of a one-time
 * cost. Callers with an actual memory ceiling to enforce can still opt into
 * the old bounded behavior via
 * {@link #createForPagedRegistry(int, int, CgGlyphAtlas.Type, int, int)} with
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
 * <p>Each page uses a {@link CgPackingStrategy} instance. By default, new pages
 * use {@link CgGuillotinePacker} for MSDF parity. Callers can supply a custom
 * strategy factory via the constructor.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>Not thread-safe. Must only be used from the GL context thread.</p>
 *
 * @see CgGlyphAtlasPage
 * @see CgPackingStrategy
 */
public class CgPagedGlyphAtlas {

    private static final Logger LOGGER = Logger.getLogger(CgPagedGlyphAtlas.class.getName());

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

    /** Default packer factory using upstream-parity guillotine packing. */
    public static final PackerFactory GUILLOTINE_FACTORY = new PackerFactory() {
        @Override
        public CgPackingStrategy create(int pageWidth, int pageHeight, int spacingPx) {
            return new CgGuillotinePacker(pageWidth, pageHeight);
        }
    };

    public static final PackerFactory MAX_RECTS_FACTORY = (pageWidth, pageHeight, spacingPx) ->
            new MaxRectsPacker(pageWidth, pageHeight);

    /**
     * A reasonable non-default page budget for callers of
     * {@link #createForPagedRegistry(int, int, CgGlyphAtlas.Type, int, int)}
     * who actually want eviction (see class javadoc "Backing Array Growth") —
     * NOT used automatically by any factory. {@link #UNBOUNDED_PAGES} is the
     * real default everywhere ({@link #createForTest} and the no-{@code maxPages}
     * {@link #createForPagedRegistry} overloads alike).
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
    private final CgGlyphAtlas.Type type;
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
    CgPagedGlyphAtlas(int pageWidth, int pageHeight, CgGlyphAtlas.Type type,
                      PackerFactory packerFactory, boolean skipGlUpload, int spacingPx) {
        this(pageWidth, pageHeight, type, packerFactory, skipGlUpload, spacingPx, UNBOUNDED_PAGES);
    }

    /**
     * Internal constructor with skip-GL-upload flag and an explicit page budget.
     */
    CgPagedGlyphAtlas(int pageWidth, int pageHeight, CgGlyphAtlas.Type type,
                      PackerFactory packerFactory, boolean skipGlUpload, int spacingPx,
                      int maxPages) {
        if (pageWidth <= 0 || pageHeight <= 0) {
            throw new IllegalArgumentException(
                    "Page dimensions must be positive, got: " + pageWidth + "x" + pageHeight);
        }
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
                    // MSDF and MTSDF both allocate RGBA16F — see class javadoc "Format".
                    type == CgGlyphAtlas.Type.BITMAP ? CgTextureSpec.R8_NEAREST : CgTextureSpec.RGBA16F_LINEAR);
        }
    }

    /**
     * Creates a test-mode paged atlas that skips all GL calls. Unbounded page budget.
     */
    public static CgPagedGlyphAtlas createForTest(int pageWidth, int pageHeight,
                                                   CgGlyphAtlas.Type type) {
        return new CgPagedGlyphAtlas(pageWidth, pageHeight, type, GUILLOTINE_FACTORY, true,
                CgPackingStrategy.DEFAULT_SPACING_PX);
    }

    /**
     * Creates a test-mode paged atlas with an explicit page budget, so eviction
     * behavior can be exercised without allocating dozens of real pages.
     */
    public static CgPagedGlyphAtlas createForTest(int pageWidth, int pageHeight,
                                                     CgGlyphAtlas.Type type,
                                                     PackerFactory packerFactory,
                                                     int maxPages) {
        return new CgPagedGlyphAtlas(pageWidth, pageHeight, type, packerFactory, true,
                CgPackingStrategy.DEFAULT_SPACING_PX, maxPages);
    }

    /**
     * Creates a paged atlas for live registry use (with a real backing array
     * texture). Unbounded — see class javadoc "Backing Array Growth".
     */
    public static CgPagedGlyphAtlas createForPagedRegistry(int pageWidth, int pageHeight,
                                                             CgGlyphAtlas.Type type) {
        return createForPagedRegistry(pageWidth, pageHeight, type,
                CgPackingStrategy.DEFAULT_SPACING_PX, UNBOUNDED_PAGES);
    }

    public static CgPagedGlyphAtlas createForPagedRegistry(int pageWidth, int pageHeight,
                                                             CgGlyphAtlas.Type type,
                                                             int spacingPx) {
        return createForPagedRegistry(pageWidth, pageHeight, type, spacingPx, UNBOUNDED_PAGES);
    }

    /**
     * Creates a paged atlas for live registry use with an explicit page budget.
     * Pass {@link #UNBOUNDED_PAGES} (the default used by the other overloads)
     * to grow forever and never evict, or a positive bound (e.g.
     * {@link #DEFAULT_MAX_PAGES}) to opt into eviction once the array grows to
     * that bound — see class javadoc "Backing Array Growth".
     */
    public static CgPagedGlyphAtlas createForPagedRegistry(int pageWidth, int pageHeight,
                                                             CgGlyphAtlas.Type type,
                                                             int spacingPx, int maxPages) {
        PackerFactory factory = type == CgGlyphAtlas.Type.BITMAP ? GUILLOTINE_FACTORY : MAX_RECTS_FACTORY;
        return new CgPagedGlyphAtlas(pageWidth, pageHeight, type, factory, false,
                spacingPx, maxPages);
    }

    // ── Core API ───────────────────────────────────────────────────────

    /**
     * Looks up a cached glyph placement across all pages.
     *
     * @param key          glyph key
     * @param currentFrame current frame for LRU tracking
     * @return the placement, or {@code null} if not found in any page
     */
    public CgGlyphPlacement get(CgGlyphKey key, long currentFrame) {
        // Search pages in reverse (hot page first)
        for (int i = pages.size() - 1; i >= 0; i--) {
            CgGlyphPlacement placement = pages.get(i).get(key, currentFrame);
            if (placement != null) {
                return placement;
            }
        }
        return null;
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
        for (int i = pages.size() - 1; i >= 0; i--) {
            CgGlyphPlacement placement = pages.get(i).allocateBitmap(
                    key, bitmapData, width, height,
                    bearingX, bearingY, metricsWidth, metricsHeight, currentFrame);
            if (placement != null) {
                return placement;
            }
        }

        // Create a new page and try again
        CgGlyphAtlasPage newPage = createPage();
        return newPage.allocateBitmap(
                key, bitmapData, width, height,
                bearingX, bearingY, metricsWidth, metricsHeight, currentFrame);
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

        for (int i = pages.size() - 1; i >= 0; i--) {
            CgGlyphPlacement placement = pages.get(i).allocateMsdf(
                    key, msdfData, width, height,
                    bearingX, bearingY,
                    planeLeft, planeBottom, planeRight, planeTop,
                    metricsWidth, metricsHeight, pxRange, currentFrame);
            if (placement != null) {
                return placement;
            }
        }

        CgGlyphAtlasPage newPage = createPage();
        return newPage.allocateMsdf(
                key, msdfData, width, height,
                bearingX, bearingY,
                planeLeft, planeBottom, planeRight, planeTop,
                metricsWidth, metricsHeight, pxRange, currentFrame);
    }

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
        if (arrayTexture != null && pages.size() >= capacity
                && (maxPages == UNBOUNDED_PAGES || capacity < maxPages)) {
            growCapacity();
        }

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
    private void growCapacity() {
        int grown = Math.max(capacity + 1, (capacity * 3 + 1) / 2);
        int newCapacity = maxPages == UNBOUNDED_PAGES ? grown : Math.min(maxPages, grown);
        arrayTexture.growLayers(newCapacity);
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
        int coldestIndex = 0;
        long coldestFrame = pages.get(0).getLastTouchedFrame();
        for (int i = 1; i < pages.size(); i++) {
            long touched = pages.get(i).getLastTouchedFrame();
            if (touched < coldestFrame) {
                coldestFrame = touched;
                coldestIndex = i;
            }
        }

        CgGlyphAtlasPage evicted = pages.remove(coldestIndex);
        LOGGER.fine("[AtlasDiag] Evicting atlas page/layer " + evicted.getPageIndex()
                + " (last touched frame " + coldestFrame + ") — page budget " + maxPages + " reached");
        evicted.delete();
        return evicted.getPageIndex();
    }

    private void checkNotDeleted() {
        if (deleted) throw new IllegalStateException("Paged atlas has been deleted");
        
    }
}
