package io.github.somehussar.crystalgraphics.harness.tool;

import com.msdfgen.FreeTypeIntegration;
import com.msdfgen.MsdfException;
import com.msdfgen.Shape;
import io.github.somehussar.crystalgraphics.api.font.CgGlyphKey;
import io.github.somehussar.crystalgraphics.gl.text.msdf.CgMsdfAtlasConfig;
import io.github.somehussar.crystalgraphics.gl.text.msdf.CgMsdfGlyphLayout;
import io.github.somehussar.crystalgraphics.text.atlas.CgGuillotinePacker;
import io.github.somehussar.crystalgraphics.text.atlas.PackedRect;

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

        Set<Integer> glyphIds = collectGlyphIds(text);
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

        int candidate = nextPowerOfTwo((int) Math.ceil(Math.sqrt(totalArea * 1.15d)));
        candidate = Math.max(64, candidate);

        while (candidate <= 4096) {
            if (fits(candidate, layouts, config.getSpacingPx())) {
                return candidate;
            }
            candidate *= 2;
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

    private static Set<Integer> collectGlyphIds(String text) {
        Set<Integer> glyphIds = new LinkedHashSet<Integer>();
        if (text == null) {
            return glyphIds;
        }
        for (int i = 0; i < text.length(); i++) {
            glyphIds.add(Integer.valueOf(text.charAt(i)));
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

    static int nextPowerOfTwo(int value) {
        int power = 1;
        while (power < value) {
            power <<= 1;
        }
        return power;
    }
}
