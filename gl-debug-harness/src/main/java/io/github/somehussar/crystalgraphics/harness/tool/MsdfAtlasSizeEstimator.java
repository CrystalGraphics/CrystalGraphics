package io.github.somehussar.crystalgraphics.harness.tool;

import com.msdfgen.FreeTypeIntegration;
import com.msdfgen.MsdfException;
import com.msdfgen.Shape;
import io.github.somehussar.crystalgraphics.text.msdf.CgMsdfAtlasConfig;
import io.github.somehussar.crystalgraphics.text.msdf.CgMsdfGlyphLayout;
import io.github.somehussar.crystalgraphics.text.atlas.packing.CgGuillotinePacker;
import io.github.somehussar.crystalgraphics.text.atlas.packing.PackedRect;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Estimates a content-derived MSDF atlas page size for parity/harness runs.
 */
public final class MsdfAtlasSizeEstimator {

    private static final Logger LOGGER = Logger.getLogger(MsdfAtlasSizeEstimator.class.getName());

    private MsdfAtlasSizeEstimator() {
    }

    public static int estimate(FreeTypeIntegration.Font msdfFont,
                               String text,
                               CgMsdfAtlasConfig config) {
        if (msdfFont == null) {
            throw new IllegalArgumentException("msdfFont must not be null");
        }
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }

        Set<Integer> glyphIds = collectGlyphIds(msdfFont, text);
        if (glyphIds.isEmpty()) {
            return config.getPageSize();
        }

        int totalArea = 0;
        java.util.List<CgMsdfGlyphLayout> layouts = new java.util.ArrayList<CgMsdfGlyphLayout>();
        for (Integer glyphId : glyphIds) {
            CgMsdfGlyphLayout layout = computeLayout(msdfFont, glyphId.intValue(), config);
            if (layout == null || layout.isEmpty()) {
                continue;
            }
            layouts.add(layout);
            totalArea += layout.getBoxWidth() * layout.getBoxHeight();
        }

        if (layouts.isEmpty()) {
            return config.getPageSize();
        }

        Collections.sort(layouts, (a, b) -> {
            int areaA = a.getBoxWidth() * a.getBoxHeight();
            int areaB = b.getBoxWidth() * b.getBoxHeight();
            if (areaA != areaB) {
                return Integer.compare(areaB, areaA);
            }
            if (a.getBoxHeight() != b.getBoxHeight()) {
                return Integer.compare(b.getBoxHeight(), a.getBoxHeight());
            }
            return Integer.compare(b.getBoxWidth(), a.getBoxWidth());
        });

        int maxGlyphWidth = 0;
        int maxGlyphHeight = 0;
        for (int i = 0; i < layouts.size(); i++) {
            CgMsdfGlyphLayout layout = layouts.get(i);
            maxGlyphWidth = Math.max(maxGlyphWidth, layout.getBoxWidth());
            maxGlyphHeight = Math.max(maxGlyphHeight, layout.getBoxHeight());
        }

        int candidate;
        candidate = Math.max(Math.max(64, maxGlyphWidth), maxGlyphHeight);
        candidate = roundUpToMultiple(candidate, 4);

        while (candidate <= 4096) {
            if (fits(candidate, layouts, config.getSpacingPx())) {
                return candidate;
            }
            candidate += 4;
        }

        return 4096;
    }

    private static boolean fits(int side, java.util.List<CgMsdfGlyphLayout> layouts, int spacingPx) {
        CgGuillotinePacker packer = new CgGuillotinePacker(side, side);
        for (int i = 0; i < layouts.size(); i++) {
            CgMsdfGlyphLayout layout = layouts.get(i);
            PackedRect rect = packer.insert(layout.getBoxWidth(), layout.getBoxHeight(), spacingPx, i);
            if (rect == null) {
                return false;
            }
        }
        return true;
    }

    private static Set<Integer> collectGlyphIds(FreeTypeIntegration.Font msdfFont, String text) {
        Set<Integer> glyphIds = new LinkedHashSet<>();
        if (msdfFont == null || text == null) {
            return glyphIds;
        }
        for (int i = 0; i < text.length(); i++) {
            int glyphIndex = msdfFont.getGlyphIndex(text.charAt(i));
            if (glyphIndex > 0) {
                glyphIds.add(Integer.valueOf(glyphIndex));
            }
        }
        return glyphIds;
    }

    private static CgMsdfGlyphLayout computeLayout(FreeTypeIntegration.Font msdfFont,
                                                   int glyphId,
                                                   CgMsdfAtlasConfig config) {
        try {
            FreeTypeIntegration.GlyphData glyphData = msdfFont.loadGlyphByIndex(
                    glyphId, FreeTypeIntegration.FONT_SCALING_EM_NORMALIZED);
            Shape shape = glyphData.getShape();
            if (shape.getEdgeCount() == 0) {
                return null;
            }
            shape.normalize();
            double[] bounds = shape.getBounds();
            return CgMsdfGlyphLayout.compute(
                    bounds[0], bounds[1], bounds[2], bounds[3],
                    config.getAtlasScalePx(),
                    config.getPxRange(),
                    config.getMiterLimit(),
                    config.isAlignOriginX(),
                    config.isAlignOriginY());
        } catch (MsdfException e) {
            LOGGER.log(Level.FINE, "Failed to estimate glyph layout for glyph " + glyphId, e);
            return null;
        }
    }

    static int roundUpToMultiple(int value, int multiple) {
        if (multiple <= 0) {
            throw new IllegalArgumentException("multiple must be > 0");
        }
        int remainder = value % multiple;
        return remainder == 0 ? value : value + (multiple - remainder);
    }
}
