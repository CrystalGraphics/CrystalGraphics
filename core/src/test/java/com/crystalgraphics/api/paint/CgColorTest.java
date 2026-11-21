package com.crystalgraphics.api.paint;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import com.crystalgraphics.serialization.CgPlainOps;

/** {@link CgColor} against published values: CSS Color 4's, Ottosson's OKLab pairs and WCAG 2's ratios. */
public class CgColorTest {

    @Test
    public void everyByteSurvivesDecodeAndEncode() {
        for (int v = 0; v < 256; v++) {
            int argb = 0xFF000000 | v << 16 | (255 - v) << 8 | v;
            assertEquals("byte " + v, argb, CgColor.argb(argb).toArgb());
        }
        assertEquals("linear 0.5 is sRGB 0xBC", 0xFFBCBCBC, new CgColor(0.5f, 0.5f, 0.5f, 1f).toArgb());
    }

    @Test
    public void byteOrdersAndHex() {
        assertEquals(CgColor.argb(0x80FF8800), CgColor.rgba(0xFF880080));
        assertEquals(0xFF880080, CgColor.argb(0x80FF8800).toRgba());
        assertEquals(0xFFFF8800, CgColor.hex("#f80").toArgb());
        assertEquals("alpha last, as CSS writes it", 0x80FF8800, CgColor.hex("ff880080").toArgb());
        assertEquals("#ff880080", CgColor.argb(0x80FF8800).toHex());
        assertEquals("#336699", CgColor.argb(0xFF336699).toHex());
        assertThrows(IllegalArgumentException.class, () -> CgColor.hex("#12"));
    }

    @Test
    public void hslAndHsvMatchCss() {
        // CSS Color 4: hsl(210 50% 40%) is #336699
        assertEquals(0xFF336699, CgColor.css("hsl(210 50% 40%)").toArgb());
        assertEquals(0xFF336699, CgColor.css("hsla(210, 50%, 40%, 1)").toArgb());
        assertEquals(0xFF00FF00, CgColor.hsl(120f, 1f, 0.5f, 1f).toArgb());
        assertEquals(0xFFFF0000, CgColor.hsv(0f, 1f, 1f, 1f).toArgb());
        assertArrayEquals(new float[]{210f, 0.5f, 0.4f, 1f}, CgColor.argb(0xFF336699).toHsl(), 0.002f);
        assertArrayEquals(new float[]{210f, 2f / 3f, 0.6f, 1f}, CgColor.argb(0xFF336699).toHsv(), 0.002f);
    }

    @Test
    public void packedFactoriesMatchTheRecords() {
        for (int h = -30; h <= 390; h += 15) {
            for (float s = 0f; s <= 1f; s += 0.25f) {
                for (float x = 0f; x <= 1f; x += 0.125f) {
                    String at = h + " " + s + " " + x;
                    assertEquals("hsv " + at, CgColor.hsv(h, s, x, 0.5f).toArgb(), CgColor.hsvArgb(h, s, x, 0.5f));
                    assertEquals("hsl " + at, CgColor.hsl(h, s, x, 0.5f).toArgb(), CgColor.hslArgb(h, s, x, 0.5f));
                    assertEquals("oklch " + at, CgColor.oklch(x, s * 0.3f, h, 1f).toArgb(), CgColor.oklchArgb(x, s * 0.3f, h, 1f));
                }
            }
        }
        assertEquals(CgColor.srgb(0.2f, 0.4f, 0.6f, 1f).toArgb(), CgColor.srgbArgb(0.2f, 0.4f, 0.6f, 1f));
        assertEquals(0x80FF8800, CgColor.hexArgb("ff880080"));
        assertEquals(0xFF336699, CgColor.cssArgb("hsl(210 50% 40%)"));
        assertEquals(0xFF663399, CgColor.cssArgb("RebeccaPurple"));
        assertEquals(CgColor.css("oklch(62.8% 0.2577 29.23)").toArgb(), CgColor.cssArgb("oklch(62.8% 0.2577 29.23)"));
        assertThrows(IllegalArgumentException.class, () -> CgColor.cssArgb("hsl(1)"));
    }

    @Test
    public void oklabMatchesOttosson() {
        // Ottosson's reference pairs, linear sRGB in
        assertArrayEquals(new float[]{1f, 0f, 0f, 1f}, CgColor.WHITE.toOklab(), 1e-4f);
        assertArrayEquals(new float[]{0.627955f, 0.224863f, 0.125846f, 1f}, new CgColor(1f, 0f, 0f, 1f).toOklab(), 1e-4f);
        assertArrayEquals(new float[]{0.866440f, -0.233888f, 0.179498f, 1f}, new CgColor(0f, 1f, 0f, 1f).toOklab(), 1e-4f);
        assertArrayEquals(new float[]{0.452014f, -0.032457f, -0.311528f, 1f}, new CgColor(0f, 0f, 1f, 1f).toOklab(), 1e-4f);
        CgColor back = CgColor.oklab(0.627955f, 0.224863f, 0.125846f, 1f);
        assertEquals(1f, back.r(), 1e-4f);
        assertEquals(0f, back.g(), 1e-4f);
        assertEquals(0f, back.b(), 1e-4f);
    }

