package com.crystalgraphics.vfx;

import com.crystalgraphics.vfx.effect.beam.CgEnergyWave;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEventRows;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/** One dispatch for effects' and particles' collisions: the effect's listeners, its owner's, then the global ones. */
public class CgVfxCollisionsTest {

    private static final CgVfxLook LOOK = CgVfxLook.builder(new CgVfxSchema()).build();

    /** Strikes entity 7 on its second tick. */
    static final class Striker extends CgVfxEffect {
        int ticks;

        Striker() {
            super(LOOK, 10, 0, 0);
        }

        @Override
        protected void tick(float dt) {
            if (++ticks == 2) collided(CgVfxCollision.Hit.ENTITY, 7, 12, 1, 0, -1f, 0f, 0f, 30f, 0f, 0f);
        }

        @Override
        protected void submit(CgVfxFrame frame) {
        }
    }

    @Test
    public void anEffectsCollisionIsHeardByItThenItsOwnerThenEveryone() {
        CgVfxSystem vfx = new CgVfxSystem();
        CgVfxOwner owner = CgVfx.owner("collide_test", vfx);
        List<String> heard = new ArrayList<>();
        Striker striker = owner.play(new Striker());
        striker.onCollide(c -> heard.add("effect " + c.hit() + " " + c.entity() + " " + c.x()));
        striker.onCollide(() -> heard.add("effect again"));
        CgVfxCollision.Registration mod = owner.onCollide(c -> heard.add("owner " + c.source()));
        CgVfxCollision.Registration all = CgVfx.onCollide(c -> {
            if (c.effect() == striker) heard.add("global");
        });
        try {
            for (int f = 0; f <= 3; f++) vfx.update(f / 60.0);
        } finally {
            mod.close();
            all.close();
        }
        assertEquals(List.of("effect ENTITY 7 12.0", "effect again", "owner EFFECT", "global"), heard);
    }

    @Test
    public void particleRowsReachTheEffectWhoseSlotTheyCameFrom() {
        CgVfxEmitter debris = CgVfxEmitter.builder("debris").capacity(16).reportCollisions(4)
                .event(CgVfxEvent.onLanding().readback(4)).build();
        Striker effect = new Striker();
        List<CgVfxCollision> heard = new ArrayList<>();
        effect.onCollide(c -> {
            assertSame(effect, c.effect());
            assertSame(debris, c.emitter());
            assertEquals(CgVfxCollision.Source.PARTICLE, c.source());
            heard.add(c);
        });

        CgVfxEventRows rows = new CgVfxEventRows();
        rows.add(3, 99, 1, 2, 3, 0f, -4f, 0f, 0f, 1f, 0f, 2);
        rows.tag(effect, CgVfxEventRows.HIT_BLOCK, 0);
        rows.add(3, 98, 1, 2, 3, 0f, -4f, 0f, 0f, 1f, 0f, 1);
        rows.tag(effect, CgVfxEventRows.HIT_ENTITY, 42);
        rows.add(3, 97, 1, 2, 3, 0f, -4f, 0f, 0f, 1f, 0f, 1);   // another effect's: not this one's
        rows.tag(new Striker(), CgVfxEventRows.HIT_BLOCK, 0);
        CgVfxCollisions.ROWS.events(debris, 0, rows);
        assertEquals(2, heard.size());

        rows.clear();
        rows.add(3, 96, 5, 6, 7, 0f, -1f, 0f, 0f, 1f, 0f);
        rows.tag(effect, CgVfxEventRows.HIT_NONE, 0);
        heard.clear();
        List<String> kinds = new ArrayList<>();
        effect.onCollide(c -> kinds.add(c.hit() + " " + c.entity() + " " + c.firing()));
        CgVfxCollisions.ROWS.events(debris, 1, rows);
        assertEquals("a landing is the ground's", List.of("GROUND -1 0"), kinds);
    }

    @Test
    public void aWaveReportsItsBeamOnceAsItHitsThenItsBlastWithItsRadius() {
        CgVfxSystem vfx = new CgVfxSystem();
        CgVfxOwner owner = CgVfx.owner("wave_test", vfx);
        List<String> heard = new ArrayList<>();
        CgEnergyWave wave = owner.play(new CgEnergyWave(CgEnergyWave.kamehameha(), 0, 0, 0));
        wave.aim(1f, 0f, 0f).target(8, 0, 0);
        wave.onCollide(c -> heard.add(c.part() + " " + c.hit() + " " + Math.round(c.x()) + (c.radius() > 0f ? " wide" : "")));
        for (int f = 0; f <= 600; f++) vfx.update(f / 60.0);
        assertEquals("held on the target, not every tick", List.of("beam TARGET 8"), heard);
        wave.stop();
        for (int f = 601; f <= 1500; f++) vfx.update(f / 60.0);
        assertEquals(List.of("beam TARGET 8", "blast TARGET 8 wide"), heard);
    }

    @Test
    public void hitCodesNameWhatWasStruck() {
        CgVfxEmitter debris = CgVfxEmitter.builder("debris").capacity(16).reportCollisions().build();
        Striker effect = new Striker();
        List<String> kinds = new ArrayList<>();
        effect.onCollide(c -> kinds.add(c.hit() + " " + c.entity() + " " + c.firing()));
        CgVfxEventRows rows = new CgVfxEventRows();
        int[] codes = {CgVfxEventRows.HIT_BLOCK, CgVfxEventRows.HIT_ENTITY, CgVfxEventRows.HIT_SHAPE,
                CgVfxEventRows.HIT_SCENE, CgVfxEventRows.HIT_GROUND, CgVfxEventRows.HIT_OTHER};
        for (int i = 0; i < codes.length; i++) {
            rows.add(0, i, 0, 0, 0, 0f, 0f, 0f, 0f, 1f, 0f, i + 1);
            rows.tag(effect, codes[i], 5);
        }
        CgVfxCollisions.ROWS.events(debris, 0, rows);
        assertEquals(List.of("BLOCK -1 1", "ENTITY 5 2", "SHAPE -1 3", "SCENE -1 4", "GROUND -1 5", "OTHER -1 6"), kinds);
    }
}
