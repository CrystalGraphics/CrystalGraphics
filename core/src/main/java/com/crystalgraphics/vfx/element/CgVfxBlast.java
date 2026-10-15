package com.crystalgraphics.vfx.element;

import com.crystalgraphics.collision.hit.CgHitPart;
import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.render.post.volume.CgImpact;
import com.crystalgraphics.render.post.volume.CgImpactFrame;
import com.crystalgraphics.render.post.volume.CgImpactSequence;
import com.crystalgraphics.render.post.volume.CgPostSettings;
import com.crystalgraphics.render.post.volume.CgPostVolume;
import com.crystalgraphics.settings.CgQuality;
import com.crystalgraphics.shape.primitive.CgShell;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxElement;
import com.crystalgraphics.vfx.CgVfxFrame;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.CgVfxTrace;
import com.crystalgraphics.vfx.camera.CgCameraShake;
import com.crystalgraphics.vfx.camera.CgCameraShakes;
import com.crystalgraphics.vfx.look.CgVfxKit;
import com.crystalgraphics.vfx.look.CgVfxLayer;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.look.CgVfxSlot;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * A big explosion with an impact frame before it: ignited at a point, it holds at its ignition while {@link #BEATS} play,
 * then bursts out of their last beat into a dome of light eroding as it cools, a flash, a shock front, a ring, debris
 * and {@link #EXPLOSION}'s cloud, dust and embers, a front hitbox reaching each entity within {@link #RADIUS} once. A
 * wave's final blast, a bomb, a meteor.
 *
 * <pre>{@code
 * private final CgVfxBlast blast = new CgVfxBlast(this, PART_BLAST);
 *
 * blast.ignite(x, y, z, nx, ny, nz);          // once: where, facing (nx, ny, nz)
 * if (blast.tick(dt)) burst(x, y, z);         // every tick: true on the tick it bursts out of its impact frame
 * blast.submit(frame);
 * if (blast.done()) die();
 * }</pre>
 *
 * <ul>
 *   <li>Its look needs {@link #KIT}, or a kit of its own on its slots. {@code KIT}'s light layers read the look's
 *       energy palette by name ({@code core}, {@code coreRim}, {@code shell}, {@code shellHot}, {@code glow}), as
 *       {@code CgEnergyWave}'s do.</li>
 *   <li>An impact frame is whole or absent: {@link #IMPACT} at 0 when it ignites plays none, and its burst then shakes
 *       with the arriving front ({@link #SHAKE}) rather than the cut ({@link #CUT_SHAKE}, {@link #RETURN}).</li>
 *   <li>On a server it bursts and its front hits, with no particles, flash or shake.</li>
 * </ul>
 */
public final class CgVfxBlast extends CgVfxElement {

    public static final CgVfxSchema SCHEMA = CgVfxSchema.of("crystalgraphics:blast");
    /** Where the default {@link #FLASH} peaks, a share of {@link #TIME}: what an impact frame holds it at. */
    private static final float MAIN_PULSE = 0.0625f;
    /** Seconds it bursts out over. */
    public static final CgVfxParam.Scalar TIME = SCHEMA.time("time", 2.4f);
    /** Its full radius: the dome's, and how far its front hits. */
    public static final CgVfxParam.Scalar RADIUS = SCHEMA.length("radius", 9.6f);
    /** Seconds the dome takes to cool and erode away. */
    public static final CgVfxParam.Scalar DOME_TIME = SCHEMA.time("domeTime", 4.5f);
    /** The dome's size over the blast (0..1 of {@link #TIME}), a share of {@link #RADIUS}: bursting out, then drifting. */
    public static final CgVfxParam.Curve SIZE = SCHEMA.curve("size", CgKeyframes.start(0f, 0.08f)
            .to(0.35f, 1f, CgEasings.OUT_EXPO)
            .to(1f, 1.15f, CgEasings.LINEAR)
            .build());
    /** Its heart's glow over the blast. */
    public static final CgVfxParam.Curve GLOW = SCHEMA.curve("glow", CgKeyframes.start(0f, 0f)
            .to(0.04f, 3.5f, CgEasings.OUT_QUAD)
            .to(0.6f, 0.4f, CgEasings.OUT_CUBIC)
            .to(1f, 0f, CgEasings.LINEAR)
            .build());
    /**
     * Its screen flash over the blast, a share of its peak: a big explosion's double flash, a short pulse, a dip as the
     * shock front hides the fireball, then the main pulse, held and gone as it cools.
     */
    public static final CgVfxParam.Curve FLASH = SCHEMA.curve("flash", CgKeyframes.start(0f, 0f)
            .to(0.008f, 0.3f, CgEasings.OUT_QUAD)
            .to(0.025f, 0.1f, CgEasings.LINEAR)
            .to(MAIN_PULSE, 1f, CgEasings.OUT_QUAD)
            .to(0.12f, 0.85f, CgEasings.LINEAR)
            .to(0.55f, 0f, CgEasings.OUT_CUBIC)
            .build());
    /** 1 plays the impact frame; 0 none. Read as it ignites; 0 later hides a frame already playing. */
    public static final CgVfxParam.Scalar IMPACT = SCHEMA.scalar("impact", 1f);
    /** 1 draws the cloud's billows; 0 keeps simulating them, undrawn, so the dome shows whole. Read every frame. */
    public static final CgVfxParam.Scalar BILLOWS = SCHEMA.scalar("billows", 1f);
    /**
     * Seconds into the blast its impact frame starts: its ignition, a small ball blazing, which the beats ink and the
     * blast holds at while they play. The flash runs on beneath them to its main pulse and holds there, so the white cuts
     * to the burst at its brightest.
     */
    public static final CgVfxParam.Scalar HOLD_AT = SCHEMA.time("holdAt", 0.02f);
    /** The flash's peak: stops of exposure, red and blue split from the burst (0 to 1), and bloom's multiple. */
    public static final CgVfxParam.Scalar FLASH_STOPS = SCHEMA.scalar("flashStops", 2.5f);
    public static final CgVfxParam.Scalar FLASH_CHROMATIC = SCHEMA.scalar("flashChromatic", 0.5f);
    public static final CgVfxParam.Scalar FLASH_BLOOM = SCHEMA.scalar("flashBloom", 2f);
    /** The flash is full within this many radii of the burst, fading out over twice that beyond. */
    public static final CgVfxParam.Scalar FLASH_REACH = SCHEMA.scalar("flashReach", 3f);
    /** The impact frame plays for a camera within this many radii: by default as far as the flash shows. */
    public static final CgVfxParam.Scalar IMPACT_REACH = SCHEMA.scalar("impactReach", 9f);
    /** How thick its front hitbox is. */
    public static final CgVfxParam.Scalar FRONT = SCHEMA.length("front", 1.6f);
    /** Played at a burst with no impact frame, at the scale of {@link #RADIUS}, arriving with its shock front. */
    public static final CgVfxParam.Shake SHAKE = SCHEMA.shake("shake",
            CgCameraShakes.EXPLOSION.toBuilder().arrives(2.6f, 1.2f, CgEasings.OUT_CUBIC).build());
    /**
     * Played as the impact frame cuts back instead: felt at once, since the front crossed most of its reach beneath the
     * beats, and without the explosion's widening kick, which would cancel {@link #RETURN}.
     */
    public static final CgVfxParam.Shake CUT_SHAKE = SCHEMA.shake("cutShake",
            CgCameraShakes.EXPLOSION.toBuilder().kick(0f, 0f).build());
    /**
     * The return shot, played with it: the field of view narrowed at once and easing back, so the picture comes back
     * closer than it left and pulls away, never to the framing the beats cut from.
     */
    public static final CgVfxParam.Shake RETURN = SCHEMA.shake("return",
            CgCameraShake.builder().kick(-0.2f, 0.5f).radii(3f, 4f).build());

    /** Its first beat: what glows white on black, a flash star with its cross at the burst. */
    public static final CgImpactFrame HIT = CgImpactFrame.drawn().paper(CgImpactFrame.Tone.DARK).fillSubject(true)
            .star(0.045f).cross(true).jitter(3f).build();
    /** Its impact frame's beats, on twos but for the cut: the hit, inverted, the edge hatched, focus lines, then white. */
    public static final CgImpactSequence BEATS = CgImpactSequence.at(24f)
            .beat(HIT, 2).beat(CgImpact.SUBJECT_INVERTED, 1).beat(CgImpact.HATCHED, 2)
            .beat(CgImpact.FOCUS_LINES, 4).beat(CgImpact.WHITE, 1).build();

    /** The cloud, dust, debris, embers, hot streaks, ground shock and ink, its emitters started as it bursts. */
    public static final CgVfxExplosion EXPLOSION = new CgVfxExplosion("crystalgraphics:blast/explosion");

    /** The dome: a sphere facing as it was ignited. */
    public static final CgVfxSlot DOME = CgVfxSlot.mesh("crystalgraphics:blast/dome");
    /** The dome's light on what is round it: a sphere the dome's size, burning down as it cools. */
    public static final CgVfxSlot LIGHT = CgVfxSlot.mesh("crystalgraphics:blast/light");
    /** Its heart: a sphere half the dome's, turning, while it glows. */
    public static final CgVfxSlot CORE = CgVfxSlot.mesh("crystalgraphics:blast/core");
    /** Its shock front racing out ahead of the dust: a sphere, {@code .z} what is left of it, {@code .w} its run 0..1. */
    public static final CgVfxSlot SHOCK = CgVfxSlot.mesh("crystalgraphics:blast/shock");
    /** Its ring on the ground: a disc. */
    public static final CgVfxSlot RING = CgVfxSlot.mesh("crystalgraphics:blast/ring");
    /** Its debris: ribbons, one burst, {@code .w} the seconds since it burst. */
    public static final CgVfxSlot DEBRIS = CgVfxSlot.ribbons("crystalgraphics:blast/debris");

    private static final String BEAM = "crystalgraphics:shaders/vfx/beam/";
    /** The dome, its light on what is round it, and the debris thrown off it. */
    public static final CgVfxKit FIREBALL = CgVfxKit.of("crystalgraphics:blast/fireball")
            .layer(CgVfxLayer.of(BEAM + "orb_light.shader").on(LIGHT).size(2.2f).role(CgVfxLayer.Role.LIGHT).build())
            .layer(CgVfxLayer.of(BEAM + "blast_dome.shader").on(DOME).build())
            .layer(CgVfxLayer.of(BEAM + "impact_splash.shader").on(DEBRIS)
                    .set("_Burst", 1f).set("_Count", 90f).set("_Speed", 18f).set("_Life", 1.2f).set("_Width", 0.08f)
                    .set("_Streak", 0.08f).build())
            .build();
    /** The plasma heart blazing in the dome, and the haze round it. */
    public static final CgVfxKit HEART = CgVfxKit.of("crystalgraphics:blast/heart")
            .layer(CgVfxLayer.of("crystalgraphics:shaders/vfx/air/haze_orb.shader").on(CORE).size(3.4f)
                    .role(CgVfxLayer.Role.DISTORTION).from(CgQuality.MEDIUM).build())
            .layer(CgVfxLayer.of(BEAM + "orb_plasma.shader").on(CORE).build())
            .build();
    /** The shock front bending the air, and the ring it leaves on the ground. */
    public static final CgVfxKit SHOCKWAVE = CgVfxKit.of("crystalgraphics:blast/shockwave")
            .layer(CgVfxLayer.of("crystalgraphics:shaders/vfx/air/shock.shader").on(SHOCK)
                    .role(CgVfxLayer.Role.DISTORTION).from(CgQuality.MEDIUM).build())
            .layer(CgVfxLayer.of(BEAM + "disc_shock.shader").on(RING).build())
            .build();
    /**
     * The engine's blast: {@link #FIREBALL}, {@link #HEART}, {@link #SHOCKWAVE} and {@link #EXPLOSION}'s kit, each of
     * which a look may drop alone; its emitters authored for a blast of {@link #RADIUS} 9.6.
     */
    public static final CgVfxKit KIT = CgVfxKit.of("crystalgraphics:blast").params(SCHEMA)
            .scalesWith(RADIUS, 9.6f)
            .kit(EXPLOSION.kit()).kit(FIREBALL).kit(HEART).kit(SHOCKWAVE)
            .build();

    /** The trace marker each blast leaves as it bursts: where a profile splits a frame's beams from its blasts. */
    public static final String MARKER = "vfx.blast";
    /** Its moments; the k-th frame of its impact frame, from 1, is {@code MOMENT_IMPACT_FRAME + k}. */
    public static final String MOMENT_START = "blast-start", MOMENT_PEAK = "blast-peak", MOMENT_FADE = "blast-fade",
            MOMENT_IMPACT_FRAME = "impact-frame-";
    private static final String[] IMPACT_FRAME_MOMENTS = new String[14];

    static {
        for (int k = 0; k < IMPACT_FRAME_MOMENTS.length; k++) IMPACT_FRAME_MOMENTS[k] = MOMENT_IMPACT_FRAME + (k + 1);
    }

    /** Seconds its shock front lives, and how far it reaches in radii: what {@link #SHAKE} arrives with. */
    public static final float SHOCK_SECONDS = 1.2f, SHOCK_REACH = 2.6f;
    /** Seconds its ring on the ground takes to sweep out and fade. */
    public static final float RING_SECONDS = 0.7f;
    /** Its volumes' priorities, over a scene's mood volumes: the impact frame over the flash. */
    public static final int FLASH_PRIORITY = 10, IMPACT_PRIORITY = 11;
    private static final int BURST_ZONE = CgTrace.name("vfx.blast.burst");

    private final CgHitPart front;
    private final Matrix4f placed = new Matrix4f();
    private final List<CgVfxEmitterInstance> emitters = new ArrayList<>();
    private CgPostVolume flash, impact;
    private CgPostSettings impactLook;
    private float x, y, z, nx, ny = 1f, nz;
    /** The age it ignited at, NaN before; its own clock, held at its ignition through its impact frame. */
    private float ignited = Float.NaN, since = Float.NaN;
    private boolean burst, released, emitted, showing;

    /** A blast whose front hitbox's hits are {@code front}'s. */
    public CgVfxBlast(CgVfxEffect<?> effect, CgHitPart front) {
        super(effect);
        this.front = front;
    }

    /** Ignites it at {@code (x, y, z)} from the origin, facing {@code (nx, ny, nz)}, a unit vector. Once. */
    public void ignite(float x, float y, float z, float nx, float ny, float nz) {
        if (!Float.isNaN(ignited)) return;
        ignited = age();
        this.x = x;
        this.y = y;
        this.z = z;
        this.nx = nx;
        this.ny = ny;
        this.nz = nz;
        float radius = get(RADIUS), reach = radius * get(FLASH_REACH);
        flash = openVolume(FLASH_PRIORITY, new CgPostSettings().flash(get(FLASH_STOPS)).chromatic(get(FLASH_CHROMATIC))
                .bloom(get(FLASH_BLOOM)), x, y, z).radius(reach).blend(2f * reach);
        if (get(IMPACT) > 0f) {
            // A hard edge: an impact frame is whole or absent, since part of one is a muddy grey.
            impactLook = new CgPostSettings().impact(CgImpact.SUBJECT, 1f).subject(subject());
            impact = openVolume(IMPACT_PRIORITY, impactLook, x, y, z).radius(radius * get(IMPACT_REACH));
        }
    }

    /**
     * Runs its clock, its emitters and its front; answers true on the tick it bursts out of its impact frame (at once
     * without one). Every tick from its ignition.
     */
    public boolean tick(float dt) {
        boolean now = false;
        if (!Float.isNaN(ignited)) now = timeline();
        boolean finished = true;
        for (int i = 0; i < emitters.size(); i++) {
            tick(emitters.get(i), dt);
            finished &= emitters.get(i).finished();
        }
        emitted = finished;
        if (momentsHeard() && !Float.isNaN(ignited)) moments();
        return now;
    }

    /**
     * The impact frame comes before the explosion: the clock runs to its ignition ({@link #HOLD_AT}), holds while the
     * beats play, and runs on at full rate from the cut, where its emitters, front and shake start. The flash runs to
     * its main pulse beneath the beats, holds, and fades from the cut.
     */
    private boolean timeline() {
        float real = age() - ignited;
        boolean framed = impact != null;
        float holdAt = framed ? get(HOLD_AT) : 0f, hold = framed ? BEATS.seconds() : 0f, cut = holdAt + hold;
        since = real <= holdAt ? real : Math.max(holdAt, real - hold);
        boolean now = false;
        if (!burst && real >= cut) {
            burst = now = true;
            try (CgTrace.Zone ignored = CgTrace.zone(CgVfxTrace.CHANNEL, BURST_ZONE)) {
                start();
            }
        }
        // A beat early, under the last beat: the host's camera takes an offset the frame after it is played.
        if (!released && (hold == 0f || real >= cut - 1f / BEATS.fps())) {
            released = true;
            float scale = get(RADIUS);
            if (hold == 0f) {
                playShake(SHAKE, x, y, z, scale);
            } else {
                playShake(CUT_SHAKE, x, y, z, scale);
                playShake(RETURN, x, y, z, scale);
            }
        }
        float time = get(TIME), peak = framed ? MAIN_PULSE * time : Float.POSITIVE_INFINITY;
        float flashSince = Math.min(real, peak) + Math.max(0f, real - Math.max(cut, peak));
        flash.weight(curve(FLASH).at(Math.min(flashSince / time, 1f)));
        if (impact != null) {
            CgImpactFrame look = BEATS.look(real - holdAt);
            if (look != null) impactLook.impact(look, BEATS.amount(real - holdAt), BEATS.seed(real - holdAt));
            showing = look != null && get(IMPACT) > 0f;
            impact.weight(showing ? 1f : 0f);
        }
        return now;
    }

    /** The burst: its front as a hitbox, and its emitters over the world's ground there, the ink only after a frame. */
    private void start() {
        float ticks = SHOCK_SECONDS / CgVfxSystem.GAME_TICK, radius = get(RADIUS);
        // OUT_CUBIC's inverse: the share of its run at which the front passes the blast's radius.
        float within = ticks * (1f - (float) Math.cbrt(1.0 - 1.0 / SHOCK_REACH));
        hitbox(front, CgShell.front(get(FRONT))).at(originX() + x, originY() + y, originZ() + z)
                .radius(CgKeyframes.start(0f, 0f).to(ticks, radius * SHOCK_REACH, CgEasings.OUT_CUBIC).build())
                .activeFor(within).source(followingId());
        if (authoritative()) return;
        CgTrace.marker(CgVfxTrace.CHANNEL, MARKER);
        List<CgVfxEmitter> kit = EXPLOSION.emitters();
        for (int i = 0; i < kit.size(); i++) {
            CgVfxEmitterInstance e = instance(kit.get(i), KIT, i * 0.137f);
            if (e == null || impact == null && EXPLOSION.drawn(e.emitter())) continue;
            e.start(x, y, z);
            e.ground(groundHeight());
            emitters.add(e);
        }
    }

    private void moments() {
        float radius = get(RADIUS), time = get(TIME), frame = radius * 1.3f;
        if (since >= 0.06f) once(0, MOMENT_START, x, y, z, frame);
        if (since >= 0.3f * time) once(1, MOMENT_PEAK, x, y, z, frame);
        // By then the cloud has spread past the dome.
        if (since >= 0.7f * time) once(2, MOMENT_FADE, x, y, z, radius * 1.9f);
        if (impact == null) return;
        float intoBeats = age() - ignited - get(HOLD_AT);
        int frames = Math.min(BEATS.frames() + BEATS.fadeFrames(), IMPACT_FRAME_MOMENTS.length);
        for (int k = 0; k < frames; k++) {
            if (intoBeats >= k / BEATS.fps()) once(3 + k, IMPACT_FRAME_MOMENTS[k], x, y, z, frame);
        }
    }

    /** Whether it has ignited. */
    public boolean ignited() {
        return !Float.isNaN(ignited);
    }

    /** Whether it has burst out of its impact frame, its explosion started. */
    public boolean burst() {
        return burst;
    }

    /** Whether its impact frame shows this tick. */
    public boolean impactFrameShowing() {
        return showing;
    }

    /** Whether it has played out: its dome cooled, its emitters finished. */
    public boolean done() {
        return released && since > Math.max(get(TIME), get(DOME_TIME)) && emitted;
    }

    /** Draws its dome, light, heart, shock front, ring, debris and particles. */
    public void submit(CgVfxFrame frame) {
        if (Float.isNaN(ignited)) return;
        float radius = get(RADIUS), time = get(TIME), t = Math.min(since / time, 1f);
        float dome = radius * curve(SIZE).at(t) * (1f + 0.04f * Math.max(since - time, 0f));
        facing(placed, nx, ny, nz).scale(dome);
        float cooled = Math.min(since / get(DOME_TIME), 1f);
        frame.mesh(effect, DOME, x, y, z, placed, dome, dome, 1f, cooled);
        float glow = curve(GLOW).at(t);
        // Its light fits it: a source the dome's size reaching past it, burning down as it cools.
        float lit = 0.35f * glow + 0.9f * (float) Math.pow(1f - cooled, 1.5);
        if (lit > 0f) frame.mesh(effect, LIGHT, x, y, z, placed, dome, dome, lit, 0f);
        if (glow > 0f) {
            float heart = dome * 0.5f;
            facing(placed, nx, ny, nz).rotateZ(age()).scale(heart);
            frame.mesh(effect, CORE, x, y, z, placed, heart, heart, glow, 0f);
        }
        // The shock front races out well ahead of the dust, gone in a little over a second.
        float shock = since / SHOCK_SECONDS;
        if (shock < 1f) {
            float reach = radius * SHOCK_REACH * (float) CgEasings.OUT_CUBIC.ease(shock);
            placed.identity().scale(reach);
            frame.mesh(effect, SHOCK, x, y, z, placed, reach, reach, (float) Math.sqrt(1f - shock), shock);
        }
        float ring = Math.min(since / RING_SECONDS, 1f);
        if (ring < 1f) {
            float reach = radius * 1.3f, left = 1f - ring;
            facing(placed, nx, ny, nz).scale(reach, reach, 0.002f);
            frame.mesh(effect, RING, x, y, z, placed, reach, reach, left * left, (float) CgEasings.OUT_CUBIC.ease(ring));
        }
        float debris = radius * 2f;
        facing(placed, nx, ny, nz).scale(debris);
        frame.ribbons(effect, DEBRIS, x, y, z, placed, debris, 0f, 1f, since);
        boolean billows = get(BILLOWS) > 0f;
        for (int i = 0; i < emitters.size(); i++) {
            CgVfxEmitterInstance e = emitters.get(i);
            if (!billows && e.emitter().name().equals(EXPLOSION.billows.name())) continue;
            frame.particles(effect, e);
        }
    }
}
