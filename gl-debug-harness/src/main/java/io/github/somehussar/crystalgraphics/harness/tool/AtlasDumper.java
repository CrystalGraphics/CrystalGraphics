package io.github.somehussar.crystalgraphics.harness.tool;

import io.github.somehussar.crystalgraphics.api.font.CgGlyphKey;
import io.github.somehussar.crystalgraphics.api.font.CgGlyphPlacement;
import io.github.somehussar.crystalgraphics.gl.text.CgGlyphAtlas;
import io.github.somehussar.crystalgraphics.gl.text.atlas.CgGlyphAtlasPage;
import io.github.somehussar.crystalgraphics.harness.util.ScreenshotUtil;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;

/**
 * Agent-debug tool #7: Dump a {@link CgGlyphAtlas} texture to PNG
 * plus a metadata summary.
 */
public final class AtlasDumper {

    private static final Logger LOGGER = Logger.getLogger(AtlasDumper.class.getName());

    /**
     * Dump an atlas texture to PNG and write a metadata summary.
     *
     * @param atlas     the glyph atlas
     * @param atlasSize atlas dimensions (assumed square)
     * @param format    GL internal format (e.g., GL_R8 = 0x8229)
     * @param outputDir output directory
     * @param baseName  base filename without extension
     */
    public static void dump(CgGlyphAtlas atlas, int atlasSize, int format,
                     String outputDir, String baseName) {
        if (atlas == null || atlas.isDeleted() || atlas.getTextureId() == 0) {
            LOGGER.warning("[AtlasDumper] Atlas is null, deleted, or has no texture");
            return;
        }

        // Dump texture
        String pngName = baseName + ".png";
        ScreenshotUtil.captureTexture(atlas.getTextureId(), atlasSize, atlasSize,
                format, outputDir, pngName);

        // Dump metadata
        String manifestName = baseName + "-manifest.txt";
        File manifestFile = new File(outputDir, manifestName);
        try {
            PrintWriter pw = new PrintWriter(new FileWriter(manifestFile));
            try {
                pw.println("=== Atlas Manifest ===");
                pw.println("Texture ID: " + atlas.getTextureId());
                pw.println("Size: " + atlasSize + "x" + atlasSize);
                pw.println("Type: " + atlas.getType());
                pw.println();
                pw.println("=== End Atlas Manifest ===");
            } finally {
                pw.close();
            }
            LOGGER.info("[AtlasDumper] Wrote manifest to " + manifestFile.getAbsolutePath());
        } catch (IOException e) {
            LOGGER.warning("[AtlasDumper] Failed to write manifest: " + e.getMessage());
        }
    }

    // ── Multi-page dump support ────────────────────────────────────────

    /**
     * Dumps all atlas pages for a given type with per-page PNGs and a combined manifest.
     *
     * <p>Filename convention (from atlas overhaul plan §7.8):</p>
     * <ul>
     *   <li>{@code msdf-atlas-dump-128px-page-0.png}</li>
     *   <li>{@code msdf-atlas-dump-128px-page-1.png}</li>
     *   <li>{@code msdf-atlas-dump-128px-manifest.txt} (combined manifest)</li>
     * </ul>
     *
     * @param pages       list of populated atlas pages to dump
     * @param typePrefix  filename prefix: "msdf-atlas-dump" or "bitmap-atlas-dump"
     * @param pxSizeSuffix  pixel size suffix: e.g., "128px"
     * @param format      GL internal format for texture readback
     * @param outputDir   output directory
     */
    public static void dumpAllPages(List<CgGlyphAtlas> pages,
                                    String typePrefix, String pxSizeSuffix,
                                    int format, String outputDir) {
        if (pages == null || pages.isEmpty()) {
            LOGGER.warning("[AtlasDumper] No pages to dump for " + typePrefix + "-" + pxSizeSuffix);
            return;
        }

        String basePrefix = typePrefix + "-" + pxSizeSuffix;

        // Dump each page as PNG
        for (int i = 0; i < pages.size(); i++) {
            CgGlyphAtlas page = pages.get(i);
            if (page == null || page.isDeleted() || page.getTextureId() == 0) {
                LOGGER.warning("[AtlasDumper] Skipping null/deleted page " + i);
                continue;
            }

            String pagePng = basePrefix + "-page-" + i + ".png";
            ScreenshotUtil.captureTexture(page.getTextureId(),
                    page.getPageWidth(), page.getPageHeight(),
                    format, outputDir, pagePng);
            LOGGER.info("[AtlasDumper] Dumped page " + i + ": " + pagePng
                    + " (" + page.getPageWidth() + "x" + page.getPageHeight() + ")");
        }

        // Write combined manifest
        String manifestName = basePrefix + "-manifest.txt";
        writeMultiPageManifest(pages, basePrefix, outputDir, manifestName);
    }

