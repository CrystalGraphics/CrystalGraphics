package com.crystalgraphics.vfx.sim;

import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.path.CgVfxPath;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * Homing streams as the showcase fires them: the body must stay smooth all the way into the target, under a swinging
 * aim and through a sharp bend at a waypoint, rather than kinking where samples arrive off-line.
 */
public class CgVfxStreamTest {

    private static final float TICK = CgVfxSystem.TICK, MAX_LENGTH = 80f;
    /** A curve turning this much between rings 0.2 blocks apart has a radius under 2 blocks: it reads as a kink. */
    private static final float KINK_DEGREES = 6f;

    @Test
    public void swingingAimHomesSmoothlyIntoTheTarget() {
        CgVfxStream stream = new CgVfxStream();
        float tx = 24f, ty = -0.8f, tz = 1f;
        stream.target(tx, ty, tz);
        float length = (float) Math.sqrt(tx * tx + ty * ty + tz * tz);
        Bends bends = new Bends();
        for (int t = 0; t < 120 * 30; t++) {
            float seconds = t * TICK;
            float yaw = 0.8f * (float) Math.sin(seconds * 0.45f), pitch = 0.3f * (float) Math.sin(seconds * 0.31f + 1f);
            float cos = (float) Math.cos(yaw), sin = (float) Math.sin(yaw);
            stream.emit(0f, 0f, 0f, cos * tx - sin * tz, ty + pitch * length, sin * tx + cos * tz, 40f);
            stream.tick(TICK, 4f, 3f, MAX_LENGTH);
            if (seconds >= 2f) bends.measure(stream, seconds);
        }
        bends.check();
    }

    /** The showcase's Bending Kamehameha: straight back to a waypoint, then a hard turn toward a target to the side. */
    @Test
    public void waypointBendsSharplyButSmoothly() {
        for (float side : new float[]{-26f, 44f}) {
            CgVfxStream stream = new CgVfxStream();
            stream.via(0f, 0.5f, -22f);
            stream.target(side, 0.5f, -36f);
            Bends bends = new Bends();
            for (int t = 0; t < 120 * 10; t++) {
                float seconds = t * TICK;
                stream.emit(0f, 0f, 0f, 0f, 0f, -1f, 30f);
                stream.tick(TICK, 6f, 5f, MAX_LENGTH);
                if (seconds >= 4f) bends.measure(stream, seconds);
            }
            bends.check();
        }
    }

    /** The sharpest bend between neighbouring rings, and how often the head is at the target. */
    private static final class Bends {
        private final CgVfxPath path = new CgVfxPath();
        private final float[] points = new float[4096 * 3];
        private float worst, worstAt, worstArc;
        private int frames, impacting;

        void measure(CgVfxStream stream, float seconds) {
            frames++;
            if (stream.impacting()) impacting++;
            path.build(points, stream.points(points, 0f, 0f, 0f, 0f, true), 0.2f);
            for (int i = 1; i < path.count(); i++) {
                float dot = path.tangentX(i) * path.tangentX(i - 1) + path.tangentY(i) * path.tangentY(i - 1)
                        + path.tangentZ(i) * path.tangentZ(i - 1);
                float degrees = (float) Math.toDegrees(Math.acos(Math.max(-1f, Math.min(1f, dot))));
                if (degrees > worst) {
                    worst = degrees;
                    worstAt = seconds;
                    worstArc = path.arc(i);
                }
            }
        }

        void check() {
            String report = String.format("worst bend %.2f deg between rings 0.2 blocks apart, at %.1f s, %.1f blocks from the"
                    + " muzzle; head at the target %.0f%% of the time", worst, worstAt, worstArc, 100f * impacting / frames);
            System.out.println(report);
            assertTrue(report, worst < KINK_DEGREES);
            assertTrue(report, impacting > frames * 0.95f);
        }
    }
}
