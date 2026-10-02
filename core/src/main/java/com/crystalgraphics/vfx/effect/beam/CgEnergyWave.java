package com.crystalgraphics.vfx.effect.beam;

import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxFrame;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.look.CgVfxLayer;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.path.CgVfxPath;
import com.crystalgraphics.vfx.sim.CgVfxStream;
import org.joml.Matrix4f;

import java.util.List;

/**
 * An energy wave: charged at a muzzle, then a beam that flows outward, bends where its source turns, homes on a target
 * and stops where it hits. {@link #kamehameha()} is its default look; another palette and other layers make a Final
 * Flash or a Galick Gun.
 *
 * <pre>{@code
 * CgEnergyWave wave = vfx.play(new CgEnergyWave(CgEnergyWave.kamehameha(), x, y, z));  // the muzzle; charging starts
 * wave.aim(dx, dy, dz);              // where the source points; call as it turns
 * wave.target(tx, ty, tz);           // what the beam homes on, in world coordinates
 * wave.fire();                       // release now rather than after CHARGE_TIME
 * wave.set(CgEnergyWave.RADIUS, 1f); // any parameter, this wave only
 * wave.stop();                       // the tail runs out, then the wave ends
 * }</pre>
 *
 * <p>Its life: a ball of plasma charges at the muzzle for {@link #CHARGE_TIME}, growing along {@link #CHARGE_SIZE}
 * while streaks of energy fall into it and arcs crackle over it; at the release a flash ({@link #FLASH}) and a shock
 * ring, and the ball settles into the beam's root while the body flies out; after {@link #stop()} the root fades and the
 * tail runs out into the target.</p>
 *
 * <p>Its look's layers draw in slots: {@link CgVfxLayer#SLOT_BODY} as tubes along the body; {@link #SLOT_HEAD},
 * {@link #SLOT_CHARGE} and {@link #SLOT_FLASH} on spheres ({@link CgVfxFrame#mesh}, {@code CG_OBJECT_CUSTOM1} the
 * half-width, half-length and intensity); {@link #SLOT_SHOCK} on a sphere flattened to a disc facing along the aim
 * ({@code CG_OBJECT_CUSTOM1.z} the intensity, {@code .w} its progress, 0..1); {@link #SLOT_STREAKS} and
 * {@link #SLOT_ARCS} as stateless ribbons ({@link CgVfxFrame#ribbons}, {@code CG_OBJECT_CUSTOM1} the ball's radius in
 * blocks, the ball's share of the streaks' sphere, and the intensity).</p>
 *
 * <p>It announces each moment of that life ({@link #MOMENT_CHARGE_START} to {@link #MOMENT_END}) to
 * {@code CgVfxSystem.onMoment}, framed on the muzzle or on the whole flight: what a capture tool photographs.</p>
 *
 * <ul>
 *   <li>Positions are absolute; the wave simulates relative to where it was made.</li>
 *   <li>Every sample of the body homes on the target, turning at most {@link #TURN_RATE}, so the body curves smoothly
 *       into it, and a wave runs down it when the aim moves.</li>
 *   <li>A {@link #CHARGE_TIME} of 0 fires at once.</li>
 * </ul>
 */
public final class CgEnergyWave extends CgVfxEffect {

    public static final CgVfxSchema SCHEMA = new CgVfxSchema();
    /** The slot a layer draws the head in: a sphere at the front, its +z along the body. */
    public static final String SLOT_HEAD = "head";
    /** The charge ball, and after the release the beam's root: a sphere at the muzzle, its +z along the aim. */
    public static final String SLOT_CHARGE = "charge";
    /** The release flash: a sphere at the muzzle. */
    public static final String SLOT_FLASH = "flash";
    /** Streaks falling into the charge: ribbons inside a sphere round the muzzle, its +z along the aim. */
    public static final String SLOT_STREAKS = "streaks";
    /** Arcs crackling over the charge and the root: ribbons round the ball, its radius their unit. */
    public static final String SLOT_ARCS = "arcs";
    /** The shock ring at the release: a disc at the muzzle facing along the aim. */
    public static final String SLOT_SHOCK = "shock";

