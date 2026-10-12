package com.crystalgraphics.collision.debug;

import java.nio.ByteBuffer;

/**
 * Line segments packed for the wire: an origin, then each end as shorts in {@link #UNIT}s from it. What a server sends a
 * client that shows collision. Ends past {@link #REACH} are clamped.
 */
final class CgLinePack implements CgShapeLines.Sink {

    static final double UNIT = 1.0 / 128;
    static final double REACH = Short.MAX_VALUE * UNIT;
    private static final int HEADER = 3 * 8 + 4, SEGMENT = 6 * 2;

    private ByteBuffer segments = ByteBuffer.allocate(SEGMENT * 256);
    private double ox, oy, oz;
    private int count, max;

    /** Starts a pack about the origin, holding at most {@code max} segments. */
    void begin(double ox, double oy, double oz, int max) {
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.max = max;
        count = 0;
        segments.clear();
    }

    boolean full() {
        return count >= max;
    }

    int count() {
        return count;
    }

    @Override
    public void line(double ax, double ay, double az, double bx, double by, double bz) {
        if (count >= max) return;
        if (segments.remaining() < SEGMENT) {
            ByteBuffer grown = ByteBuffer.allocate(segments.capacity() * 2);
            segments.flip();
            grown.put(segments);
            segments = grown;
        }
        segments.putShort(unit(ax - ox)).putShort(unit(ay - oy)).putShort(unit(az - oz))
                .putShort(unit(bx - ox)).putShort(unit(by - oy)).putShort(unit(bz - oz));
        count++;
    }

    /** The pack as bytes. */
    byte[] finish() {
        ByteBuffer out = ByteBuffer.allocate(HEADER + count * SEGMENT);
        out.putDouble(ox).putDouble(oy).putDouble(oz).putInt(count);
        out.put(segments.array(), 0, count * SEGMENT);
        return out.array();
    }

    /** Hands {@code out} each segment of a pack, back in world space; how many there were. */
    static int read(byte[] pack, CgShapeLines.Sink out) {
        ByteBuffer in = ByteBuffer.wrap(pack);
        if (in.remaining() < HEADER) return 0;
        double ox = in.getDouble(), oy = in.getDouble(), oz = in.getDouble();
        int count = Math.min(in.getInt(), in.remaining() / SEGMENT);
        for (int i = 0; i < count; i++) {
            out.line(ox + in.getShort() * UNIT, oy + in.getShort() * UNIT, oz + in.getShort() * UNIT,
                    ox + in.getShort() * UNIT, oy + in.getShort() * UNIT, oz + in.getShort() * UNIT);
        }
        return count;
    }

    private static short unit(double d) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(d / UNIT)));
    }
}
