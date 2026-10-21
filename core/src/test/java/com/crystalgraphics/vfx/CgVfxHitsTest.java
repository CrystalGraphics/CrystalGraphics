package com.crystalgraphics.vfx;

import com.crystalgraphics.collision.hit.CgHit;
import com.crystalgraphics.collision.hit.CgHitPart;
import com.crystalgraphics.vfx.effect.beam.CgEnergyWave;
import com.crystalgraphics.vfx.system.CgVfxSystemInstance;
import com.crystalgraphics.vfx.look.CgVfxLayout;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/** An effect's hits reach its own listeners, by part; a wave reports its beam, its burst, then its blast. */
public class CgVfxHitsTest {

    private static final CgVfxSchema SCHEMA = CgVfxSchema.of("test:hits");
    private static final CgHitPart PART_BOLT = SCHEMA.part("bolt"), PART_ARC = SCHEMA.part("arc");
    private static final CgVfxLook LOOK = CgVfxLook.builder(SCHEMA).build();

    /** Strikes entity 7 with its bolt, then block 3,0,4 with its arc, each tick. */
    static final class Striker extends CgVfxEffect<Striker> {
        private final CgHit struck = new CgHit();

        Striker() {
            super(LOOK, 10, 0, 0);
        }

        @Override
        protected void tick(float dt) {
            hit(struck.setPoint(12, 1, 0, PART_BOLT).setEntity(7, 7));
            hit(struck.setPoint(3.5, 1, 4.5, PART_ARC).setBlock(3, 0, 4));
        }

        @Override
        protected void submit(CgVfxFrame frame) {
        }
    }

    @Test
    public void anEffectsHitsReachItsListenersByPart() {
        List<String> heard = new ArrayList<>();
        Striker striker = new Striker();
        striker.onHit(h -> heard.add(h.part() + " " + h.kind() + " " + h.entityId()))
                .onHit(PART_ARC, h -> heard.add("arc alone at " + h.blockX() + "," + h.blockZ()));
        striker.tick(0f);
        assertEquals(List.of("bolt ENTITY 7", "arc BLOCK -1", "arc alone at 3,4"), heard);
        assertEquals(1, CgVfxLayout.of(List.of(SCHEMA)).indexOf(PART_ARC));
        assertSame(PART_BOLT, CgVfxLayout.of(List.of(SCHEMA)).partAt(0));
    }

    @Test
    public void aListenerThatThrowsLeavesTheOthersHearing() {
        Striker striker = new Striker();
        List<String> heard = new ArrayList<>();
        striker.onHit(h -> { throw new IllegalStateException("a listener's own bug"); }).onHit(h -> heard.add("" + h.kind()));
        striker.tick(0f);
        assertEquals(List.of("ENTITY", "BLOCK"), heard);
    }

    @Test
    public void aWaveReportsItsBeamOnceThenItsBurstAtTheSamePoint() {
        CgVfxSystem vfx = new CgVfxSystem();
        CgVfxSystemInstance<CgEnergyWave> beam = CgVfx.owner("wave_hits_test", vfx).play(CgEnergyWave.instance(CgEnergyWave.kamehameha(), 0, 0, 0));
        beam.behaviour().aim(1f, 0f, 0f).target(8, 0, 0);
        List<String> heard = new ArrayList<>();
        beam.onHit(h -> heard.add(h.part() + " " + h.kind() + " " + Math.round(h.x())));
        for (int f = 0; f <= 600; f++) vfx.update(f / 60.0);
        assertEquals("held on the target, not every tick", List.of("beam POINT 8"), heard);
        beam.stop();
        for (int f = 601; f <= 1500; f++) vfx.update(f / 60.0);
        assertEquals(List.of("beam POINT 8", "burst POINT 8"), heard);
    }
}
