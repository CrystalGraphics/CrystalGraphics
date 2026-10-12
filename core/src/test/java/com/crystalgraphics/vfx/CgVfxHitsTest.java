package com.crystalgraphics.vfx;

import com.crystalgraphics.collision.CgHitPart;
import com.crystalgraphics.collision.hitbox.CgSentHit;
import com.crystalgraphics.collision.query.CgHit;
import com.crystalgraphics.net.object.CgNetWire;
import com.crystalgraphics.vfx.effect.beam.CgEnergyWave;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/** An effect's hits reach its own listeners, by part; a wave reports its beam, its burst, then its blast. */
public class CgVfxHitsTest {

    private static final CgVfxSchema SCHEMA = new CgVfxSchema();
    private static final CgHitPart PART_BOLT = SCHEMA.part("bolt"), PART_ARC = SCHEMA.part("arc");
    private static final CgVfxLook LOOK = CgVfxLook.builder(SCHEMA).build();

    /** Strikes entity 7 with its bolt, then block 3,0,4 with its arc, each tick. */
    static final class Striker extends CgVfxEffect {
        private final CgHit struck = new CgHit();

        Striker() {
            super(LOOK, 10, 0, 0);
        }

        @Override
        protected void tick(float dt) {
            hit(struck.setEntity(7, 7, 12, 1, 0, Float.NaN, PART_BOLT));
            hit(struck.setBlock(3, 0, 4, 3.5, 1, 4.5, PART_ARC));
        }

        @Override
        protected void submit(CgVfxFrame frame) {
        }
    }

    @Test
    public void anEffectsHitsReachItsListenersByPart() {
        List<String> heard = new ArrayList<>();
        Striker striker = new Striker();
        striker.onHit(h -> heard.add(h.part() + " " + h.kind() + " " + h.entity()))
                .onHit(PART_ARC, h -> heard.add("arc alone at " + h.blockX() + "," + h.blockZ()));
        striker.tick(0f);
        assertEquals(List.of("bolt ENTITY 7", "arc BLOCK -1", "arc alone at 3,4"), heard);
        assertEquals(1, SCHEMA.indexOf(PART_ARC));
        assertSame(PART_BOLT, SCHEMA.partAt(0));
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
        CgEnergyWave wave = CgVfx.owner("wave_hits_test", vfx).play(new CgEnergyWave(CgEnergyWave.kamehameha(), 0, 0, 0));
        wave.aim(1f, 0f, 0f).target(8, 0, 0);
        List<String> heard = new ArrayList<>();
        wave.onHit(h -> heard.add(h.part() + " " + h.kind() + " " + Math.round(h.x())));
        for (int f = 0; f <= 600; f++) vfx.update(f / 60.0);
        assertEquals("held on the target, not every tick", List.of("beam POINT 8"), heard);
        wave.stop();
        for (int f = 601; f <= 1500; f++) vfx.update(f / 60.0);
        assertEquals(List.of("beam POINT 8", "burst POINT 8"), heard);
    }

    @Test
    public void aHitCrossesTheWireAsItsKindPointAndPart() {
        CgNetWire<CgSentHit> wire = CgSentHit.wire();
        List<String> read = new ArrayList<>();
        CgHit[] hits = {new CgHit().setEntity(2001, 2000, 1.5, 64, -3.25, 0.5f, PART_ARC),
                new CgHit().setBlock(-4, 63, 9, -3.5, 64, 9.5, PART_BOLT), new CgHit().setPoint(8, 0, 0, null)};
        for (CgHit h : hits) {
            CgSentHit sent = new CgSentHit().set(h, SCHEMA.indexOf(h.part()));
            Tape tape = new Tape();
            wire.write(sent, tape);
            CgSentHit back = wire.read(tape);
            CgHit got = back.read(new CgHit(), SCHEMA.partAt(back.part()));
            read.add(got.kind() + " " + got.entity() + "/" + got.parent() + " " + got.blockX() + "," + got.blockY() + ","
                    + got.blockZ() + " " + got.x() + " " + got.part());
        }
        assertEquals(List.of("ENTITY 2001/2000 0,0,0 1.5 arc", "BLOCK -1/-1 -4,63,9 -3.5 bolt",
                "POINT -1/-1 0,0,0 8.0 null"), read);
    }

    /** Values written, read back in order. */
    private static final class Tape implements CgNetWire.Out, CgNetWire.In {
        private final ArrayDeque<Object> values = new ArrayDeque<>();

        @Override public void writeBool(boolean b) { values.add(b); }
        @Override public void writeVarInt(int v) { values.add(v); }
        @Override public void writeZigZag(long v) { values.add(v); }
        @Override public void writeInt(int v) { values.add(v); }
        @Override public void writeLong(long v) { values.add(v); }
        @Override public void writeFloat(float v) { values.add(v); }
        @Override public void writeDouble(double v) { values.add(v); }
        @Override public boolean readBool() { return (Boolean) values.poll(); }
        @Override public int readVarInt() { return (Integer) values.poll(); }
        @Override public long readZigZag() { return (Long) values.poll(); }
        @Override public int readInt() { return (Integer) values.poll(); }
        @Override public long readLong() { return (Long) values.poll(); }
        @Override public float readFloat() { return (Float) values.poll(); }
        @Override public double readDouble() { return (Double) values.poll(); }
    }
}
