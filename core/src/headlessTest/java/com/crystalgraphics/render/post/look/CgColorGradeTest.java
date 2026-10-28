package com.crystalgraphics.render.post.look;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.crystalgraphics.render.post.volume.CgPostSettings;
import com.crystalgraphics.render.post.volume.CgPostSettings.Control;
import com.crystalgraphics.render.post.volume.CgPostSettings.Region;

/**
 * The grade against Unreal 4.21's {@code ColorCorrectAll}, transliterated from its shader into NumPy line by line
 * and run on ACEScg pixels in the shadows, the midtones, the highlights and past white.
 */
public class CgColorGradeTest {

    private static CgPostSettings graded() {
        return new CgPostSettings()
                .grade(Region.GLOBAL, Control.SATURATION, 1f, 1f, 1f, 0.8f)
                .grade(Region.GLOBAL, Control.CONTRAST, 1.1f, 1f, 0.9f, 1f)
                .grade(Region.GLOBAL, Control.GAMMA, 1f, 1f, 1f, 1.2f)
                .grade(Region.GLOBAL, Control.GAIN, 1f, 0.95f, 1.05f, 1f)
                .grade(Region.GLOBAL, Control.OFFSET, 0f, 0f, 0f, 0.01f)
                .grade(Region.SHADOWS, Control.GAIN, 0.8f, 0.9f, 1.2f, 1f)
                .grade(Region.SHADOWS, Control.OFFSET, 0.01f, 0f, 0.02f, 0f)
                .grade(Region.MIDTONES, Control.SATURATION, 1.2f, 1f, 1f, 1f)
                .grade(Region.MIDTONES, Control.GAMMA, 0.9f, 1f, 1.1f, 1f)
                .grade(Region.HIGHLIGHTS, Control.GAIN, 1f, 1f, 1f, 1.3f)
                .grade(Region.HIGHLIGHTS, Control.CONTRAST, 1f, 1f, 1f, 0.9f);
    }

    @Test
    public void aGradeIsUnrealsColorCorrectAll() {
        double[] p = new double[CgColorGrade.PACKED];
        CgColorGrade.pack(graded(), p);
        check(p, 0.02, 0.03, 0.05, 0.0439131214624, 0.0567847329391, 0.134264469801);
        check(p, 0.18, 0.15, 0.12, 0.213295151462, 0.206916867757, 0.236179068965);
        check(p, 0.7, 0.8, 0.6, 0.908845580725, 0.855044055532, 0.732364458235);
        check(p, 2.0, 0.5, 0.1, 2.10373092596, 0.705000824325, 0.419625387421);
    }

    @Test
    public void theGpuConstantsAreThePackedGrade() {
        double[] p = new double[CgColorGrade.PACKED];
        CgPostSettings s = graded().whiteBalance(5000f, 0.2f).tint(1f, 0.9f, 0.8f);
        CgColorGrade.pack(s, p);
        float[] gpu = new CgColorGrade().constants(s);
        for (int i = 0; i < p.length; i++) assertArrayEquals("float " + i, new float[] {(float) p[i]}, new float[] {gpu[i]}, 0f);
    }

    @Test
    public void aNeutralGrade_isOff() {
        CgPostSettings neutral = new CgPostSettings().grade(Region.SHADOWS, Control.GAIN, 1f, 1f, 1f, 1f)
                .grade(Region.GLOBAL, Control.OFFSET, 0f, 0f, 0f, 0f).whiteBalance(6500f, 0f).tint(1f, 1f, 1f);
        assertFalse(neutral.grades());
        assertTrue(new CgPostSettings().whiteBalance(6000f, 0f).grades());
        assertTrue(new CgPostSettings().tint(0xFFFF8080, 1f).grades());
        // Off because it is not identity: Unreal's white balance at 6500 K is not quite D65 (sRGB's white).
        double[] p = new double[CgColorGrade.PACKED], out = new double[3];
        CgColorGrade.pack(neutral, p);
        CgColorGrade.apply(p, new double[] {0.3, 0.6, 0.9}, out);
        assertArrayEquals(new double[] {0.3, 0.6, 0.9}, out, 2e-3);
    }

    private static void check(double[] p, double r, double g, double b, double wr, double wg, double wb) {
        double[] out = new double[3];
        CgColorGrade.correctAll(p, r, g, b, out);
        assertArrayEquals("ACEScg (" + r + ", " + g + ", " + b + ")", new double[] {wr, wg, wb}, out, 1e-6);
    }
}
