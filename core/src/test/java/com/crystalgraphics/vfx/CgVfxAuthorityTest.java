package com.crystalgraphics.vfx;

import com.crystalgraphics.collision.query.CgCollisionWorld;
import com.crystalgraphics.platform.service.CgBoxSink;
import com.crystalgraphics.platform.service.CgEntityQuery;
import com.crystalgraphics.platform.service.CgWorldQuery;
import com.crystalgraphics.vfx.effect.beam.CgEnergyWave;
import com.crystalgraphics.world.CgEntityAttachments;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** A wave run as a server runs it: against one level's queries, with nothing in the client's slots. */
public class CgVfxAuthorityTest {

    /** Solid below y = 0. */
    public static final CgWorldQuery FLOOR = new CgWorldQuery() {
        @Override public float collisionTop(int x, int y, int z) { return y < 0 ? 1f : Float.NaN; }
        @Override public float collisionBottom(int x, int y, int z) { return y < 0 ? 0f : Float.NaN; }
        @Override public int collisionBoxes(int x, int y, int z, float[] out) {
            if (y >= 0) return 0;
            out[0] = out[1] = out[2] = 0f;
            out[3] = out[4] = out[5] = 1f;
            return 1;
        }
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
        @Override public int minY() { return -64; }
        @Override public int maxY() { return 320; }
        @Override public int seaLevel() { return 0; }
        @Override public int levelEpoch() { return 1; }
    };

    /** The caster (1) at the origin facing +x, a zombie (2) twelve blocks out. */
    static final class Crowd implements CgEntityQuery {
        final double[][] at = {{}, {0, 0, 0}, {12, 0, 0}};

        @Override public int localPlayer() { return -1; }
        @Override public int cameraEntity() { return -1; }
        @Override public boolean pose(int id, float partialTick, double[] out) {
            if (id < 1 || id >= at.length) return false;
            out[X] = at[id][0];
            out[Y] = at[id][1];
            out[Z] = at[id][2];
            out[WIDTH] = 0.6;
            out[HEIGHT] = 1.95;
            out[EYE_HEIGHT] = 1.62;
            out[YAW] = out[BODY_YAW] = out[HEAD_YAW] = -90;   // facing +x
            return true;
        }
        @Override public int flags(int id) { return ALIVE | LIVING | PICKABLE; }
        @Override public int kind(int id) { return id == 1 ? KIND_PLAYER : KIND_MONSTER; }
        @Override public boolean box(int id, float partialTick, CgBoxSink out) {
            if (id < 1 || id >= at.length) return false;
            double[] a = at[id];
            out.set(a[0] - 0.3, a[1], a[2] - 0.3, a[0] + 0.3, a[1] + 1.95, a[2] + 0.3);
            return true;
        }
        @Override public int parent(int id) { return id; }
        @Override public int rootVehicle(int id) { return id; }
        @Override public void within(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                                     IntConsumer visitor) {
            for (int id = 1; id < at.length; id++) {
                double[] a = at[id];
                if (a[0] + 0.3 >= minX && a[0] - 0.3 <= maxX && a[1] + 1.95 >= minY && a[1] <= maxY
                        && a[2] + 0.3 >= minZ && a[2] - 0.3 <= maxZ) visitor.accept(id);
            }
        }
    }

    @Test
    public void aServersWaveStrikesWhatItsLevelHoldsThenBursts() {
        CgEnergyWave wave = new CgEnergyWave(CgEnergyWave.kamehameha(), 0, 1, 0).collide();
        wave.follow(1, CgEntityAttachments.CHEST);
        wave.aim(1f, 0f, 0f);
        CgVfxAuthority run = new CgVfxAuthority(wave, CgCollisionWorld.of(FLOOR, new Crowd()));
        List<String> heard = new ArrayList<>();
        wave.onCollide(c -> heard.add(c.part() + " " + c.hit() + " " + c.entity() + (c.radius() > 0f ? " wide" : "")));

        for (int t = 0; t < 200; t++) run.advance(0.05f);
        assertEquals("through its caster, onto the zombie, once", List.of("beam ENTITY 2"), heard);
        assertEquals(2, wave.hitEntity());

        wave.stop();
        for (int t = 0; t < 600 && !run.done(); t++) run.advance(0.05f);
        assertEquals("the burst at its point, then its front on the zombie",
                List.of("beam ENTITY 2", "blast TARGET -1 wide", "blast ENTITY 2"), heard);
        assertTrue("it ends by itself, its particles never stepped", run.done());
    }

    @Test(expected = IllegalStateException.class)
    public void anEffectRunsInOnePlaceOnly() {
        CgEnergyWave wave = new CgEnergyWave(CgEnergyWave.kamehameha(), 0, 1, 0);
        new CgVfxAuthority(wave, CgCollisionWorld.of(FLOOR, new Crowd()));
        new CgVfxAuthority(wave, CgCollisionWorld.of(FLOOR, new Crowd()));
    }
}