    @Test
    public void oklchReadsCssAndKeepsHdr() {
        // CSS Color 4's red, oklch(62.8% 0.2577 29.23)
        int red = CgColor.css("oklch(62.8% 0.2577 29.23)").toArgb();
        assertEquals(0xFF, red >>> 16 & 0xFF);
        assertTrue(Integer.toHexString(red), (red >>> 8 & 0xFF) <= 1 && (red & 0xFF) <= 1);
        float[] lch = new CgColor(1f, 0f, 0f, 1f).toOklch();
        assertEquals(29.23f, lch[2], 0.05f);
        CgColor hot = CgColor.css("oklab(1.4 0 0)");
        assertTrue("lightness past white stays past it: " + hot, hot.isHdr());
        assertTrue(hot.toCss(), hot.toCss().startsWith("color(srgb-linear "));
    }

    @Test
    public void wcagContrast() {
        assertEquals(21.0, CgColor.WHITE.contrast(CgColor.BLACK), 1e-6);
        assertEquals(21.0, CgColor.BLACK.contrast(CgColor.WHITE), 1e-6);
        // The commonly cited pair: #767676 on white is the lightest grey to pass AA, at 4.54
        assertEquals(4.54, CgColor.argb(0xFF767676).contrast(CgColor.WHITE), 0.01);
        assertEquals(1.0, CgColor.argb(0xFF336699).contrast(CgColor.argb(0xFF336699)), 1e-9);
    }

    @Test
    public void mixing() {
        assertEquals("half the light", 0xFFBCBCBC, CgColor.BLACK.lerp(CgColor.WHITE, 0.5f).toArgb());
        assertEquals("half the encoded value", 0xFF808080, CgColor.BLACK.lerpSrgb(CgColor.WHITE, 0.5f).toArgb());
        CgColor a = CgColor.argb(0xFFFF0000), b = CgColor.argb(0xFF0000FF);
        assertEquals(a.toArgb(), a.lerpOklab(b, 0f).toArgb());
        assertEquals(b.toArgb(), a.lerpOklab(b, 1f).toArgb());
        CgColor half = new CgColor(0.6f, 0.4f, 0.2f, 0.5f);
        assertEquals(new CgColor(0.3f, 0.2f, 0.1f, 0.5f), half.premultiply());
        assertEquals(half, half.premultiply().unpremultiply());
        assertEquals(CgColor.TRANSPARENT, new CgColor(1f, 1f, 1f, 0f).unpremultiply());
        assertEquals(new CgColor(2f, 1f, 0.5f, 0.5f), new CgColor(1f, 0.5f, 0.25f, 0.5f).intensity(2f));
        assertEquals(new CgColor(1f, 0f, 1f, 1f), new CgColor(3f, -1f, 1f, 2f).clamp());
    }

    @Test
    public void codecReadsEveryFormAndWritesLosslessly() {
        CgPlainOps ops = CgPlainOps.INSTANCE;
        CgColor gold = CgColor.argb(0xFFFFC040);
        assertEquals(gold, CgColor.CODEC.decode(ops, 0xFFFFC040L));
        assertEquals(gold, CgColor.CODEC.decode(ops, "#ffc040"));
        assertEquals(gold, CgColor.CODEC.decode(ops, CgColor.CODEC.encode(ops, gold)));
        assertEquals("8-bit when exact", 0xFFFFC040L, ((Number) CgColor.CODEC.encode(ops, gold)).longValue());
        CgColor glow = new CgColor(4f, 0.25f, 0.1f, 1f);
        Object written = CgColor.CODEC.encode(ops, glow);
        assertTrue("HDR is written linear: " + written, written instanceof List<?>);
        assertEquals(glow, CgColor.CODEC.decode(ops, written));
        assertEquals(new CgColor(0.5f, 0.1f, 0f, 1f), CgColor.CODEC.decode(ops, List.of(0.5, 0.1, 0.0)));
    }

    @Test
    public void cssNamesAndRefusals() {
        assertEquals(0xFF663399, CgColor.css("RebeccaPurple").toArgb());
        assertEquals(CgColor.TRANSPARENT, CgColor.css("transparent"));
        assertThrows(IllegalArgumentException.class, () -> CgColor.css("currentColor"));
        assertThrows(IllegalArgumentException.class, () -> CgColor.css("hsl(10)"));
        assertEquals("the style sheet's parser reads it too", Integer.valueOf(0xFF336699), CgCssColors.parse("hsl(210 50% 40%)"));
    }
}
