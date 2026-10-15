package com.crystalgraphics.vfx.effect.beam;

import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxElement;
import com.crystalgraphics.vfx.CgVfxFrame;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.CgVfxTrace;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.look.CgVfxSlot;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.vfx.path.CgVfxPath;
import com.crystalgraphics.vfx.sim.CgVfxStream;
import org.joml.Matrix4f;

/**
 * A beam's body: the stream of energy leaving its muzzle and flying to its target ({@link CgVfxStream}), the path
 * through it each frame, the body's profile along it (swelling after the release, flared from the muzzle, throbbing,
 * surges racing to the head) and the head at its front.
 *
 * <pre>{@code
 * body.tick(dt, firing, muzzleX, muzzleY, muzzleZ, aimX, aimY, aimZ);          // every tick
 * body.submit(frame, firing, muzzleX, muzzleY, muzzleZ, releaseAge, stopAge);  // every frame
 * if (body.stream().impacting()) ... body.normalX() ...                        // back along the beam at its end
 * }</pre>
 *
 * <p>Its slots: {@link #BODY}, tubes along the path ({@code CgVfxTube}); {@link #ARCS}, ribbons riding the path
 * ({@code CgVfxFrame.pathRibbons}); {@link #HEAD}, a sphere at the front, its +z along the body, {@code CG_OBJECT_CUSTOM1}
 * its half-width and half-length. Each ring's radius and intensity are the profile's, in the path texture.</p>
 */
public final class CgBeamBody extends CgVfxElement {

    public static final CgVfxSchema SCHEMA = CgVfxSchema.of("crystalgraphics:wave/body");
    /** Seconds the body takes to swell from thin to its full radius after the release. */
    public static final CgVfxParam.Scalar LAUNCH = SCHEMA.time("launch", 0.6f);
    /** Distance between the body's rings. */
    public static final CgVfxParam.Scalar RING_SPACING = SCHEMA.length("ringSpacing", 0.2f);
    /** How much pulses running down the body swell it, a share of its radius. */
    public static final CgVfxParam.Scalar THROB = SCHEMA.scalar("throb", 0.07f);
    /** How much brighter the pulses racing down the body flash, a share of its brightness. */
    public static final CgVfxParam.Scalar PULSE = SCHEMA.scalar("pulse", 0.25f);
    /**
     * Seconds between the surges of energy that leave the muzzle while it fires, on average and jittered: each a bulge
     * outrunning the body to its head; 0 for none.
     */
    public static final CgVfxParam.Scalar SURGE_GAP = SCHEMA.time("surgeGap", 0.6f);
    /** How much a surge swells the body, a share of its radius. */
    public static final CgVfxParam.Scalar SURGE = SCHEMA.scalar("surge", 0.6f);
    /** How much brighter a surge flares, a share of the body's brightness. */
    public static final CgVfxParam.Scalar SURGE_FLASH = SCHEMA.scalar("surgeFlash", 1.8f);
    /** The head's half-width and half-length along the body, at the body's full radius. */
    public static final CgVfxParam.Scalar HEAD_WIDTH = SCHEMA.length("headWidth", 1.16f);
    public static final CgVfxParam.Scalar HEAD_LENGTH = SCHEMA.length("headLength", 1.52f);
    /** How far back from the body's end the head's centre sits, a share of its half-length. */
    public static final CgVfxParam.Scalar HEAD_CENTRE = SCHEMA.scalar("headCentre", 0.55f);

    /** The body: tubes along the path. */
    public static final CgVfxSlot BODY = CgVfxSlot.tube("crystalgraphics:wave/body");
    /** Lightning and the like riding the body: ribbons along the path. */
    public static final CgVfxSlot ARCS = CgVfxSlot.pathRibbons("crystalgraphics:wave/body/arcs");
    /** The head: a sphere at the front, its +z along the body. */
    public static final CgVfxSlot HEAD = CgVfxSlot.mesh("crystalgraphics:wave/head");

    /** Surges in flight at most, and how much faster than the body they run. */
    private static final int MAX_SURGES = 16;
    private static final float SURGE_RUN = 1.6f;
    private static final int STREAM_NS = CgTrace.name("vfx.wave.stream-ns"), PATH_NS = CgTrace.name("vfx.wave.path-ns"),
            PATH_RINGS = CgTrace.name("vfx.wave.path-rings");
    private static final int PATH_ZONE = CgTrace.name("vfx.wave.path"), SHAPE_ZONE = CgTrace.name("vfx.wave.shape"),
            BODY_ZONE = CgTrace.name("vfx.wave.body"), HEAD_ZONE = CgTrace.name("vfx.wave.head");

