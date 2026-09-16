package com.crystalgraphics.text.cache;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.text.msdf.CgMsdfAtlasConfig;
import com.crystalgraphics.text.render.context.CgTextScaleResolver;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * <b>A face is banded once, and the band is what bounds its stroke.</b>
 *
 * <p>Dense scripts keep the narrow range because eight bits cannot quantise a wider one without
 * merging strokes a kanji keeps apart; everything else is given {@link
 * CgMsdfAtlasConfig#WIDE_PX_RANGE}, worth 2.3x the reach. Both bands share an atlas scale and a page
 * size, so this costs no second atlas -- only a per-placement range.</p>
 */
public class CgFontBandingTest {

    private static final String LATIN = "src/test/resources/fonts/IBMPlexSans-Regular.ttf";
    private static final String CJK = "src/test/resources/fonts/MPLUS1p-Regular.ttf";

    private static CgFont load(String path) {
        File f = new File(path);
        assertTrue("test font missing at " + f.getAbsolutePath(), f.isFile());
        return CgFont.load(f.getPath(), CgFontStyle.REGULAR, 80);
    }

    @Test
    public void aDenseFaceKeepsTheNarrowBandAndEverythingElseGetsTheWideOne() {
        CgMsdfAtlasConfig narrow = CgMsdfAtlasConfig.defaultConfig();
        CgFontRegistry registry = new CgFontRegistry(1024, narrow);

        CgFont latin = load(LATIN);
        CgFont cjk = load(CJK);
        try {
            float latinEm = registry.maxStrokeWidthEm(latin);
            float cjkEm = registry.maxStrokeWidthEm(cjk);

            assertEquals("a dense face must stay on the range 8-bit storage can hold",
                    narrow.maxStrokeWidthEm(), cjkEm, 1e-6f);
            assertEquals("a face with no dense script must be banded wide",
                    narrow.withPxRange(CgMsdfAtlasConfig.WIDE_PX_RANGE).maxStrokeWidthEm(),
                    latinEm, 1e-6f);

            assertTrue("the wide band bought no extra reach", latinEm > cjkEm * 2.0f);

            // The band also moves the TIER floor, which is the half of this nobody asks for: a
            // wider range resolves its own edge at a smaller raster, so a stroked Latin label stays
            // on the field tier well below where a dense face has to fall back.
            int narrowFloor = narrow.minAntialiasablePx();
            int wideFloor = narrow.withPxRange(CgMsdfAtlasConfig.WIDE_PX_RANGE).minAntialiasablePx();
            System.out.println("[band] tier floor narrow " + narrowFloor + "px, wide " + wideFloor + "px");
            assertTrue("the wide band must not raise the size at which a stroke falls back",
                    wideFloor < narrowFloor);

            // Asking before anything is drawn must agree with asking after: the answer is the
            // face's, not a function of what the atlas happens to hold.
            assertEquals("the ceiling moved on a second ask", latinEm,
                    registry.maxStrokeWidthEm(latin), 1e-6f);
        } finally {
            latin.dispose();
            cjk.dispose();
        }
    }

    /**
     * <b>The tier must never hold the field at a size the field cannot antialias.</b>
     *
     * <p>{@code MSDF_ENTER_THRESHOLD} is a fossil of the pxRange 6 era, when it WAS the field's own
     * floor; the floor has since moved to 15 and 7 and the threshold has not. That gap is fine and
     * deliberate -- they answer different questions -- but the ordering between them is not
     * negotiable. Let a band's floor rise past the exit threshold and the engine keeps the field tier
     * below the size msdfgen's rule says it works at, which does not throw and does not log: text
     * simply stops resolving its own edges.</p>
     */
    @Test
    public void noBandsFloorMayRiseAboveTheTierItIsSelectedBy() {
        CgMsdfAtlasConfig narrow = CgMsdfAtlasConfig.defaultConfig();
        CgMsdfAtlasConfig wide = narrow.withPxRange(CgMsdfAtlasConfig.WIDE_PX_RANGE);

        for (CgMsdfAtlasConfig band : new CgMsdfAtlasConfig[]{narrow, wide}) {
            int floor = band.minAntialiasablePx();
            System.out.println("[tier] band pxRange " + band.pxRange() + " floor " + floor
                    + "px, tier exits the field at " + CgTextScaleResolver.MSDF_EXIT_THRESHOLD + "px");
            assertTrue("a face on pxRange " + band.pxRange() + " antialiases only from " + floor
                            + "px, but the tier keeps the field down to "
                            + CgTextScaleResolver.MSDF_EXIT_THRESHOLD + "px -- below its own floor",
                    CgTextScaleResolver.MSDF_EXIT_THRESHOLD >= floor);
        }
    }

    @Test
    public void anUnknownFaceGetsTheBandEveryFaceCanHold() {
        CgMsdfAtlasConfig narrow = CgMsdfAtlasConfig.defaultConfig();
        CgFontRegistry registry = new CgFontRegistry(1024, narrow);
        assertEquals("an absent face must answer the safe band, never the wide one",
                narrow.maxStrokeWidthEm(), registry.maxStrokeWidthEm((CgFont) null), 1e-6f);
    }
}
