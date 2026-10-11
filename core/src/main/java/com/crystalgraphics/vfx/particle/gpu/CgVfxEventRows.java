package com.crystalgraphics.vfx.particle.gpu;

import java.util.Arrays;

/**
 * One step's rows of one event, as a {@link CgVfxEventListener} reads them: where each fired, absolute, its parent's
 * velocity and the event's normal, the slot it came from, the parent's id and, for a trigger that repeats, which firing. Both paths fill it, so a listener never
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
 * rows.add(slot, parentId, x, y, z, vx, vy, vz, nx, ny, nz, firing);   // a collision's or a rate's, from 1
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
    private int[] ids = new int[3 * 16];
    private Object[] owners = new Object[16];
    /** Per row: the hit code and entity ({@link #tag}). */
    private int[] tags = new int[2 * 16];
    private int count, dropped;

    /**
     * What a collision met, {@code fx_hit}'s codes ({@code FX_HIT_*} in {@code fx_types.glsl}): nothing said, a kind that
     * does not say, a shape collider, the scene's depth, a block, the fixed ground, an entity.
     */
    public static final int HIT_NONE = 0, HIT_OTHER = 1, HIT_SHAPE = 2, HIT_SCENE = 3, HIT_BLOCK = 4, HIT_GROUND = 5,
            HIT_ENTITY = 6;

    public void clear() {
        count = dropped = 0;
    }

    public void add(int slot, int parentId, double x, double y, double z, float vx, float vy, float vz, float nx, float ny, float nz) {
        add(slot, parentId, x, y, z, vx, vy, vz, nx, ny, nz, 0);
    }

    public void add(int slot, int parentId, double x, double y, double z, float vx, float vy, float vz, float nx, float ny,
                    float nz, int firing) {
        if (count * 3 == at.length) {
            at = Arrays.copyOf(at, at.length * 2);
            motion = Arrays.copyOf(motion, motion.length * 2);
            ids = Arrays.copyOf(ids, ids.length * 2);
            owners = Arrays.copyOf(owners, owners.length * 2);
            tags = Arrays.copyOf(tags, tags.length * 2);
        }
        owners[count] = null;
        tags[count * 2] = HIT_NONE;
        tags[count * 2 + 1] = -1;
        at[count * 3] = x;
        at[count * 3 + 1] = y;
        at[count * 3 + 2] = z;
        motion[count * 6] = vx;
        motion[count * 6 + 1] = vy;
        motion[count * 6 + 2] = vz;
        motion[count * 6 + 3] = nx;
        motion[count * 6 + 4] = ny;
        motion[count * 6 + 5] = nz;
        ids[count * 3] = slot;
        ids[count * 3 + 1] = parentId;
        ids[count * 3 + 2] = firing;
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
    public int slot(int i) { return ids[i * 3]; }

    public int parentId(int i) { return ids[i * 3 + 1]; }

    /** Which firing of a trigger that repeats, from 1: a collision's count, a rate's period index. 0 for the rest. */
    public int firing(int i) { return ids[i * 3 + 2]; }

    /** What played the slot it came from (a system's rows: the effect); null when unknown. */
    public Object owner(int i) { return owners[i]; }

    /** What a collision met: a {@code HIT_} code, {@link #HIT_NONE} for any other trigger or when unknown. */
    public int hit(int i) { return tags[i * 2]; }

    /** The entity a collision met, when {@link #hit} is {@link #HIT_ENTITY}; -1 otherwise. */
    public int entity(int i) { return tags[i * 2 + 1]; }

    /** A path: the last row added came from {@code owner}'s slot and met {@code hit} ({@code entity}'s, if one). */
    public void tag(Object owner, int hit, int entity) {
        int i = count - 1;
        owners[i] = owner;
        tags[i * 2] = hit;
        tags[i * 2 + 1] = hit == HIT_ENTITY ? entity : -1;
    }
}
