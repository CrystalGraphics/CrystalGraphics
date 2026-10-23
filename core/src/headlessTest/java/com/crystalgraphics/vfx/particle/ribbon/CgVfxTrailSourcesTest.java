package com.crystalgraphics.vfx.particle.ribbon;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.crystalgraphics.vfx.particle.gpu.CgVfxInstanceView;
import com.crystalgraphics.vfx.particle.renderer.CgVfxRibbonRendererProperties;
import com.crystalgraphics.vfx.particle.renderer.CgVfxRibbonRendererProperties.Along;
import com.crystalgraphics.vfx.particle.renderer.CgVfxRibbonRendererProperties.AnimTrail;
import com.crystalgraphics.vfx.particle.spawn.CgVfxSpawnPerUnit;

/**
 * The two Cascade trail sources' rows against Cascade's own arithmetic: a ribbon trail's spawn per unit
 * ({@code GetSpawnPerUnitAmount}) and an anim trail's samples ({@code SpawnParticle}), worked out by hand.
 */
public class CgVfxTrailSourcesTest {

    private static final float TICK = 0.05f, E = 1e-5f;

    private final int[] next = {0};
    private final CgVfxTrailSource.Spawner books = new CgVfxTrailSource.Spawner() {
        @Override public int spawn(float born, int key) { return next[0]++; }
        @Override public boolean thinned(int k) { return false; }
        @Override public float life(int key) { return 100f; }
    };

    private static CgVfxRibbonRendererProperties.Trail trail(boolean recalculate) {
        return new CgVfxRibbonRendererProperties.Trail(1, 500, 1, CgVfxRibbonRendererProperties.RenderAxis.CAMERA_UP, 0.15f, 25,
                5f, false, recalculate, false, 0f, false, true, true, true, 0f);
    }

    private static AnimTrail anim(int life, int retract) {
        return new AnimTrail("a", "b", CgVfxRibbonRendererProperties.WidthMode.CENTRE, life, 0.1f, 0f, 0f, 0, true, false,
                Along.constant(1f), Along.constant(1f, 1f, 1f, 1f), 1f, retract, 0.25f, 0.5f);
    }

    private static float f(int[] w, int at) {
        return Float.intBitsToFloat(w[at]);
    }

    @Test
    public void ribbonTrailSpawnsAUnitApartAndCarriesTheRest() {
        CgVfxTrailSource source = new CgVfxTrailSource(trail(false), CgVfxSpawnPerUnit.every(1f, 0.25f), 2f, 64, new float[3]);
        float[] xs = {0f, 1f, 1.3f, 1.6f, 6.6f};
        int[] spawned = {0, 4, 1, 1, 0};
        for (int k = 0; k < xs.length; k++) {
            int before = next[0];
            source.tick(books, TICK, k * TICK, xs[k], 0f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, true, k * TICK, (k + 1) * TICK);
            assertEquals("tick " + k + "'s spawns", spawned[k], next[0] - before);
            if (spawned[k] > 0) {
                assertEquals(1, source.rows());
                int[] w = source.rowWords();
                assertEquals("its count", spawned[k], f(w, 27), 0f);
                assertEquals("from", xs[k - 1], f(w, 4), E);
                assertEquals("to", xs[k], f(w, 8), E);
                assertEquals("movement", CgVfxTrailSource.MOVEMENT, f(w, 19), 0f);
            } else {
                assertEquals("tick " + k + " lays no row", 0, source.rows());
            }
        }
    }