    /** Its moments, in the order they come; each fires once. The charge's three are skipped when it fires at once. */
    public static final String MOMENT_CHARGE_START = "charge-start", MOMENT_CHARGE_MID = "charge-mid",
            MOMENT_CHARGE_PEAK = "charge-peak", MOMENT_RELEASE = "release", MOMENT_FLASH = "flash",
            MOMENT_RING = "ring", MOMENT_SHOCK = "shock", MOMENT_LAUNCH = "launch", MOMENT_WAYPOINT = "waypoint", MOMENT_IMPACT = "impact",
            MOMENT_HOLDING = "holding", MOMENT_STOP = "stop", MOMENT_TAIL = "tail", MOMENT_END = "end";

    /** The body's radius, in blocks. */
    public static final CgVfxParam RADIUS = SCHEMA.scalar("radius", 0.55f);
    /** How fast energy leaves the muzzle, in blocks a second. */
    public static final CgVfxParam SPEED = SCHEMA.scalar("speed", 40f);
    /** The most the body turns toward its target, in radians a second; it homes by proportional navigation under it. */
    public static final CgVfxParam TURN_RATE = SCHEMA.scalar("turnRate", 4f);
    /** How hard the body homes: 3 curves smoothly into the target, more bends harder early and runs straighter after. */
    public static final CgVfxParam NAVIGATION = SCHEMA.scalar("navigation", 3f);
    /** Seconds the body takes to swell from thin to its full radius after the release. */
    public static final CgVfxParam LAUNCH = SCHEMA.scalar("launch", 0.6f);
    /** The head's half-width, as a multiple of the body's radius. */
    public static final CgVfxParam HEAD_WIDTH = SCHEMA.scalar("headWidth", 1.45f);
    /** The head's half-length along the body, as a multiple of the body's radius. */
    public static final CgVfxParam HEAD_LENGTH = SCHEMA.scalar("headLength", 1.9f);
    /** How far the head flies when it hits nothing, in blocks. */
    public static final CgVfxParam MAX_LENGTH = SCHEMA.scalar("maxLength", 120f);
    /** Distance between the body's rings, in blocks. */
    public static final CgVfxParam RING_SPACING = SCHEMA.scalar("ringSpacing", 0.2f);
    /** How much pulses running down the body swell it, as a share of the radius. */
    public static final CgVfxParam THROB = SCHEMA.scalar("throb", 0.07f);

    /** Seconds the ball charges before the wave fires on its own; 0 fires at once. */
    public static final CgVfxParam CHARGE_TIME = SCHEMA.scalar("chargeTime", 1.6f);
    /** The charge ball's full radius, as a multiple of the body's. */
    public static final CgVfxParam CHARGE_RADIUS = SCHEMA.scalar("chargeRadius", 1.7f);
    /** The charge ball's size over the charge (0..1 of it), a share of {@link #CHARGE_RADIUS}: it swells past full, then settles. */
    public static final CgVfxParam CHARGE_SIZE = SCHEMA.curve("chargeSize", CgKeyframes.start(0f, 0.15f)
            .to(0.85f, 1.25f, CgEasings.OUT_CUBIC)
            .to(1f, 1f, CgEasings.IN_OUT_QUAD)
            .build());
    /** The beam's root after the release, a share of the charge ball's full size. */
    public static final CgVfxParam ROOT_SIZE = SCHEMA.scalar("rootSize", 0.8f);
    /** The release flash's brightness over the seconds since the release. */
    public static final CgVfxParam FLASH = SCHEMA.curve("flash", CgKeyframes.start(0f, 0f)
            .to(0.05f, 3f, CgEasings.OUT_QUAD)
            .to(0.5f, 0f, CgEasings.OUT_CUBIC)
            .build());
    /** The release flash's half-width, as a multiple of the body's radius. */
    public static final CgVfxParam FLASH_RADIUS = SCHEMA.scalar("flashRadius", 3f);
    /** Seconds the shock ring takes to sweep out, and how far it reaches, as a multiple of the body's radius. */
    public static final CgVfxParam SHOCK_TIME = SCHEMA.scalar("shockTime", 0.55f);
    public static final CgVfxParam SHOCK_RADIUS = SCHEMA.scalar("shockRadius", 5.5f);
    /** The sphere the charge's streaks fall in from, as a multiple of the body's radius. */
    public static final CgVfxParam STREAK_RADIUS = SCHEMA.scalar("streakRadius", 3.6f);

