package com.crystalgraphics.vfx;

import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import org.joml.Matrix4f;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Scalability: culled by distance to bounds, by count and out of view; put to sleep and woken at its true age. */
public class CgVfxScalabilityTest {

    private static final CgVfxSchema SCHEMA = CgVfxSchema.of("test:scalability");
    private static final CgVfxLook LOOK = CgVfxLook.builder(SCHEMA).build();

    /** Counts its ticks; drawn nowhere. */
    static final class Dot extends CgVfxEffect<Dot> {
        int ticks;

        Dot(double z, CgVfxScalability scalability) {
            super(LOOK, 0, 0, z);
            scalability(scalability);
        }

        @Override
        protected void tick(float dt) {
            ticks++;
        }

        @Override
        protected void submit(CgVfxFrame frame) {
        }
    }

    /** The world's camera at {@code (0, 0, z)}, looking down -z. */
    private static void camera(double z) {
        CgRenderStage.WORLD_OPAQUE.host().view().set(0, 0, z, new Matrix4f(), new Matrix4f().perspective(1.2f, 1f, 0.05f, 2000f));
    }

    @After
    public void noCamera() {
        CgRenderStage.WORLD_OPAQUE.host().view().set(0, 0, 0, new Matrix4f(), new Matrix4f().zero());
    }

    private static void run(CgVfxSystem vfx, double from, double seconds) {
        for (int f = 0; f <= Math.round(seconds * 60); f++) vfx.update(from + f / 60.0);
    }

    @Test
    public void aFarEffectSleepsThenWakesAtItsTrueAge() {
        camera(0);
        CgVfxSystem vfx = new CgVfxSystem();
        Dot far = vfx.play(new Dot(-300, CgVfxScalability.DEFAULT));
        run(vfx, 0, 1);
        assertTrue(far.asleep());
        assertEquals(0, far.ticks);
        camera(-290);
        run(vfx, 1 + 1 / 60.0, 0.5);
        assertFalse(far.asleep());
        // Every tick of its 1.5 s, the slept second stepped at once as startAt steps one.
        assertEquals(180, far.ticks, 3);
    }

    @Test
    public void aCapKeepsTheNearest() {
        camera(0);
        CgVfxSystem vfx = new CgVfxSystem();
        List<Dot> dots = new ArrayList<>();
        for (int i = 0; i < 60; i++) dots.add(vfx.play(new Dot(-2 - i, CgVfxScalability.IMPACT)));
        run(vfx, 0, 0.1);
        for (int i = 0; i < 60; i++) {
            boolean alive = dots.get(i).state() != CgVfxEffect.State.DEAD;
            assertEquals("dot " + i, i < 48, alive);
        }
    }

    @Test
    public void anEffectOutOfViewIsCulledAfterItsUnseenTime() {
        camera(0);
        CgVfxSystem vfx = new CgVfxSystem();
        Dot behind = vfx.play(new Dot(10, CgVfxScalability.AMBIENT)), ahead = vfx.play(new Dot(-10, CgVfxScalability.AMBIENT));
        run(vfx, 0, 1.5);
        assertFalse(behind.asleep());
        run(vfx, 1.5 + 1 / 60.0, 1);
        assertTrue(behind.asleep());
        assertFalse(ahead.asleep());
    }

    @Test
    public void distanceLowersTheTierAndTheShare() {
        camera(0);
        CgVfxSystem vfx = new CgVfxSystem();
        Dot near = vfx.play(new Dot(-20, CgVfxScalability.DEFAULT)), mid = vfx.play(new Dot(-60, CgVfxScalability.DEFAULT));
        Dot far = vfx.play(new Dot(-120, CgVfxScalability.DEFAULT));
        run(vfx, 0, 0.1);
        assertEquals(0, near.lowered);
        assertEquals(1, mid.lowered);
        assertEquals(2, far.lowered);
        assertEquals(0.25f, far.distanceShare, 0f);
    }

    @Test
    public void heroIsNeverCulledAndBoundsCountOverTheOrigin() {
        camera(0);
        CgVfxSystem vfx = new CgVfxSystem();
        Dot hero = vfx.play(new Dot(-1000, CgVfxScalability.HERO));
        // Its origin is 400 blocks out, but what it drew reaches back to 20 of the camera.
        Dot beam = vfx.play(new Dot(-400, CgVfxScalability.DEFAULT));
        beam.boundBox(-1, -1, 0, 1, 1, 380);
        run(vfx, 0, 0.5);
        assertFalse(hero.asleep());
        assertFalse(beam.asleep());
        assertEquals(20f, beam.cullDistance, 0.01f);
    }
}
