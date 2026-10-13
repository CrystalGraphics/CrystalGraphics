package com.crystalgraphics.collision.mirror;

/**
 * One section's block writes in the order made, each a cell and the state written: appended by the level's thread,
 * replayed by the mirror's worker up to a position the level thread marked at a publish.
 *
 * <ul>
 *   <li>One writer, one reader. Entries up to a mark are visible to the reader because the mark reaches it through the
 *       batch queue, after them.</li>
 *   <li>A full ring grows into a new one, published through a volatile field, so a reader still on the old ring reads
 *       entries the writer never touches again.</li>
 *   <li>Past {@link #CAPACITY} unread entries {@link #append} refuses, and the section copies itself again.</li>
 * </ul>
 */
final class WriteLog {

    static final int CAPACITY = 1 << 16;

    private static final class Ring {
        final char[] cells;
        final Object[] states;
        final int mask;

        Ring(int size) {
            cells = new char[size];
            states = new Object[size];
            mask = size - 1;
        }
    }

    private volatile Ring ring = new Ring(16);
    /** The level thread's. */
    private long tail;
    /** The worker's: everything before it is read. */
    private volatile long head;

    /** The level thread: records a write; false when {@link #CAPACITY} entries wait unread. */
    boolean append(int cell, Object state) {
        long t = tail;
        Ring r = ring;
        if (t - head == r.cells.length) {
            if (r.cells.length >= CAPACITY) return false;
            r = grow(r, t);
        }
        int slot = (int) t & r.mask;
        r.cells[slot] = (char) cell;
        r.states[slot] = state;
        tail = t + 1;
        return true;
    }

    /** The level thread: where the next write goes, the mark a batch carries. */
    long tail() {
        return tail;
    }

    /** The worker: hands {@code to} each entry from the last read up to {@code mark}, in order. */
    void replay(long mark, Visitor to) {
        Ring r = ring;
        for (long i = head; i < mark; i++) {
            int slot = (int) i & r.mask;
            Object state = r.states[slot];
            r.states[slot] = null;
            to.write(r.cells[slot], state);
        }
        head = mark;
    }

    private Ring grow(Ring old, long t) {
        Ring grown = new Ring(old.cells.length * 2);
        for (long i = head; i < t; i++) {
            int from = (int) i & old.mask, to = (int) i & grown.mask;
            grown.cells[to] = old.cells[from];
            grown.states[to] = old.states[from];
        }
        ring = grown;
        return grown;
    }

    /** One replayed write. */
    interface Visitor {
        void write(int cell, Object state);
    }
}
