package com.crystalgraphics.text.atlas;

import com.crystalgraphics.api.font.CgGlyphKey;
import com.crystalgraphics.api.font.CgGlyphPlacement;
import com.crystalgraphics.text.atlas.packing.CgGuillotinePacker;
import com.crystalgraphics.text.atlas.packing.MaxRectsPacker;
import com.crystalgraphics.text.atlas.packing.CgPackingStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Multi-page atlas manager for glyph storage.
 *
 * <p>Replaces the single {@link CgGlyphAtlas}-per-bucket model with a paged
 * system. When a glyph does not fit on the current page, the manager allocates
 * a new page instead of evicting existing glyphs. This ensures placement
 * stability — once a glyph is allocated, its position and page never change.</p>
 *
 * <h3>Page Allocation Policy</h3>
 * <ol>
 *   <li>Try the most recently allocated page (hot page).</li>
 *   <li>If the glyph does not fit, scan earlier pages for available space.</li>
 *   <li>If no existing page fits, create a new page.</li>
 * </ol>
 *
 * <h3>Page Budget / Eviction</h3>
 * <p>Unbounded page growth is a real leak for continuously-varying text (e.g.
 * a scrolling/animated HUD generates a new sub-pixel-bucket glyph variant on
 * every distinct fractional offset it passes through). When creating a new
 * page would exceed {@code maxPages}, the atlas evicts the <strong>coldest
 * whole page</strong> first — the one with the lowest {@link
 * CgGlyphAtlasPage#getLastTouchedFrame()} — freeing all of its slots at once,
 * rather than the per-slot LRU eviction {@link CgGlyphAtlas} (the legacy
 * single-page model) does. This preserves placement stability for every
 * glyph on every page that survives; a glyph is only ever displaced by
 * losing its entire page, never individually. {@code maxPages <= 0} means
 * unbounded (the historical behavior, still the default for {@link
 * #createForTest}, matched by existing tests asserting unlimited paging
 * under test conditions) — production registries opt into a bounded budget
 * via {@link #createForPagedRegistry}.</p>
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
     * Default page budget for {@link #createForPagedRegistry} — the production
     * entry point. {@code 0} (unbounded) remains the default everywhere else,
     * including {@link #createForTest}.
     */
    public static final int DEFAULT_MAX_PAGES = 32;

    /** Sentinel meaning "no page budget — grow forever" (the historical behavior). */
    public static final int UNBOUNDED_PAGES = 0;

    // ── Instance fields ────────────────────────────────────────────────

    private final int pageWidth;
    private final int pageHeight;
    private final CgGlyphAtlas.Type type;
    private final PackerFactory packerFactory;
    private final boolean skipGlUpload;
    private final int spacingPx;
    private final int maxPages;

    private final List<CgGlyphAtlasPage> pages;
    private boolean deleted;

    // Monotonic — never reused, even after eviction removes a page from `pages`.
    // Reusing pages.size() as the next index would collide with a surviving
    // page's index once eviction has removed an earlier page from the list.
    private int nextPageIndex;

    // ── Constructors ──────────────────────────────────────────────────

    /**
     * Creates a new paged atlas with the default guillotine packer factory.
     *
     * @param pageWidth  page width in pixels
     * @param pageHeight page height in pixels
     * @param type       bitmap or MSDF
     */
    public CgPagedGlyphAtlas(int pageWidth, int pageHeight, CgGlyphAtlas.Type type) {
        this(pageWidth, pageHeight, type, GUILLOTINE_FACTORY, false, CgPackingStrategy.DEFAULT_SPACING_PX);
    }

    public CgPagedGlyphAtlas(int pageWidth, int pageHeight, CgGlyphAtlas.Type type, int spacingPx) {
        this(pageWidth, pageHeight, type, GUILLOTINE_FACTORY, false, spacingPx);
    }

    /**
     * Creates a new paged atlas with a custom packer factory.
     *
     * @param pageWidth     page width in pixels
     * @param pageHeight    page height in pixels
     * @param type          bitmap or MSDF
     * @param packerFactory factory for per-page packing strategies
     */
    public CgPagedGlyphAtlas(int pageWidth, int pageHeight, CgGlyphAtlas.Type type,
                             PackerFactory packerFactory) {
        this(pageWidth, pageHeight, type, packerFactory, false, CgPackingStrategy.DEFAULT_SPACING_PX);
    }

    public CgPagedGlyphAtlas(int pageWidth, int pageHeight, CgGlyphAtlas.Type type,
                             PackerFactory packerFactory, int spacingPx) {
        this(pageWidth, pageHeight, type, packerFactory, false, spacingPx);
    }

    /**
     * Internal constructor with skip-GL-upload flag for testing. Unbounded page budget.
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
     * Creates a test-mode paged atlas with a custom packer factory. Unbounded page budget.
     */
    public static CgPagedGlyphAtlas createForTest(int pageWidth, int pageHeight,
                                                     CgGlyphAtlas.Type type,
                                                     PackerFactory packerFactory) {
        return new CgPagedGlyphAtlas(pageWidth, pageHeight, type, packerFactory, true,
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
     * Creates a paged atlas for live registry use (with GL texture allocation),
     * bounded by {@link #DEFAULT_MAX_PAGES}.
     */
    public static CgPagedGlyphAtlas createForPagedRegistry(int pageWidth, int pageHeight,
                                                             CgGlyphAtlas.Type type) {
        return createForPagedRegistry(pageWidth, pageHeight, type,
                CgPackingStrategy.DEFAULT_SPACING_PX, DEFAULT_MAX_PAGES);
    }

    public static CgPagedGlyphAtlas createForPagedRegistry(int pageWidth, int pageHeight,
                                                             CgGlyphAtlas.Type type,
                                                             int spacingPx) {
        return createForPagedRegistry(pageWidth, pageHeight, type, spacingPx, DEFAULT_MAX_PAGES);
    }

    /**
     * Creates a paged atlas for live registry use with an explicit page budget.
     * Pass {@link #UNBOUNDED_PAGES} to opt out of eviction entirely.
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

    /** Returns the list of all pages (read-only intent). */
    public List<CgGlyphAtlasPage> getPages() {
        return pages;
    }

    /**
     * Returns the first page with glyphs, or {@code null} if none exist.
     * For backward compatibility with single-page dump code.
     */
    public CgGlyphAtlasPage getFirstPopulatedPage() {
        for (CgGlyphAtlasPage page : pages) {
            if (page.getSlotCount() > 0) {
                return page;
            }
        }
        return null;
    }

    /** Returns the page width in pixels. */
    public int getPageWidth() { return pageWidth; }

    /** Returns the page height in pixels. */
    public int getPageHeight() { return pageHeight; }

    /** Returns the atlas type. */
    public CgGlyphAtlas.Type getType() { return type; }

    /** Whether this atlas is deleted. */
    public boolean isDeleted() { return deleted; }

    /**
     * Returns the total number of glyphs across all pages.
     */
    public int getTotalSlotCount() {
        int total = 0;
        for (CgGlyphAtlasPage page : pages) {
            total += page.getSlotCount();
        }
        return total;
    }

    /** Returns the configured page budget, or {@link #UNBOUNDED_PAGES} if unbounded. */
    public int getMaxPages() { return maxPages; }

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
     * Deletes all pages and their GL textures.
     */
    public void delete() {
        if (deleted) {
            return;
        }
        for (CgGlyphAtlasPage page : pages) {
            if (!page.isDeleted()) {
                page.delete();
            }
        }
        pages.clear();
        deleted = true;
    }

    // ── Internal: page allocation ─────────────────────────────────────

    private CgGlyphAtlasPage createPage() {
        if (maxPages > UNBOUNDED_PAGES && pages.size() >= maxPages) {
            evictColdestPage();
        }

        int newPageIndex = nextPageIndex++;
        CgPackingStrategy packer = packerFactory.create(pageWidth, pageHeight, spacingPx);

        CgGlyphAtlasPage page;
        if (skipGlUpload) {
            page = CgGlyphAtlasPage.createForTest(
                    pageWidth, pageHeight, type, newPageIndex, packer);
        } else {
            page = CgGlyphAtlasPage.create(
                    pageWidth, pageHeight, type, newPageIndex, packer);
        }

        pages.add(page);
        LOGGER.fine("Allocated new atlas page " + newPageIndex
                + " (" + pageWidth + "x" + pageHeight + " " + type + ")");
        return page;
    }

    /**
     * Deletes the page with the lowest {@link CgGlyphAtlasPage#getLastTouchedFrame()}
     * and removes it from {@link #pages}. Every glyph that was resident on it
     * becomes a cache miss on its next lookup and is re-rasterized/re-allocated
     * like any other uncached glyph — the same fallback path already used for a
     * glyph that was never cached, so no special invalidation signal is needed
     * by callers.
     *
     * <p>No-op if there are no pages yet (can't happen via {@link #createPage()}'s
     * guard, since {@code maxPages > 0} implies at least one page must already
     * exist to trigger eviction, but kept defensive for direct callers.)</p>
     */
    private void evictColdestPage() {
        if (pages.isEmpty()) {
            return;
        }

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
        LOGGER.fine("Evicting atlas page " + evicted.getPageIndex()
                + " (last touched frame " + coldestFrame + ") — page budget " + maxPages + " reached");
        evicted.delete();
    }

    private void checkNotDeleted() {
        if (deleted) {
            throw new IllegalStateException("Paged atlas has been deleted");
        }
    }
}
