package com.crystalgraphics.vfx.element;

import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxElement;
import com.crystalgraphics.vfx.CgVfxFrame;
import com.crystalgraphics.vfx.camera.CgCameraShakes;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.look.CgVfxSlot;
import org.joml.Matrix4f;

/**
 * A charge: a ball of energy gathering at a point for {@link #TIME}, growing along {@link #SIZE} while streaks fall into
 * it and arcs crackle over it, and at the release a flash and a shock ring, the ball settling to {@link #ROOT_SIZE} as
 * the root of whatever it released. A wave's muzzle, a ki blast's palm.
 *
 * <pre>{@code
 * private final CgVfxCharge charge = new CgVfxCharge(this);
 *
 * @Override protected void tick(float dt) {
 *     charge.tick(muzzleX, muzzleY, muzzleZ, aimX, aimY, aimZ, releaseAge, stopAge);   // every tick
 * }
 * @Override protected void submit(CgVfxFrame frame) {
 *     charge.submit(frame);
 * }
 * }</pre>
 *
 * <p>What it draws, each on a slot a look's kit puts layers on ({@code CgEnergyWave.REALISTIC_CHARGE} is one):</p>
 * <ul>
 *   <li>{@link #BALL}, {@link #HAZE}, {@link #GLARE}: spheres at its point, {@code CG_OBJECT_CUSTOM1} their radius
 *       twice and an intensity; the ball's +z along the aim, turning, the haze at the ball's steady size.</li>
 *   <li>{@link #SHOCK}: a sphere flattened to a disc facing along the aim, {@code .z} an intensity and {@code .w} its
 *       sweep 0..1.</li>
 *   <li>{@link #STREAKS} and {@link #ARCS}: ribbons, {@code CG_OBJECT_CUSTOM1} the ball's radius in blocks, the ball's
 *       share of the streaks' sphere and an intensity.</li>
 * </ul>
 *
 * <ul>
 *   <li>Its shakes play only while its effect plays: a tremor held over the charge, a recoil along the aim at the
 *       release.</li>
 *   <li>It fades over {@link #FADE} seconds from the age its effect was stopped at.</li>
 * </ul>
 */
public final class CgVfxCharge extends CgVfxElement {

    public static final CgVfxSchema SCHEMA = CgVfxSchema.of("crystalgraphics:charge");
    /** Seconds it charges before releasing on its own; 0 releases at once. */
    public static final CgVfxParam.Scalar TIME = SCHEMA.time("time", 1.6f);
    /** The ball's full radius. */
    public static final CgVfxParam.Scalar RADIUS = SCHEMA.length("radius", 1.36f);
    /** The ball's size over the charge (0..1 of it), a share of {@link #RADIUS}: it swells past full, then settles. */
    public static final CgVfxParam.Curve SIZE = SCHEMA.curve("size", CgKeyframes.start(0f, 0.15f)
            .to(0.85f, 1.25f, CgEasings.OUT_CUBIC)
            .to(1f, 1f, CgEasings.IN_OUT_QUAD)
            .build());
    /** The root it settles into after the release, a share of {@link #RADIUS}. */
    public static final CgVfxParam.Scalar ROOT_SIZE = SCHEMA.scalar("rootSize", 0.8f);
    /** The release flash's brightness over the seconds since the release. */
    public static final CgVfxParam.Curve FLASH = SCHEMA.curve("flash", CgKeyframes.start(0f, 0f)
            .to(0.05f, 3f, CgEasings.OUT_QUAD)
            .to(0.5f, 0f, CgEasings.OUT_CUBIC)
            .build());
    /** The release flash's half-width. */
    public static final CgVfxParam.Scalar FLASH_RADIUS = SCHEMA.length("flashRadius", 2f);
    /** Seconds the shock ring takes to sweep out, and how far it reaches. */
    public static final CgVfxParam.Scalar SHOCK_TIME = SCHEMA.time("shockTime", 0.55f);
    public static final CgVfxParam.Scalar SHOCK_RADIUS = SCHEMA.length("shockRadius", 4.4f);
    /** The sphere the streaks fall in from. */
    public static final CgVfxParam.Scalar STREAK_RADIUS = SCHEMA.length("streakRadius", 2.88f);
    /** Held over the charge at its point, its level the charge's progress squared. */
    public static final CgVfxParam.Shake SHAKE = SCHEMA.shake("shake",
            CgCameraShakes.RUMBLE.toBuilder().trauma(0.4f).radii(3f, 24f).build());
    /** Played at its point as it releases, its punch back along the aim. */
    public static final CgVfxParam.Shake RELEASE_SHAKE = SCHEMA.shake("releaseShake", CgCameraShakes.RECOIL);

