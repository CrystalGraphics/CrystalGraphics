package com.crystalgraphics.vfx.sim;

/**
 * A pool of simple particles an effect emits and ticks itself: each a point with a velocity, slowed by drag and lifted
 * by buoyancy, that lives for a set time. Fixed capacity and held in plain arrays, so ticking and drawing allocate
 * nothing. What a particle looks like is the effect's: it reads each one's place, life and seed when it submits.
 *
 * <pre>{@code
 * CgVfxParticles smoke = new CgVfxParticles(64);
 *
 * // once, when the blast starts
 * smoke.emit(x, y, z, vx, vy, vz, 2.4f, 1.5f, seed);
 *
 * // every tick
 * smoke.tick(dt, 2.2f, 0.8f);
 *
 * // every frame
 * float ahead = frame.alpha() * CgVfxSystem.TICK;
 * for (int i = 0; i < smoke.count(); i++)
 *     frame.billboard(this, layer, smoke.x(i, ahead), smoke.y(i, ahead), smoke.z(i, ahead), smoke.size(i), ...);
 * }</pre>
 *
 * <ul>
 *   <li>An emit into a full pool is dropped and answers false.</li>
 *   <li>A dead particle is replaced by the last one, so an index means nothing across a {@link #tick}.</li>
 * </ul>
 */
public final class CgVfxParticles {

    private final float[] x, y, z, vx, vy, vz, age, life, size, seed;
    private int count;

    public CgVfxParticles(int capacity) {
        x = new float[capacity];
        y = new float[capacity];
        z = new float[capacity];
        vx = new float[capacity];
        vy = new float[capacity];
        vz = new float[capacity];
        age = new float[capacity];
        life = new float[capacity];
        size = new float[capacity];
        seed = new float[capacity];
    }

    /** Adds a particle at {@code (x, y, z)} moving at {@code (vx, vy, vz)} a second, for {@code life} seconds. */
    public boolean emit(float x, float y, float z, float vx, float vy, float vz, float life, float size, float seed) {
        if (count == this.x.length) return false;
        int i = count++;
        this.x[i] = x;
        this.y[i] = y;
        this.z[i] = z;
        this.vx[i] = vx;
        this.vy[i] = vy;
        this.vz[i] = vz;
        this.age[i] = 0f;
        this.life[i] = Math.max(life, 1.0e-3f);
        this.size[i] = size;
        this.seed[i] = seed;
        return true;
    }

    /** Moves every particle on by {@code dt}: velocity decays by {@code drag} a second, {@code buoyancy} lifts it (+y). */
    public void tick(float dt, float drag, float buoyancy) {
        float keep = (float) Math.exp(-drag * dt);
        for (int i = count - 1; i >= 0; i--) {
            age[i] += dt;
            if (age[i] >= life[i]) {
                remove(i);
                continue;
            }
            vx[i] *= keep;
            vy[i] = vy[i] * keep + buoyancy * dt;
            vz[i] *= keep;
            x[i] += vx[i] * dt;
            y[i] += vy[i] * dt;
            z[i] += vz[i] * dt;
        }
    }

    public void clear() {
        count = 0;
    }

    public int count() {
        return count;
    }

    /** Where particle {@code i} will be {@code ahead} seconds after the last tick: what a frame between ticks draws. */
    public float x(int i, float ahead) {
        return x[i] + vx[i] * ahead;
    }

    public float y(int i, float ahead) {
        return y[i] + vy[i] * ahead;
    }

    public float z(int i, float ahead) {
        return z[i] + vz[i] * ahead;
    }

    /** How far through its life particle {@code i} is, 0..1. */
    public float progress(int i) {
        return age[i] / life[i];
    }

    public float size(int i) {
        return size[i];
    }

    public float seed(int i) {
        return seed[i];
    }

    private void remove(int i) {
        int last = --count;
        x[i] = x[last];
        y[i] = y[last];
        z[i] = z[last];
        vx[i] = vx[last];
        vy[i] = vy[last];
        vz[i] = vz[last];
        age[i] = age[last];
        life[i] = life[last];
        size[i] = size[last];
        seed[i] = seed[last];
    }
}