    public static final CgVfxParam CORE = SCHEMA.color("core", 1f, 1f, 1f, 1f);
    public static final CgVfxParam CORE_RIM = SCHEMA.color("coreRim", 0.7f, 0.95f, 1f, 1f);
    public static final CgVfxParam SHELL = SCHEMA.color("shell", 0.15f, 0.55f, 1.6f, 1f);
    public static final CgVfxParam SHELL_HOT = SCHEMA.color("shellHot", 0.7f, 0.95f, 1.6f, 1f);
    public static final CgVfxParam SPIRAL = SCHEMA.color("spiral", 0.5f, 0.85f, 1.6f, 1f);
    public static final CgVfxParam GLOW = SCHEMA.color("glow", 0.18f, 0.45f, 1.4f, 0.6f);

    /** A band per block, sectors around and the frame's normal as a line: add it to a look to check the path. */
    public static final CgVfxLayer DEBUG = CgVfxLayer.builder("crystalgraphics:shaders/vfx/beam/debug.shader")
            .colors(SHELL, CORE).priority(CgVfxLayer.PRIORITY_BANDS).build();

    private static final String BEAM = "crystalgraphics:shaders/vfx/beam/";
    private static final CgVfxLook KAMEHAMEHA = CgVfxLook.builder(SCHEMA)
            .layer(CgVfxLayer.builder(BEAM + "body_glow.shader").volume()
                    .radius(3.6f).colors(GLOW, null).priority(CgVfxLayer.PRIORITY_VOLUME).build())
            .layer(CgVfxLayer.builder(BEAM + "body_shell.shader")
                    .radius(1f).colors(SHELL, SHELL_HOT).priority(CgVfxLayer.PRIORITY_SURFACE).build())
            .layer(CgVfxLayer.builder(BEAM + "body_core.shader")
                    .radius(0.62f).colors(CORE, CORE_RIM).priority(CgVfxLayer.PRIORITY_CORE).build())
            .layer(orb("orb_glow", SLOT_HEAD, 3.2f, 1.3f, GLOW, null, CgVfxLayer.PRIORITY_VOLUME))
            .layer(orb("orb_shell", SLOT_HEAD, 1.15f, 1f, SHELL, SHELL_HOT, CgVfxLayer.PRIORITY_SURFACE))
            .layer(orb("orb_core", SLOT_HEAD, 0.75f, 0f, CORE, CORE_RIM, CgVfxLayer.PRIORITY_CORE))
            .layer(orb("orb_glow", SLOT_CHARGE, 3.2f, 1.6f, GLOW, null, CgVfxLayer.PRIORITY_VOLUME))
            .layer(orb("orb_plasma", SLOT_CHARGE, 1f, 0f, CORE, SHELL, CgVfxLayer.PRIORITY_CORE))
            .layer(orb("orb_glow", SLOT_FLASH, 3.2f, 1f, CORE_RIM, null, CgVfxLayer.PRIORITY_VOLUME))
            .layer(CgVfxLayer.builder(BEAM + "charge_streaks.shader").slot(SLOT_STREAKS)
                    .colors(SHELL_HOT, CORE).priority(CgVfxLayer.PRIORITY_BANDS).build())
            .layer(CgVfxLayer.builder(BEAM + "charge_arcs.shader").slot(SLOT_ARCS)
                    .colors(SPIRAL, CORE).priority(CgVfxLayer.PRIORITY_BANDS).build())
            .layer(CgVfxLayer.builder(BEAM + "disc_shock.shader").slot(SLOT_SHOCK)
                    .colors(CORE_RIM, SHELL).priority(CgVfxLayer.PRIORITY_BANDS).build())
            .build();

