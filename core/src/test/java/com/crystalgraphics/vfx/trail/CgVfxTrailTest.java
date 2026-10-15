package com.crystalgraphics.vfx.trail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A trail's points: kept by spacing, aged out with one past its life, its head between ticks, teleports and stopping. */
public class CgVfxTrailTest {

    private static final float TICK = 1f / 120f;

    /** What a trail wrote, a point a row: x y z age bx by bz along width intensity. */
    private static List<float[]> written(CgVfxTrail trail, float alpha) {
        List<float[]> out = new ArrayList<>();
        trail.write((x, y, z, age, bx, by, bz, along, width, intensity, u0, u1) ->
                out.add(new float[]{x, y, z, age, bx, by, bz, along, width, intensity}), alpha,
                trail.anchorX(), trail.anchorY(), trail.anchorZ(), new float[8]);
        return out;
    }

    @Test
    public void aMovingHeadKeepsAPointEachSpacingAndAStillOneKeepsNone() {
        CgVfxTrail trail = CgVfxTrail.ribbon(10f).spacing(0.5f);
        for (int i = 0; i <= 40; i++) trail.tick(TICK, i * 0.1, 0, 0);
        // 4 blocks in steps of 0.1: a point every 0.5, the first where it began.
        assertEquals(8, trail.count());
        for (int i = 0; i < 60; i++) trail.tick(TICK, 4.0, 0, 0);
        assertEquals(9, trail.count());
        for (int i = 0; i < 60; i++) trail.tick(TICK, 4.0, 0, 0);
        assertEquals(9, trail.count());
    }

    @Test
    public void pointsPastTheirLifeGoButTheOneTheTailIsCutAgainst() {
        CgVfxTrail trail = CgVfxTrail.ribbon(0.255f).spacing(0f);
        for (int i = 0; i <= 120; i++) trail.tick(TICK, i * 0.05, 0, 0);
        List<float[]> points = written(trail, 1f);
        int pastLife = 0;
        for (float[] p : points) if (p[3] > 0.255f) pastLife++;
        assertEquals(1, pastLife);
        // Newest first: ages rise, distance along falls.
        for (int i = 1; i < points.size(); i++) {
            assertTrue(points.get(i)[3] >= points.get(i - 1)[3]);
            assertTrue(points.get(i)[7] <= points.get(i - 1)[7]);
        }
    }

    @Test
    public void theHeadIsDrawnBetweenTheLastTwoTicks() {
        CgVfxTrail trail = CgVfxTrail.ribbon(1f).spacing(0f);
        trail.tick(TICK, 0, 0, 0);
        trail.tick(TICK, 1, 0, 0);
        trail.tick(TICK, 2, 0, 0);
        // The anchor is the newest tick's place; at alpha 0.5 the head is halfway from the last tick to it.
        float[] head = written(trail, 0.5f).get(0);
        assertEquals(-0.5f, head[0], 1e-6f);
        assertEquals(0f, head[3], 0f);
        // An exact head this frame wins.
        trail.head(1.75, 0.5, 0);
        head = written(trail, 0.5f).get(0);
        assertEquals(-0.25f, head[0], 1e-6f);
        assertEquals(0.5f, head[1], 1e-6f);
    }

    @Test
    public void aTeleportStartsItAgain() {
        CgVfxTrail trail = CgVfxTrail.ribbon(1f).spacing(0f).maxStep(4f);
        for (int i = 0; i < 10; i++) trail.tick(TICK, i * 0.2, 0, 0);
        assertTrue(trail.count() > 5);
        trail.tick(TICK, 50, 0, 0);
        assertEquals(0, trail.count());
        trail.tick(TICK, 50.2, 0, 0);
        assertEquals(1, trail.count());
    }

    @Test
    public void stoppedItRunsOutAndIsDone() {
        CgVfxTrail trail = CgVfxTrail.ribbon(0.2f).spacing(0f);
        for (int i = 0; i < 30; i++) trail.tick(TICK, i * 0.1, 0, 0);
        trail.emitting(false);
        assertFalse(trail.done());
        for (int i = 0; i < 60; i++) trail.tick(TICK);
        assertTrue(trail.done());
    }

    @Test
    public void aBladeKeepsItsTips() {
        CgVfxTrail blade = CgVfxTrail.blade(1f).spacing(0f);
        for (int i = 0; i <= 10; i++) {
            double a = i * 0.1;
            blade.tick(TICK, 0, 0, 0, Math.cos(a) * 2, 0, Math.sin(a) * 2);
        }
        for (float[] p : written(blade, 1f)) {
            float tx = p[4] - p[0], tz = p[6] - p[2];
            assertEquals(2f, (float) Math.sqrt(tx * tx + tz * tz), 1e-4f);
        }
    }
}