    /** The ball, then the root: a sphere, its +z along the aim. */
    public static final CgVfxSlot BALL = CgVfxSlot.mesh("crystalgraphics:charge/ball");
    /** Heat haze round the ball at its steady size, without its pulse. */
    public static final CgVfxSlot HAZE = CgVfxSlot.mesh("crystalgraphics:charge/haze");
    /** The release flash: a sphere. */
    public static final CgVfxSlot GLARE = CgVfxSlot.mesh("crystalgraphics:charge/glare");
    /** The shock ring at the release: a disc facing along the aim. */
    public static final CgVfxSlot SHOCK = CgVfxSlot.mesh("crystalgraphics:charge/shock");
    /** Streaks falling into the ball: ribbons inside a sphere round it, its +z along the aim. */
    public static final CgVfxSlot STREAKS = CgVfxSlot.ribbons("crystalgraphics:charge/streaks");
    /** Arcs crackling over the ball and the root: ribbons round the ball, its radius their unit. */
    public static final CgVfxSlot ARCS = CgVfxSlot.ribbons("crystalgraphics:charge/arcs");

    /** Its moments, each once; the charge's three are skipped when it releases at once. */
    public static final String MOMENT_START = "charge-start", MOMENT_MID = "charge-mid", MOMENT_PEAK = "charge-peak",
            MOMENT_RELEASE = "release", MOMENT_FLASH = "flash", MOMENT_RING = "ring", MOMENT_SHOCK = "shock";

    /** Seconds the ball takes to settle into the root after the release, and to fade after a stop. */
    public static final float SETTLE = 0.25f, FADE = 0.35f;

    private final Matrix4f placed = new Matrix4f();
    private float x, y, z, aimX = 1f, aimY, aimZ, releaseAge = Float.POSITIVE_INFINITY, stopAge = Float.NaN;
    private boolean released;

    public CgVfxCharge(CgVfxEffect<?> effect) {
        super(effect);
    }