    /** Seconds the root takes to settle after the release, and to fade after a stop. */
    private static final float SETTLE = 0.25f, FADE = 0.35f;

    private final CgVfxStream stream = new CgVfxStream();
    private final CgVfxPath path = new CgVfxPath();
    private final Matrix4f placed = new Matrix4f();
    private float[] points = new float[64 * 3];
    /** The body's radius this frame, before the shape along it: what the head is sized from. */
    private float bodyRadius;
    private float aimX = 1f, aimY, aimZ;
    /** The age it releases at, and the ages it was stopped at and first hit; NaN until each happens. */
    private float releaseAge, stopAge = Float.NaN, impactAge = Float.NaN;
    /** The moments already announced, a bit each, and the stream's size when it was stopped. */
    private int momentsFired, sizeAtStop;

    public CgEnergyWave(CgVfxLook look, double x, double y, double z) {
        super(look, x, y, z);
        releaseAge = Math.max(get(CHARGE_TIME), 0f);
    }

    /** Blue-white, Sparking! Zero's. */
    public static CgVfxLook kamehameha() {
        return KAMEHAMEHA;
    }

    private static CgVfxLayer orb(String shader, String slot, float radius, float parameter, CgVfxParam a,
                                  CgVfxParam b, int priority) {
        return CgVfxLayer.builder(BEAM + shader + ".shader").slot(slot).radius(radius).parameter(parameter)
                .colors(a, b).priority(priority).build();
    }

    /** Where the source points, any length. */
    public CgEnergyWave aim(float dx, float dy, float dz) {
        aimX = dx;
        aimY = dy;
        aimZ = dz;
        return this;
    }

    /** What the body homes on, in world coordinates; NaN for straight flight. */
    public CgEnergyWave target(double x, double y, double z) {
        stream.target((float) (x - originX), (float) (y - originY), (float) (z - originZ));
        return this;
    }

    /** A point the body passes on its way to the target; NaN for none. */
    public CgEnergyWave via(double x, double y, double z) {
        stream.via((float) (x - originX), (float) (y - originY), (float) (z - originZ));
        return this;
    }

    /** Releases the charge now, if it has not yet released. */
    public CgEnergyWave fire() {
        releaseAge = Math.min(releaseAge, age);
        return this;
    }

    @Override
    public void stop() {
        if (state() == State.PLAYING) {
            stopAge = age;
            sizeAtStop = stream.size();
        }
        super.stop();
    }

    /** Whether it is still charging. */
    public boolean charging() {
        return age < releaseAge && state() == State.PLAYING;
    }

    /** Whether the head is at the target this frame. */
    public boolean impacting() {
        return stream.impacting();
    }

    @Override
    protected void tick(float dt) {
        if (state() == State.PLAYING && age >= releaseAge) stream.emit(0f, 0f, 0f, aimX, aimY, aimZ, get(SPEED));
        stream.tick(dt, get(TURN_RATE), get(NAVIGATION), get(MAX_LENGTH));
        if (Float.isNaN(impactAge) && stream.impacting()) impactAge = age;
        boolean ending = state() == State.STOPPING && stream.size() == 0 && age > stopAge + FADE;
        if (momentsHeard()) moments(ending);
        if (ending) die();
    }

