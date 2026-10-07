package com.crystalgraphics.vfx.particle.gpu;

import java.util.Arrays;

/**
 * One step's rows of one event, as a {@link CgVfxEventListener} reads them: where each fired, absolute, its parent's
 * velocity and the event's normal, the slot it came from and the parent's id. Both paths fill it, so a listener never
 * knows which ran.
 *
 * <pre>{@code
 * public void events(CgVfxGpuEmitter definition, int event, CgVfxEventRows rows) {
 *     for (int i = 0; i < rows.count(); i++) decal(rows.x(i), rows.y(i), rows.z(i), rows.nx(i), rows.ny(i), rows.nz(i));
 *     if (rows.dropped() > 0) LOG.debug("{} rows past the cap", rows.dropped());
 * }
 *
 * // a path filling it
 * rows.clear();
 * rows.add(slot, parentId, x, y, z, vx, vy, vz, nx, ny, nz);
 * rows.drop(past);                                    // found past the cap
 * }</pre>
 *
 * <ul>
 *   <li>Valid only during the call: the same object is filled again for the next delivery.</li>
 *   <li>Render thread.</li>
 * </ul>
 */
public final class CgVfxEventRows {

    private double[] at = new double[3 * 16];
    private float[] motion = new float[6 * 16];
    private int[] ids = new int[2 * 16];
    private int count, dropped;

    public void clear() {
        count = dropped = 0;
    }

    public void add(int slot, int parentId, double x, double y, double z, float vx, float vy, float vz, float nx, float ny, float nz) {
        if (count * 3 == at.length) {
            at = Arrays.copyOf(at, at.length * 2);
            motion = Arrays.copyOf(motion, motion.length * 2);
            ids = Arrays.copyOf(ids, ids.length * 2);
        }
        at[count * 3] = x;
        at[count * 3 + 1] = y;
        at[count * 3 + 2] = z;
        motion[count * 6] = vx;
        motion[count * 6 + 1] = vy;
        motion[count * 6 + 2] = vz;
        motion[count * 6 + 3] = nx;
        motion[count * 6 + 4] = ny;
        motion[count * 6 + 5] = nz;
        ids[count * 2] = slot;
        ids[count * 2 + 1] = parentId;
        count++;
    }

    /** Counts {@code n} rows found past the cap. */
    public void drop(int n) {
        dropped += n;
    }

    public int count() {
        return count;
    }

    /** Rows this step found past its cap: counted, not delivered. */
    public int dropped() {
        return dropped;
    }

    public double x(int i) { return at[i * 3]; }
    public double y(int i) { return at[i * 3 + 1]; }
    public double z(int i) { return at[i * 3 + 2]; }
    public float vx(int i) { return motion[i * 6]; }
    public float vy(int i) { return motion[i * 6 + 1]; }
    public float vz(int i) { return motion[i * 6 + 2]; }
    public float nx(int i) { return motion[i * 6 + 3]; }
    public float ny(int i) { return motion[i * 6 + 4]; }
    public float nz(int i) { return motion[i * 6 + 5]; }

    /** The pool slot the parent was in. */
    public int slot(int i) { return ids[i * 2]; }

    public int parentId(int i) { return ids[i * 2 + 1]; }
}
