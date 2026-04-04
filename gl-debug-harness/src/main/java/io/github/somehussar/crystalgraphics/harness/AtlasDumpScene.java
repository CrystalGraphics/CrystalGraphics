package io.github.somehussar.crystalgraphics.harness;

import com.crystalgraphics.freetype.FTBitmap;
import com.crystalgraphics.freetype.FTFace;
import com.crystalgraphics.freetype.FTGlyphMetrics;
import com.crystalgraphics.freetype.FTLoadFlags;
import com.crystalgraphics.freetype.FTRenderMode;
import com.crystalgraphics.freetype.FreeTypeLibrary;
import io.github.somehussar.crystalgraphics.gl.text.CgGlyphAtlas;
import io.github.somehussar.crystalgraphics.api.font.CgFontKey;
import io.github.somehussar.crystalgraphics.api.font.CgFontStyle;
import io.github.somehussar.crystalgraphics.api.font.CgGlyphKey;

import org.lwjgl.opengl.GL30;

import java.util.logging.Logger;

final class AtlasDumpScene implements HarnessScene {

    private static final Logger LOGGER = Logger.getLogger(AtlasDumpScene.class.getName());

    private static final String TEST_STRING = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int ATLAS_SIZE = 512;
    private static final int FONT_SIZE_PX = 24;

    @Override
    public void run(HarnessContext ctx, String outputDir) {
        String fontPath = findSystemFont();
        LOGGER.info("[Harness] Atlas dump: font=" + fontPath + ", size=" + FONT_SIZE_PX + "px");
        LOGGER.info("[Harness] Atlas dump: test string=\"" + TEST_STRING + "\"");
        LOGGER.info("[Harness] Atlas dump: atlas size=" + ATLAS_SIZE + "x" + ATLAS_SIZE);

        FreeTypeLibrary ftLib = FreeTypeLibrary.create();
        FTFace face = ftLib.newFace(fontPath, 0);
        face.setPixelSizes(0, FONT_SIZE_PX);

        CgGlyphAtlas atlas = CgGlyphAtlas.create(ATLAS_SIZE, ATLAS_SIZE, CgGlyphAtlas.Type.BITMAP);
        CgFontKey fontKey = new CgFontKey(fontPath, CgFontStyle.REGULAR, FONT_SIZE_PX);

        int glyphCount = 0;
        long frame = 1;

        for (int i = 0; i < TEST_STRING.length(); i++) {
            int charCode = TEST_STRING.charAt(i);
            int glyphIndex = face.getCharIndex(charCode);
            if (glyphIndex == 0) {
                continue;
            }

            face.loadGlyph(glyphIndex, FTLoadFlags.FT_LOAD_DEFAULT);
            face.renderGlyph(FTRenderMode.FT_RENDER_MODE_NORMAL);

            FTBitmap bitmap = face.getGlyphBitmap();
            int w = bitmap.getWidth();
            int h = bitmap.getHeight();
            if (w == 0 || h == 0) {
                continue;
            }

            byte[] pixels = normalizeBitmapBuffer(bitmap);
            FTGlyphMetrics metrics = face.getGlyphMetrics();
            float bearingX = metrics.getHoriBearingX() / 64.0f;
            float bearingY = metrics.getHoriBearingY() / 64.0f;

            CgGlyphKey glyphKey = new CgGlyphKey(fontKey, glyphIndex, false, 0);
            atlas.getOrAllocate(glyphKey, pixels, w, h, bearingX, bearingY, frame);
            glyphCount++;
        }

        LOGGER.info("[Harness] Atlas populated: " + glyphCount + " glyphs");
        LOGGER.info("[Harness] Atlas texture ID: " + atlas.getTextureId());
        LOGGER.info("[Harness] Atlas dimensions: " + ATLAS_SIZE + "x" + ATLAS_SIZE);

        // GL_R8 = 0x8229
        ScreenshotUtil.captureTexture(atlas.getTextureId(), ATLAS_SIZE, ATLAS_SIZE,
                0x8229, outputDir, "atlas-dump.png");

        atlas.delete();
        face.destroy();
        ftLib.destroy();

        LOGGER.info("[Harness] Atlas dump scene complete.");
    }

    private byte[] normalizeBitmapBuffer(FTBitmap bitmap) {
        byte[] source = bitmap.getBuffer();
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int pitch = bitmap.getPitch();
        if (pitch == width) {
            return source;
        }
        byte[] packed = new byte[width * height];
        int absPitch = Math.abs(pitch);
        for (int row = 0; row < height; row++) {
            int srcRow = pitch >= 0 ? row : (height - 1 - row);
            System.arraycopy(source, srcRow * absPitch, packed, row * width, width);
        }
        return packed;
    }

    static String findSystemFont() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String winDir = System.getenv("WINDIR");
            if (winDir == null) winDir = "C:\\Windows";
            return winDir + "\\Fonts\\arial.ttf";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "/System/Library/Fonts/Helvetica.ttc";
        }
        // Linux fallbacks
        String[] linuxFonts = {
            "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
            "/usr/share/fonts/TTF/DejaVuSans.ttf",
            "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
            "/usr/share/fonts/liberation-sans/LiberationSans-Regular.ttf"
        };
        for (String path : linuxFonts) {
            if (new java.io.File(path).exists()) {
                return path;
            }
        }
        throw new RuntimeException("No system font found. Set -Dharness.font.path=/path/to/font.ttf");
    }
}