    /** Announces each moment as it is crossed, framed on the muzzle or on the flight. */
    private void moments(boolean ending) {
        float radius = get(RADIUS), chargeTime = get(CHARGE_TIME), sinceRelease = age - releaseAge;
        float muzzle = radius * Math.max(get(STREAK_RADIUS), get(CHARGE_RADIUS) * 1.3f) * 1.1f;
        if (chargeTime > 0f) {
            if (age >= 0.15f * chargeTime) atMuzzle(0, MOMENT_CHARGE_START, muzzle);
            if (age >= 0.5f * chargeTime) atMuzzle(1, MOMENT_CHARGE_MID, muzzle);
            if (age >= 0.85f * chargeTime) atMuzzle(2, MOMENT_CHARGE_PEAK, muzzle);
        }
        if (sinceRelease >= 0.03f) atMuzzle(3, MOMENT_RELEASE, muzzle);
        if (sinceRelease >= 0.07f) atMuzzle(4, MOMENT_FLASH, radius * get(FLASH_RADIUS) * 2f);
        if (sinceRelease >= 0.2f * get(SHOCK_TIME)) atMuzzle(13, MOMENT_RING, radius * get(SHOCK_RADIUS) * 1.3f);
        if (sinceRelease >= 0.5f * get(SHOCK_TIME)) atMuzzle(5, MOMENT_SHOCK, radius * get(SHOCK_RADIUS) * 1.3f);
        if (sinceRelease >= 0.35f && stream.size() > 0) onFlight(6, MOMENT_LAUNCH);
        if (stream.viaReached()) onFlight(7, MOMENT_WAYPOINT);
        if (!Float.isNaN(impactAge)) onFlight(8, MOMENT_IMPACT);
        if (age >= impactAge + 0.8f) onFlight(9, MOMENT_HOLDING);
        if (age >= stopAge + 0.05f) onFlight(10, MOMENT_STOP);
        if (!Float.isNaN(stopAge) && stream.size() <= sizeAtStop / 2) onFlight(11, MOMENT_TAIL);
        if (ending) onFlight(12, MOMENT_END);
    }

    private void atMuzzle(int bit, String name, float frame) {
        if (fire(bit)) moment(name, 0f, 0f, 0f, frame);
    }

    /** Framed on the box holding the muzzle, the head and, while it hits, the impact. */
    private void onFlight(int bit, String name) {
        if (!fire(bit)) return;
        float minX = 0f, minY = 0f, minZ = 0f, maxX = 0f, maxY = 0f, maxZ = 0f;
        if (stream.size() > 0) {
            minX = Math.min(minX, stream.headX()); maxX = Math.max(maxX, stream.headX());
            minY = Math.min(minY, stream.headY()); maxY = Math.max(maxY, stream.headY());
            minZ = Math.min(minZ, stream.headZ()); maxZ = Math.max(maxZ, stream.headZ());
        }
        if (stream.impacting()) {
            minX = Math.min(minX, stream.impactX()); maxX = Math.max(maxX, stream.impactX());
            minY = Math.min(minY, stream.impactY()); maxY = Math.max(maxY, stream.impactY());
            minZ = Math.min(minZ, stream.impactZ()); maxZ = Math.max(maxZ, stream.impactZ());
        }
        float dx = maxX - minX, dy = maxY - minY, dz = maxZ - minZ;
        float frame = 0.5f * (float) Math.sqrt(dx * dx + dy * dy + dz * dz) + get(RADIUS) * 6f;
        moment(name, (minX + maxX) * 0.5f, (minY + maxY) * 0.5f, (minZ + maxZ) * 0.5f, frame);
    }

    /** True the first time {@code bit} is asked for. */
    private boolean fire(int bit) {
        if ((momentsFired & (1 << bit)) != 0) return false;
        momentsFired |= 1 << bit;
        return true;
    }

