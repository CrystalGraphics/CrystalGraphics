package com.crystalgraphics.collision;

import com.crystalgraphics.net.object.CgNetWire;
import com.crystalgraphics.platform.service.CgWorldQuery;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;

/** A hit crosses the wire as its kind, point and part; a hittable hitbox as its owner. */
public class CgSentHitTest {

    private static final CgHitPart ARC = CgHitPart.of("arc"), BOLT = CgHitPart.of("bolt");
    private static final List<CgHitPart> PARTS = Arrays.asList(ARC, BOLT);

    @Test
    public void aHitCrossesTheWireAsItsKindPointAndPart() {
        CgNetWire<CgSentHit> wire = CgSentHit.wire();
        List<String> read = new ArrayList<>();
        CgHit[] hits = {new CgHit().setEntity(2001, 2000, 1.5, 64, -3.25, 0.5f, ARC),
                new CgHit().setBlock(-4, 63, 9, -3.5, 64, 9.5, BOLT), new CgHit().setPoint(8, 0, 0, null),
                new CgHit().setFluid(2, 62, 2, CgWorldQuery.FLUID_LAVA, 2.5, 62.9, 2.5, BOLT),
                new CgHit().setUnloaded(100, 70, 0, 100, 70.5, 0.5, null),
                new CgHit().setBorder(11, 64, -20, 11, 64.5, -19.5, ARC),
                new CgHit().setHitbox(3001, 4, 65, 0, BOLT)};
        for (CgHit h : hits) {
            CgSentHit sent = new CgSentHit().set(h, PARTS.indexOf(h.part()));
            Tape tape = new Tape();
            wire.write(sent, tape);
            CgSentHit back = wire.read(tape);
            CgHit got = back.read(new CgHit(), back.part() < 0 ? null : PARTS.get(back.part()));
            read.add(got.kind() + " " + got.entity() + "/" + got.parent() + " " + got.blockX() + "," + got.blockY() + ","
                    + got.blockZ() + " " + got.x() + " " + got.part() + (got.fluid() != 0 ? " fluid " + got.fluid() : ""));
        }
        assertEquals(List.of("ENTITY 2001/2000 0,0,0 1.5 arc", "BLOCK -1/-1 -4,63,9 -3.5 bolt",
                "POINT -1/-1 0,0,0 8.0 null", "FLUID -1/-1 2,62,2 2.5 bolt fluid 2",
                "UNLOADED -1/-1 100,70,0 100.0 null", "BORDER -1/-1 11,64,-20 11.0 arc",
                "HITBOX 3001/3001 0,0,0 4.0 bolt"), read);
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
