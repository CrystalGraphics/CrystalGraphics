package com.crystalgraphics.vfx;

import com.crystalgraphics.collision.CgPart;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuEmitter;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEventRows;

/**
 * Something an effect, or one of its particles, struck: what a {@link CgVfxCollisionListener} hears. One place hears
 * them all, at the scope a caller wants:
 *
 * <pre>{@code
 * wave.onCollide(c -> damage(c.entity()));                            // one effect's
 * VFX.onCollide(c -> { if (c.hit() == CgVfxCollision.Hit.BLOCK) scorch(c.x(), c.y(), c.z()); });   // a mod's effects'
 * CgVfx.onCollide(c -> LOG.debug("{} hit {}", c.effect(), c.hit()));  // every effect's
 * }</pre>
 *
 * Particles report only from an emitter that asks to ({@code reportCollisions}), a few frames after the GPU step:
 *
 * <pre>{@code
 * static final CgVfxEmitter DEBRIS = CgVfxEmitter.builder("debris")
 *         .module(new CgVfxModule.CollideWorld(0.3f, 0.4f, 0.4f))
 *         .reportCollisions()                                          // 16 rows a step, the rest counted as dropped
 *         ...
 * effect.onCollide(c -> {
 *     if (c.source() == CgVfxCollision.Source.PARTICLE) sounds.play(TINK, c.x(), c.y(), c.z());
 * });
 * }</pre>
 *
 * <ul>
 *   <li>Valid only during the call: the same object is filled again for the next one. Copy what you keep.</li>
 *   <li>Render thread. Heard by the effect's listeners, then its owner's, then the global ones.</li>
 *   <li>An effect reports a contact as it starts, and again when what it strikes changes; never every tick it holds.</li>
 * </ul>
 */
public final class CgVfxCollision {

    /** What struck. */
    public enum Source { EFFECT, PARTICLE }

    /** What was struck. */
    public enum Hit {
        /** A block of the world. */
        BLOCK,
        /** An entity: {@link #entity()} says which. */
        ENTITY,
        /** The effect's fixed ground, where the host has no world, or a particle landing on it. */
        GROUND,
        /** The scene's depth: whatever was drawn there ({@code CollideDepth}). */
        SCENE,
        /** A collider shape of the emitter's own ({@code Collide}). */
        SHAPE,
        /** The point an effect was aimed at, struck without a collision test. */
        TARGET,
        /** A module kind that does not say. */
        OTHER
    }

    private static final Hit[] BY_CODE = {Hit.OTHER, Hit.OTHER, Hit.SHAPE, Hit.SCENE, Hit.BLOCK, Hit.GROUND, Hit.ENTITY};

    private CgVfxEffect effect;
    private Source source;
    private Hit hit;
    private int entity;
    private double x, y, z;
    private float nx, ny, nz, vx, vy, vz;
    private CgVfxGpuEmitter emitter;
    private int firing;
    private CgPart part;
    private float radius;

    CgVfxCollision() {
    }

    /** The effect that struck, or whose particle did; null for a particle of a slot no effect holds. */
    public CgVfxEffect effect() { return effect; }

    public Source source() { return source; }

    public Hit hit() { return hit; }

    /** The entity struck ({@code CgEntityQuery}'s id), when {@link #hit()} is {@link Hit#ENTITY}; -1 otherwise. */
    public int entity() { return entity; }

    /** Where, in world coordinates. */
    public double x() { return x; }
    public double y() { return y; }
    public double z() { return z; }

    /** The surface's normal there, unit length. */
    public float nx() { return nx; }
    public float ny() { return ny; }
    public float nz() { return nz; }

    /** The velocity it struck with, blocks a second. */
    public float vx() { return vx; }
    public float vy() { return vy; }
    public float vz() { return vz; }

    /** The emitter whose particle struck; null for an effect's own. */
    public CgVfxGpuEmitter emitter() { return emitter; }

    /** Which of the particle's collisions this is, from 1 (0 for a landing); 1 for an effect's. */
    public int firing() { return firing; }

    /**
     * Which part of the effect struck ({@code CgEnergyWave.PART_BEAM}, {@code PART_BLAST}); null for a particle's, or an
     * effect that has one part. To hear one part alone, subscribe to it: {@code effect.onCollide(part, listener)}.
     */
    public CgPart part() { return part; }

    /** How far round {@link #x()}, {@link #y()}, {@link #z()} it reaches, in blocks: a blast's; 0 for a point contact. */
    public float radius() { return radius; }

    /**
     * What the server's copy of an effect struck, as a viewer hears it ({@code CgVfxCue.onConfirmedCollide}), on the
     * viewer's own copy, {@code effect}, when it has one.
     */
    public static CgVfxCollision confirmed(CgVfxEffect effect, CgPart part, Hit hit, int entity, double x, double y,
                                           double z, float nx, float ny, float nz, float radius) {
        return new CgVfxCollision().effect(effect, part, hit, entity, x, y, z, nx, ny, nz, 0f, 0f, 0f, radius);
    }

    /** The {@link Hit} for an {@code fx_hit} code ({@code CgVfxEventRows.HIT_*}). */
    static Hit hitOf(int code) {
        return code >= 0 && code < BY_CODE.length ? BY_CODE[code] : Hit.OTHER;
    }

    CgVfxCollision effect(CgVfxEffect effect, CgPart part, Hit hit, int entity, double x, double y, double z, float nx,
                          float ny, float nz, float vx, float vy, float vz, float radius) {
        set(effect, Source.EFFECT, hit, entity, x, y, z, nx, ny, nz, vx, vy, vz);
        emitter = null;
        firing = 1;
        this.part = part;
        this.radius = radius;
        return this;
    }

    CgVfxCollision particle(CgVfxEffect effect, CgVfxGpuEmitter emitter, CgVfxEventRows rows, int i, Hit hit) {
        set(effect, Source.PARTICLE, hit, rows.entity(i), rows.x(i), rows.y(i), rows.z(i), rows.nx(i), rows.ny(i),
                rows.nz(i), rows.vx(i), rows.vy(i), rows.vz(i));
        this.emitter = emitter;
        firing = rows.firing(i);
        part = null;
        radius = 0f;
        return this;
    }

    private void set(CgVfxEffect effect, Source source, Hit hit, int entity, double x, double y, double z, float nx,
                     float ny, float nz, float vx, float vy, float vz) {
        this.effect = effect;
        this.source = source;
        this.hit = hit;
        this.entity = hit == Hit.ENTITY ? entity : -1;
        this.x = x;
        this.y = y;
        this.z = z;
        this.nx = nx;
        this.ny = ny;
        this.nz = nz;
        this.vx = vx;
        this.vy = vy;
        this.vz = vz;
    }

    /** A listener registered with {@link CgVfx#onCollide} or {@link CgVfxOwner#onCollide}; closing it unregisters it. */
    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    @Override
    public String toString() {
        return source + (part != null ? " " + part : "") + " " + hit + (entity >= 0 ? " #" + entity : "") + " at " + x + ", " + y
                + ", " + z + (radius > 0f ? " r " + radius : "");
    }
}
