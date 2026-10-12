package com.crystalgraphics.net.object;

/**
 * A value written straight into a frame's bytes and read back, field by field: an event's fixed-width form, which
 * allocates nothing where a codec builds a tree per value. For an event emitted often, a hit or a shot.
 *
 * <pre>{@code
 * public final class Shot {                                      // mutable, and reused on both sides
 *     public int target;
 *     public float power;
 *
 *     public static final CgNetWire<Shot> WIRE = new CgNetWire<Shot>() {
 *         private final Shot read = new Shot();
 *         @Override public void write(Shot s, Out out) { out.writeVarInt(s.target); out.writeFloat(s.power); }
 *         @Override public Shot read(In in) {
 *             read.target = in.readVarInt();
 *             read.power = in.readFloat();
 *             return read;
 *         }
 *     };
 * }
 *
 * public static final CgNetEvent<Shot> FIRED = CgNetEvent.of("fired", Shot.WIRE);
 *
 * private final Shot shot = new Shot();                          // server: one, refilled per emit
 * turret.emit(FIRED, fill(shot));
 * }</pre>
 *
 * <ul>
 *   <li>Read exactly what was written, in order; the frame's length still bounds a read, so a short read skips the
 *       rest and a long one fails the frame.</li>
 *   <li>{@link #write} runs during the emit, so the value may be refilled for the next emit at once.</li>
 *   <li>A reused value read back is valid only during the listener's call.</li>
 *   <li>No strings: a name crosses as an index into something both sides declared.</li>
 * </ul>
 */
public interface CgNetWire<P> {

    void write(P value, Out out);

    P read(In in);

    /** A frame's bytes, written. Varints are unsigned; zigzag is signed and small either side of zero. */
    interface Out {
        void writeBool(boolean b);

        void writeVarInt(int v);

        void writeZigZag(long v);

        void writeInt(int v);

        void writeLong(long v);

        void writeFloat(float v);

        void writeDouble(double v);
    }

    /** A frame's bytes, read in the order they were written. */
    interface In {
        boolean readBool();

        int readVarInt();

        long readZigZag();

        int readInt();

        long readLong();

        float readFloat();

        double readDouble();
    }
}
