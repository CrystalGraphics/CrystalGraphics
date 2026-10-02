package com.crystalgraphics.vfx.effect.beam;

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
 * An energy wave: a beam fired from a muzzle that flows outward, bends where its source turns, homes on a target and
 * stops where it hits. {@link #kamehameha()} is its default look; another palette and other layers make a Final Flash
 * or a Galick Gun.
 *
 * <pre>{@code
 * CgEnergyWave wave = vfx.play(new CgEnergyWave(CgEnergyWave.kamehameha(), x, y, z));  // the muzzle
 * wave.aim(dx, dy, dz);              // where the source points; call as it turns
 * wave.target(tx, ty, tz);           // what the beam homes on, in world coordinates
 * wave.set(CgEnergyWave.RADIUS, 1f); // any parameter, this wave only
 * wave.stop();                       // the tail runs out, then the wave ends
 * }</pre>
 *
 * <p>Its look's layers draw in two slots: {@link CgVfxLayer#SLOT_BODY} as tubes along the body, and {@link #SLOT_HEAD}
 * on a sphere at the front ({@link CgVfxFrame#mesh}), whose {@code CG_OBJECT_CUSTOM1} is the head's half-width and
 * half-length.</p>
 *
 * <ul>
 *   <li>Fires from the moment it plays. Positions are absolute; the wave simulates relative to where it was made.</li>
 *   <li>Every sample of the body homes on the target, turning at most {@link #TURN_RATE}, so the body curves smoothly
 *       into it, and a wave runs down it when the aim moves.</li>
 * </ul>
 */
public final class CgEnergyWave extends CgVfxEffect {

    public static final CgVfxSchema SCHEMA = new CgVfxSchema();
    /** The slot a layer draws the head in: a sphere at the front, its +z along the body. */
    public static final String SLOT_HEAD = "head";
    /** The body's radius, in blocks. */
    public static final CgVfxParam RADIUS = SCHEMA.scalar("radius", 0.55f);
    /** How fast energy leaves the muzzle, in blocks a second. */
    public static final CgVfxParam SPEED = SCHEMA.scalar("speed", 40f);
    /** The most the body turns toward its target, in radians a second; it homes by proportional navigation under it. */
    public static final CgVfxParam TURN_RATE = SCHEMA.scalar("turnRate", 4f);
    /** How hard the body homes: 3 curves smoothly into the target, more bends harder early and runs straighter after. */
    public static final CgVfxParam NAVIGATION = SCHEMA.scalar("navigation", 3f);
    /** Seconds the body takes to swell from thin to its full radius after firing. */
    public static final CgVfxParam LAUNCH = SCHEMA.scalar("launch", 0.6f);
    /** The head's half-width, as a multiple of the body's radius. */
    public static final CgVfxParam HEAD_WIDTH = SCHEMA.scalar("headWidth", 1.45f);
    /** The head's half-length along the body, as a multiple of the body's radius. */
    public static final CgVfxParam HEAD_LENGTH = SCHEMA.scalar("headLength", 1.9f);
    /** How far the head flies when it hits nothing, in blocks. */
    public static final CgVfxParam MAX_LENGTH = SCHEMA.scalar("maxLength", 60f);
    /** Distance between the body's rings, in blocks. */
    public static final CgVfxParam RING_SPACING = SCHEMA.scalar("ringSpacing", 0.2f);
    /** How much pulses running down the body swell it, as a share of the radius. */
    public static final CgVfxParam THROB = SCHEMA.scalar("throb", 0.07f);
    public static final CgVfxParam CORE = SCHEMA.color("core", 1f, 1f, 1f, 1f);
    public static final CgVfxParam CORE_RIM = SCHEMA.color("coreRim", 0.7f, 0.95f, 1f, 1f);
    public static final CgVfxParam SHELL = SCHEMA.color("shell", 0.15f, 0.55f, 1.6f, 1f);
    public static final CgVfxParam SHELL_HOT = SCHEMA.color("shellHot", 0.7f, 0.95f, 1.6f, 1f);
    public static final CgVfxParam SPIRAL = SCHEMA.color("spiral", 0.5f, 0.85f, 1.6f, 1f);
    public static final CgVfxParam GLOW = SCHEMA.color("glow", 0.18f, 0.45f, 1.4f, 0.6f);

    /** A band per block, sectors around and the frame's normal as a line: add it to a look to check the path. */
    public static final CgVfxLayer DEBUG = CgVfxLayer.builder("crystalgraphics:shaders/vfx/beam/debug.shader")
            .colors(SHELL, CORE).priority(CgVfxLayer.PRIORITY_BANDS).build();

    private static final CgVfxLook KAMEHAMEHA = CgVfxLook.builder(SCHEMA)
            .layer(CgVfxLayer.builder("crystalgraphics:shaders/vfx/beam/body_glow.shader").volume()
                    .radius(3.6f).colors(GLOW, null).priority(CgVfxLayer.PRIORITY_VOLUME).build())
            .layer(CgVfxLayer.builder("crystalgraphics:shaders/vfx/beam/body_shell.shader")
                    .radius(1f).colors(SHELL, SHELL_HOT).priority(CgVfxLayer.PRIORITY_SURFACE).build())
            .layer(CgVfxLayer.builder("crystalgraphics:shaders/vfx/beam/body_core.shader")
                    .radius(0.62f).colors(CORE, CORE_RIM).priority(CgVfxLayer.PRIORITY_CORE).build())
            .layer(CgVfxLayer.builder("crystalgraphics:shaders/vfx/beam/orb_glow.shader").slot(SLOT_HEAD)
                    .radius(3.2f).parameter(1.3f).colors(GLOW, null).priority(CgVfxLayer.PRIORITY_VOLUME).build())
            .layer(CgVfxLayer.builder("crystalgraphics:shaders/vfx/beam/orb_shell.shader").slot(SLOT_HEAD)
                    .radius(1.15f).parameter(1f).colors(SHELL, SHELL_HOT).priority(CgVfxLayer.PRIORITY_SURFACE).build())
            .layer(CgVfxLayer.builder("crystalgraphics:shaders/vfx/beam/orb_core.shader").slot(SLOT_HEAD)
                    .radius(0.75f).colors(CORE, CORE_RIM).priority(CgVfxLayer.PRIORITY_CORE).build())
            .build();

    private final CgVfxStream stream = new CgVfxStream();
    private final CgVfxPath path = new CgVfxPath();
    private final Matrix4f head = new Matrix4f();
    private float[] points = new float[64 * 3];
    /** The body's radius this frame, before the shape along it: what the head is sized from. */
    private float bodyRadius;
    private float aimX = 1f, aimY, aimZ;

    public CgEnergyWave(CgVfxLook look, double x, double y, double z) {
        super(look, x, y, z);
    }

    /** Blue-white, Sparking! Zero's. */
    public static CgVfxLook kamehameha() {
        return KAMEHAMEHA;
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

    /** Whether the head is at the target this frame. */
    public boolean impacting() {
        return stream.impacting();
    }

    @Override
    protected void tick(float dt) {
        if (state() == State.PLAYING) stream.emit(0f, 0f, 0f, aimX, aimY, aimZ, get(SPEED));
        stream.tick(dt, get(TURN_RATE), get(NAVIGATION), get(MAX_LENGTH));
        if (state() == State.STOPPING && stream.size() == 0) die();
    }

    @Override
    protected void submit(CgVfxFrame frame) {
        int needed = (stream.size() + 2) * 3;
        if (points.length < needed) points = new float[needed * 2];
        int n = stream.points(points, frame.alpha() * CgVfxSystem.TICK, 0f, 0f, 0f, state() == State.PLAYING);
        path.build(points, n, get(RING_SPACING));
        if (path.count() < 2) return;
        shape();
        int row = frame.path(path, seed, age);
        List<CgVfxLayer> layers = look().layers();
        for (int i = 0; i < layers.size(); i++) {
            CgVfxLayer layer = layers.get(i);
            if (CgVfxLayer.SLOT_BODY.equals(layer.slot())) frame.tube(this, path, row, layer);
        }
        submitHead(frame, layers);
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
        boolean steep = Math.abs(ty) > 0.95f;
        head.identity().rotateTowards(tx, ty, tz, steep ? 1f : 0f, steep ? 0f : 1f, 0f)
                .rotateZ(age * 1.7f).scale(width, width, length);
        float back = length * 0.55f;
        float x = path.x(last) - tx * back, y = path.y(last) - ty * back, z = path.z(last) - tz * back;
        for (int i = 0; i < layers.size(); i++) {
            CgVfxLayer layer = layers.get(i);
            if (SLOT_HEAD.equals(layer.slot())) frame.mesh(this, layer, x, y, z, head, width, length, 1f, 0f);
        }
    }

    /** The body's radius along it: swelling after it fires, flared from the muzzle, swollen behind the head, rounded at it, throbbing. */
    private void shape() {
        float launch = get(LAUNCH);
        float radius = get(RADIUS) * (launch > 0f ? 0.3f + 0.7f * smooth(0f, launch, age) : 1f);
        bodyRadius = radius;
        float throb = get(THROB), length = path.length();
        boolean firing = state() == State.PLAYING;
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