    /**
     * Dumps paged-atlas pages directly using the same filename and manifest
     * conventions as {@link #dumpAllPages(List, String, String, int, String)}.
     */
    public static void dumpAllPagedPages(List<CgGlyphAtlasPage> pages,
                                         String typePrefix, String pxSizeSuffix,
                                         int format, String outputDir) {
        if (pages == null || pages.isEmpty()) {
            LOGGER.warning("[AtlasDumper] No paged atlas pages to dump for " + typePrefix + "-" + pxSizeSuffix);
            return;
        }

        String basePrefix = typePrefix + "-" + pxSizeSuffix;

        for (int i = 0; i < pages.size(); i++) {
            CgGlyphAtlasPage page = pages.get(i);
            if (page == null || page.isDeleted() || page.getTextureId() == 0) {
                LOGGER.warning("[AtlasDumper] Skipping null/deleted paged page " + i);
                continue;
            }

            String pagePng = basePrefix + "-page-" + i + ".png";
            ScreenshotUtil.captureTexture(page.getTextureId(),
                    page.getPageWidth(), page.getPageHeight(),
                    format, outputDir, pagePng);
            LOGGER.info("[AtlasDumper] Dumped paged page " + i + ": " + pagePng
                    + " (" + page.getPageWidth() + "x" + page.getPageHeight() + ")");
        }

        String manifestName = basePrefix + "-manifest.txt";
        writeMultiPageManifestForPagedPages(pages, basePrefix, outputDir, manifestName);
    }

    /**
     * Dumps a single atlas page using the paged filename convention.
     * Convenience method when only one page exists but the caller wants
     * the new filename pattern for forward compatibility.
     */
    public static void dumpSinglePageWithPagedNaming(CgGlyphAtlas atlas,
                                                      String typePrefix, String pxSizeSuffix,
                                                      int format, String outputDir) {
        if (atlas == null || atlas.isDeleted() || atlas.getTextureId() == 0) {
            LOGGER.warning("[AtlasDumper] Atlas is null, deleted, or has no texture");
            return;
        }

        String basePrefix = typePrefix + "-" + pxSizeSuffix;
        String pagePng = basePrefix + "-page-0.png";
        ScreenshotUtil.captureTexture(atlas.getTextureId(),
                atlas.getPageWidth(), atlas.getPageHeight(),
                format, outputDir, pagePng);

        String manifestName = basePrefix + "-page-0-manifest.txt";
        writeSinglePageManifest(atlas, 0, outputDir, manifestName);
    }

    // ── Manifest writers ───────────────────────────────────────────────

