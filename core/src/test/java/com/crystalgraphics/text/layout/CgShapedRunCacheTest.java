package com.crystalgraphics.text.layout;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.api.text.CgShapedRun;
import com.crystalgraphics.api.text.CgTextLayout;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

/** Text laid out again with one line changed shapes that line alone, and the kept lines are what shaping gave. */
public class CgShapedRunCacheTest {

    private CgFont font;

    @Before
    public void load() throws Exception {
        CgShapedRunCache.clear();
        CgTextLayoutCache.clear();
        try (InputStream in = getClass().getResourceAsStream("/fonts/test-font.ttf")) {
            font = CgFont.load(in.readAllBytes(), "shape-cache", CgFontStyle.REGULAR, 24);
        }
    }

    @After
    public void dispose() {
        CgShapedRunCache.clear();
        CgTextLayoutCache.clear();
        font.dispose();
    }

    @Test
    public void anUnchangedLineIsNotShapedAgain() {
        // A new family each call, as CgTextLayout.of(text, font) builds one
        CgTextLayout first = CgTextLayout.of("FPS: 120\nClip 1 / 75  0.28 s\nRate [U/I]", font).build();
        CgTextLayout second = CgTextLayout.of("FPS: 120\nClip 1 / 75  0.29 s\nRate [U/I]", font).build();

        assertSame("the unchanged first line reuses its runs", first.lines().get(0).get(0), second.lines().get(0).get(0));
        assertSame("the unchanged last line reuses its runs", first.lines().get(2).get(0), second.lines().get(2).get(0));
        assertNotSame("the changed line is shaped again", first.lines().get(1).get(0), second.lines().get(1).get(0));
        assertEquals("three lines, then the changed one", 4, CgShapedRunCache.size());
        assertSame("the same text built again is the same layout",
                first, CgTextLayout.of("FPS: 120\nClip 1 / 75  0.28 s\nRate [U/I]", font).build());

        // What the cache hands back is what shaping gives
        CgShapedRunCache.clear();
        CgTextLayoutCache.clear();
        CgTextLayout fresh = CgTextLayout.of("FPS: 120\nClip 1 / 75  0.28 s\nRate [U/I]", font).build();
        for (int line = 0; line < 3; line++) {
            List<CgShapedRun> kept = first.lines().get(line), shaped = fresh.lines().get(line);
            assertEquals(shaped.size(), kept.size());
            for (int r = 0; r < kept.size(); r++) {
                assertArrayEquals(shaped.get(r).glyphIds(), kept.get(r).glyphIds());
                assertEquals(Arrays.toString(shaped.get(r).advancesX()), Arrays.toString(kept.get(r).advancesX()));
            }
        }
    }
}
