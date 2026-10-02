package com.crystalgraphics.vfx.particle;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgWorldQuery;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The burst's ground field over a fake cliff: ground at y = -60 where x < 5, a ravine floor at y = -70 beyond. */
public class CgVfxGroundTest {

    private static final CgWorldQuery CLIFF = new CgWorldQuery() {
        @Override public float collisionTop(int x, int y, int z) { return y < (x < 5 ? -60 : -70) ? 1f : Float.NaN; }
        @Override public float collisionBottom(int x, int y, int z) { return Float.isNaN(collisionTop(x, y, z)) ? Float.NaN : 0f; }
        @Override public int collisionBoxes(int x, int y, int z, float[] out) { return 0; }
        @Override public int surface(int x, int y, int z) { return SURFACE_NONE; }
        @Override public float hardness(int x, int y, int z) { return Float.NaN; }
        @Override public int lightEmission(int x, int y, int z) { return 0; }
        @Override public int tint(int x, int y, int z) { return 0; }
        @Override public int biomeColor(int x, int y, int z, int kind) { return 0; }
        @Override public int precipitation(int x, int y, int z) { return PRECIPITATION_NONE; }
        @Override public int surfaceY(int x, int z, int kind) { return Integer.MIN_VALUE; }
        @Override public boolean spriteRect(int x, int y, int z, float[] out) { return false; }
        @Override public float fluidHeight(int x, int y, int z) { return Float.NaN; }
        @Override public int fluidKind(int x, int y, int z) { return FLUID_NONE; }
        @Override public int light(int x, int y, int z) { return 0; }
        @Override public int mapColor(int x, int y, int z) { return 0; }
        @Override public boolean loaded(int x, int z) { return true; }
        @Override public int minY() { return -64 - 32; }
        @Override public int maxY() { return 320; }
        @Override public int seaLevel() { return -63; }
        @Override public int levelEpoch() { return 7; }
    };

    @After
    public void noWorld() {
        CgPlatform.provide(CgWorldQuery.SERVICE, CgWorldQuery.NONE);
    }

    /** The field, centred on a burst at world (0.5, -59.5, 0.5), seen from an effect whose origin is (0, -50, 0). */
    private static CgVfxGround burst() {
        return new CgVfxGround(32).reset(0.0, -50.0, 0.0, 0.5f, -9.5f, 0.5f);
    }

    @Test
    public void aColumnIsScannedOnceAParticleAsksAndThenAnswered() {
        CgPlatform.provide(CgWorldQuery.SERVICE, CLIFF);
        CgVfxGround ground = burst();
        ground.fill(CgVfxGround.FILL_PER_TICK);
        assertTrue("not scanned yet: no floor, never the fixed height", Float.isNaN(ground.floor(2.5f, -5f, -5f, 0.5f, -3f)));
        ground.fill(CgVfxGround.FILL_PER_TICK);
        assertEquals(-10f, ground.floor(2.5f, -5f, -5f, 0.5f, -3f), 1e-4f);
    }

    @Test
    public void debrisOverTheCliffFallsIntoTheRavine() {
        CgPlatform.provide(CgWorldQuery.SERVICE, CLIFF);
        CgVfxGround ground = burst();
        ground.fill(1);
        ground.floor(8.5f, -5f, -5f, 0.5f, Float.NaN);
        ground.fill(1);
        assertEquals(-20f, ground.floor(8.5f, -5f, -5f, 0.5f, Float.NaN), 1e-4f);
    }

    @Test
    public void aParticleCannotTunnelThroughTheFloorInOneTick() {
        CgPlatform.provide(CgWorldQuery.SERVICE, CLIFF);
        CgVfxGround ground = burst();
        ground.fill(1);
        ground.floor(2.5f, -5f, -5f, 0.5f, Float.NaN);
        ground.fill(1);
        // A tick ago just above the ground, now well under it: still the ground it passed.
        assertEquals(-10f, ground.floor(2.5f, -11f, -9.9f, 0.5f, Float.NaN), 1e-4f);
    }

    @Test
    public void withNoWorldTheFixedHeightStands() {
        CgVfxGround ground = burst();
        ground.fill(CgVfxGround.FILL_PER_TICK);
        assertEquals(-3f, ground.floor(2.5f, -1f, -1f, 0.5f, -3f), 0f);
    }

    @Test
    public void particlesLandOnTheRavineFloorThroughTheGroundModule() {
        CgPlatform.provide(CgWorldQuery.SERVICE, CLIFF);
        CgVfxEmitter debris = CgVfxEmitter.builder("debris").capacity(1).burst(0f, 1).life(60f, 60f)
                .launch(0f, 0f, 1f).speed(0f, 0f)
                .module(new CgVfxModule.Gravity(9.8f)).module(new CgVfxModule.Ground(0f, 1f, 5f)).build();
        CgVfxGround ground = new CgVfxGround(32).reset(0.0, 0.0, 0.0, 8.5f, -55f, 0.5f);
        CgVfxEmitterInstance run = new CgVfxEmitterInstance(debris, 0.5f);
        run.start(8.5f, -55f, 0.5f);
        run.ground(-60f).ground(ground);
        CgVfxAir air = new CgVfxAir();
        for (int t = 0; t < 600; t++) {
            ground.fill(CgVfxGround.FILL_PER_TICK);
            run.tick(1f / 120f, air, 0.0, 0.0, 0.0);
        }
        assertEquals("on the ravine floor, not the fixed height", -70f, run.particles().y[0], 1e-4f);
        assertEquals(1f, run.particles().resting[0], 0f);
    }
}