    private final CgVfxStream stream = new CgVfxStream();
    private final CgVfxPath path = new CgVfxPath();
    private final Matrix4f placed = new Matrix4f();
    private final float[] surgeAt = new float[MAX_SURGES], surgeSize = new float[MAX_SURGES];
    private float[] points = new float[64 * 3];
    /** The body's share of its full radius this frame, before the profile along it: what the head is sized by. */
    private float swell = 1f;
    private float normalX, normalY = 1f, normalZ;

    public CgBeamBody(CgVfxEffect<?> effect) {
        super(effect);
    }

    /** The stream it draws: its head, its impact, its target. */
    public CgVfxStream stream() {
        return stream;
    }

    /** The path it drew last frame: its rings, their radii. */
    public CgVfxPath path() {
        return path;
    }

    /** Back along the beam at its end, a unit vector, as of the last frame it hit. */
    public float normalX() {
        return normalX;
    }

    public float normalY() {
        return normalY;
    }

    public float normalZ() {
        return normalZ;
    }

    /**
     * Emits from the muzzle while {@code firing}, and flies the stream: homing on its target ({@code TURN_RATE},
     * {@code NAVIGATION}) or, with {@code STEER} above 0, steered into an end chasing it. Every tick.
     */
    public void tick(float dt, boolean firing, float mx, float my, float mz, float ax, float ay, float az) {
        long t = CgVfxTrace.start();
        if (firing) stream.emit(mx, my, mz, ax, ay, az, get(CgEnergyWave.SPEED));
        float steer = get(CgEnergyWave.STEER), reach = get(CgEnergyWave.MAX_LENGTH);
        if (steer > 0f) stream.steer(dt, mx, my, mz, ax, ay, az, steer, reach);
        else stream.tick(dt, get(CgEnergyWave.TURN_RATE), get(CgEnergyWave.NAVIGATION), reach);
        CgVfxTrace.lap(STREAM_NS, t);
    }

    /**
     * Builds the path from the stream at the frame's alpha, from the muzzle while {@code firing}, shapes it and draws
     * every layer on {@link #BODY}, {@link #ARCS} and {@link #HEAD}.
     */
    public void submit(CgVfxFrame frame, boolean firing, float mx, float my, float mz, float releaseAge, float stopAge) {
        try (CgTrace.Zone ignored = CgTrace.zone(CgVfxTrace.CHANNEL, PATH_ZONE)) {
            int needed = (stream.size() + 2) * 3;
            if (points.length < needed) points = new float[needed * 2];
            long t = CgVfxTrace.start();
            int n = stream.points(points, frame.alpha() * CgVfxSystem.TICK, mx, my, mz, firing);
            path.build(points, n, get(RING_SPACING));
            CgVfxTrace.lap(PATH_NS, t);
            CgVfxTrace.count(PATH_RINGS, path.count());
        }
        if (path.count() < 2) return;
        try (CgTrace.Zone ignored = CgTrace.zone(CgVfxTrace.CHANNEL, SHAPE_ZONE)) {
            shape(firing, releaseAge, stopAge);
        }
        try (CgTrace.Zone ignored = CgTrace.zone(CgVfxTrace.CHANNEL, BODY_ZONE)) {
            int row = frame.path(path, seed(), age());
            frame.tube(effect, BODY, path, row);
            frame.pathRibbons(effect, ARCS, path, row, 1f);
        }
        int last = path.count() - 1;
        if (stream.impacting()) {
            normalX = -path.tangentX(last);
            normalY = -path.tangentY(last);
            normalZ = -path.tangentZ(last);
        }
        try (CgTrace.Zone ignored = CgTrace.zone(CgVfxTrace.CHANNEL, HEAD_ZONE)) {
            head(frame, last);
        }
    }