    /**
     * Writes a combined manifest covering all pages: page count, dimensions,
     * glyph count, and utilization per page.
     *
     * <p>Manifest fields (from atlas overhaul plan §7.8):</p>
     * <ul>
     *   <li>Total page count</li>
     *   <li>Per-page: dimensions, glyph count, utilization percentage</li>
     *   <li>Aggregate totals</li>
     * </ul>
     */
    static void writeMultiPageManifest(List<CgGlyphAtlas> pages,
                                       String basePrefix,
                                       String outputDir,
                                       String manifestName) {
        File manifestFile = new File(outputDir, manifestName);
        try {
            PrintWriter pw = new PrintWriter(new FileWriter(manifestFile));
            try {
                pw.println("=== Atlas Multi-Page Manifest ===");
                pw.println("Base: " + basePrefix);
                pw.println("Page Count: " + pages.size());
                pw.println();

                int totalGlyphs = 0;
                long totalPackedArea = 0;
                long totalPageArea = 0;

                for (int i = 0; i < pages.size(); i++) {
                    CgGlyphAtlas page = pages.get(i);
                    int glyphs = page.getSlotCount();
                    int w = page.getPageWidth();
                    int h = page.getPageHeight();
                    long packedArea = page.getPackedArea();
                    float utilization = page.getUtilization();

                    pw.println("--- Page " + i + " ---");
                    pw.println("  Texture ID: " + page.getTextureId());
                    pw.println("  Dimensions: " + w + "x" + h);
                    pw.println("  Type: " + page.getType());
                    pw.println("  Glyph Count: " + glyphs);
                    pw.println("  Packed Area: " + packedArea + " px");
                    pw.printf("  Utilization: %.1f%%%n", utilization * 100.0f);
                    writeGlyphListing(pw, page);
                    pw.println();

                    totalGlyphs += glyphs;
                    totalPackedArea += packedArea;
                    totalPageArea += (long) w * h;
                }

                pw.println("--- Totals ---");
                pw.println("  Total Glyphs: " + totalGlyphs);
                pw.println("  Total Packed Area: " + totalPackedArea + " px");
                pw.println("  Total Page Area: " + totalPageArea + " px");
                if (totalPageArea > 0) {
                    pw.printf("  Overall Utilization: %.1f%%%n",
                            (float) totalPackedArea / totalPageArea * 100.0f);
                }
                pw.println();
                pw.println("=== End Atlas Multi-Page Manifest ===");
            } finally {
                pw.close();
            }
            LOGGER.info("[AtlasDumper] Wrote multi-page manifest to " + manifestFile.getAbsolutePath());
        } catch (IOException e) {
            LOGGER.warning("[AtlasDumper] Failed to write manifest: " + e.getMessage());
        }
    }

    static void writeSinglePageManifest(CgGlyphAtlas atlas, int pageIndex,
                                        String outputDir, String manifestName) {
        File manifestFile = new File(outputDir, manifestName);
        try {
            PrintWriter pw = new PrintWriter(new FileWriter(manifestFile));
            try {
                pw.println("=== Atlas Page Manifest ===");
                pw.println("Page Index: " + pageIndex);
                pw.println("Texture ID: " + atlas.getTextureId());
                pw.println("Dimensions: " + atlas.getPageWidth() + "x" + atlas.getPageHeight());
                pw.println("Type: " + atlas.getType());
                pw.println("Glyph Count: " + atlas.getSlotCount());
                pw.println("Packed Area: " + atlas.getPackedArea() + " px");
                pw.printf("Utilization: %.1f%%%n", atlas.getUtilization() * 100.0f);
                writeGlyphListing(pw, atlas);
                pw.println();
                pw.println("=== End Atlas Page Manifest ===");
            } finally {
                pw.close();
            }
            LOGGER.info("[AtlasDumper] Wrote page manifest to " + manifestFile.getAbsolutePath());
        } catch (IOException e) {
            LOGGER.warning("[AtlasDumper] Failed to write manifest: " + e.getMessage());
        }
    }

