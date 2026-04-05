package io.github.somehussar.crystalgraphics.gl.text.atlas;

import io.github.somehussar.crystalgraphics.api.font.CgGlyphKey;
import io.github.somehussar.crystalgraphics.api.font.CgGlyphPlacement;
import io.github.somehussar.crystalgraphics.gl.text.CgGlyphAtlas;
import io.github.somehussar.crystalgraphics.text.atlas.CgGuillotinePacker;
import io.github.somehussar.crystalgraphics.text.atlas.CgPackingStrategy;

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
        CgPackingStrategy create(int pageWidth, int pageHeight);
    }

    /** Default packer factory using upstream-parity guillotine packing. */
    public static final PackerFactory GUILLOTINE_FACTORY = new PackerFactory() {
        @Override
        public CgPackingStrategy create(int pageWidth, int pageHeight) {
            return new CgGuillotinePacker(pageWidth, pageHeight);
        }
    };

    // ── Instance fields ────────────────────────────────────────────────

    private final int pageWidth;
    private final int pageHeight;
    private final CgGlyphAtlas.Type type;
    private final PackerFactory packerFactory;
    private final boolean skipGlUpload;

    private final List<CgGlyphAtlasPage> pages;
    private boolean deleted;

    // ── Constructors ──────────────────────────────────────────────────

    /**
     * Creates a new paged atlas with the default guillotine packer factory.
     *
     * @param pageWidth  page width in pixels
     * @param pageHeight page height in pixels
     * @param type       bitmap or MSDF
     */
    public CgPagedGlyphAtlas(int pageWidth, int pageHeight, CgGlyphAtlas.Type type) {
        this(pageWidth, pageHeight, type, GUILLOTINE_FACTORY, false);
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
        this(pageWidth, pageHeight, type, packerFactory, false);
    }

    /**
     * Internal constructor with skip-GL-upload flag for testing.
     */
    CgPagedGlyphAtlas(int pageWidth, int pageHeight, CgGlyphAtlas.Type type,
                      PackerFactory packerFactory, boolean skipGlUpload) {
        if (pageWidth <= 0 || pageHeight <= 0) {
            throw new IllegalArgumentException(
                    "Page dimensions must be positive, got: " + pageWidth + "x" + pageHeight);
        }
        this.pageWidth = pageWidth;
        this.pageHeight = pageHeight;
        this.type = type;
        this.packerFactory = packerFactory;
        this.skipGlUpload = skipGlUpload;
        this.pages = new ArrayList<CgGlyphAtlasPage>();
        this.deleted = false;
    }

    /**
     * Creates a test-mode paged atlas that skips all GL calls.
     */
    public static CgPagedGlyphAtlas createForTest(int pageWidth, int pageHeight,
                                                   CgGlyphAtlas.Type type) {
        return new CgPagedGlyphAtlas(pageWidth, pageHeight, type, GUILLOTINE_FACTORY, true);
    }

    /**
     * Creates a test-mode paged atlas with a custom packer factory.
     */
    public static CgPagedGlyphAtlas createForTest(int pageWidth, int pageHeight,
                                                    CgGlyphAtlas.Type type,
                                                    PackerFactory packerFactory) {
        return new CgPagedGlyphAtlas(pageWidth, pageHeight, type, packerFactory, true);
    }

    /**
     * Creates a paged atlas for live registry use (with GL texture allocation).
     */
    public static CgPagedGlyphAtlas createForPagedRegistry(int pageWidth, int pageHeight,
                                                            CgGlyphAtlas.Type type) {
        return new CgPagedGlyphAtlas(pageWidth, pageHeight, type, GUILLOTINE_FACTORY, false);
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
                    bearingX, bearingY, metricsWidth, metricsHeight, pxRange, currentFrame);
            if (placement != null) {
                return placement;
            }
        }

        CgGlyphAtlasPage newPage = createPage();
        return newPage.allocateMsdf(
                key, msdfData, width, height,
                bearingX, bearingY, metricsWidth, metricsHeight, pxRange, currentFrame);
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

    // ── LRU tick ───────────────────────────────────────────────────────

    /**
     * Advances the LRU frame counter. Currently a no-op as frame tracking
     * is per-slot, but reserved for future per-page maintenance.
     */
    public void tickFrame(long frame) {
        // Reserved for future per-page maintenance (e.g., page GC).
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
        int newPageIndex = pages.size();
        CgPackingStrategy packer = packerFactory.create(pageWidth, pageHeight);

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

    private void checkNotDeleted() {
        if (deleted) {
            throw new IllegalStateException("Paged atlas has been deleted");
        }
    }
}