    /**
     * The head: an ellipsoid at the front, its tip at the body's end, sized with the body and pulsing, turning slowly
     * about the body's axis so its churn never repeats in place.
     */
    private void head(CgVfxFrame frame, int last) {
        float tx = path.tangentX(last), ty = path.tangentY(last), tz = path.tangentZ(last), age = age();
        float pulse = 1f + 0.06f * (float) Math.sin(age * 21f + seed() * 6.28f);
        float width = get(HEAD_WIDTH) * swell * pulse, length = get(HEAD_LENGTH) * swell;
        facing(placed, tx, ty, tz).rotateZ(age * 1.7f).scale(width, width, length);
        float back = length * get(HEAD_CENTRE);
        frame.mesh(effect, HEAD, path.x(last) - tx * back, path.y(last) - ty * back, path.z(last) - tz * back, placed,
                width, length, 1f, 0f);
    }

    /** The radius along it: swelling after the release, flared from the muzzle, swollen behind the head, rounded at it, throbbing. */
    private void shape(boolean firing, float releaseAge, float stopAge) {
        float launch = get(LAUNCH), age = age(), since = age - releaseAge, seed = seed();
        swell = launch > 0f ? 0.3f + 0.7f * smooth(0f, launch, since) : 1f;
        float radius = get(CgEnergyWave.RADIUS) * swell;
        float throb = get(THROB), pulse = get(PULSE), length = path.length();
        int surges = surges(radius, length, releaseAge, stopAge);
        float surgeSwell = get(SURGE), surgeFlash = get(SURGE_FLASH);
        // A surge's steep front and long wake, in blocks.
        float front = 1.1f * radius, wake = 4.5f * radius;
        for (int i = 0; i < path.count(); i++) {
            float s = path.arc(i);
            float muzzle = firing ? 0.55f + 0.45f * smooth(0f, 2.5f * radius, s) : smooth(0f, radius, s);
            float head = 1f + 0.3f * smooth(length - 4f * radius, length - radius, s);
            float tip = (float) Math.sqrt(smooth(0f, 0.8f * radius, length - s));
            float throbbing = 1f + throb * (float) Math.sin(s * 1.3f - age * 16f)
                    + 0.5f * throb * (float) Math.sin(s * 3.1f - age * 29f + seed * 6.28f);
            float surge = 0f;
            for (int k = 0; k < surges; k++) {
                float d = s - surgeAt[k];
                if (d > 3f * front || d < -3f * wake) continue;
                float w = d > 0f ? d / front : d / wake;
                surge += surgeSize[k] * (float) Math.exp(-w * w);
            }
            path.radius(i, radius * muzzle * head * tip * throbbing * (1f + surgeSwell * surge));
            // Sharp bright pulses racing down the body, faster than it flows.
            float flash = 0.5f + 0.5f * (float) Math.sin(s * 0.9f - age * 22f);
            flash *= flash;
            flash *= flash;
            path.intensity(i, 1f + pulse * flash * flash + surgeFlash * surge);
        }
    }

    /**
     * Fills {@link #surgeAt} with each surge in flight, as arc length along the body, and {@link #surgeSize}; answers how
     * many. Surge {@code k} leaves the muzzle near {@code k} gaps after the release, only while it fires, and grows to its
     * size over its first few radii.
     */
    private int surges(float radius, float length, float releaseAge, float stopAge) {
        float gap = get(SURGE_GAP), age = age(), since = age - releaseAge;
        if (gap <= 0f || since <= 0f) return 0;
        float speed = get(CgEnergyWave.SPEED), run = speed * SURGE_RUN;
        // Arc length is measured from the tail, which flies off from the muzzle once stopped.
        float drift = Float.isNaN(stopAge) ? 0f : (age - stopAge) * speed;
        float last = Float.isNaN(stopAge) ? since : stopAge - releaseAge;
        int bits = Float.floatToIntBits(seed()), n = 0;
        int first = Math.max(0, (int) Math.floor((since - (length + drift + 12f * radius) / run) / gap) - 1);
        for (int k = first; n < MAX_SURGES && gap * (k - 0.4f) <= last; k++) {
            float born = gap * (k + 0.8f * (CgVfxEmitterInstance.rand(bits, k, 0) - 0.5f));
            if (born < 0f || born > last) continue;
            float fromMuzzle = (since - born) * run;
            surgeAt[n] = fromMuzzle - drift;
            surgeSize[n] = (0.6f + 0.4f * CgVfxEmitterInstance.rand(bits, k, 1)) * smooth(0f, 4f * radius, fromMuzzle);
            n++;
        }
        return n;
    }
}