    @Test
    public void animTrailSamplesItsEdgeAndRefitsTheOneBefore() {
        CgVfxAnimTrailSource source = new CgVfxAnimTrailSource(anim(0, 0), 64);
        Edge edge = new Edge();
        float s = (float) Math.sqrt(0.5);
        // a, b; the sample between, its tangent and interpolation parameter, its tiled distance; the refit's.
        float[][] ticks = {
                {0, 0, 0, 0, 2, 0}, {1, 0, 0, 1, 2, 0}, {1, 0, 1, 1, 2, 1}};
        float[][] samples = {{0, 1, 0, 0, 0, 0, 0, 0}, {1, 1, 0, 1, 0, 0, 1, 1}, {1, 1, 1, s, 0, s, 1, 2}};
        float[][] refit = {null, {0, 1, 0, 0, 1}, {1, s, 0, s, 1}};
        for (int k = 0; k < ticks.length; k++) {
            edge.set(ticks[k]);
            source.tick(books, TICK, k * TICK, edge, true, 0f, 0f);
            int[] w = source.rowWords();
            assertEquals(1, source.rows());
            assertEquals("a sample", CgVfxAnimTrailSource.SAMPLE, f(w, 19), 0f);
            for (int c = 0; c < 3; c++) {
                assertEquals("sample " + k + " place " + c, samples[k][c], f(w, 8 + c), E);
                assertEquals("sample " + k + " tangent " + c, samples[k][3 + c], f(w, 12 + c), E);
            }
            assertEquals("its half edge, straight up", 1f, f(w, 17), E);
            assertEquals("its interpolation parameter", samples[k][6], f(w, 30), E);
            assertEquals("its tiled distance", samples[k][7], f(w, 23), E);
            assertEquals("one chain", 1f, f(w, 29), 0f);
            if (refit[k] == null) {
                assertEquals(0, source.refits());
            } else {
                int[] r = source.refitWords();
                assertEquals(1, source.refits());
                assertEquals("the one before", (int) refit[k][0], r[1]);
                for (int c = 0; c < 3; c++) assertEquals("refit tangent " + c, refit[k][1 + c], f(r, 13 + c), E);
                assertEquals("refit interpolation", refit[k][4], f(r, 4), E);
            }
        }
        // Stopped, it is dead; started again, a new chain from nothing.
        source.tick(books, TICK, 3 * TICK, edge, false, 0f, 0f);
        assertEquals(0, source.rows());
        edge.set(new float[] {5, 0, 0, 5, 2, 0});
        source.tick(books, TICK, 4 * TICK, edge, true, 0f, 0f);
        assertEquals("a second chain", 2f, f(source.rowWords(), 29), 0f);
        assertEquals("from nothing", 0f, f(source.rowWords(), 12), 0f);
        assertEquals("nothing tiled", 0f, f(source.rowWords(), 23), 0f);
        assertEquals("nothing refit", 0, source.refits());
    }

    @Test
    public void retractingTrailLosesItsOldestWhileItKeepsSampling() {
        CgVfxAnimTrailSource source = new CgVfxAnimTrailSource(anim(0, 2), 64);
        Edge edge = new Edge();
        for (int k = 0; k < 4; k++) {
            edge.set(new float[] {k, 0, 0, k, 2, 0});
            source.tick(books, TICK, k * TICK, edge, true, 0f, 0f);
        }
        source.tick(books, TICK, 4 * TICK, edge, false, 0f, 0f);
        assertEquals("the two oldest gone", 2, source.minIds()[0]);
        assertEquals("still sampling", 1, source.rows());
        assertEquals(4, source.rowFirst(0));
        assertEquals(0.75f, source.fadeAlpha(), E);
        assertEquals(0.5f, source.fadeWidth(), E);
    }

    @Test
    public void lifeKeepsThatManySamples() {
        CgVfxAnimTrailSource source = new CgVfxAnimTrailSource(anim(2, 0), 64);
        Edge edge = new Edge();
        for (int k = 0; k < 3; k++) {
            edge.set(new float[] {k, 0, 0, k, 2, 0});
            source.tick(books, TICK, k * TICK, edge, true, 0f, 0f);
        }
        assertEquals("the oldest of three gone", 1, source.minIds()[0]);
    }

    /** User parameters {@code a} and {@code b}: an edge's two points. */
    private static final class Edge implements CgVfxInstanceView {
        private final float[] at = new float[6];

        void set(float[] v) {
            System.arraycopy(v, 0, at, 0, 6);
        }

        @Override public float user(String name, int component, float fallback) {
            return name.equals("a") ? at[component] : name.equals("b") ? at[3 + component] : fallback;
        }

        @Override public double originX() { return 0; }
        @Override public double originY() { return 0; }
        @Override public double originZ() { return 0; }
        @Override public float time() { return 0f; }
        @Override public float sourceX() { return 0f; }
        @Override public float sourceY() { return 0f; }
        @Override public float sourceZ() { return 0f; }
    }
}
