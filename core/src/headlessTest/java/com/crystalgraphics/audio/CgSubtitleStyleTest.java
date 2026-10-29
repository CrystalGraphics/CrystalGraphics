package com.crystalgraphics.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.asset.kind.CgAssetKinds;

/** A game's subtitle style: read and validated against its declaration, scaled with the screen, faded on its keys. */
public class CgSubtitleStyleTest {

    /** A game's widget, measured: a line, its speaker's name over an underline, a band, and the fades. */
    private static final String STYLE = """
            _v: 1
            reference_height: 1080
            ui_scale: 1.0
            lines: 1
            line:
              font: stylekit:fonts/line.otf
              size: 20
              bold: false
              colour: 0xFFFFFFFF
              outline: { size: 3, colour: 0xFF000000 }
              line_height: 1.0
              wrap: 1940
              justify: left
              anchor: [0.5, 0.5]
              alignment: [0.5, 0]
              offset: [-10, 328]
            name:
              font: stylekit:fonts/name.otf
              size: 22
              bold: true
              colour: 0xFF000000
              outline: { size: 3, colour: 0xFFFFFFFF }
              anchor: [0.5, 0.5]
              alignment: [0, 0]
              offset: [-425, 273]
              underline: { image: stylekit:textures/fade.png, size: [352, 2], offset: [-257, 315] }
            band: { colour: 0x80030305, anchor: [0.5, 0.5], height: 200, offset: [0, 330] }
            fade_in: { band: [[0, 0], [0.067, 1]], text: [[0.167, 0], [0.3, 1]] }
            fade_out: { band: [[0.033, 1], [0.2, 0.7], [0.233, 0]], text: [[0, 1], [0.1, 0]] }
            """;

    @BeforeClass
    public static void kinds() {
        CgAssetKinds.register();
    }

    private static Path kit(String style) throws IOException {
        Path kit = Files.createTempDirectory("stylekit");
        Files.write(kit.resolve("subtitle_style.yaml"), style.getBytes(StandardCharsets.UTF_8));
        return kit;
    }

    @Test
    public void readsTheGamesWidget() throws IOException {
        CgSubtitleStyle.registerKit("stylekit", kit(STYLE));
        CgSubtitleStyle s = CgSubtitleStyle.of("stylekit");
        assertNotNull(s);
        assertEquals("a unit is a pixel at the reference height", 1f, s.scale(1080f), 1e-6f);
        assertEquals("and two at twice it", 2f, s.scale(2160f), 1e-6f);
        assertEquals("stylekit:fonts/line.otf", s.line().font());
        assertEquals(20f, s.line().size(), 0f);
        assertEquals(3f, s.line().outlineSize(), 0f);
        assertEquals(0xFF000000, s.line().outlineColour());
        assertEquals(-10f, s.line().offsetX(), 0f);
        assertEquals(328f, s.line().offsetY(), 0f);
        assertEquals(0.5f, s.line().alignmentX(), 0f);
        assertEquals(0xFF000000, s.name().colour());
        assertEquals(352f, s.name().underline().width(), 0f);
        assertEquals(0x80030305, s.band().colour());
        assertEquals(330f, s.band().offsetY(), 0f);
        assertNull("a kit with none: the canvas look", CgSubtitleStyle.of("nokit"));
    }

    @Test
    public void fadesAreLinearBetweenKeysAndHeldPastThem() throws IOException {
        CgSubtitleStyle s = CgSubtitleStyle.read(kit(STYLE).resolve("subtitle_style.yaml"));
        assertEquals("the band, before its first key", 0f, s.fadeIn().band(-1f), 0f);
        assertEquals("halfway up", 0.5f, s.fadeIn().band(0.0335f), 1e-4f);
        assertEquals("the text waits for the band", 0f, s.fadeIn().text(0.1f), 0f);
        assertEquals(1f, s.fadeIn().text(1f), 0f);
        assertEquals("the band's middle key", 0.7f, s.fadeOut().band(0.2f), 1e-6f);
        assertEquals("the fade out ends at its last key", 0.233f, s.fadeOut().seconds(), 1e-6f);
        assertEquals("no fade is whole", 1f, CgSubtitleStyle.Fade.NONE.text(0f), 0f);
    }

    /** UMG's In: alpha and a slide on a rich curve, auto tangents. */
    @Test
    public void aCubicFadeIsARichCurvesAutoTangents() throws IOException {
        CgSubtitleStyle s = CgSubtitleStyle.read(kit(STYLE.replaceAll("(?s)fade_in:.*", """
                fade_in: { text: [[0, 0], [0.2, 1]], slide: [[0, 100], [0.2, 0]], interp: cubic }
                fade_out: { text: [[0, 0], [1, 1], [2, 3]], interp: cubic }
                """)).resolve("subtitle_style.yaml"));
        assertEquals("two flat keys: a smoothstep, halfway", 50f, s.fadeIn().slide(0.1f), 1e-4f);
        assertEquals("a quarter in: 3u^2 - 2u^3 of the way", 100f - 100f * 0.15625f, s.fadeIn().slide(0.05f), 1e-3f);
        assertEquals("an inner key's slope is its neighbours' chord: 1.5", 0.3125f, s.fadeOut().text(0.5f), 1e-5f);
        assertEquals("no slide keys: no slide", 0f, s.fadeOut().slide(0.5f), 0f);
        assertEquals("the fade's end is its last key's", 2f, s.fadeOut().seconds(), 0f);
    }

    @Test
    public void aStyleThatDoesNotValidateFailsByName() throws IOException {
        Path bad = kit(STYLE.replace("reference_height: 1080\n", "").replace("size: 20", "size: big"));
        try {
            CgSubtitleStyle.read(bad.resolve("subtitle_style.yaml"));
            fail("an invalid style read");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("subtitle_style.yaml"));
            assertTrue(e.getMessage(), e.getMessage().contains("reference_height"));
        }
    }
}
