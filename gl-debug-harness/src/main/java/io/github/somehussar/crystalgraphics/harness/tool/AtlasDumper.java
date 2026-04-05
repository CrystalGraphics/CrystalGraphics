package io.github.somehussar.crystalgraphics.harness.tool;

import io.github.somehussar.crystalgraphics.gl.text.CgGlyphAtlas;
import io.github.somehussar.crystalgraphics.harness.util.ScreenshotUtil;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
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

    private AtlasDumper() { }
}
