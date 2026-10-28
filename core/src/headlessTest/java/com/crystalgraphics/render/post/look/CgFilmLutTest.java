package com.crystalgraphics.render.post.look;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.Assume;
import org.junit.Test;

import com.crystalgraphics.render.post.volume.CgPostSettings;

/**
 * The tonemapper's table against a Kakarot frame's (Unreal 4.21, its legacy film curve at FilmHealAmount 0.18): the
 * curve's constants as its combine pass bound them, its pixels through the whole pass, and its table cell by cell.
 */
public class CgFilmLutTest {

    private static CgPostSettings stage() {
        return new CgPostSettings().tonemap().filmHeal(0.18f);
    }

    @Test
    public void theLegacyCurveIsTheOneTheFrameBound() {
        // The combine pass's constants: ColorMatrix*_ColorCurve* and ColorCurve_* as the frame bound them.
        CgFilmLut.Curve c = new CgFilmLut.Curve(0.03, 4, 1, 0.18, 1);
        assertEquals(-0.11625, c.cd1, 1e-5);
        assertEquals(-0.0054, c.cd3cm3, 1e-5);
        assertEquals(1.03, c.cm2, 1e-5);
        assertEquals(0.0270874, c.cm0cd0, 1e-6);
        assertEquals(0.112864, c.cd2, 1e-6);
        assertEquals(0.323301, c.ch0cm1, 1e-6);
        assertEquals(0.357881, c.ch3, 1e-6);
        assertEquals(0.701617, c.ch1, 1e-6);
        assertEquals(-0.226833, c.ch2, 1e-6);
    }

    @Test
    public void aFramesPixelsComeOutAsTheGameDrewThem() {
        float[] lut = new float[CgFilmLut.CELLS * 3];
        CgFilmLut.bake(stage(), lut);
        // 1920x1080, vignette 0.5, exposure 1: the scene, the bloom under it, what the tonemapper wrote (8-bit, dithered).
        check(lut, 1340, 485, new double[] {0.0108185, 0.0305786, 0.0830688}, new double[] {0.00567627, 0.0183105, 0.0351562},
                new double[] {24, 55, 90});
        check(lut, 990, 355, new double[] {0.00404358, 0.01828, 0.0515137}, new double[] {0.0673828, 0.117188, 0.140625},
                new double[] {72, 100, 119});
        check(lut, 350, 185, new double[] {0.0291443, 0.137695, 0.760742}, new double[] {0.0292969, 0.201172, 0.492188},
                new double[] {51, 132, 208});
    }

    /** -PgameLut: a frame's own table, x (r) fastest, each channel within a 10-bit code of ours. */
    @Test
    public void theTableIsTheFramesCellByCell() throws IOException {
        String file = System.getProperty("crystalgraphics.gameLut");
        Assume.assumeTrue("no -PgameLut", file != null && Files.exists(Paths.get(file)));
        float[] lut = new float[CgFilmLut.CELLS * 3];
        CgFilmLut.bake(stage(), lut);
        Path path = Paths.get(file);
        List<String> lines = Files.readAllLines(path);
        int read = 0;
        double worst = 0;
        for (String line : lines) {
            if (line.isEmpty() || !Character.isDigit(line.charAt(0))) continue;
            String[] v = line.split(",");
            int at = 3 * (Integer.parseInt(v[0]) + CgFilmLut.SIZE * (Integer.parseInt(v[1]) + CgFilmLut.SIZE * Integer.parseInt(v[2])));
            for (int k = 0; k < 3; k++) {
                double ours = Math.round(lut[at + k] * 1023.0), theirs = Math.round(Double.parseDouble(v[3 + k]) * 1023.0);
                worst = Math.max(worst, Math.abs(ours - theirs));
            }
            read++;
        }
        assertEquals("every cell", CgFilmLut.CELLS, read);
        assertTrue("within a code of 1023: " + worst, worst <= 1);
    }

    /** The pass at pixel {@code (px, py)}: scene plus bloom, the vignette, the table through the shaper, times 1.05. */
    private static void check(float[] lut, int px, int py, double[] scene, double[] bloom, double[] want) {
        double a = 1080.0 / 1920.0, scale = Math.sqrt(2) / Math.sqrt(1 + a * a);
        double x = (px + 0.5) / 1920 * 2 - 1, y = 1 - (py + 0.5) / 1080 * 2;
        double vx = x * scale * 0.5, vy = y * a * scale * 0.5, k = 1 / (vx * vx + vy * vy + 1), mask = k * k;
        double[] got = new double[3];
        CgFilmLut.tonemap(lut, (scene[0] + bloom[0]) * mask, (scene[1] + bloom[1]) * mask, (scene[2] + bloom[2]) * mask, got);
        for (int c = 0; c < 3; c++) assertEquals("pixel (" + px + ", " + py + ") channel " + c, want[c], got[c] * 255, 1.5);
    }
}
