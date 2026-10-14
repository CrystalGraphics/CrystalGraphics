package com.crystalgraphics.collision.debug;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Line segments and face triangles packed for the wire: an origin, runs of one colour and one kind each, then each
 * point as shorts in {@link #UNIT}s from the origin. What a server sends a client that shows collision. Points past
 * {@link #REACH} are clamped; a run's colour 0 is drawn in the reader's own; a run of faces is scaled by the reader's
 * fill opacity, may begin a piece, and may be drawn only by a reader drawing transparent.
 */
final class CgLinePack implements CgShapeLines.Sink {

    static final double UNIT = 1.0 / 128;
    static final double REACH = Short.MAX_VALUE * UNIT;
    private static final int HEADER = 3 * 8 + 4 + 4, RUN = 4 + 4, SEGMENT = 6 * 2, TRIANGLE = 9 * 2;
    /** Set in a run's length when it counts triangles, when those begin a piece, and when their hitbox set no fill. */
    private static final int FACES = 1 << 31, PIECE = 1 << 30, DEFAULT = 1 << 29;

    private ByteBuffer segments = ByteBuffer.allocate(SEGMENT * 256);
    private int[] runColours = new int[8], runLengths = new int[8];
    private double ox, oy, oz;
    private int count, max, runs, colour;
    private boolean pieceNext, defaultFill;

    /** Starts a pack about the origin, holding at most {@code max} segments and triangles. */
    void begin(double ox, double oy, double oz, int max) {
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.max = max;
        count = runs = colour = 0;
        pieceNext = defaultFill = false;
        segments.clear();
    }

    /** The colour the segments after this are drawn in; 0 for the reader's own. */
    void colour(int argb) {
        colour = argb;
    }

    @Override
    public void piece() {
        pieceNext = true;
    }

    /** Whether the faces after this are drawn only by a reader drawing transparent: their hitbox set no fill. */
    void defaultFill(boolean on) {
        defaultFill = on;
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
        record(SEGMENT, 0);
        segments.putShort(unit(ax - ox)).putShort(unit(ay - oy)).putShort(unit(az - oz))
                .putShort(unit(bx - ox)).putShort(unit(by - oy)).putShort(unit(bz - oz));
    }

    @Override
    public void triangle(double ax, double ay, double az, double bx, double by, double bz, double cx, double cy,
            double cz) {
        if (count >= max) return;
        record(TRIANGLE, FACES);
        segments.putShort(unit(ax - ox)).putShort(unit(ay - oy)).putShort(unit(az - oz))
                .putShort(unit(bx - ox)).putShort(unit(by - oy)).putShort(unit(bz - oz))
                .putShort(unit(cx - ox)).putShort(unit(cy - oy)).putShort(unit(cz - oz));
    }

    /** Makes room for {@code bytes} and counts one more in the run of this colour and {@code kind}, begun if new. */
    private void record(int bytes, int kind) {
        if (segments.remaining() < bytes) {
            ByteBuffer grown = ByteBuffer.allocate(segments.capacity() * 2);
            segments.flip();
            grown.put(segments);
            segments = grown;
        }
        boolean piece = kind == FACES && pieceNext;
        int flags = kind | (kind == FACES && defaultFill ? DEFAULT : 0);
        if (piece || runs == 0 || runColours[runs - 1] != colour || (runLengths[runs - 1] & (FACES | DEFAULT)) != flags) {
            if (runs == runColours.length) {
                runColours = Arrays.copyOf(runColours, runs * 2);
                runLengths = Arrays.copyOf(runLengths, runs * 2);
            }
            runColours[runs] = colour;
            runLengths[runs++] = flags | (piece ? PIECE : 0);
        }
        if (kind == FACES) pieceNext = false;
        runLengths[runs - 1]++;
        count++;
    }

    /** The pack as bytes. */
    byte[] finish() {
        ByteBuffer out = ByteBuffer.allocate(HEADER + runs * RUN + segments.position());
        out.putDouble(ox).putDouble(oy).putDouble(oz).putInt(count).putInt(runs);
        for (int r = 0; r < runs; r++) out.putInt(runColours[r]).putInt(runLengths[r]);
        out.put(segments.array(), 0, segments.position());
        return out.array();
    }

    /**
     * Hands {@code out} what a pack holds, back in world space, in its run's colour or {@code argb}, faces at
     * {@code opacity} of 255 of their alpha and those marked only {@code transparent}; how many it holds.
     */
    static int read(byte[] pack, int argb, int opacity, boolean transparent, CgRecentLines.Out out) {
        ByteBuffer in = ByteBuffer.wrap(pack);
        if (in.remaining() < HEADER) return 0;
        double ox = in.getDouble(), oy = in.getDouble(), oz = in.getDouble();
        int count = in.getInt(), runs = in.getInt();
        if (runs < 0 || runs > in.remaining() / RUN) return 0;
        int table = in.position(), at = table + runs * RUN;
        int read = 0;
        for (int r = 0; r < runs && read < count; r++) {
            int colour = in.getInt(table + r * RUN), n = in.getInt(table + r * RUN + 4);
            int drawn = colour == 0 ? argb : colour;
            boolean faces = (n & FACES) != 0, skipped = false;
            int size = faces ? TRIANGLE : SEGMENT;
            if ((n & PIECE) != 0) out.piece();
            if (faces) {
                skipped = (n & DEFAULT) != 0 && !transparent;
                drawn = CgRecentLines.scaled(drawn, opacity);
            }
            n &= ~(FACES | PIECE | DEFAULT);
            for (int k = 0; k < n && read < count && at + size <= pack.length; k++, read++, at += size) {
                double ax = ox + in.getShort(at) * UNIT, ay = oy + in.getShort(at + 2) * UNIT;
                double az = oz + in.getShort(at + 4) * UNIT, bx = ox + in.getShort(at + 6) * UNIT;
                double by = oy + in.getShort(at + 8) * UNIT, bz = oz + in.getShort(at + 10) * UNIT;
                if (skipped) {
                    continue;
                } else if (faces) {
                    out.triangle(drawn, ax, ay, az, bx, by, bz, ox + in.getShort(at + 12) * UNIT,
                            oy + in.getShort(at + 14) * UNIT, oz + in.getShort(at + 16) * UNIT);
                } else {
                    out.line(drawn, ax, ay, az, bx, by, bz);
                }
            }
        }
        return read;
    }

    private static short unit(double d) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(d / UNIT)));
    }
}
