package com.crystalgraphics.text.font;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontMetrics;
import com.crystalgraphics.api.font.CgFontStyle;

/** <b>A font's own underline and strikeout are read from its tables, and reach the metrics a layout positions by.</b> */
public class SfntDecorationsTest {

    @Test
    public void theFontsOwnLinesAreRead() throws Exception {
        byte[] data = Files.readAllBytes(Paths.get("src/test/resources/fonts/IBMPlexSans-Regular.ttf"));
        Sfnt.Decorations lines = Sfnt.decorations(data);
        assertTrue("an underline below the baseline: " + lines, lines.underlinePosition() < 0);
        assertTrue("with a thickness", lines.underlineThickness() > 0);
        assertTrue("a strikeout above it", lines.strikeoutPosition() > 0);
        assertTrue("with a size", lines.strikeoutSize() > 0);
    }

    @Test
    public void notAFontIsNoLines() {
        assertEquals(Sfnt.Decorations.NONE, Sfnt.decorations(new byte[] {1, 2, 3}));
        assertEquals(Sfnt.Decorations.NONE, Sfnt.decorations(null));
    }

    @Test
    public void theMetricsCarryThemInPixels() throws Exception {
        CgFont font = CgFont.load(Files.readAllBytes(Paths.get("src/test/resources/fonts/IBMPlexSans-Regular.ttf")),
                "IBMPlexSans-Regular.ttf", CgFontStyle.REGULAR, 40);
        try {
            CgFontMetrics metrics = font.getMetrics();
            assertTrue(metrics.hasUnderline() && metrics.hasStrikeout());
            assertTrue("the underline's centre is below the baseline: " + metrics, metrics.getUnderlineOffset() > 0f);
            assertTrue("the strikeout's is above it", metrics.getStrikeoutOffset() < 0f);
            assertTrue("and inside the ascent", -metrics.getStrikeoutOffset() < metrics.getAscender());
        } finally {
            font.dispose();
        }
    }
}
