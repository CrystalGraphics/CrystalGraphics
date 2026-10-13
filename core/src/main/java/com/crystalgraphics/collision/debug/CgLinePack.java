package com.crystalgraphics.collision.debug;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Line segments packed for the wire: an origin, runs of one colour each, then each end as shorts in {@link #UNIT}s from
 * the origin. What a server sends a client that shows collision. Ends past {@link #REACH} are clamped; a run's colour
 * 0 is drawn in the reader's own.
 */
final class CgLinePack implements CgShapeLines.Sink {

    static final double UNIT = 1.0 / 128;
    static final double REACH = Short.MAX_VALUE * UNIT;
    private static final int HEADER = 3 * 8 + 4 + 4, RUN = 4 + 4, SEGMENT = 6 * 2;

    private ByteBuffer segments = ByteBuffer.allocate(SEGMENT * 256);
    private int[] runColours = new int[8], runLengths = new int[8];
    private double ox, oy, oz;
    private int count, max, runs, colour;

    /** Starts a pack about the origin, holding at most {@code max} segments. */
    void begin(double ox, double oy, double oz, int max) {
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.max = max;
        count = runs = colour = 0;
        segments.clear();
    }

    /** The colour the segments after this are drawn in; 0 for the reader's own. */
    void colour(int argb) {
        colour = argb;
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
        if (runs == 0 || runColours[runs - 1] != colour) {
            if (runs == runColours.length) {
                runColours = Arrays.copyOf(runColours, runs * 2);
                runLengths = Arrays.copyOf(runLengths, runs * 2);
            }
            runColours[runs] = colour;
            runLengths[runs++] = 0;
        }
        runLengths[runs - 1]++;
        count++;
    }

    /** The pack as bytes. */
    byte[] finish() {
        ByteBuffer out = ByteBuffer.allocate(HEADER + runs * RUN + count * SEGMENT);
        out.putDouble(ox).putDouble(oy).putDouble(oz).putInt(count).putInt(runs);
        for (int r = 0; r < runs; r++) out.putInt(runColours[r]).putInt(runLengths[r]);
        out.put(segments.array(), 0, count * SEGMENT);
        return out.array();
    }

    /** Hands {@code out} each segment of a pack, back in world space, in its run's colour or {@code argb}; how many. */
    static int read(byte[] pack, int argb, CgRecentLines.Out out) {
        ByteBuffer in = ByteBuffer.wrap(pack);
        if (in.remaining() < HEADER) return 0;
        double ox = in.getDouble(), oy = in.getDouble(), oz = in.getDouble();
        int count = in.getInt(), runs = in.getInt();
        if (runs < 0 || runs > in.remaining() / RUN) return 0;
        int table = in.position(), at = table + runs * RUN;
        count = Math.max(0, Math.min(count, (pack.length - at) / SEGMENT));
        int read = 0;
        for (int r = 0; r < runs && read < count; r++) {
            int colour = in.getInt(table + r * RUN), n = in.getInt(table + r * RUN + 4);
            int drawn = colour == 0 ? argb : colour;
            for (int k = 0; k < n && read < count; k++, read++, at += SEGMENT) {
                out.line(drawn, ox + in.getShort(at) * UNIT, oy + in.getShort(at + 2) * UNIT,
                        oz + in.getShort(at + 4) * UNIT, ox + in.getShort(at + 6) * UNIT,
                        oy + in.getShort(at + 8) * UNIT, oz + in.getShort(at + 10) * UNIT);
            }
        }
        return read;
    }

    private static short unit(double d) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(d / UNIT)));
    }
}
