package com.crystalgraphics.mc.level;

import java.util.Arrays;
import java.util.List;

import com.crystalgraphics.mc.CgCommandReader;
import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.CgCodecs;

/**
 * A block's position: three whole numbers, as the game's {@code BlockPos}. Written as a command writes it.
 *
 * <pre>{@code
 * CgBlockPos spawn = CgBlockPos.parse("10 64 -3");
 * CgBlockPos above = spawn.above();                       // 10 65 -3
 * CgBlockPos east = spawn.relative(CgDirection.EAST, 2);  // 12 64 -3
 * long key = spawn.asLong();                              // the game's own packing, 1.14 on
 * }</pre>
 *
 * <ul>
 *   <li>{@link #toString} is {@code x y z}, what {@link #parse} reads; {@link #CODEC} is the game's {@code [x, y, z]}.</li>
 *   <li>Relative coordinates ({@code ~}) are a command's, against a sender: not a position, so not parsed.</li>
 * </ul>
 */
public record CgBlockPos(int x, int y, int z) {

    public static final CgBlockPos ORIGIN = new CgBlockPos(0, 0, 0);

    /** {@code [x, y, z]}, as the game's codec writes a position. */
    public static final CgCodec<CgBlockPos> CODEC = CgCodecs.xmap(CgCodecs.listOf(CgCodecs.INT), CgBlockPos::fromList,
            p -> Arrays.asList(p.x, p.y, p.z));

    private static final int BITS_XZ = 26, BITS_Y = 12;
    private static final long MASK_XZ = (1L << BITS_XZ) - 1, MASK_Y = (1L << BITS_Y) - 1;

    /** From {@code x y z}. */
    public static CgBlockPos parse(String text) {
        CgCommandReader r = new CgCommandReader(text);
        CgBlockPos pos = read(r);
        r.expectEnd();
        return pos;
    }

    /** Three numbers from {@code r}, spaces before each skipped: how a box or a command reads a position. */
    public static CgBlockPos read(CgCommandReader r) {
        r.skipSpaces();
        int x = r.readInt();
        r.skipSpaces();
        int y = r.readInt();
        r.skipSpaces();
        int z = r.readInt();
        return new CgBlockPos(x, y, z);
    }

    /** From {@link #asLong}'s packing. */
    public static CgBlockPos of(long packed) {
        return new CgBlockPos((int) (packed >> (BITS_XZ + BITS_Y)), (int) (packed << (64 - BITS_Y) >> (64 - BITS_Y)),
                (int) (packed << (64 - BITS_Y - BITS_XZ) >> (64 - BITS_XZ)));
    }

    /** The game's packing from 1.14 ({@code BlockPos.asLong}): x in 26 bits, z in 26, y in 12. */
    public long asLong() {
        return (x & MASK_XZ) << (BITS_XZ + BITS_Y) | (z & MASK_XZ) << BITS_Y | (y & MASK_Y);
    }

    public CgBlockPos offset(int dx, int dy, int dz) {
        return new CgBlockPos(x + dx, y + dy, z + dz);
    }

    public CgBlockPos relative(CgDirection face) {
        return relative(face, 1);
    }

    public CgBlockPos relative(CgDirection face, int distance) {
        return offset(face.stepX() * distance, face.stepY() * distance, face.stepZ() * distance);
    }

    public CgBlockPos above() {
        return relative(CgDirection.UP);
    }

    public CgBlockPos below() {
        return relative(CgDirection.DOWN);
    }

    @Override
    public String toString() {
        return x + " " + y + " " + z;
    }

    private static CgBlockPos fromList(List<Integer> xyz) {
        if (xyz.size() != 3) throw new CgCodecException("a position is [x, y, z], not " + xyz);
        return new CgBlockPos(xyz.get(0), xyz.get(1), xyz.get(2));
    }
}
