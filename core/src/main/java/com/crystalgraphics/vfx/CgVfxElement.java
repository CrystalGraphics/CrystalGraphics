package com.crystalgraphics.vfx;

import com.crystalgraphics.collision.CgCollisionWorld;
import com.crystalgraphics.collision.hit.CgHit;
import com.crystalgraphics.collision.hit.CgHitPart;
import com.crystalgraphics.collision.hitbox.CgHitbox;
import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.render.post.volume.CgPostSettings;
import com.crystalgraphics.render.post.volume.CgPostVolume;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.vfx.look.CgVfxKit;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import org.joml.Matrix4f;

/**
 * A part of an effect with its own parameters, slots and behaviour, which any effect needing it reuses: a charge ball,
 * a blast, a beam's body. The effect makes its elements and calls them from its own {@code tick} and {@code submit}, in
 * the order it wants; an element reaches the effect's values, shakes, post volumes, hits and moments through the
 * methods here, so it behaves as code inside the effect would.
 *
 * <pre>{@code
 * public final class KiBlast extends CgVfxEffect {
 *     private final CgVfxCharge charge = new CgVfxCharge(this);
 *     private final CgVfxBlast blast = new CgVfxBlast(this, PART_BLAST);
 *
 *     @Override protected void tick(float dt) {
 *         charge.tick(0f, 0f, 0f, aimX, aimY, aimZ, releaseAge, Float.NaN);
 *         if (age >= releaseAge && !blast.ignited()) blast.ignite(tx, ty, tz, -aimX, -aimY, -aimZ);
 *         blast.tick(dt);
 *         if (blast.done()) die();
 *     }
 *
 *     @Override protected void submit(CgVfxFrame frame) {
 *         charge.submit(frame);
 *         blast.submit(frame);
 *     }
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Positions are relative to the effect's origin, as everything an effect draws is.</li>
 *   <li>An element's parameters are its schema's: a look carrying the element's slots carries that schema, through the
 *       kit that draws them.</li>
 *   <li>Its moments are its own: each element keeps which it has announced ({@link #once}).</li>
 * </ul>
 */
public abstract class CgVfxElement {

    protected final CgVfxEffect<?> effect;
    private long announced;

    protected CgVfxElement(CgVfxEffect<?> effect) {
        this.effect = effect;
    }

    protected final float age() {
        return effect.age;
    }

    protected final float seed() {
        return effect.seed;
    }

    protected final boolean authoritative() {
        return effect.authoritative();
    }

    /** The effect's origin in the world: what every position here is relative to. */
    protected final double originX() {
        return effect.originX;
    }

    protected final double originY() {
        return effect.originY;
    }

    protected final double originZ() {
        return effect.originZ;
    }

    /** The effect's subject, which an impact frame inks. */
    protected final int subject() {
        return effect.subject;
    }

    /** The entity the effect follows, or -1: what its hitboxes and casts pass through. */
    protected final int followingId() {
        return effect.followingId();
    }

    protected final float get(CgVfxParam.Scalar param) {
        return effect.get(param);
    }

    /** Component {@code index} of a vector of its effect's: 0 x, 1 y, 2 z. */
    protected final float get(CgVfxParam.Vector param, int index) {
        return effect.get(param, index);
    }

    protected final CgKeyframes curve(CgVfxParam.Curve param) {
        return effect.curve(param);
    }

    protected final CgCollisionWorld world() {
        return effect.world();
    }

    protected final void hit(CgHit hit) {
        effect.hit(hit);
    }

    protected final CgHitbox hitbox(CgHitPart part, CgShape shape) {
        return effect.hitbox(part, shape);
    }

    protected final CgPostVolume openVolume(int priority, CgPostSettings settings, float x, float y, float z) {
        return effect.openVolume(priority, settings, x, y, z);
    }

    protected final void playShake(CgVfxParam.Shake param, float x, float y, float z, float scale) {
        effect.playShake(param, x, y, z, scale);
    }

    protected final void playShake(CgVfxParam.Shake param, float x, float y, float z, float dx, float dy, float dz,
                                   float scale) {
        effect.playShake(param, x, y, z, dx, dy, dz, scale);
    }

    protected final void holdShake(CgVfxParam.Shake param, float x, float y, float z, float scale, float level) {
        effect.holdShake(param, x, y, z, scale, level);
    }

    /** Advances one of its emitters by a tick: the effect's {@code tick(instance, dt)}. */
    protected final void tick(CgVfxEmitterInstance emitter, float dt) {
        effect.tick(emitter, dt);
    }

    /**
     * An instance of the look's emitter of {@code definition}'s name, or null where the look has none: scaled by
     * {@code kit}'s {@link CgVfxKit#emitterScale} at {@code length}'s value, from the effect's seed offset by
     * {@code salt}.
     */
    protected final CgVfxEmitterInstance instance(CgVfxEmitter definition, CgVfxKit kit, float salt) {
        CgVfxEmitter played = effect.look().emitter(definition.name());
        if (played == null) return null;
        CgVfxParam.Scalar length = kit.scalesWith();
        float scale = length == null ? 1f : kit.emitterScale(effect.get(length));
        return new CgVfxEmitterInstance(scale == 1f ? played : played.scaled(scale), effect.seed + salt);
    }

    /** The ground's height relative to the origin, or NaN. */
    protected final float groundHeight() {
        return effect.groundHeight();
    }

    protected final boolean momentsHeard() {
        return effect.momentsHeard();
    }

    /** Announces {@code name} the first time {@code bit} (0 to 63) is asked for, framed on a point and a radius. */
    protected final void once(int bit, String name, float x, float y, float z, float radius) {
        if ((announced & (1L << bit)) != 0) return;
        announced |= 1L << bit;
        effect.moment(name, x, y, z, radius);
    }

    /** {@code m} turned so its +z runs along {@code (x, y, z)}, a unit vector. */
    protected static Matrix4f facing(Matrix4f m, float x, float y, float z) {
        boolean steep = Math.abs(y) > 0.95f;
        return m.identity().rotateTowards(x, y, z, steep ? 1f : 0f, steep ? 0f : 1f, 0f);
    }

    /** GLSL's smoothstep. */
    protected static float smooth(float edge0, float edge1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3f - 2f * t);
    }
}
