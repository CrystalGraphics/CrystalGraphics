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
import com.crystalgraphics.vfx.sim.CgVfxParticles;
import com.crystalgraphics.vfx.sim.CgVfxStream;
import org.joml.Matrix4f;

import java.util.List;

/**
 * An energy wave: charged at a muzzle, then a beam that flows outward, bends where its source turns, homes on a target
 * and stops where it hits. {@link #kamehameha()} is its default look; {@link #finalFlash()} and {@link #galickGun()}
 * are its layers with another palette, which is all a new look needs.
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
 * ring, and the ball settles into the beam's root while the body flies out; where it hits, a contact orb throws sparks
 * and pulses rings; after {@link #stop()} the root fades and the tail runs out into the target, which bursts into the
 * final blast: a dome of light eroding as it cools, a flash, a ring, debris and smoke, for {@link #BLAST_TIME}.</p>
 *
 * <p>Its look's layers draw in slots: {@link CgVfxLayer#SLOT_BODY} as tubes along the body; {@link #SLOT_HEAD},
 * {@link #SLOT_CHARGE} and {@link #SLOT_FLASH} on spheres ({@link CgVfxFrame#mesh}, {@code CG_OBJECT_CUSTOM1} the
 * half-width, half-length and intensity); {@link #SLOT_SHOCK} on a sphere flattened to a disc facing along the aim
 * ({@code CG_OBJECT_CUSTOM1.z} the intensity, {@code .w} its progress, 0..1); {@link #SLOT_STREAKS} and
 * {@link #SLOT_ARCS} as stateless ribbons ({@link CgVfxFrame#ribbons}, {@code CG_OBJECT_CUSTOM1} the ball's radius in
 * blocks, the ball's share of the streaks' sphere, and the intensity). At the target, facing back along the beam:
 * {@link #SLOT_IMPACT} and {@link #SLOT_BLAST_GLOW} on spheres, {@link #SLOT_IMPACT_RING} on a disc, {@link #SLOT_SPLASH}
 * and {@link #SLOT_DEBRIS} as ribbons ({@code CG_OBJECT_CUSTOM1.z} the intensity, {@code .w} the burst's age);
 * {@link #SLOT_SPECKS}, {@link #SLOT_SPARKLES} and {@link #SLOT_INK} as ribbons ({@code CG_OBJECT_CUSTOM1} the
 * blast's radius in blocks, which batch, the intensity, and seconds since the blast); and
 * {@link #SLOT_BLAST} on a sphere ({@code .w} the blast's progress, 0..1), and {@link #SLOT_SMOKE} on one sphere per
 * billow ({@link CgVfxFrame#mesh}, {@code CG_OBJECT_CUSTOM1} the billow's life 0..1, its seed, its opacity and how
 * hot it still is).</p>
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
    /** Lightning crawling along the body: ribbons riding the path ({@link CgVfxFrame#pathRibbons}). */
    public static final String SLOT_BODY_ARCS = "bodyArcs";
    /** The orb at the target while the beam hits it: a sphere, its +z back along the beam. */
    public static final String SLOT_IMPACT = "impact";
    /** Sparks thrown back off the impact while it hits: ribbons, their +z back along the beam. */
    public static final String SLOT_SPLASH = "splash";
    /** Rings pulsing out from the impact, and the blast's ring: a disc facing back along the beam. */
    public static final String SLOT_IMPACT_RING = "impactRing";
    /** The blast's dome: a sphere at the target. */
    public static final String SLOT_BLAST = "blast";
    /** The blast's flash and its heart: spheres at the target. */
    public static final String SLOT_BLAST_GLOW = "blastGlow";
    /** The blast's debris, one burst of ribbons. */
    public static final String SLOT_DEBRIS = "debris";
    /** The blast's dark debris specks, its glowing sparkles, and the ink streaks of its shock: ribbons at the target. */
    public static final String SLOT_SPECKS = "specks";
    public static final String SLOT_SPARKLES = "sparkles";
    public static final String SLOT_INK = "ink";
    /** Draws of the specks and sparkles layers per frame, each a different set of up to {@code CgVfxRibbons.COUNT}. */
    private static final int SPECK_BATCHES = 6, SPARKLE_BATCHES = 4;
    /** The blast's cloud: a cel-shaded, displaced sphere per billow, opaque. */
    public static final String SLOT_SMOKE = "smoke";
    /** The shock ring at the release: a disc at the muzzle facing along the aim. */
    public static final String SLOT_SHOCK = "shock";

    /** Its moments, in the order they come; each fires once. The charge's three are skipped when it fires at once. */
    public static final String MOMENT_CHARGE_START = "charge-start", MOMENT_CHARGE_MID = "charge-mid",
            MOMENT_CHARGE_PEAK = "charge-peak", MOMENT_RELEASE = "release", MOMENT_FLASH = "flash",
            MOMENT_RING = "ring", MOMENT_SHOCK = "shock", MOMENT_LAUNCH = "launch", MOMENT_WAYPOINT = "waypoint", MOMENT_IMPACT = "impact",
            MOMENT_SPLASH = "splash", MOMENT_HOLDING = "holding", MOMENT_STOP = "stop", MOMENT_TAIL = "tail",
            MOMENT_BLAST_START = "blast-start", MOMENT_BLAST_PEAK = "blast-peak", MOMENT_BLAST_FADE = "blast-fade",
            MOMENT_END = "end";

    /** The body's radius, in blocks. */
    public static final CgVfxParam RADIUS = SCHEMA.scalar("radius", 0.8f);
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
    /** How much brighter the pulses racing down the body flash, as a share of its brightness. */
    public static final CgVfxParam PULSE = SCHEMA.scalar("pulse", 0.45f);

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
    public static final CgVfxParam FLASH_RADIUS = SCHEMA.scalar("flashRadius", 2.5f);
    /** Seconds the shock ring takes to sweep out, and how far it reaches, as a multiple of the body's radius. */
    public static final CgVfxParam SHOCK_TIME = SCHEMA.scalar("shockTime", 0.55f);
    public static final CgVfxParam SHOCK_RADIUS = SCHEMA.scalar("shockRadius", 5.5f);
    /** The sphere the charge's streaks fall in from, as a multiple of the body's radius. */
    public static final CgVfxParam STREAK_RADIUS = SCHEMA.scalar("streakRadius", 3.6f);
    /** The contact orb's radius while the beam hits, as a multiple of the body's radius. */
    public static final CgVfxParam IMPACT_RADIUS = SCHEMA.scalar("impactRadius", 2.2f);
    /** Seconds between the rings pulsing out from the impact. */
    public static final CgVfxParam IMPACT_RING_PERIOD = SCHEMA.scalar("impactRingPeriod", 0.45f);
    /** Seconds the final blast lasts, and its full radius as a multiple of the body's radius. */
    public static final CgVfxParam BLAST_TIME = SCHEMA.scalar("blastTime", 2.4f);
    public static final CgVfxParam BLAST_RADIUS = SCHEMA.scalar("blastRadius", 12f);
    /** The dome's size over the blast (0..1 of it), a share of {@link #BLAST_RADIUS}: bursting out, then drifting. */
    public static final CgVfxParam BLAST_SIZE = SCHEMA.curve("blastSize", CgKeyframes.start(0f, 0.08f)
            .to(0.35f, 1f, CgEasings.OUT_EXPO)
            .to(1f, 1.15f, CgEasings.LINEAR)
            .build());
    /** The blast's flash over the blast (0..1 of it). */
    public static final CgVfxParam BLAST_GLOW = SCHEMA.curve("blastGlow", CgKeyframes.start(0f, 0f)
            .to(0.04f, 3.5f, CgEasings.OUT_QUAD)
            .to(0.6f, 0.4f, CgEasings.OUT_CUBIC)
            .to(1f, 0f, CgEasings.LINEAR)
            .build());
    /** A billow's opacity over its life (0..1 of it): it breaks up quickly at first, its last shreds thinning slowly. */
    public static final CgVfxParam SMOKE = SCHEMA.curve("smoke", CgKeyframes.start(0f, 0f)
            .to(0.1f, 1f, CgEasings.OUT_QUAD)
            .to(0.5f, 0.92f, CgEasings.LINEAR)
            .to(1f, 0f, CgEasings.OUT_QUAD)
            .build());
    /** Seconds the blast's specks, sparkles and streaks hang after it, all easing out over the last {@link #TAIL}. */
    public static final CgVfxParam BLAST_LINGER = SCHEMA.scalar("blastLinger", 7f);
    /** Puffs of smoke the blast throws out, at most {@link #SMOKE_CAPACITY}. */
    public static final CgVfxParam SMOKE_COUNT = SCHEMA.scalar("smokeCount", 64f);
    /** A puff's radius at birth, and how many times that it swells to, as shares of the blast's radius. */
    public static final CgVfxParam SMOKE_SIZE = SCHEMA.scalar("smokeSize", 0.2f);
    public static final CgVfxParam SMOKE_GROWTH = SCHEMA.scalar("smokeGrowth", 2.1f);
    /** How fast puffs fly out, in blast radii a second; how fast that decays a second; their lift, in radii a second squared. */
    public static final CgVfxParam SMOKE_SPEED = SCHEMA.scalar("smokeSpeed", 0.45f);
    public static final CgVfxParam SMOKE_DRAG = SCHEMA.scalar("smokeDrag", 0.6f);
    public static final CgVfxParam SMOKE_RISE = SCHEMA.scalar("smokeRise", 0.08f);
    /** Seconds a puff lives, on average. */
    public static final CgVfxParam SMOKE_LIFE = SCHEMA.scalar("smokeLife", 5.5f);
    public static final int SMOKE_CAPACITY = 96;

    public static final CgVfxParam CORE = SCHEMA.color("core", 1f, 1f, 1f, 1f);
    /** The blast cloud's body and its hot core. */
    public static final CgVfxParam SMOKE_COLOR = SCHEMA.color("smokeColor", 0.06f, 0.3f, 0.95f, 1f);
    public static final CgVfxParam SMOKE_HOT = SCHEMA.color("smokeHot", 0.3f, 0.88f, 1f, 1f);
    /** The blast's dark debris and the ink of its shock streaks. */
    public static final CgVfxParam DEBRIS = SCHEMA.color("debris", 0.02f, 0.04f, 0.12f, 1f);
    public static final CgVfxParam CORE_RIM = SCHEMA.color("coreRim", 0.7f, 0.95f, 1f, 1f);
    public static final CgVfxParam SHELL = SCHEMA.color("shell", 0.15f, 0.55f, 1.6f, 1f);
    public static final CgVfxParam SHELL_HOT = SCHEMA.color("shellHot", 0.7f, 0.95f, 1.6f, 1f);
    public static final CgVfxParam SPIRAL = SCHEMA.color("spiral", 0.5f, 0.85f, 1.6f, 1f);
    public static final CgVfxParam GLOW = SCHEMA.color("glow", 0.18f, 0.45f, 1.4f, 0.9f);

    /** A band per block, sectors around and the frame's normal as a line: add it to a look to check the path. */
    public static final CgVfxLayer DEBUG = CgVfxLayer.builder("crystalgraphics:shaders/vfx/beam/debug.shader")
            .colors(SHELL, CORE).priority(CgVfxLayer.PRIORITY_BANDS).build();

    private static final String BEAM = "crystalgraphics:shaders/vfx/beam/";
    private static final CgVfxLook KAMEHAMEHA = CgVfxLook.builder(SCHEMA)
            .layer(CgVfxLayer.builder(BEAM + "body_light.shader").volume()
                    .radius(10f).colors(GLOW, null).priority(CgVfxLayer.PRIORITY_VOLUME).build())
            .layer(CgVfxLayer.builder(BEAM + "body_glow.shader").volume()
                    .radius(4.4f).colors(GLOW, null).priority(CgVfxLayer.PRIORITY_VOLUME).build())
            .layer(CgVfxLayer.builder(BEAM + "body_shell.shader")
                    .radius(1f).colors(SHELL, SHELL_HOT).priority(CgVfxLayer.PRIORITY_SURFACE).build())
            .layer(CgVfxLayer.builder(BEAM + "body_core.shader")
                    .radius(0.52f).colors(CORE, CORE_RIM).priority(CgVfxLayer.PRIORITY_CORE).build())
            .layer(CgVfxLayer.builder(BEAM + "body_spiral.shader")
                    .radius(1.2f).colors(SPIRAL, CORE).priority(CgVfxLayer.PRIORITY_BANDS).build())
            .layer(CgVfxLayer.builder(BEAM + "body_arcs.shader").slot(SLOT_BODY_ARCS)
                    .colors(SPIRAL, CORE).priority(CgVfxLayer.PRIORITY_BANDS).build())
            .layer(orb("orb_light", SLOT_HEAD, 9f, 0f, GLOW, null, CgVfxLayer.PRIORITY_VOLUME))
            .layer(orb("orb_glow", SLOT_HEAD, 3.2f, 1.5f, GLOW, null, CgVfxLayer.PRIORITY_VOLUME))
            .layer(orb("orb_shell", SLOT_HEAD, 1.15f, 1f, SHELL, SHELL_HOT, CgVfxLayer.PRIORITY_SURFACE))
            .layer(orb("orb_core", SLOT_HEAD, 0.75f, 0f, CORE, CORE_RIM, CgVfxLayer.PRIORITY_CORE))
            .layer(orb("orb_light", SLOT_CHARGE, 9f, 0f, GLOW, null, CgVfxLayer.PRIORITY_VOLUME))
            .layer(orb("orb_glow", SLOT_CHARGE, 3.2f, 1.8f, GLOW, null, CgVfxLayer.PRIORITY_VOLUME))
            .layer(orb("orb_plasma", SLOT_CHARGE, 1f, 0f, CORE, SHELL, CgVfxLayer.PRIORITY_CORE))
            .layer(orb("orb_light", SLOT_FLASH, 7f, 0f, CORE_RIM, null, CgVfxLayer.PRIORITY_VOLUME))
            .layer(orb("orb_glow", SLOT_FLASH, 3.2f, 1f, CORE_RIM, null, CgVfxLayer.PRIORITY_VOLUME))
            .layer(CgVfxLayer.builder(BEAM + "charge_streaks.shader").slot(SLOT_STREAKS)
                    .colors(SHELL_HOT, CORE).priority(CgVfxLayer.PRIORITY_BANDS).build())
            .layer(CgVfxLayer.builder(BEAM + "charge_arcs.shader").slot(SLOT_ARCS)
                    .colors(SPIRAL, CORE).priority(CgVfxLayer.PRIORITY_BANDS).build())
            .layer(orb("orb_light", SLOT_IMPACT, 9f, 0f, GLOW, null, CgVfxLayer.PRIORITY_VOLUME))
            .layer(orb("orb_glow", SLOT_IMPACT, 3.2f, 2f, GLOW, null, CgVfxLayer.PRIORITY_VOLUME))
            .layer(orb("orb_plasma", SLOT_IMPACT, 1f, 0f, CORE, SHELL, CgVfxLayer.PRIORITY_CORE))
            .layer(CgVfxLayer.builder(BEAM + "impact_splash.shader").slot(SLOT_SPLASH)
                    .colors(SHELL_HOT, CORE).priority(CgVfxLayer.PRIORITY_BANDS).build())
            .layer(CgVfxLayer.builder(BEAM + "disc_shock.shader").slot(SLOT_IMPACT_RING)
                    .colors(CORE_RIM, SHELL).priority(CgVfxLayer.PRIORITY_BANDS).build())
            .layer(CgVfxLayer.builder(BEAM + "blast_dome.shader").slot(SLOT_BLAST)
                    .colors(CORE, SHELL).priority(CgVfxLayer.PRIORITY_SURFACE).build())
            .layer(orb("orb_light", SLOT_BLAST_GLOW, 6f, 0f, GLOW, null, CgVfxLayer.PRIORITY_VOLUME))
            .layer(orb("orb_glow", SLOT_BLAST_GLOW, 3.2f, 1.4f, CORE_RIM, null, CgVfxLayer.PRIORITY_VOLUME))
            .layer(orb("orb_plasma", SLOT_BLAST_GLOW, 1f, 0f, CORE, SHELL, CgVfxLayer.PRIORITY_CORE))
            .layer(CgVfxLayer.builder(BEAM + "impact_splash.shader").slot(SLOT_DEBRIS)
                    .colors(SHELL_HOT, CORE).priority(CgVfxLayer.PRIORITY_BANDS)
                    .properties(b -> b.set1f("_Burst", 1f).set1f("_Count", 90f).set1f("_Speed", 18f).set1f("_Life", 1.2f)
                            .set1f("_Width", 0.08f).set1f("_Streak", 0.08f))
                    .build())
            .layer(CgVfxLayer.builder("crystalgraphics:shaders/vfx/smoke/billow.shader").slot(SLOT_SMOKE)
                    .colors(SMOKE_COLOR, SMOKE_HOT).priority(CgVfxLayer.PRIORITY_SMOKE).build())
            .layer(CgVfxLayer.builder(BEAM + "blast_specks.shader").slot(SLOT_SPECKS)
                    .colors(DEBRIS, null).priority(CgVfxLayer.PRIORITY_SMOKE).build())
            .layer(CgVfxLayer.builder(BEAM + "blast_ink.shader").slot(SLOT_INK)
                    .colors(DEBRIS, null).priority(CgVfxLayer.PRIORITY_SMOKE).build())
            .layer(CgVfxLayer.builder(BEAM + "blast_sparkles.shader").slot(SLOT_SPARKLES)
                    .colors(SMOKE_HOT, CORE).priority(CgVfxLayer.PRIORITY_BANDS).build())
            .layer(CgVfxLayer.builder(BEAM + "disc_shock.shader").slot(SLOT_SHOCK)
                    .colors(CORE_RIM, SHELL).priority(CgVfxLayer.PRIORITY_BANDS).build())
            .build();

    private static final CgVfxLook FINAL_FLASH = KAMEHAMEHA.toBuilder()
            .set(CORE, 1f, 1f, 0.9f, 1f)
            .set(CORE_RIM, 1f, 0.92f, 0.55f, 1f)
            .set(SHELL, 1.5f, 1.0f, 0.12f, 1f)
            .set(SHELL_HOT, 1.6f, 1.35f, 0.55f, 1f)
            .set(SPIRAL, 1.6f, 1.15f, 0.3f, 1f)
            .set(GLOW, 1.4f, 0.85f, 0.12f, 0.9f)
            .set(SMOKE_COLOR, 0.95f, 0.3f, 0.04f, 1f)
            .set(SMOKE_HOT, 1f, 0.88f, 0.3f, 1f)
            .set(DEBRIS, 0.1f, 0.03f, 0.01f, 1f)
            .set(RADIUS, 1f)
            .set(SPEED, 50f)
            .build();
    private static final CgVfxLook GALICK_GUN = KAMEHAMEHA.toBuilder()
            .set(CORE, 1f, 0.95f, 1f, 1f)
            .set(CORE_RIM, 0.95f, 0.72f, 1f, 1f)
            .set(SHELL, 0.85f, 0.2f, 1.6f, 1f)
            .set(SHELL_HOT, 1.3f, 0.75f, 1.6f, 1f)
            .set(SPIRAL, 1.2f, 0.45f, 1.6f, 1f)
            .set(GLOW, 0.75f, 0.18f, 1.4f, 0.9f)
            .set(SMOKE_COLOR, 0.42f, 0.08f, 0.85f, 1f)
            .set(SMOKE_HOT, 0.98f, 0.65f, 1f, 1f)
            .set(DEBRIS, 0.06f, 0.01f, 0.1f, 1f)
            .build();

    /** Seconds the root takes to settle after the release, and to fade after a stop. */
    private static final float SETTLE = 0.25f, FADE = 0.35f;
    /** Seconds the blast's flecks take to ease out at the end of {@link #BLAST_LINGER}. */
    public static final float TAIL = 1.5f;

    private final CgVfxStream stream = new CgVfxStream();
    private final CgVfxPath path = new CgVfxPath();
    private final Matrix4f placed = new Matrix4f();
    private final CgVfxParticles smoke = new CgVfxParticles(SMOKE_CAPACITY);
    private float[] points = new float[64 * 3];
    /** The body's radius this frame, before the shape along it: what the head is sized from. */
    private float bodyRadius;
    private float aimX = 1f, aimY, aimZ;
    /** The age it releases at, and the ages it was stopped at, first hit and burst; NaN until each happens. */
    private float releaseAge, stopAge = Float.NaN, impactAge = Float.NaN, blastAge = Float.NaN;
    /** How hard the beam is hitting, eased toward 1 while it hits and 0 when it does not. */
    private float impactLevel;
    /** Back along the beam at the impact: where the impact's effects face. */
    private float normalX, normalY = 1f, normalZ;
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

    /** Gold and wider: the Kamehameha's layers with another palette, which is all a new look needs. */
    public static CgVfxLook finalFlash() {
        return FINAL_FLASH;
    }

    /** Violet: the Kamehameha's layers with another palette. */
    public static CgVfxLook galickGun() {
        return GALICK_GUN;
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
        impactLevel += ((stream.impacting() ? 1f : 0f) - impactLevel) * Math.min(1f, dt * 10f);
        boolean drained = state() == State.STOPPING && stream.size() == 0;
        // The tail has run into the target: it bursts.
        if (drained && !Float.isNaN(impactAge) && Float.isNaN(blastAge)) {
            blastAge = age;
            emitSmoke();
        }
        float blastScale = get(RADIUS) * get(BLAST_RADIUS);
        smoke.tick(dt, get(SMOKE_DRAG), get(SMOKE_RISE) * blastScale);
        boolean ending = Float.isNaN(blastAge) ? drained && age > stopAge + FADE
                : age > blastAge + Math.max(get(BLAST_TIME), get(BLAST_LINGER)) && smoke.count() == 0;
        if (momentsHeard()) moments(ending);
        if (ending) die();
    }

    /**
     * The blast's cloud: billows thrown out from the target, mostly outward with a little lift, so it spreads as it rises;
     * each its own size, speed and life.
     */
    private void emitSmoke() {
        float scale = get(RADIUS) * get(BLAST_RADIUS);
        float x = stream.impactX(), y = stream.impactY(), z = stream.impactZ();
        int count = Math.min((int) get(SMOKE_COUNT), SMOKE_CAPACITY);
        for (int i = 0; i < count; i++) {
            float up = -0.15f + 0.85f * (float) Math.pow(rand(i, 0), 1.5), turn = rand(i, 1) * 6.2831853f;
            float flat = (float) Math.sqrt(Math.max(1f - up * up, 0f));
            float dx = flat * (float) Math.cos(turn), dz = flat * (float) Math.sin(turn);
            float start = scale * 0.25f * rand(i, 2), speed = scale * get(SMOKE_SPEED) * (0.5f + rand(i, 3));
            smoke.emit(x + dx * start, y + up * start, z + dz * start, dx * speed, up * speed, dz * speed,
                    get(SMOKE_LIFE) * (0.75f + 0.5f * rand(i, 4)), scale * get(SMOKE_SIZE) * (0.7f + 0.6f * rand(i, 5)),
                    rand(i, 6));
        }
    }

    /** A number in 0..1 for puff {@code i}, draw {@code k}, fixed by the effect's seed. */
    private float rand(int i, int k) {
        int h = Float.floatToIntBits(seed) * 0x9E3779B1 ^ i * 0x85EBCA77 ^ k * 0xC2B2AE3D;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        h *= 0x297A2D39;
        h ^= h >>> 15;
        return (h >>> 8) * (1f / (1 << 24));
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
        float atTarget = radius * get(BLAST_RADIUS) * 1.3f;
        if (age >= impactAge + 0.3f) atImpact(14, MOMENT_SPLASH, radius * 12f);
        float blastTime = get(BLAST_TIME);
        if (age >= blastAge + 0.06f) atImpact(15, MOMENT_BLAST_START, atTarget);
        if (age >= blastAge + 0.3f * blastTime) atImpact(16, MOMENT_BLAST_PEAK, atTarget);
        // By then the cloud has spread past the dome.
        if (age >= blastAge + 0.7f * blastTime) atImpact(17, MOMENT_BLAST_FADE, radius * get(BLAST_RADIUS) * 1.9f);
        if (age >= impactAge + 0.8f) onFlight(9, MOMENT_HOLDING);
        if (age >= stopAge + 0.05f) onFlight(10, MOMENT_STOP);
        if (!Float.isNaN(stopAge) && stream.size() <= sizeAtStop / 2) onFlight(11, MOMENT_TAIL);
        if (ending) onFlight(12, MOMENT_END);
    }

    private void atMuzzle(int bit, String name, float frame) {
        if (fire(bit)) moment(name, 0f, 0f, 0f, frame);
    }

    private void atImpact(int bit, String name, float frame) {
        if (fire(bit)) moment(name, stream.impactX(), stream.impactY(), stream.impactZ(), frame);
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
        submitImpact(frame, layers);
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
            else if (SLOT_BODY_ARCS.equals(layer.slot())) frame.pathRibbons(this, path, row, layer, 1f);
        }
        if (stream.impacting()) {
            int last = path.count() - 1;
            normalX = -path.tangentX(last);
            normalY = -path.tangentY(last);
            normalZ = -path.tangentZ(last);
        }
        submitHead(frame, layers);
    }

    /** At the target: the contact orb, its sparks and rings while the beam hits, then the final blast. */
    private void submitImpact(CgVfxFrame frame, List<CgVfxLayer> layers) {
        if (Float.isNaN(impactAge)) return;
        float radius = get(RADIUS);
        float x = stream.impactX(), y = stream.impactY(), z = stream.impactZ();
        if (impactLevel > 0.01f) {
            float orb = radius * get(IMPACT_RADIUS) * (1f + 0.08f * (float) Math.sin(age * 19f + seed * 6.28f))
                    * (float) Math.sqrt(impactLevel);
            facing(placed, normalX, normalY, normalZ).rotateZ(age * 1.1f).scale(orb);
            drawAt(frame, layers, SLOT_IMPACT, x, y, z, placed, orb, orb, impactLevel, 0f, false);
            float reach = radius * 12f;
            facing(placed, normalX, normalY, normalZ).scale(reach);
            drawAt(frame, layers, SLOT_SPLASH, x, y, z, placed, reach, 0f, impactLevel, -1f, true);
            float period = get(IMPACT_RING_PERIOD);
            float ring = ((age - impactAge) / period) % 1f;
            float rings = radius * 6f;
            facing(placed, normalX, normalY, normalZ).scale(rings, rings, 0.002f);
            drawAt(frame, layers, SLOT_IMPACT_RING, x, y, z, placed, rings, rings,
                    impactLevel * (1f - ring) * (1f - ring), (float) CgEasings.OUT_CUBIC.ease(ring), false);
        }
        if (Float.isNaN(blastAge)) return;
        float since = age - blastAge, blastTime = get(BLAST_TIME), t = Math.min(since / blastTime, 1f);
        float dome = radius * get(BLAST_RADIUS) * curve(BLAST_SIZE).at(t);
        facing(placed, normalX, normalY, normalZ).scale(dome);
        drawAt(frame, layers, SLOT_BLAST, x, y, z, placed, dome, dome, 1f, t, false);
        float glow = curve(BLAST_GLOW).at(t);
        if (glow > 0f) {
            float heart = dome * 0.5f;
            facing(placed, normalX, normalY, normalZ).rotateZ(age).scale(heart);
            drawAt(frame, layers, SLOT_BLAST_GLOW, x, y, z, placed, heart, heart, glow, 0f, false);
        }
        float ringTime = Math.min(since / 0.7f, 1f);
        if (ringTime < 1f) {
            float reach = radius * get(BLAST_RADIUS) * 1.3f;
            facing(placed, normalX, normalY, normalZ).scale(reach, reach, 0.002f);
            float left = 1f - ringTime;
            drawAt(frame, layers, SLOT_IMPACT_RING, x, y, z, placed, reach, reach, left * left,
                    (float) CgEasings.OUT_CUBIC.ease(ringTime), false);
        }
        float reach = radius * get(BLAST_RADIUS) * 2f;
        facing(placed, normalX, normalY, normalZ).scale(reach);
        drawAt(frame, layers, SLOT_DEBRIS, x, y, z, placed, reach, 0f, 1f, since, true);
        // Specks, sparkles and ink fly within four blast radii; their shaders read the blast's radius to scale.
        float blastRadius = radius * get(BLAST_RADIUS), linger = get(BLAST_LINGER);
        float tail = 1f - smooth(linger - TAIL, linger, since);
        placed.scaling(blastRadius * 4f);
        for (int batch = 0; batch < SPECK_BATCHES; batch++)
            drawAt(frame, layers, SLOT_SPECKS, x, y, z, placed, blastRadius, batch, tail, since, true);
        for (int batch = 0; batch < SPARKLE_BATCHES; batch++)
            drawAt(frame, layers, SLOT_SPARKLES, x, y, z, placed, blastRadius, batch, tail, since, true);
        drawAt(frame, layers, SLOT_INK, x, y, z, placed, blastRadius, 0f, tail, since, true);
        submitSmoke(frame, layers, 1f - Math.min(since / (blastTime * 0.55f), 1f));
    }

    /** Every billow of the blast's cloud, swelling as it ages; {@code hot} is how much fire still lights it, 0..1. */
    private void submitSmoke(CgVfxFrame frame, List<CgVfxLayer> layers, float hot) {
        float ahead = frame.alpha() * CgVfxSystem.TICK, growth = get(SMOKE_GROWTH) - 1f;
        for (int k = 0; k < layers.size(); k++) {
            CgVfxLayer layer = layers.get(k);
            if (!SLOT_SMOKE.equals(layer.slot())) continue;
            for (int i = 0; i < smoke.count(); i++) {
                float t = smoke.progress(i), left = 1f - t;
                float size = smoke.size(i) * (1f + growth * (1f - left * left)), turn = smoke.seed(i) * 6.2831853f;
                placed.rotationXYZ(turn * 1.7f, turn * 2.3f, turn).scale(size);
                frame.mesh(this, layer, smoke.x(i, ahead), smoke.y(i, ahead), smoke.z(i, ahead), placed,
                        t, smoke.seed(i), curve(SMOKE).at(t), hot);
            }
        }
    }

    /** Every layer in {@code slot}, at {@code (x, y, z)} from the origin, as spheres or as ribbons. */
    private void drawAt(CgVfxFrame frame, List<CgVfxLayer> layers, String slot, float x, float y, float z,
                        Matrix4f transform, float a, float b, float intensity, float w, boolean ribbons) {
        for (int i = 0; i < layers.size(); i++) {
            CgVfxLayer layer = layers.get(i);
            if (!slot.equals(layer.slot())) continue;
            if (ribbons) frame.ribbons(this, layer, x, y, z, transform, a, b, intensity, w);
            else frame.mesh(this, layer, x, y, z, transform, a, b, intensity, w);
        }
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
        float throb = get(THROB), pulse = get(PULSE), length = path.length();
        for (int i = 0; i < path.count(); i++) {
            float s = path.arc(i);
            float muzzle = firing ? 0.55f + 0.45f * smooth(0f, 2.5f * radius, s) : smooth(0f, radius, s);
            float head = 1f + 0.3f * smooth(length - 4f * radius, length - radius, s);
            float tip = (float) Math.sqrt(smooth(0f, 0.8f * radius, length - s));
            float swell = 1f + throb * (float) Math.sin(s * 1.3f - age * 16f)
                    + 0.5f * throb * (float) Math.sin(s * 3.1f - age * 29f + seed * 6.28f);
            path.radius(i, radius * muzzle * head * tip * swell);
            // Sharp bright pulses racing down the body, faster than it flows.
            float flash = 0.5f + 0.5f * (float) Math.sin(s * 0.9f - age * 22f);
            flash *= flash;
            flash *= flash;
            path.intensity(i, 1f + pulse * flash * flash);
        }
    }

    private static float smooth(float edge0, float edge1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3f - 2f * t);
    }
}