    static void writeMultiPageManifestForPagedPages(List<CgGlyphAtlasPage> pages,
                                                    String basePrefix,
                                                    String outputDir,
                                                    String manifestName) {
        File manifestFile = new File(outputDir, manifestName);
        try {
            PrintWriter pw = new PrintWriter(new FileWriter(manifestFile));
            try {
                pw.println("=== Atlas Multi-Page Manifest ===");
                pw.println("Base: " + basePrefix);
                pw.println("Page Count: " + pages.size());
                pw.println();

                int totalGlyphs = 0;
                long totalPackedArea = 0;
                long totalPageArea = 0;

                for (int i = 0; i < pages.size(); i++) {
                    CgGlyphAtlasPage page = pages.get(i);
                    int glyphs = page.getSlotCount();
                    int w = page.getPageWidth();
                    int h = page.getPageHeight();
                    long packedArea = page.getPackedArea();
                    float utilization = page.getUtilization();

                    pw.println("--- Page " + i + " ---");
                    pw.println("  Texture ID: " + page.getTextureId());
                    pw.println("  Dimensions: " + w + "x" + h);
                    pw.println("  Type: " + page.getType());
                    pw.println("  Glyph Count: " + glyphs);
                    pw.println("  Packed Area: " + packedArea + " px");
                    pw.printf("  Utilization: %.1f%%%n", utilization * 100.0f);
                    writeGlyphListing(pw, page);
                    pw.println();

                    totalGlyphs += glyphs;
                    totalPackedArea += packedArea;
                    totalPageArea += (long) w * h;
                }

                pw.println("--- Totals ---");
                pw.println("  Total Glyphs: " + totalGlyphs);
                pw.println("  Total Packed Area: " + totalPackedArea + " px");
                pw.println("  Total Page Area: " + totalPageArea + " px");
                if (totalPageArea > 0) {
                    pw.printf("  Overall Utilization: %.1f%%%n",
                            (float) totalPackedArea / totalPageArea * 100.0f);
                }
                pw.println();
                pw.println("=== End Atlas Multi-Page Manifest ===");
            } finally {
                pw.close();
            }
            LOGGER.info("[AtlasDumper] Wrote paged multi-page manifest to " + manifestFile.getAbsolutePath());
        } catch (IOException e) {
            LOGGER.warning("[AtlasDumper] Failed to write paged manifest: " + e.getMessage());
        }
    }

    private static void writeGlyphListing(PrintWriter pw, CgGlyphAtlas atlas) {
        java.util.Set<CgGlyphKey> keys = atlas.getGlyphKeys();
        if (keys.isEmpty()) {
            return;
        }
        pw.println("  Glyph IDs: " + formatGlyphIds(keys));
    }

    private static void writeGlyphListing(PrintWriter pw, CgGlyphAtlasPage page) {
        java.util.Set<CgGlyphKey> keys = page.getGlyphKeys();
        if (keys.isEmpty()) {
            return;
        }
        pw.println("  Glyph IDs: " + formatGlyphIds(keys));
        List<CgGlyphKey> sorted = new ArrayList<>(keys);
        Collections.sort(sorted, Comparator.comparingInt(CgGlyphKey::getGlyphId));
        pw.println("  Glyph Metadata:");
        for (CgGlyphKey key : sorted) {
            CgGlyphPlacement placement = page.get(key, 0L);
            if (placement == null) {
                continue;
            }
            pw.println(String.format(
                    "    glyphId=%d codePoint=U+%04X plane=[%.4f, %.4f, %.4f, %.4f] atlas=[%d, %d, %d, %d] size=%dx%d pxRange=%.2f type=%s",
                    key.getGlyphId(),
                    key.getGlyphId(),
                    placement.getPlaneLeft(),
                    placement.getPlaneBottom(),
                    placement.getPlaneRight(),
                    placement.getPlaneTop(),
                    placement.getAtlasLeft(),
                    placement.getAtlasBottom(),
                    placement.getAtlasRight(),
                    placement.getAtlasTop(),
                    placement.getAtlasRight() - placement.getAtlasLeft(),
                    placement.getAtlasTop() - placement.getAtlasBottom(),
                    placement.getPxRange(),
                    placement.getAtlasType()));
        }
    }

    private static String formatGlyphIds(java.util.Set<CgGlyphKey> keys) {
        java.util.List<Integer> ids = new java.util.ArrayList<Integer>();
        for (CgGlyphKey key : keys) {
            ids.add(key.getGlyphId());
        }
        java.util.Collections.sort(ids);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(ids.get(i));
        }
        return sb.toString();
    }

    private AtlasDumper() { }
}
