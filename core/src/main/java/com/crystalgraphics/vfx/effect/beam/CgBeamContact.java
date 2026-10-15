package com.crystalgraphics.vfx.effect.beam;

import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxElement;
import com.crystalgraphics.vfx.CgVfxFrame;
import com.crystalgraphics.vfx.camera.CgCameraShakes;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.look.CgVfxSlot;
import org.joml.Matrix4f;

/**
 * Where a beam meets what it hits, for as long as it hits: an orb at the contact, haze round it, sparks thrown back off
 * it and rings pulsing out, all facing back along the beam and growing with how hard it hits ({@link #level()}), and
 * a rumble held there.
 *
 * <pre>{@code
 * contact.tick(dt, stream.impacting(), stream.impactX(), stream.impactY(), stream.impactZ());   // every tick
 * contact.submit(frame, normalX, normalY, normalZ);                                           // back along the beam
 * }</pre>
 *
 * <p>Its slots: {@link #ORB} and {@link #HAZE}, spheres ({@code CG_OBJECT_CUSTOM1} the orb's radius twice and the
 * level); {@link #SPLASH}, ribbons over the hemisphere back along the beam ({@code .x} their reach, {@code .z} the
 * level, {@code .w} -1: a steady spray); {@link #RINGS}, a disc ({@code .z} the ring's intensity, {@code .w} its sweep
 * 0..1).</p>
 */
public final class CgBeamContact extends CgVfxElement {

    public static final CgVfxSchema SCHEMA = CgVfxSchema.of("crystalgraphics:wave/contact");
    /** The orb's radius while it hits fully. */
    public static final CgVfxParam.Scalar RADIUS = SCHEMA.length("radius", 1.76f);
    /** How far its sparks fly. */
    public static final CgVfxParam.Scalar SPLASH_REACH = SCHEMA.length("splashReach", 9.6f);
    /** How far its rings sweep out. */
    public static final CgVfxParam.Scalar RINGS_RADIUS = SCHEMA.length("ringsRadius", 4.8f);
    /** Seconds between its rings. */
    public static final CgVfxParam.Scalar RING_PERIOD = SCHEMA.time("ringPeriod", 0.45f);
    /** Held at the contact while it hits, at the scale of {@link #RADIUS}, its level how hard it hits. */
    public static final CgVfxParam.Shake SHAKE = SCHEMA.shake("shake",
            CgCameraShakes.RUMBLE.toBuilder().trauma(0.3f).radii(2.7f, 22f).build());

    public static final CgVfxSlot ORB = CgVfxSlot.mesh("crystalgraphics:wave/contact/orb");
    public static final CgVfxSlot HAZE = CgVfxSlot.mesh("crystalgraphics:wave/contact/haze");
    public static final CgVfxSlot SPLASH = CgVfxSlot.ribbons("crystalgraphics:wave/contact/splash");
    public static final CgVfxSlot RINGS = CgVfxSlot.mesh("crystalgraphics:wave/contact/rings");

    /** Announced once it has hit for a moment, framed on its sparks. */
    public static final String MOMENT_SPLASH = "splash";

    private final Matrix4f placed = new Matrix4f();
    private float x, y, z, level, since = Float.NaN;

    public CgBeamContact(CgVfxEffect<?> effect) {
        super(effect);
    }

    /** Whether the beam hits this tick, and where, from the origin. Every tick, before {@link #submit}. */
    public void tick(float dt, boolean hitting, float x, float y, float z) {
        if (hitting && Float.isNaN(since)) since = age();
        if (hitting) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
        level += ((hitting ? 1f : 0f) - level) * Math.min(1f, dt * 10f);
        if (level > 0.01f) holdShake(SHAKE, this.x, this.y, this.z, get(RADIUS), level);
        if (!Float.isNaN(since) && age() >= since + 0.3f && momentsHeard()) {
            once(0, MOMENT_SPLASH, this.x, this.y, this.z, get(SPLASH_REACH));
        }
    }

    /** How hard it hits, 0 to 1: eased toward 1 while the beam hits and 0 once it does not. */
    public float level() {
        return level;
    }

    /** The age the beam first hit at, or NaN before. */
    public float firstHit() {
        return since;
    }

    /** Draws it facing {@code (nx, ny, nz)}, a unit vector back along the beam. */
    public void submit(CgVfxFrame frame, float nx, float ny, float nz) {
        if (level <= 0.01f) return;
        float age = age();
        float steady = get(RADIUS) * (float) Math.sqrt(level);
        placed.identity().scale(steady);
        frame.mesh(effect, HAZE, x, y, z, placed, steady, steady, level, 0f);
        float orb = steady * (1f + 0.08f * (float) Math.sin(age * 19f + seed() * 6.28f));
        facing(placed, nx, ny, nz).rotateZ(age * 1.1f).scale(orb);
        frame.mesh(effect, ORB, x, y, z, placed, orb, orb, level, 0f);
        float reach = get(SPLASH_REACH);
        facing(placed, nx, ny, nz).scale(reach);
        frame.ribbons(effect, SPLASH, x, y, z, placed, reach, 0f, level, -1f);
        float ring = ((age - since) / get(RING_PERIOD)) % 1f, rings = get(RINGS_RADIUS);
        facing(placed, nx, ny, nz).scale(rings, rings, 0.002f);
        frame.mesh(effect, RINGS, x, y, z, placed, rings, rings, level * (1f - ring) * (1f - ring),
                (float) CgEasings.OUT_CUBIC.ease(ring));
    }
}
