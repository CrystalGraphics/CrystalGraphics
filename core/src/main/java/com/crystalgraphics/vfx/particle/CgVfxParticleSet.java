package com.crystalgraphics.vfx.particle;

/**
 * One emitter instance's particles: struct-of-arrays with a fixed capacity, so simulating and drawing allocate nothing.
 * Positions are relative to the effect's origin, in blocks. Owned by a {@link CgVfxEmitterInstance}, which spawns into it
 * and runs its emitter's modules over it.
 *
 * <pre>{@code
 * CgVfxParticleSet p = emitter.particles();
 * for (int i = 0; i < p.count(); i++) {
 *     float x = p.x(i, alpha), y = p.y(i, alpha), z = p.z(i, alpha);   // between the last tick and the next
 *     float t = p.progress(i);                                           // 0..1 through its life
 * }
 * }</pre>
 *
 * <ul>
 *   <li>{@link #ax}, {@link #drag} and {@link #dragQuad} are accumulators: modules add to them, the solver consumes and
 *       clears them every tick.</li>
 *   <li>A dead particle is replaced by the last one, so an index means nothing across a tick.</li>
 * </ul>
 */
public final class CgVfxParticleSet {

    /** Position now and at the previous tick, velocity, and this tick's acceleration. */
    public final float[] x, y, z, px, py, pz, vx, vy, vz, ax, ay, az;
    /** This tick's drag: linear (a second) and quadratic (a block). */
    public final float[] drag, dragQuad;
    /** Seconds lived and to live; size in blocks; a 0..1 seed; spin angle and rate in radians; heat 0..1 for buoyancy. */
    public final float[] age, life, size, seed, spin, spinRate, heat;
    /** 1 once it has come to rest on the ground. */
    public final float[] resting;
    /** Its spawn index in its instance: the key the GPU simulation's records carry, so the two paths match by it. */
    public final int[] id;
    /** Its punctual impacts so far, held at 65,535: a collision event's firing. */
    public final int[] collisions;
    /** 1 when a module struck it this tick ({@link #hit}), and the surface's normal; cleared before the solver's after-modules. */
    public final float[] hit, hitNx, hitNy, hitNz;
    private int count;

    public CgVfxParticleSet(int capacity) {
        x = new float[capacity];
        y = new float[capacity];
        z = new float[capacity];
        px = new float[capacity];
        py = new float[capacity];
        pz = new float[capacity];
        vx = new float[capacity];
        vy = new float[capacity];
        vz = new float[capacity];
        ax = new float[capacity];
        ay = new float[capacity];
        az = new float[capacity];
        drag = new float[capacity];
        dragQuad = new float[capacity];
        age = new float[capacity];
        life = new float[capacity];
        size = new float[capacity];
        seed = new float[capacity];
        spin = new float[capacity];
        spinRate = new float[capacity];
        heat = new float[capacity];
        resting = new float[capacity];
        id = new int[capacity];
        collisions = new int[capacity];
        hit = new float[capacity];
        hitNx = new float[capacity];
        hitNy = new float[capacity];
        hitNz = new float[capacity];
    }

    /**
     * Particle {@code i} struck a surface whose normal is {@code (nx, ny, nz)} this tick: a punctual impact, never a slide.
     * What a collision event fires on; {@code fx_hit} on the GPU.
     */
    public void hit(int i, float nx, float ny, float nz) {
        hit[i] = 1f;
        hitNx[i] = nx;
        hitNy[i] = ny;
        hitNz[i] = nz;
        collisions[i] = Math.min(collisions[i] + 1, 65535);
    }

    public int count() {
        return count;
    }

    public int capacity() {
        return x.length;
    }

    /** How far through its life particle {@code i} is, 0..1. */
    public float progress(int i) {
        return Math.min(age[i] / life[i], 1f);
    }

    /** Particle {@code i}'s position {@code alpha} of the way from the last tick to the next: what a frame draws. */
    public float x(int i, float alpha) {
        return px[i] + (x[i] - px[i]) * alpha;
    }

    public float y(int i, float alpha) {
        return py[i] + (y[i] - py[i]) * alpha;
    }

    public float z(int i, float alpha) {
        return pz[i] + (z[i] - pz[i]) * alpha;
    }

    /**
     * A new particle at the origin, at rest, living one second; its index, or -1 when the set is full. Its emitter
     * spawns through it; a check seeds chosen states with it.
     */
    public int add() {
        if (count == x.length) return -1;
        int i = count++;
        x[i] = y[i] = z[i] = px[i] = py[i] = pz[i] = 0f;
        vx[i] = vy[i] = vz[i] = ax[i] = ay[i] = az[i] = 0f;
        drag[i] = dragQuad[i] = 0f;
        age[i] = 0f;
        life[i] = 1f;
        size[i] = 1f;
        seed[i] = spin[i] = spinRate[i] = heat[i] = resting[i] = 0f;
        collisions[i] = 0;
        hit[i] = 0f;
        return i;
    }

    void remove(int i) {
        int last = --count;
        x[i] = x[last];
        y[i] = y[last];
        z[i] = z[last];
        px[i] = px[last];
        py[i] = py[last];
        pz[i] = pz[last];
        vx[i] = vx[last];
        vy[i] = vy[last];
        vz[i] = vz[last];
        ax[i] = ax[last];
        ay[i] = ay[last];
        az[i] = az[last];
        drag[i] = drag[last];
        dragQuad[i] = dragQuad[last];
        age[i] = age[last];
        life[i] = life[last];
        size[i] = size[last];
        seed[i] = seed[last];
        spin[i] = spin[last];
        spinRate[i] = spinRate[last];
        heat[i] = heat[last];
        resting[i] = resting[last];
        id[i] = id[last];
        collisions[i] = collisions[last];
        hit[i] = hit[last];
        hitNx[i] = hitNx[last];
        hitNy[i] = hitNy[last];
        hitNz[i] = hitNz[last];
    }

    void clear() {
        count = 0;
    }
}
