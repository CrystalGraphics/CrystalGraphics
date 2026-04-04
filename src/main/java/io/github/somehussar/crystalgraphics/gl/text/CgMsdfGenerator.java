package io.github.somehussar.crystalgraphics.gl.text;

import com.msdfgen.Bitmap;
import com.msdfgen.FreeTypeIntegration;
import com.msdfgen.Generator;
import com.msdfgen.MsdfException;
import com.msdfgen.Shape;
import com.msdfgen.Transform;
import io.github.somehussar.crystalgraphics.api.font.CgAtlasRegion;
import io.github.somehussar.crystalgraphics.api.font.CgGlyphKey;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Render-thread MSDF generator for glyph atlases.
 *
 * <p>This class converts glyph outlines from the local msdfgen bindings into
 * RGB float MSDF images and uploads them into a {@link CgGlyphAtlas}. A strict
 * per-frame budget is enforced to avoid frame spikes. When generation is not
 * allowed for the current glyph or budget, callers are expected to use the
 * bitmap fallback path.</p>
 */
public class CgMsdfGenerator {

    private static final Logger LOGGER = Logger.getLogger(CgMsdfGenerator.class.getName());

    public static final float PX_RANGE = 4.0f;
    public static final int MAX_PER_FRAME = 4;
    static final int COMPLEXITY_EDGE_THRESHOLD = 24;
    static final int SIMPLE_MSDF_MIN_PX = 32;
    static final int COMPLEX_MSDF_MIN_PX = 48;

    private int generatedThisFrame;

    public CgMsdfGenerator() {
        this.generatedThisFrame = 0;
    }

    /**
     * Generates one MSDF glyph and inserts it into the target atlas.
     *
     * <p>Returns {@code null} when the frame budget is exhausted, when the
     * shape is empty, or when the complexity heuristic says bitmap rendering is
     * still the better choice at the current font size.</p>
     */
    public CgAtlasRegion queueOrGenerate(CgGlyphKey key,
                                         FreeTypeIntegration.Font font,
                                         CgGlyphAtlas atlas,
                                         long currentFrame) {
        if (generatedThisFrame >= MAX_PER_FRAME) {
            return null;
        }

        FreeTypeIntegration.GlyphData glyphData;
        try {
            glyphData = font.loadGlyphByIndex(key.getGlyphId(), FreeTypeIntegration.FONT_SCALING_EM_NORMALIZED);
        } catch (MsdfException e) {
            LOGGER.log(Level.WARNING, "Failed to load glyph index " + key.getGlyphId(), e);
            return null;
        }

        Shape shape = glyphData.getShape();
        try {
            if (shape.getEdgeCount() == 0) {
                return null;
            }
            if (!shouldUseMsdf(shape, key.getFontKey().getTargetPx())) {
                return null;
            }

            shape.normalize();
            shape.edgeColoringSimple(3.0);

            int cellSize = cellSizeForFontPx(key.getFontKey().getTargetPx());
            Bitmap bitmap = Bitmap.allocMsdf(cellSize, cellSize);
            try {
                Transform transform = Transform.autoFrame(shape, cellSize, cellSize, PX_RANGE);
                Generator.generateMsdf(bitmap, shape, transform);
                Generator.errorCorrection(bitmap, shape, transform);

                double[] bounds = shape.getBounds();
                float bearingX = (float) (bounds[0] * transform.getScaleX() + transform.getTranslateX());
                float bearingY = (float) (bounds[3] * transform.getScaleY() + transform.getTranslateY());
                float[] pixelData = bitmap.getPixelData();

                CgAtlasRegion region = atlas.getOrAllocateMsdf(key, pixelData, cellSize, cellSize, bearingX, bearingY, currentFrame);
                generatedThisFrame++;
                return region;
            } finally {
                bitmap.free();
            }
        } finally {
            shape.free();
        }
    }

    public void tickFrame() {
        generatedThisFrame = 0;
    }

    public static int cellSizeForFontPx(int fontPx) {
        if (fontPx >= 64) {
            return 64;
        }
        if (fontPx >= 48) {
            return 48;
        }
        return 32;
    }

    public static boolean shouldUseMsdf(Shape shape, int fontPx) {
        int totalEdges = shape.getEdgeCount();
        if (totalEdges > COMPLEXITY_EDGE_THRESHOLD) {
            return fontPx >= COMPLEX_MSDF_MIN_PX;
        }
        return fontPx >= SIMPLE_MSDF_MIN_PX;
    }

    int getGeneratedThisFrame() {
        return generatedThisFrame;
    }

    void simulateGeneration() {
        generatedThisFrame++;
    }
}
