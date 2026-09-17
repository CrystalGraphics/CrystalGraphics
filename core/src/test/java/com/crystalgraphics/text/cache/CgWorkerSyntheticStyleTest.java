package com.crystalgraphics.text.cache;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.api.font.CgGlyphKey;

/**
 * <b>A glyph rasterised on a worker is the glyph the frame thread would have rasterised.</b>
 *
 * <p>The atlas keeps whichever raster lands first under a key, so a worker that skipped synthetic bold stored a
 * regular glyph as the bold one, and bold text drew regular for the rest of the process.</p>
 */
public class CgWorkerSyntheticStyleTest {

    private static final int PX = 14;

    private static CgFont font;
    private static CgWorkerFontContext worker;

    @BeforeClass
    public static void load() throws IOException {
        font = CgFont.load(Files.readAllBytes(Paths.get("src/test/resources/fonts/IBMPlexSans-Regular.ttf")),
                "IBMPlexSans-Regular.ttf", CgFontStyle.REGULAR, PX);
        worker = new CgWorkerFontContext();
    }

    @AfterClass
    public static void dispose() {
        worker.close();
        font.dispose();
    }

    @Test
    public void aWorkerEmboldensASyntheticBoldGlyph() {
        int glyph = font.getGlyphIndex('H');
        double regular = ink(new CgGlyphKey(font.getKey(), glyph, false, 0, false, false));
        double bold = ink(new CgGlyphKey(font.getKey(), glyph, false, 0, true, false));
        assertTrue("bold carries more ink: " + bold + " against " + regular, bold > regular * 1.2);
    }

    private static double ink(CgGlyphKey key) {
        CgRasterFontKey raster = new CgRasterFontKey(font.getKey(), PX);
        CgGlyphGenerationResult result = worker.generateBitmap(
                CgGlyphGenerationJob.bitmap(font.getKey(), font.getData(), key, raster, PX, 0));
        double sum = 0;
        for (byte b : result.getBitmapData()) sum += (b & 0xFF) / 255.0;
        return sum;
    }
}
