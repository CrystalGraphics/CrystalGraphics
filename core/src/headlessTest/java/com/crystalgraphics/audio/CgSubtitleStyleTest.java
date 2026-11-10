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
import java.util.List;

import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.asset.kind.CgAssetKinds;

/** A game's subtitle style: its widget, the text blocks a line is set into, and its show and hide animations. */
public class CgSubtitleStyleTest {

    /** Kakarot's, as its importer writes it. */
    private static final String STYLE = """
            _v: 1
            widget: stylekit:Art/UI/Xcmn/Xcmn_Subtitles
            line: Txt_Selif
            name: Txt_Name
            with_name: [Tex_Fade_Right]
            lines: 1
            show: stylekit:Art/UI/Xcmn/Xcmn_Subtitles/in
            hide: stylekit:Art/UI/Xcmn/Xcmn_Subtitles/out
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
        assertEquals("its kit, whose UI scale sizes it", "stylekit", s.namespace());
        assertEquals("stylekit:Art/UI/Xcmn/Xcmn_Subtitles", s.widget().id());
        assertEquals("Txt_Selif", s.line());
        assertEquals("Txt_Name", s.name());
        assertEquals(List.of("Tex_Fade_Right"), s.withName());
        assertEquals("stylekit:Art/UI/Xcmn/Xcmn_Subtitles/in", s.show().id());
        assertEquals("stylekit:Art/UI/Xcmn/Xcmn_Subtitles/out", s.hide().id());
        assertNull("a kit with none: the canvas look", CgSubtitleStyle.of("nokit"));
    }

    @Test
    public void nameAndAnimationsAreOptional() throws IOException {
        CgSubtitleStyle s = CgSubtitleStyle.read("stylekit", kit("""
                _v: 1
                widget: stylekit:hud/subtitles
                line: Txt_Line
                """).resolve("subtitle_style.yaml"));
        assertNull(s.name());
        assertNull(s.show());
        assertEquals(List.of(), s.withName());
        assertEquals(1, s.lines());
    }

    @Test
    public void aStyleThatDoesNotValidateFailsByName() throws IOException {
        Path bad = kit(STYLE.replace("widget: stylekit:Art/UI/Xcmn/Xcmn_Subtitles\n", ""));
        try {
            CgSubtitleStyle.read("stylekit", bad.resolve("subtitle_style.yaml"));
            fail("an invalid style read");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("subtitle_style.yaml"));
            assertTrue(e.getMessage(), e.getMessage().contains("widget"));
        }
    }
}