    @Override
    protected void submit(CgVfxFrame frame) {
        List<CgVfxLayer> layers = look().layers();
        submitMuzzle(frame, layers);
        int needed = (stream.size() + 2) * 3;
        if (points.length < needed) points = new float[needed * 2];
        boolean firing = state() == State.PLAYING && age >= releaseAge;
        int n = stream.points(points, frame.alpha() * CgVfxSystem.TICK, 0f, 0f, 0f, firing);
        path.build(points, n, get(RING_SPACING));
        if (path.count() < 2) return;
        shape(firing);
        int row = frame.path(path, seed, age);
        for (int i = 0; i < layers.size(); i++) {
            CgVfxLayer layer = layers.get(i);
            if (CgVfxLayer.SLOT_BODY.equals(layer.slot())) frame.tube(this, path, row, layer);
        }
        submitHead(frame, layers);
    }

    /** The charge ball and then the root, its streaks and arcs, the release flash and the shock ring, all at the muzzle. */
    private void submitMuzzle(CgVfxFrame frame, List<CgVfxLayer> layers) {
        float radius = get(RADIUS);
        float sinceRelease = age - releaseAge;
        float fade = Float.isNaN(stopAge) ? 1f : 1f - smooth(stopAge, stopAge + FADE, age);
        if (fade <= 0f) return;

        float full = radius * get(CHARGE_RADIUS);
        float ball;
        if (sinceRelease < 0f) {
            float chargeTime = Math.max(get(CHARGE_TIME), 1.0e-3f);
            ball = full * curve(CHARGE_SIZE).at(age / chargeTime);
        } else {
            ball = full * (1f + (get(ROOT_SIZE) - 1f) * smooth(0f, SETTLE, sinceRelease));
        }
        ball *= 1f + 0.05f * (float) Math.sin(age * 23f + seed * 6.28f);
        alongAim(placed);
        placed.rotateZ(age * 1.3f).scale(ball);
        draw(frame, layers, SLOT_CHARGE, placed, ball, ball, fade, 0f);

        if (sinceRelease >= 0f) {
            float flash = curve(FLASH).at(sinceRelease);
            if (flash > 0f) {
                float flashRadius = radius * get(FLASH_RADIUS) * (0.6f + 0.6f * smooth(0f, 0.4f, sinceRelease));
                alongAim(placed).scale(flashRadius);
                draw(frame, layers, SLOT_FLASH, placed, flashRadius, flashRadius, flash, 0f);
            }
            float shockTime = get(SHOCK_TIME);
            if (sinceRelease < shockTime) {
                float progress = (float) CgEasings.OUT_CUBIC.ease(sinceRelease / shockTime);
                float reach = radius * get(SHOCK_RADIUS);
                alongAim(placed).scale(reach, reach, 0.002f);
                // Gone well before the end of its sweep, so it never lingers as a faint outline.
                float left = 1f - sinceRelease / shockTime;
                draw(frame, layers, SLOT_SHOCK, placed, reach, reach, left * left * (float) Math.sqrt(left), progress);
            }
        }

        float chargeTime = get(CHARGE_TIME);
        float streaks = sinceRelease < 0f
                ? smooth(0f, 0.15f, chargeTime > 0f ? age / chargeTime : 1f)
                : 1f - smooth(0f, 0.3f, sinceRelease);
        if (streaks > 0f) {
            float spawn = radius * get(STREAK_RADIUS);
            alongAim(placed).scale(spawn);
            ribbons(frame, layers, SLOT_STREAKS, placed, ball, ball / spawn, streaks * fade);
        }
        // The arcs crackle on, quieter, round the root while the wave fires.
        float arcs = (sinceRelease < 0f ? 1f : 0.5f) * fade;
        alongAim(placed).rotateZ(age * 0.7f).scale(ball);
        ribbons(frame, layers, SLOT_ARCS, placed, ball, 0f, arcs);
    }

