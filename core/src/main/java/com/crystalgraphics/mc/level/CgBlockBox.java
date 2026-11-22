package com.crystalgraphics.mc.level;

import com.crystalgraphics.mc.CgCommandReader;
import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecs;

/**
 * A box of whole blocks between two corners, both inside it, as {@code /fill} takes them and the game's
 * {@code BoundingBox} holds them.
 *
 * <pre>{@code
 * CgBlockBox room = CgBlockBox.parse("0 64 0 9 70 9");           // /fill's corners, any order
 * CgBlockBox same = CgBlockBox.of(new CgBlockPos(9, 70, 9), new CgBlockPos(0, 64, 0));
 * boolean in = room.contains(new CgBlockPos(4, 66, 4));         // true
 * int blocks = room.sizeX() * room.sizeY() * room.sizeZ();     // 10 * 7 * 10
 * }</pre>
 *
 * <p>The corners are always ordered: {@code min} at or below {@code max} on each axis, whichever way they were given.</p>
 */
public record CgBlockBox(CgBlockPos min, CgBlockPos max) {

    /** {@code {min: [x, y, z], max: [x, y, z]}}. */
    public static final CgCodec<CgBlockBox> CODEC = CgCodecs.record(
            CgCodecs.field("min", CgBlockPos.CODEC, CgBlockBox::min),
            CgCodecs.field("max", CgBlockPos.CODEC, CgBlockBox::max),
            CgBlockBox::of);

    public CgBlockBox {
        CgBlockPos lo = new CgBlockPos(Math.min(min.x(), max.x()), Math.min(min.y(), max.y()), Math.min(min.z(), max.z()));
        CgBlockPos hi = new CgBlockPos(Math.max(min.x(), max.x()), Math.max(min.y(), max.y()), Math.max(min.z(), max.z()));
        min = lo;
        max = hi;
    }

    /** The box both corners are in, given in any order. */
    public static CgBlockBox of(CgBlockPos a, CgBlockPos b) {
        return new CgBlockBox(a, b);
    }

    /** One block. */
    public static CgBlockBox of(CgBlockPos pos) {
        return new CgBlockBox(pos, pos);
    }

    /** From {@code x1 y1 z1 x2 y2 z2}. */
    public static CgBlockBox parse(String text) {
        CgCommandReader r = new CgCommandReader(text);
        CgBlockPos a = CgBlockPos.read(r);
        CgBlockPos b = CgBlockPos.read(r);
        r.expectEnd();
        return new CgBlockBox(a, b);
    }

    public int sizeX() {
        return max.x() - min.x() + 1;
    }

    public int sizeY() {
        return max.y() - min.y() + 1;
    }

    public int sizeZ() {
        return max.z() - min.z() + 1;
    }

    public boolean contains(CgBlockPos p) {
        return p.x() >= min.x() && p.x() <= max.x() && p.y() >= min.y() && p.y() <= max.y()
                && p.z() >= min.z() && p.z() <= max.z();
    }

    /** The smallest box holding this one and {@code p}. */
    public CgBlockBox encapsulating(CgBlockPos p) {
        return new CgBlockBox(new CgBlockPos(Math.min(min.x(), p.x()), Math.min(min.y(), p.y()), Math.min(min.z(), p.z())),
                new CgBlockPos(Math.max(max.x(), p.x()), Math.max(max.y(), p.y()), Math.max(max.z(), p.z())));
    }

    @Override
    public String toString() {
        return min + " " + max;
    }
}