    /**
     * Its state this tick: its point from the origin, the way it points (any length), the age it releases at (infinite
     * while held) and the age its effect was stopped at (NaN while it plays). Every tick, before {@link #submit}.
     */
    public void tick(float x, float y, float z, float dx, float dy, float dz, float releaseAge, float stopAge) {
        this.x = x;
        this.y = y;
        this.z = z;
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length > 0f) {
            aimX = dx / length;
            aimY = dy / length;
            aimZ = dz / length;
        }
        this.releaseAge = releaseAge;
        this.stopAge = stopAge;
        float age = age();
        if (Float.isNaN(stopAge) && age < releaseAge) {
            float progress = Math.min(age / Math.max(get(TIME), 1.0e-3f), 1f);
            holdShake(SHAKE, x, y, z, 1f, progress * progress);
        } else if (Float.isNaN(stopAge) && !released) {
            released = true;
            playShake(RELEASE_SHAKE, x, y, z, -aimX, -aimY, -aimZ, 1f);
        }
        if (momentsHeard()) moments(age);
    }

    private void moments(float age) {
        float time = get(TIME), since = age - releaseAge;
        float frame = Math.max(get(STREAK_RADIUS), get(RADIUS) * 1.3f) * 1.1f;
        if (time > 0f) {
            if (age >= 0.15f * time) once(0, MOMENT_START, x, y, z, frame);
            if (age >= 0.5f * time) once(1, MOMENT_MID, x, y, z, frame);
            if (age >= 0.85f * time) once(2, MOMENT_PEAK, x, y, z, frame);
        }
        if (since >= 0.03f) once(3, MOMENT_RELEASE, x, y, z, frame);
        if (since >= 0.07f) once(4, MOMENT_FLASH, x, y, z, get(FLASH_RADIUS) * 2f);
        float ring = get(SHOCK_RADIUS) * 1.3f;
        if (since >= 0.2f * get(SHOCK_TIME)) once(5, MOMENT_RING, x, y, z, ring);
        if (since >= 0.5f * get(SHOCK_TIME)) once(6, MOMENT_SHOCK, x, y, z, ring);
    }

    /** How far through its charge it is, 0 to 1; 1 once released. */
    public float progress() {
        if (age() >= releaseAge) return 1f;
        return Math.min(age() / Math.max(get(TIME), 1.0e-3f), 1f);
    }

    /** Draws the ball or the root, the haze, and the release's flash, ring, streaks and arcs, at its point. */
    public void submit(CgVfxFrame frame) {
        float age = age(), since = age - releaseAge;
        float fade = Float.isNaN(stopAge) ? 1f : 1f - smooth(stopAge, stopAge + FADE, age);
        if (fade <= 0f) return;
        float full = get(RADIUS), ball;
        if (since < 0f) {
            ball = full * curve(SIZE).at(age / Math.max(get(TIME), 1.0e-3f));
        } else {
            ball = full * (1f + (get(ROOT_SIZE) - 1f) * smooth(0f, SETTLE, since));
        }
        placed.identity().scale(ball);
        frame.mesh(effect, HAZE, x, y, z, placed, ball, ball, fade, 0f);
        ball *= 1f + 0.05f * (float) Math.sin(age * 23f + seed() * 6.28f);
        facing(placed, aimX, aimY, aimZ).rotateZ(age * 1.3f).scale(ball);
        frame.mesh(effect, BALL, x, y, z, placed, ball, ball, fade, 0f);

        if (since >= 0f) {
            float flash = curve(FLASH).at(since);
            if (flash > 0f) {
                float r = get(FLASH_RADIUS) * (0.6f + 0.6f * smooth(0f, 0.4f, since));
                facing(placed, aimX, aimY, aimZ).scale(r);
                frame.mesh(effect, GLARE, x, y, z, placed, r, r, flash, 0f);
            }
            float shockTime = get(SHOCK_TIME);
            if (since < shockTime) {
                float progress = (float) CgEasings.OUT_CUBIC.ease(since / shockTime);
                float reach = get(SHOCK_RADIUS);
                facing(placed, aimX, aimY, aimZ).scale(reach, reach, 0.002f);
                // Gone well before the end of its sweep, so it never lingers as a faint outline.
                float left = 1f - since / shockTime;
                frame.mesh(effect, SHOCK, x, y, z, placed, reach, reach, left * left * (float) Math.sqrt(left), progress);
            }
        }

        float time = get(TIME);
        float streaks = since < 0f ? smooth(0f, 0.15f, time > 0f ? age / time : 1f) : 1f - smooth(0f, 0.3f, since);
        if (streaks > 0f) {
            float spawn = get(STREAK_RADIUS);
            facing(placed, aimX, aimY, aimZ).scale(spawn);
            frame.ribbons(effect, STREAKS, x, y, z, placed, ball, ball / spawn, streaks * fade, 0f);
        }
        // The arcs crackle on, quieter, round the root once released.
        facing(placed, aimX, aimY, aimZ).rotateZ(age * 0.7f).scale(ball);
        frame.ribbons(effect, ARCS, x, y, z, placed, ball, 0f, (since < 0f ? 1f : 0.5f) * fade, 0f);
    }
}