    /**
     * The head: an ellipsoid at the front, its tip at the body's end, sized from the body and pulsing, turning slowly
     * about the body's axis so its churn never repeats in place.
     */
    private void submitHead(CgVfxFrame frame, List<CgVfxLayer> layers) {
        int last = path.count() - 1;
        float tx = path.tangentX(last), ty = path.tangentY(last), tz = path.tangentZ(last);
        float pulse = 1f + 0.06f * (float) Math.sin(age * 21f + seed * 6.28f);
        float width = bodyRadius * get(HEAD_WIDTH) * pulse, length = bodyRadius * get(HEAD_LENGTH);
        facing(placed, tx, ty, tz).rotateZ(age * 1.7f).scale(width, width, length);
        float back = length * 0.55f;
        float x = path.x(last) - tx * back, y = path.y(last) - ty * back, z = path.z(last) - tz * back;
        for (int i = 0; i < layers.size(); i++) {
            CgVfxLayer layer = layers.get(i);
            if (SLOT_HEAD.equals(layer.slot())) frame.mesh(this, layer, x, y, z, placed, width, length, 1f, 0f);
        }
    }

    /** Every layer in {@code slot}, at the muzzle. */
    private void draw(CgVfxFrame frame, List<CgVfxLayer> layers, String slot, Matrix4f transform,
                      float width, float length, float intensity, float progress) {
        for (int i = 0; i < layers.size(); i++) {
            CgVfxLayer layer = layers.get(i);
            if (slot.equals(layer.slot())) frame.mesh(this, layer, 0f, 0f, 0f, transform, width, length, intensity, progress);
        }
    }

    /** Every layer in {@code slot} as ribbons, at the muzzle. */
    private void ribbons(CgVfxFrame frame, List<CgVfxLayer> layers, String slot, Matrix4f transform,
                         float ball, float share, float intensity) {
        for (int i = 0; i < layers.size(); i++) {
            CgVfxLayer layer = layers.get(i);
            if (slot.equals(layer.slot())) frame.ribbons(this, layer, 0f, 0f, 0f, transform, ball, share, intensity, 0f);
        }
    }

    /** {@code m} turned so its +z runs along the aim. */
    private Matrix4f alongAim(Matrix4f m) {
        float length = (float) Math.sqrt(aimX * aimX + aimY * aimY + aimZ * aimZ);
        return facing(m, aimX / length, aimY / length, aimZ / length);
    }

    private static Matrix4f facing(Matrix4f m, float x, float y, float z) {
        boolean steep = Math.abs(y) > 0.95f;
        return m.identity().rotateTowards(x, y, z, steep ? 1f : 0f, steep ? 0f : 1f, 0f);
    }

    /** The body's radius along it: swelling after the release, flared from the muzzle, swollen behind the head, rounded at it, throbbing. */
    private void shape(boolean firing) {
        float launch = get(LAUNCH), sinceRelease = age - releaseAge;
        float radius = get(RADIUS) * (launch > 0f ? 0.3f + 0.7f * smooth(0f, launch, sinceRelease) : 1f);
        bodyRadius = radius;
        float throb = get(THROB), length = path.length();
        for (int i = 0; i < path.count(); i++) {
            float s = path.arc(i);
            float muzzle = firing ? 0.55f + 0.45f * smooth(0f, 2.5f * radius, s) : smooth(0f, radius, s);
            float head = 1f + 0.3f * smooth(length - 4f * radius, length - radius, s);
            float tip = (float) Math.sqrt(smooth(0f, 0.8f * radius, length - s));
            float pulse = 1f + throb * (float) Math.sin(s * 1.3f - age * 16f)
                    + 0.5f * throb * (float) Math.sin(s * 3.1f - age * 29f + seed * 6.28f);
            path.radius(i, radius * muzzle * head * tip * pulse);
        }
    }

    private static float smooth(float edge0, float edge1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3f - 2f * t);
    }
}
