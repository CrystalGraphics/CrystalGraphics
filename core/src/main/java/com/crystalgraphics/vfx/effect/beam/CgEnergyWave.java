package com.crystalgraphics.vfx.effect.beam;

import com.crystalgraphics.collision.CgCollisionWorld;
import com.crystalgraphics.collision.hit.CgHit;
import com.crystalgraphics.collision.hit.CgHitPart;
import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.net.object.CgNetField;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.settings.CgQuality;
import com.crystalgraphics.shape.primitive.CgSphere;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxFrame;
import com.crystalgraphics.vfx.CgVfxTrace;
import com.crystalgraphics.vfx.camera.CgCameraShakes;
import com.crystalgraphics.vfx.element.CgVfxBlast;
import com.crystalgraphics.vfx.element.CgVfxCharge;
import com.crystalgraphics.vfx.look.CgVfxEnding;
import com.crystalgraphics.vfx.look.CgVfxKit;
import com.crystalgraphics.vfx.look.CgVfxLayer;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.net.CgVfxCue;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * An energy wave: charged at a muzzle, then a beam that flows outward, bends where its source turns, homes on a target
 * and stops where it hits, bursting into a blast when its tail runs out into what it hit. {@link #kamehameha()} is its
 * look; {@link #finalFlash()} and {@link #galickGun()} are the same kits in another palette, and {@link #toon()} its
 * body in flat bands.
 *
 * <pre>{@code
 * CgEnergyWave wave = VFX.play(new CgEnergyWave(CgEnergyWave.kamehameha(), x, y, z)
 *         .follow(playerId, CgEntityAttachments.HANDS_FORWARD).hold());   // charging until fired
 * wave.aim(dx, dy, dz).target(tx, ty, tz);   // each tick, as the source turns
 * wave.fire();                               // release
 * wave.stop();                               // the tail runs out into the target, which bursts
 * wave.end(CgEnergyWave.FADE);               // or: thins away where it is, no blast
 * }</pre>
 *
 * <p>It is made of elements, each with its own parameters and slots, which its looks draw through kits:</p>
 * <ul>
 *   <li>{@link CgVfxCharge} at the muzzle: the ball growing over {@link CgVfxCharge#TIME}, the release's flash and ring,
 *       then the beam's root. Drawn by {@link #REALISTIC_CHARGE}.</li>
 *   <li>{@link CgBeamBody}: the stream, its body along the path and its head. Drawn by {@link #REALISTIC_BODY} and
 *       {@link #REALISTIC_HEAD}.</li>
 *   <li>{@link CgBeamContact} where it hits, and {@link CgVfxBlast} where it bursts. Drawn by {@link #REALISTIC_CONTACT}
 *       and {@link CgVfxBlast#KIT}.</li>
 *   <li>{@link CgBeamWake}: dust torn off the ground under it. Drawn by {@link #WAKE}.</li>
 * </ul>
 *
 * <ul>
 *   <li>Its realistic kits read the energy palette by name: {@link #CORE}, {@link #CORE_RIM}, {@link #SHELL},
 *       {@link #SHELL_HOT}, {@link #SPIRAL}, {@link #GLOW}. A look of its own drawing may read them too.</li>
 *   <li>Every size in its looks follows {@link #RADIUS} (a link per element's length), so a look sets the radius and
 *       the rest follows, or sets a length outright.</li>
 *   <li>Positions are absolute; {@code follow} moves only its muzzle: the beam, the contact and the blast stay where
 *       they are in the world.</li>
 *   <li>It homes on its target ({@link #TURN_RATE}, {@link #NAVIGATION}) or, with {@link #STEER} above 0, is steered:
 *       it leaves the muzzle along the aim into an end that chases the target, for a beam a player aims.</li>
 *   <li>It flies to the target it is given; {@link #collide} has it find its own along its aim.</li>
 *   <li>Its look must carry its elements' schemas: derive it from one of the three, or add them with {@code params}.</li>
 * </ul>
 */
public final class CgEnergyWave extends CgVfxEffect<CgEnergyWave> implements CgVfxCue.Live, CgVfxCue.Authoritative {

    public static final CgVfxSchema SCHEMA = CgVfxSchema.of("crystalgraphics:wave");

    /**
     * What a cue playing a wave carries beyond its look: whether it charges until fired, and the age it fired at, in
     * seconds. An authoritative cue's server wave publishes both; a cosmetic one sets them.
     *
     * <pre>{@code
     * Playing wave = KAMEHAMEHA.on(player, CgEntityAttachments.HANDS_FORWARD).setBool(CgEnergyWave.HELD, true).start();
     * wave.set(CgEnergyWave.FIRED_AT, wave.age());   // released
     * }</pre>
     */
    public static final CgNetField<Boolean> HELD = CgNetField.ofBool("wave/held");
    public static final CgNetField<Float> FIRED_AT = CgNetField.ofFloat("wave/fired_at").fallback(Float.NaN);

    static {
        SCHEMA.networked(HELD, FIRED_AT);
    }

    /**
     * The parts of it that strike, each hit's {@code part}. The beam, as its head reaches what it hits and again when
     * that changes: the entity or block its cast met, or with no cast ({@link #collide}) the point it was aimed at. The
     * burst, once, where it bursts after its impact frame: the block the beam last met, else that point alone. The
     * blast, once for each entity within {@link #blastRadius()}, as its shock front reaches it.
     *
     * <pre>{@code
     * wave.onHit(CgEnergyWave.PART_BEAM, h -> { if (h.entityId() >= 0) stagger(h.entity()); })
     *     .onHit(CgEnergyWave.PART_BURST, h -> crater(h.x(), h.y(), h.z(), wave.blastRadius()))
     *     .onHit(CgEnergyWave.PART_BLAST, h -> damage(h.entity()));
     * }</pre>
     */
    public static final CgHitPart PART_BEAM = SCHEMA.part("beam"), PART_BURST = SCHEMA.part("burst"),
            PART_BLAST = SCHEMA.part("blast");

    /** The body's radius: every other size of its looks follows it. */
    public static final CgVfxParam.Scalar RADIUS = SCHEMA.length("radius", 0.8f);
    /** How fast energy leaves the muzzle. */
    public static final CgVfxParam.Scalar SPEED = SCHEMA.speed("speed", 40f);
    /** The most the body turns toward its target, radians a second; it homes by proportional navigation under it. */
    public static final CgVfxParam.Scalar TURN_RATE = SCHEMA.rate("turnRate", 4f);
    /** How hard the body homes: 3 curves smoothly into the target, more bends harder early and runs straighter after. */
    public static final CgVfxParam.Scalar NAVIGATION = SCHEMA.scalar("navigation", 3f);
    /**
     * 0 flies the body as a homing stream. Above 0 it is steered, for a beam a player aims: a curve leaving the muzzle
     * along the aim into an end that chases the target, turning at most this many radians a second about the muzzle.
     */
    public static final CgVfxParam.Scalar STEER = SCHEMA.rate("steer", 0f);
    /** How far the head flies when it hits nothing. */
    public static final CgVfxParam.Scalar MAX_LENGTH = SCHEMA.length("maxLength", 120f);
    /**
     * How far along the aim from the muzzle the drawn wave starts once fired, eased in over the release: a caster's
     * hands are the muzzle, and the beam clears their body. Its casts still start at the muzzle.
     */
    public static final CgVfxParam.Scalar MUZZLE_FORWARD = SCHEMA.length("muzzleForward", 0f);
    /** Held at the muzzle while it fires. */
    public static final CgVfxParam.Shake FIRING_SHAKE = SCHEMA.shake("firingShake",
            CgCameraShakes.RUMBLE.toBuilder().trauma(0.2f).radii(3f, 24f).build());

    /** The energy palette its kits read by name. */
    public static final CgVfxParam.Color CORE = SCHEMA.color("core", 0xFFFFFFFF);
    public static final CgVfxParam.Color CORE_RIM = SCHEMA.color("coreRim", 0xFFB2F2FF);
    public static final CgVfxParam.Color SHELL = SCHEMA.color("shell", 0xFF1858FF, 1.6f);
    public static final CgVfxParam.Color SHELL_HOT = SCHEMA.color("shellHot", 0xFF7097FF, 1.6f);
    public static final CgVfxParam.Color SPIRAL = SCHEMA.color("spiral", 0xFF5087FF, 1.6f);
    public static final CgVfxParam.Color GLOW = SCHEMA.color("glow", 0xE62152FF, 1.4f);

    /** Its moments, in the order they come, each once; its elements announce theirs ({@code CgVfxCharge.MOMENT_*}). */
    public static final String MOMENT_LAUNCH = "launch", MOMENT_WAYPOINT = "waypoint", MOMENT_IMPACT = "impact",
            MOMENT_HOLDING = "holding", MOMENT_STOP = "stop", MOMENT_TAIL = "tail", MOMENT_END = "end";

    /**
     * Thins it away where it is over a third of a second, the whole of it at once: no tail running out, no blast. It
     * gives way rather than deflates.
     *
     * <pre>{@code
     * wave.end(CgEnergyWave.FADE);
     * }</pre>
     */
    public static final CgVfxEnding FADE = CgVfxEnding.over("crystalgraphics:wave/fade", 0.35f)
            .animate(RADIUS, 0f, CgEasings.IN_QUAD).build();

    private static final String BEAM = "crystalgraphics:shaders/vfx/beam/", AIR = "crystalgraphics:shaders/vfx/air/";

    /** The body: its light, haze, skin, spirals, lightning and core along the path. */
    public static final CgVfxKit REALISTIC_BODY = CgVfxKit.of("crystalgraphics:wave/body").params(CgBeamBody.SCHEMA)
            .layer(CgVfxLayer.of(BEAM + "body_light.shader").on(CgBeamBody.BODY).volume().size(10f)
                    .role(CgVfxLayer.Role.LIGHT).build())
            .layer(CgVfxLayer.of(AIR + "haze_tube.shader").on(CgBeamBody.BODY).size(4.5f).set("_Core", 1.4f)
                    .bind("_Cap", RADIUS, 4.5f).role(CgVfxLayer.Role.DISTORTION).from(CgQuality.MEDIUM).build())
            .layer(CgVfxLayer.of(BEAM + "body_shell.shader").on(CgBeamBody.BODY).size(1.15f).build())
            .layer(CgVfxLayer.of(BEAM + "body_spiral.shader").on(CgBeamBody.BODY).size(1.2f).build())
            .layer(CgVfxLayer.of(BEAM + "body_arcs.shader").on(CgBeamBody.ARCS).build())
            .layer(CgVfxLayer.of(BEAM + "body_core.shader").on(CgBeamBody.BODY).closed().size(1.25f).build())
            .build();
    /** The head: its light, skin and white core. */
    public static final CgVfxKit REALISTIC_HEAD = CgVfxKit.of("crystalgraphics:wave/head").params(CgBeamBody.SCHEMA)
            .layer(CgVfxLayer.of(BEAM + "orb_light.shader").on(CgBeamBody.HEAD).size(9f).role(CgVfxLayer.Role.LIGHT).build())
            .layer(CgVfxLayer.of(BEAM + "orb_shell.shader").on(CgBeamBody.HEAD).size(1.15f).build())
            .layer(CgVfxLayer.of(BEAM + "orb_core.shader").on(CgBeamBody.HEAD).size(0.75f).build())
            .build();
    /** The charge: the plasma ball and its light and haze, streaks, arcs, the release's flash and shock ring. */
    public static final CgVfxKit REALISTIC_CHARGE = CgVfxKit.of("crystalgraphics:wave/charge").params(CgVfxCharge.SCHEMA)
            .layer(CgVfxLayer.of(BEAM + "orb_light.shader").on(CgVfxCharge.BALL).size(9f).role(CgVfxLayer.Role.LIGHT).build())
            .layer(CgVfxLayer.of(BEAM + "orb_light.shader").on(CgVfxCharge.GLARE).size(7f).bind("_Glow", CORE_RIM)
                    .role(CgVfxLayer.Role.LIGHT).build())
            .layer(CgVfxLayer.of(AIR + "haze_orb.shader").on(CgVfxCharge.HAZE).size(4.2f)
                    .role(CgVfxLayer.Role.DISTORTION).from(CgQuality.MEDIUM).build())
            .layer(CgVfxLayer.of(BEAM + "charge_streaks.shader").on(CgVfxCharge.STREAKS).build())
            .layer(CgVfxLayer.of(BEAM + "charge_arcs.shader").on(CgVfxCharge.ARCS).build())
            .layer(CgVfxLayer.of(BEAM + "disc_shock.shader").on(CgVfxCharge.SHOCK).build())
            .layer(CgVfxLayer.of(BEAM + "orb_plasma.shader").on(CgVfxCharge.BALL).build())
            .layer(CgVfxLayer.of(BEAM + "orb_plasma.shader").on(CgVfxCharge.GLARE).bind("_Shell", CORE_RIM).build())
            .build();
    /** Where it hits: the contact orb and its light and haze, sparks and rings. */
    public static final CgVfxKit REALISTIC_CONTACT = CgVfxKit.of("crystalgraphics:wave/contact").params(CgBeamContact.SCHEMA)
            .layer(CgVfxLayer.of(BEAM + "orb_light.shader").on(CgBeamContact.ORB).size(9f).role(CgVfxLayer.Role.LIGHT).build())
            .layer(CgVfxLayer.of(AIR + "haze_orb.shader").on(CgBeamContact.HAZE).size(4.2f)
                    .role(CgVfxLayer.Role.DISTORTION).from(CgQuality.MEDIUM).build())
            .layer(CgVfxLayer.of(BEAM + "impact_splash.shader").on(CgBeamContact.SPLASH).build())
            .layer(CgVfxLayer.of(BEAM + "disc_shock.shader").on(CgBeamContact.RINGS).build())
            .layer(CgVfxLayer.of(BEAM + "orb_plasma.shader").on(CgBeamContact.ORB).build())
            .build();
    /** The dust torn off the ground under the body, in the blast's dust colour, warmed by the body. */
    public static final CgVfxKit WAKE = CgVfxKit.of("crystalgraphics:wave/wake").params(CgVfxBlast.EXPLOSION.schema)
            .scalesWith(RADIUS, 0.8f)
            .emitter(CgBeamWake.EMITTER)
            .layer(CgVfxLayer.of("crystalgraphics:shaders/vfx/particle/dust.shader").on(CgBeamWake.EMITTER)
                    .bind("_Color", CgVfxBlast.EXPLOSION.dust).bind("_Hot", SHELL_HOT).set("_Aspect", 0.55f)
                    .set("_Boil", 1.8f).role(CgVfxLayer.Role.SMOKE).build())
            .build();
    /** What {@link #TOON} reads: its bands' palette. */
    public static final CgVfxSchema TOON_SCHEMA = CgVfxSchema.of("crystalgraphics:wave/toon");
    /** The toon body's colours, innermost first, then its halo's; each alpha a strength. */
    public static final CgVfxParam.Palette BANDS = TOON_SCHEMA.palette("bands",
            0xFFFFFFFF, 0xFF66D9FF, 0xFF1A73FF, 0x80338CFF);
    /**
     * The body, its ball and its head as a toon draws them: three flat bands of {@link #BANDS} and a soft halo, one
     * draw a slot, and its light on what is round it. In place of the realistic body, head and charge:
     *
     * <pre>{@code
     * CgVfxLook mine = CgEnergyWave.toon().derive().set(CgEnergyWave.BANDS, 1, 0xFFFFD040).build();
     * }</pre>
     */
    public static final CgVfxKit TOON = CgVfxKit.of("crystalgraphics:wave/toon")
            .params(TOON_SCHEMA, CgBeamBody.SCHEMA, CgVfxCharge.SCHEMA)
            .layer(CgVfxLayer.of(BEAM + "body_light.shader").on(CgBeamBody.BODY).volume().size(10f)
                    .role(CgVfxLayer.Role.LIGHT).build())
            .layer(CgVfxLayer.of(BEAM + "orb_light.shader").on(CgVfxCharge.BALL, CgBeamBody.HEAD).size(9f)
                    .role(CgVfxLayer.Role.LIGHT).build())
            // The halo's edge 2.43 times the body: the bands at 1, 1.4 and 1.8 of it.
            .layer(CgVfxLayer.of("crystalgraphics:shaders/vfx/toon/banded.shader")
                    .on(CgBeamBody.BODY, CgVfxCharge.BALL, CgBeamBody.HEAD).closed().size(2.43f)
                    .set("_Edges", 1f / 2.43f, 1.4f / 2.43f, 1.8f / 2.43f, 0f).build())
            .build();
    /** A band per block, sectors around and the frame's normal as a line: add it to a look to check the path. */
    public static final CgVfxLayer DEBUG = CgVfxLayer.of(BEAM + "debug.shader").on(CgBeamBody.BODY).build();

    private static final CgVfxLook KAMEHAMEHA = CgVfxLook.builder(SCHEMA)
            .with(REALISTIC_BODY, REALISTIC_HEAD, REALISTIC_CHARGE, REALISTIC_CONTACT, CgVfxBlast.KIT, WAKE)
            .link(CgBeamBody.HEAD_WIDTH, RADIUS, 1.45f).link(CgBeamBody.HEAD_LENGTH, RADIUS, 1.9f)
            .link(CgVfxCharge.RADIUS, RADIUS, 1.7f).link(CgVfxCharge.FLASH_RADIUS, RADIUS, 2.5f)
            .link(CgVfxCharge.SHOCK_RADIUS, RADIUS, 5.5f).link(CgVfxCharge.STREAK_RADIUS, RADIUS, 3.6f)
            .link(CgBeamContact.RADIUS, RADIUS, 2.2f).link(CgBeamContact.SPLASH_REACH, RADIUS, 12f)
            .link(CgBeamContact.RINGS_RADIUS, RADIUS, 6f)
            .link(CgVfxBlast.RADIUS, RADIUS, 12f).link(CgVfxBlast.FRONT, RADIUS, 2f)
            .build();
    private static final CgVfxLook TOON_LOOK = KAMEHAMEHA.derive()
            .without(REALISTIC_BODY, REALISTIC_HEAD, REALISTIC_CHARGE).with(TOON)
            .build();
    private static final CgVfxLook FINAL_FLASH = KAMEHAMEHA.derive()
            .set(CORE, 0xFFFFFFE6)
            .set(CORE_RIM, 0xFFFFEB8C)
            .set(SHELL, 0xFFFFAA14, 1.5f)
            .set(SHELL_HOT, 0xFFFFD758, 1.6f)
            .set(SPIRAL, 0xFFFFB730, 1.6f)
            .set(GLOW, 0xE6FF9B16, 1.4f)
            .set(CgVfxBlast.EXPLOSION.body, 0xFFF24C0A)
            .set(CgVfxBlast.EXPLOSION.hot, 0xFFFFE04C)
            .set(CgVfxBlast.EXPLOSION.debris, 0xFF1A0803)
            .set(RADIUS, 1f)
            .set(SPEED, 50f)
            .build();
    // Sparking! Zero's core #fafef5, band #9675fc, head #eb98f7 and rim #383be3 (the glow), decoded to linear.
    private static final CgVfxLook GALICK_GUN = KAMEHAMEHA.derive()
            .set(CORE, 0xFFF5FCE8)
            .set(CORE_RIM, 0xFFD44FED)
            .set(SHELL, 0xFF4C2EF7)
            .set(SHELL_HOT, 0xFFD44FED)
            .set(SPIRAL, 0xFF734CFF)
            .set(GLOW, 0xE60D0FFF, 1.15f)
            .set(CgVfxBlast.EXPLOSION.body, 0xFF6B14D9)
            .set(CgVfxBlast.EXPLOSION.hot, 0xFFFAA6FF)
            .set(CgVfxBlast.EXPLOSION.debris, 0xFF0F031A)
            .build();

    /** Blocks the end must move before a server publishes it again, and how far the unit aim must turn. */
    private static final double PUBLISH_MOVE = 0.05, AIM_EPSILON = 1.0e-4;
    /** A server tests its aim once a server tick: entities and the aim move no faster. */
    private static final float SERVER_RAY_SECONDS = 0.05f - 1.0e-4f;
    private static final int STREAM_ZONE = CgTrace.name("vfx.wave.stream"), MUZZLE_ZONE = CgTrace.name("vfx.wave.muzzle"),
            IMPACT_ZONE = CgTrace.name("vfx.wave.impact");
    private static final Logger LOGGER = LogManager.getLogger("CgVfx");
    private static final CgVar.Bool TRACE_AUTHORITY = CgVar.bool("crystalgraphics.vfx.traceAuthority", false, CgVar.DEV,
            "Logs each cast a server's wave makes for its beam: from where, along what, what it met, and every entity"
                    + " its level lists along the first 32 blocks.");

    private final CgVfxCharge charge = new CgVfxCharge(this);
    private final CgBeamBody body = new CgBeamBody(this);
    private final CgBeamContact contact = new CgBeamContact(this);
    private final CgVfxBlast blast = new CgVfxBlast(this, PART_BLAST);
    private final CgBeamWake wake = new CgBeamWake(this, WAKE);

    private float aimX = 1f, aimY, aimZ;
    /** Where the muzzle is from the origin: {@code follow} moves it, and leaves what it fired where it is. */
    private float muzzleX, muzzleY, muzzleZ;
    /** The muzzle as drawn this tick: {@link #MUZZLE_FORWARD} along the aim once fired. */
    private float drawnX, drawnY, drawnZ;
    /** {@link #collide}: whether it targets what its aim meets, and the entity it met this tick. */
    private boolean collides;
    private int hitEntity = -1;
    private final CgHit hit = new CgHit();
    /** The beam's cross-section, cast along the aim; made again when {@link #RADIUS} changes. */
    private CgSphere beam;
    private boolean aimMet;
    /** Where {@link #collide} last aimed it, in world coordinates, and the age of that test; NaN before the first. */
    private double endX = Double.NaN, endY, endZ;
    private float rayAge = Float.NEGATIVE_INFINITY;
    /** The end last published, NaN before the first, and the entity it was on. */
    private double publishedX = Double.NaN, publishedY, publishedZ;
    private int publishedEntity = -1;
    /** Homing on a server's end, the entity it is on ({@code CgVfxCue.Play.struck}); -1 for none. */
    private int cueStruck = -1;
    /** The contact last reported and its entity, null while none. */
    private CgHit.Kind struck;
    private int struckEntity = -1;
    /** The last hit reported: the beam's contact, then the burst. */
    private final CgHit reported = new CgHit();
    /** The age it releases at, and the age it was stopped at; NaN until stopped. */
    private float releaseAge, stopAge = Float.NaN;
    private int momentsFired, sizeAtStop;
    private boolean held, ended;

    public CgEnergyWave(CgVfxLook look, double x, double y, double z) {
        super(look, x, y, z);
        for (CgVfxSchema s : new CgVfxSchema[] {CgBeamBody.SCHEMA, CgVfxCharge.SCHEMA, CgBeamContact.SCHEMA, CgVfxBlast.SCHEMA}) {
            if (!look.layout().schemas().contains(s)) {
                throw new IllegalArgumentException("A wave's look carries " + s + ": derive it from kamehameha(), or add"
                        + " it with params(...)");
            }
        }
        releaseAge = Math.max(get(CgVfxCharge.TIME), 0f);
    }

    /** Blue-white, Sparking! Zero's. */
    public static CgVfxLook kamehameha() {
        return KAMEHAMEHA;
    }

    /** Gold and wider, its blast wider with it. */
    public static CgVfxLook finalFlash() {
        return FINAL_FLASH;
    }

    /** Violet. */
    public static CgVfxLook galickGun() {
        return GALICK_GUN;
    }

    /** The Kamehameha drawn as a toon: {@link #TOON} for its body, ball and head; its contact and blast the same. */
    public static CgVfxLook toon() {
        return TOON_LOOK;
    }

    @Override
    protected void followed(double x, double y, double z) {
        muzzleX = (float) (x - originX);
        muzzleY = (float) (y - originY);
        muzzleZ = (float) (z - originZ);
    }

    /** Where the source points, any length. Any time. */
    public CgEnergyWave aim(float dx, float dy, float dz) {
        aimX = dx;
        aimY = dy;
        aimZ = dz;
        return this;
    }

    /** What the body homes on, in world coordinates; NaN for straight flight. */
    public CgEnergyWave target(double x, double y, double z) {
        body.stream().target((float) (x - originX), (float) (y - originY), (float) (z - originZ));
        return this;
    }

    /** A point the body passes on its way to the target; NaN for none. */
    public CgEnergyWave via(double x, double y, double z) {
        body.stream().via((float) (x - originX), (float) (y - originY), (float) (z - originZ));
        return this;
    }

    /**
     * Finds its own target each tick while it plays: where a sphere of {@link #RADIUS} cast from the muzzle along the
     * aim first meets a block or an entity, passing through the entity it follows, else {@link #MAX_LENGTH} ahead. Once
     * stopped it keeps the last, so the blast lands where it was hitting; a {@link #target} call is overridden.
     *
     * <pre>{@code
     * wave = owner.play(new CgEnergyWave(look, x, y, z).follow(playerId, CgEntityAttachments.HANDS_FORWARD).collide());
     * wave.aim(look.x, look.y, look.z);                               // each tick
     * wave.onHit(CgEnergyWave.PART_BEAM, h -> damage(h.entity()));    // as the head reaches it, and as it changes
     * }</pre>
     */
    public CgEnergyWave collide() {
        collides = true;
        return this;
    }

    /** The entity the beam meets this tick while it {@link #collide collides}; -1 for none, or a block. */
    public int hitEntity() {
        return hitEntity;
    }

    /** How far its blast reaches from where it bursts, in blocks. */
    public float blastRadius() {
        return get(CgVfxBlast.RADIUS);
    }

    /**
     * Charges until {@link #fire()}, however long: the ball grows over {@link CgVfxCharge#TIME} and stays full, its
     * streaks, arcs and tremor running on. Any time before it fires; a held wave never fired charges until stopped.
     *
     * <pre>{@code
     * wave = owner.play(new CgEnergyWave(CgEnergyWave.kamehameha(), x, y, z).hold());   // key down
     * wave.fire();                                                                       // key up
     * }</pre>
     */
    public CgEnergyWave hold() {
        if (age == 0f || age < releaseAge) {
            releaseAge = Float.POSITIVE_INFINITY;
            held = true;
        }
        return this;
    }

    /** Releases the charge now, if it has not yet. */
    public CgEnergyWave fire() {
        releaseAge = Math.min(releaseAge, age);
        return this;
    }

    /** Releases the charge at {@code seconds} into its life: a viewer joining late fires where the caster fired. */
    public CgEnergyWave fireAt(float seconds) {
        releaseAge = Math.min(releaseAge, Math.max(seconds, 0f));
        return this;
    }

    /** Whether it is still charging. */
    public boolean charging() {
        return age < releaseAge && state() == State.PLAYING;
    }

    /** Whether the head is at the target this tick. */
    public boolean impacting() {
        return body.stream().impacting();
    }

    /** Whether the blast has burst: its explosion started, after its impact frame when it has one. */
    public boolean burst() {
        return blast.burst();
    }

    /** Whether the blast's impact frame shows this tick. */
    public boolean impactFrameShowing() {
        return blast.impactFrameShowing();
    }

    /**
     * Follows its cue as the server changes it: the aim, the target, what it struck, and {@link #FIRED_AT}. An
     * authoritative cue's server wave publishes all of them; a cosmetic one sets them.
     */
    @Override
    public void cueChanged(CgVfxCue.Play cue) {
        if (cue.hasDirection()) aim((float) cue.dx(), (float) cue.dy(), (float) cue.dz());
        if (cue.hasTarget()) target(cue.tx(), cue.ty(), cue.tz());
        cueStruck = cue.struck();
        float fireAge = cue.getFloat(FIRED_AT);
        if (!Float.isNaN(fireAge)) fireAt(fireAge);
    }

    /**
     * On a server, what its viewers draw: where the beam ends and the entity there, which they home on rather than test
     * their own worlds; and its aim, hold and release as the server drove it.
     */
    @Override
    public void publish(CgVfxCue.Play cue) {
        float length = (float) Math.sqrt(aimX * aimX + aimY * aimY + aimZ * aimZ);
        if (length > 0f && Math.abs(aimX / length - cue.dx()) + Math.abs(aimY / length - cue.dy())
                + Math.abs(aimZ / length - cue.dz()) > AIM_EPSILON) {
            cue.toward(aimX, aimY, aimZ);
        }
        cue.setBool(HELD, held);
        if (releaseAge != Float.POSITIVE_INFINITY) cue.setFloat(FIRED_AT, releaseAge);
        if (Double.isNaN(endX)) return;
        double dx = endX - publishedX, dy = endY - publishedY, dz = endZ - publishedZ;
        // The comparison is false while the last is NaN.
        if (dx * dx + dy * dy + dz * dz < PUBLISH_MOVE * PUBLISH_MOVE && hitEntity == publishedEntity) return;
        publishedX = endX;
        publishedY = endY;
        publishedZ = endZ;
        publishedEntity = hitEntity;
        cue.target(endX, endY, endZ).struck(hitEntity);
    }

    /**
     * A wave from a cue: at its point, aimed and targeted as it says, charging and fired as {@link #HELD} and
     * {@link #FIRED_AT} say. The one factory for both sides of an authoritative cue: the server's wave
     * {@linkplain #collide collides}, finding its own end, unless the cue gave it a target.
     *
     * <pre>{@code
     * public static final CgVfxCue WAVE = VFX.cue("kamehameha", CgEnergyWave.kamehameha(), CgEnergyWave::fromCue)
     *         .range(96).authoritative().build();
     * }</pre>
     */
    public static CgEnergyWave fromCue(CgVfxLook look, CgVfxCue.Play cue) {
        CgEnergyWave wave = new CgEnergyWave(look, cue.x(), cue.y(), cue.z());
        if (cue.hasDirection()) wave.aim((float) cue.dx(), (float) cue.dy(), (float) cue.dz());
        if (cue.hasTarget()) wave.target(cue.tx(), cue.ty(), cue.tz());
        else if (cue.authoritative()) wave.collide();
        wave.cueStruck = cue.struck();
        if (cue.getBool(HELD)) wave.hold();
        float fireAge = cue.getFloat(FIRED_AT);
        if (!Float.isNaN(fireAge)) wave.fireAt(fireAge);
        return wave;
    }

    @Override
    public void stop() {
        if (state() == State.PLAYING) {
            stopAge = age;
            sizeAtStop = body.stream().size();
        }
        super.stop();
    }

    /** An ending thins it where it is: its tail never runs out into a blast. */
    @Override
    protected void ending(CgVfxEnding ending) {
        ended = true;
    }

    @Override
    protected void tick(float dt) {
        boolean playing = state() == State.PLAYING, fired = playing && age >= releaseAge;
        if (collides && playing) targetAlongAim();
        drawnMuzzle();
        try (CgTrace.Zone ignored = CgTrace.zone(CgVfxTrace.CHANNEL, STREAM_ZONE)) {
            body.tick(dt, fired, drawnX, drawnY, drawnZ, aimX, aimY, aimZ);
        }
        reportContact();
        contact.tick(dt, impacting(), body.stream().impactX(), body.stream().impactY(), body.stream().impactZ());
        charge.tick(drawnX, drawnY, drawnZ, aimX, aimY, aimZ, releaseAge, stopAge);
        if (fired) holdShake(FIRING_SHAKE, drawnX, drawnY, drawnZ, 1f, 1f);
        boolean drained = state() == State.STOPPING && body.stream().size() == 0;
        // The tail has run into what it hit: it bursts.
        if (drained && !ended && !Float.isNaN(contact.firstHit()) && !blast.ignited()) {
            blast.ignite(body.stream().impactX(), body.stream().impactY(), body.stream().impactZ(), body.normalX(),
                    body.normalY(), body.normalZ());
        }
        if (blast.tick(dt)) reportBurst();
        wake.tick(dt, body.path(), fired);
        boolean done = blast.ignited() ? blast.done() : drained && age > stopAge + CgVfxCharge.FADE;
        if (momentsHeard()) moments(done);
        if (done) die();
    }

    /** The muzzle as drawn: {@link #MUZZLE_FORWARD} along the aim, eased in over the charge's settle once fired. */
    private void drawnMuzzle() {
        float forward = get(MUZZLE_FORWARD) * smooth(0f, CgVfxCharge.SETTLE, age - releaseAge);
        float length = (float) Math.sqrt(aimX * aimX + aimY * aimY + aimZ * aimZ);
        float k = length > 0f ? forward / length : 0f;
        drawnX = muzzleX + aimX * k;
        drawnY = muzzleY + aimY * k;
        drawnZ = muzzleZ + aimZ * k;
    }

    /**
     * {@link #collide}'s target: where the beam, a sphere of {@link #RADIUS} cast from the muzzle along the aim, first
     * meets a block or an entity, passing through what it follows; else its reach ahead.
     */
    private void targetAlongAim() {
        double length = Math.sqrt(aimX * aimX + aimY * aimY + aimZ * aimZ);
        if (length == 0.0) return;
        if (authoritative() && age - rayAge < SERVER_RAY_SECONDS) return;
        rayAge = age;
        double x = originX + muzzleX, y = originY + muzzleY, z = originZ + muzzleZ;
        float reach = get(MAX_LENGTH), radius = get(RADIUS);
        if (beam == null || beam.radius() != radius) beam = CgSphere.of(Math.max(radius, 1.0e-3f));
        CgCollisionWorld world = world();
        boolean found = world.cast(beam).at(x, y, z).along(aimX, aimY, aimZ, reach).source(followingId()).blocksToo()
                .part(PART_BEAM).first(hit);
        // Stopped at the edge of what is loaded, it met nothing, and ends there.
        aimMet = found && hit.kind() != CgHit.Kind.UNLOADED;
        hitEntity = aimMet ? hit.entityId() : -1;
        double distance = found ? hit.distance() : reach;
        endX = x + aimX / length * distance;
        endY = y + aimY / length * distance;
        endZ = z + aimZ / length * distance;
        if (authoritative() && TRACE_AUTHORITY.get()) traceCast(world, x, y, z, reach, radius);
        target(endX, endY, endZ);
    }

    private void traceCast(CgCollisionWorld world, double x, double y, double z, float reach, float radius) {
        double length = Math.sqrt(aimX * aimX + aimY * aimY + aimZ * aimZ), span = Math.min(reach, 32.0);
        double ex = x + aimX / length * span, ey = y + aimY / length * span, ez = z + aimZ / length * span;
        CgEntityQuery entities = world.entities();
        double[] pose = new double[CgEntityQuery.POSE_LENGTH];
        StringBuilder listed = new StringBuilder();
        entities.within(Math.min(x, ex) - radius, Math.min(y, ey) - radius, Math.min(z, ez) - radius,
                Math.max(x, ex) + radius, Math.max(y, ey) + radius, Math.max(z, ez) + radius, id -> {
                    listed.append(" [").append(id).append(" kind ").append(entities.kind(id)).append(" flags 0x")
                            .append(Integer.toHexString(entities.flags(id)));
                    if (entities.pose(id, 1f, pose)) {
                        listed.append(String.format(" at %.2f %.2f %.2f w %.2f h %.2f", pose[CgEntityQuery.X],
                                pose[CgEntityQuery.Y], pose[CgEntityQuery.Z], pose[CgEntityQuery.WIDTH],
                                pose[CgEntityQuery.HEIGHT]));
                    }
                    listed.append(']');
                });
        String met = !aimMet ? "nothing" : (hitEntity >= 0 ? "entity " + hitEntity : "a block")
                + String.format(" at %.2f %.2f %.2f", endX, endY, endZ);
        LOGGER.info(String.format("[CgVfx] a wave on the server, age %.3f: from %.2f %.2f %.2f along %.3f %.3f %.3f,"
                        + " reach %.1f, radius %.2f, source %d: met %s; world %s, entities %s; listed:%s", age, x, y, z,
                aimX, aimY, aimZ, reach, radius, followingId(), met, world.blocks().getClass().getSimpleName(),
                entities.getClass().getSimpleName(), listed.length() == 0 ? " none" : listed));
    }

    /**
     * Reports {@link #PART_BEAM} as the head reaches what it strikes, and again when that changes: another entity, or a
     * block for an entity. Colliding, what the cast met (air at full reach is no contact); else the entity the server
     * said it is on, or the point it was aimed at.
     */
    private void reportContact() {
        CgHit.Kind now = !impacting() ? null
                : !collides ? (cueStruck >= 0 ? CgHit.Kind.ENTITY : CgHit.Kind.POINT)
                : aimMet ? hit.kind() : null;
        int entity = now != CgHit.Kind.ENTITY ? -1 : collides ? hitEntity : cueStruck;
        if (now != null && (now != struck || entity != struckEntity)) {
            double x = originX + body.stream().impactX(), y = originY + body.stream().impactY(),
                    z = originZ + body.stream().impactZ();
            if (collides) reported.set(hit);
            else if (now == CgHit.Kind.ENTITY) reported.setPoint(x, y, z, PART_BEAM).setEntity(entity, entity).from(world().entities());
            else reported.setPoint(x, y, z, PART_BEAM);
            hit(reported);
        }
        struck = now;
        struckEntity = entity;
    }

    /** {@link #PART_BURST}, where the beam ended: on the block it last met, else that point alone. */
    private void reportBurst() {
        double x = originX + body.stream().impactX(), y = originY + body.stream().impactY(),
                z = originZ + body.stream().impactZ();
        if (reported.kind() != null && reported.kind().isTerrain()) {
            int bx = reported.blockX(), by = reported.blockY(), bz = reported.blockZ();
            reported.setPoint(x, y, z, PART_BURST).setBlock(bx, by, bz);
        } else {
            reported.setPoint(x, y, z, PART_BURST);
        }
        hit(reported);
    }

    /** Its own moments, framed on the flight; its elements announce theirs. */
    private void moments(boolean done) {
        if (age - releaseAge >= 0.35f && body.stream().size() > 0) onFlight(0, MOMENT_LAUNCH);
        if (body.stream().viaReached()) onFlight(1, MOMENT_WAYPOINT);
        float hitAt = contact.firstHit();
        if (!Float.isNaN(hitAt)) onFlight(2, MOMENT_IMPACT);
        if (age >= hitAt + 0.8f) onFlight(3, MOMENT_HOLDING);
        if (age >= stopAge + 0.05f) onFlight(4, MOMENT_STOP);
        if (!Float.isNaN(stopAge) && body.stream().size() <= sizeAtStop / 2) onFlight(5, MOMENT_TAIL);
        if (done) onFlight(6, MOMENT_END);
    }

    /** Framed on the box holding the muzzle, the head and, while it hits, the impact. */
    private void onFlight(int bit, String name) {
        if ((momentsFired & (1 << bit)) != 0) return;
        momentsFired |= 1 << bit;
        float minX = drawnX, minY = drawnY, minZ = drawnZ, maxX = drawnX, maxY = drawnY, maxZ = drawnZ;
        if (body.stream().size() > 0) {
            minX = Math.min(minX, body.stream().headX()); maxX = Math.max(maxX, body.stream().headX());
            minY = Math.min(minY, body.stream().headY()); maxY = Math.max(maxY, body.stream().headY());
            minZ = Math.min(minZ, body.stream().headZ()); maxZ = Math.max(maxZ, body.stream().headZ());
        }
        if (impacting()) {
            minX = Math.min(minX, body.stream().impactX()); maxX = Math.max(maxX, body.stream().impactX());
            minY = Math.min(minY, body.stream().impactY()); maxY = Math.max(maxY, body.stream().impactY());
            minZ = Math.min(minZ, body.stream().impactZ()); maxZ = Math.max(maxZ, body.stream().impactZ());
        }
        float dx = maxX - minX, dy = maxY - minY, dz = maxZ - minZ;
        float frame = 0.5f * (float) Math.sqrt(dx * dx + dy * dy + dz * dz) + get(RADIUS) * 6f;
        moment(name, (minX + maxX) * 0.5f, (minY + maxY) * 0.5f, (minZ + maxZ) * 0.5f, frame);
    }

    @Override
    protected void submit(CgVfxFrame frame) {
        try (CgTrace.Zone ignored = CgTrace.zone(CgVfxTrace.CHANNEL, MUZZLE_ZONE)) {
            charge.submit(frame);
        }
        try (CgTrace.Zone ignored = CgTrace.zone(CgVfxTrace.CHANNEL, IMPACT_ZONE)) {
            contact.submit(frame, body.normalX(), body.normalY(), body.normalZ());
            blast.submit(frame);
        }
        wake.submit(frame);
        body.submit(frame, state() == State.PLAYING && age >= releaseAge, drawnX, drawnY, drawnZ, releaseAge, stopAge);
    }

    private static float smooth(float edge0, float edge1, float x) {
        float t = Math.max(0f, Math.min(1f, (x - edge0) / (edge1 - edge0)));
        return t * t * (3f - 2f * t);
    }
}
