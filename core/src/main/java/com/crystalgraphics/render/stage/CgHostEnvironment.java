package com.crystalgraphics.render.stage;

/**
 * This frame's facts about the host's world and its player: the sun and sky, the weather, the dimension, the fog, where
 * the camera is, the player's sight, their settings and the game's clock. Filled by the host when it captures the
 * camera, beside {@link CgHostView} on the same {@link CgHostFrame}; allocates nothing. What an effect reads to light
 * itself like the world, dim at night, switch to its underwater look, hold still while the game is paused, and spawn
 * fewer particles for a player who asked for fewer.
 *
 * <pre>{@code
 * CgHostEnvironment world = frame.host().environment();
 * if (world.hasSky()) world.sunDirection(sun);            // a unit vector toward the sun, in world axes
 * int count = (int) (burst * world.particleShare());     // the player's particles setting
 * if (world.paused()) return;                            // a paused game freezes its effects
 *
 * // a host, with the camera: clear, then every group it can answer
 * CgHostEnvironment out = CgRenderStage.WORLD_OPAQUE.host().environment().clear();
 * out.sun(celestialAngle, moonPhase, stars, skyBrightness).weather(rain, thunder, flash)
 *    .sky(hasSky, hasCeiling, ultraWarm, r, g, b, cloudHeight)
 *    .camera(fluid, perspective, fov, renderDistance, guiHidden).screen(open).sight(nightVision, blindness, darkness)
 *    .settings(particles, graphics, screenEffects, fovEffects).time(gameTime, dayTime, paused, tickRate, frozen);
 * }</pre>
 *
 * <ul>
 *   <li>Absent is absent, never a made-up default: with no level, {@link #hasSky} is false, every number NaN and every
 *       kind -1. A host leaves a group it cannot answer cleared.</li>
 *   <li>The fog is NaN where the host keeps none readable on the CPU: Minecraft 1.17.1 to 1.21.1 hand it over.</li>
 *   <li>Read during a stage; copy what you keep, as with the view.</li>
 * </ul>
 */
public final class CgHostEnvironment {

    /** The fluid the camera is in: {@link #cameraFluid}. */
    public static final int FLUID_NONE = 0, FLUID_WATER = 1, FLUID_LAVA = 2, FLUID_POWDER_SNOW = 3;
    /** Where the camera is: {@link #perspective}. */
    public static final int FIRST_PERSON = 0, THIRD_PERSON_BACK = 1, THIRD_PERSON_FRONT = 2;
    /** The player's particles setting: {@link #particles}. */
    public static final int PARTICLES_ALL = 0, PARTICLES_DECREASED = 1, PARTICLES_MINIMAL = 2;
    /** The player's graphics setting: {@link #graphics}. */
    public static final int GRAPHICS_FAST = 0, GRAPHICS_FANCY = 1, GRAPHICS_FABULOUS = 2;

    private float celestialAngle, starBrightness, skyBrightness;
    private int moonPhase;
    private float rain, thunder, flash;
    private boolean hasSky, hasCeiling, ultraWarm;
    private float skyRed, skyGreen, skyBlue, cloudHeight;
    private float fogRed, fogGreen, fogBlue, fogStart, fogEnd;
    private int cameraFluid, perspective;
    private float fov, renderDistance;
    private boolean guiHidden, screenOpen;
    private float nightVision, blindness, darkness;
    private int particles, graphics;
    private float screenEffects, fovEffects;
    private long gameTime, dayTime;
    private boolean paused, frozen;
    private float tickRate;

    public CgHostEnvironment() {
        clear();
    }

    // ── Sun and sky ────────────────────────────────────────────────────────────

    /**
     * How far round the sky the sun is, 0 to 1, as Minecraft counts it: 0 the sun overhead at noon, 0.25 setting in
     * the west, 0.5 midnight, 0.75 rising in the east.
     */
    public float celestialAngle() {
        return celestialAngle;
    }

    /** A unit vector toward the sun, in world axes, into {@code out} {x, y, z}: NaN with no sky angle. */
    public float[] sunDirection(float[] out) {
        double a = celestialAngle * Math.PI * 2.0;
        // Minecraft turns the sky -90 degrees about y, then by the angle about x, and draws the sun straight up.
        out[0] = (float) -Math.sin(a);
        out[1] = (float) Math.cos(a);
        out[2] = Float.isNaN(celestialAngle) ? Float.NaN : 0f;
        return out;
    }

    /** The moon's phase, 0 (full) to 7; -1 when absent. */
    public int moonPhase() {
        return moonPhase;
    }

    /** How bright the stars are, 0 to 1: 0 by day. */
    public float starBrightness() {
        return starBrightness;
    }

    /** How bright daylight is, 0 to 1: Minecraft's sky darkening inverted, 1 at noon, low at night and in storms. */
    public float skyBrightness() {
        return skyBrightness;
    }

    /** How hard it rains, 0 to 1. */
    public float rain() {
        return rain;
    }

    /** How stormy it is, 0 to 1. */
    public float thunder() {
        return thunder;
    }

    /** A lightning flash lighting the sky, 0 to 1: 0 between strikes. */
    public float flash() {
        return flash;
    }

    /** Whether the level has a sky: false in the Nether and the End, and with no level. */
    public boolean hasSky() {
        return hasSky;
    }

    /** Whether the level has a bedrock ceiling: the Nether. */
    public boolean hasCeiling() {
        return hasCeiling;
    }

    /** Whether the level is ultra-warm: water evaporates there, so a splash should hiss into steam. */
    public boolean ultraWarm() {
        return ultraWarm;
    }

    /** The sky's colour at the camera, 0 to 1 each. */
    public float skyRed() {
        return skyRed;
    }

    public float skyGreen() {
        return skyGreen;
    }

    public float skyBlue() {
        return skyBlue;
    }

    /** The height of the clouds' base, a world y. */
    public float cloudHeight() {
        return cloudHeight;
    }

    // ── Fog ────────────────────────────────────────────────────────────────────

    public float fogRed() {
        return fogRed;
    }

    public float fogGreen() {
        return fogGreen;
    }

    public float fogBlue() {
        return fogBlue;
    }

    /** Where the fog begins, in blocks from the eye. */
    public float fogStart() {
        return fogStart;
    }

    /** Where nothing shows through the fog, in blocks from the eye. */
    public float fogEnd() {
        return fogEnd;
    }

    // ── The camera and the player's sight ──────────────────────────────────────

    /** The fluid the camera is in: a {@code FLUID_} constant, for an effect's underwater look; -1 when absent. */
    public int cameraFluid() {
        return cameraFluid;
    }

    /** {@link #FIRST_PERSON}, {@link #THIRD_PERSON_BACK} or {@link #THIRD_PERSON_FRONT}; -1 when absent. */
    public int perspective() {
        return perspective;
    }

    /** The vertical field of view this frame, in degrees, with every effect on it (sprinting, a bow drawn). */
    public float fov() {
        return fov;
    }

    /** How far the world is drawn, in blocks. */
    public float renderDistance() {
        return renderDistance;
    }

    /** Whether the player has hidden the HUD (F1): a screen-space effect should step aside too. */
    public boolean guiHidden() {
        return guiHidden;
    }

    /** Whether a screen is up (chat, the inventory, a menu): the keys go to it, not to the game. */
    public boolean screenOpen() {
        return screenOpen;
    }

    /** The player's night vision, 0 to 1. */
    public float nightVision() {
        return nightVision;
    }

    /** How blinded the player is, 0 to 1. */
    public float blindness() {
        return blindness;
    }

    /** The darkness effect's pulse, 0 to 1. */
    public float darkness() {
        return darkness;
    }

    // ── The player's settings ──────────────────────────────────────────────────

    /** The particles setting: a {@code PARTICLES_} constant; -1 when absent. */
    public int particles() {
        return particles;
    }

    /** The share of an effect's particles to spawn for the particles setting: 1, 0.5 or 0.15, and 1 when absent. */
    public float particleShare() {
        return particles == PARTICLES_DECREASED ? 0.5f : particles == PARTICLES_MINIMAL ? 0.15f : 1f;
    }

    /** The graphics setting: a {@code GRAPHICS_} constant; -1 when absent. */
    public int graphics() {
        return graphics;
    }

    /** The accessibility scale for screen distortion (nausea, a portal), 0 to 1: scale shake and warps by it. */
    public float screenEffects() {
        return screenEffects;
    }

    /** The accessibility scale for field-of-view effects, 0 to 1: scale an FOV kick by it. */
    public float fovEffects() {
        return fovEffects;
    }

    // ── The clock ──────────────────────────────────────────────────────────────

    /** Ticks since the level began, the same on the server and every client: what keeps an effect in step; -1 absent. */
    public long gameTime() {
        return gameTime;
    }

    /** The time of day in ticks, 0 to 24000 a day and counting on; -1 when absent. */
    public long dayTime() {
        return dayTime;
    }

    /** Whether the game is paused: a single-player menu stops the world, and its effects should stop with it. */
    public boolean paused() {
        return paused;
    }

    /** Game ticks a second: 20, unless {@code /tick rate} says otherwise. */
    public float tickRate() {
        return tickRate;
    }

    /** Whether {@code /tick freeze} holds the world still. */
    public boolean frozen() {
        return frozen;
    }

    // ── Host side ──────────────────────────────────────────────────────────────

    /** Host side: the sun's place, the moon's phase, the stars and daylight. */
    public CgHostEnvironment sun(float celestialAngle, int moonPhase, float starBrightness, float skyBrightness) {
        this.celestialAngle = celestialAngle;
        this.moonPhase = moonPhase;
        this.starBrightness = starBrightness;
        this.skyBrightness = skyBrightness;
        return this;
    }

    /** Host side: rain, storm and a lightning flash. */
    public CgHostEnvironment weather(float rain, float thunder, float flash) {
        this.rain = rain;
        this.thunder = thunder;
        this.flash = flash;
        return this;
    }

    /** Host side: the dimension's sky and its colour at the camera, and the clouds. */
    public CgHostEnvironment sky(boolean hasSky, boolean hasCeiling, boolean ultraWarm, float red, float green, float blue,
                                 float cloudHeight) {
        this.hasSky = hasSky;
        this.hasCeiling = hasCeiling;
        this.ultraWarm = ultraWarm;
        skyRed = red;
        skyGreen = green;
        skyBlue = blue;
        this.cloudHeight = cloudHeight;
        return this;
    }

    /** Host side: this frame's fog, where the host keeps it on the CPU. */
    public CgHostEnvironment fog(float red, float green, float blue, float start, float end) {
        fogRed = red;
        fogGreen = green;
        fogBlue = blue;
        fogStart = start;
        fogEnd = end;
        return this;
    }

    /** Host side: where the camera is and how it sees. */
    public CgHostEnvironment camera(int fluid, int perspective, float fov, float renderDistance, boolean guiHidden) {
        cameraFluid = fluid;
        this.perspective = perspective;
        this.fov = fov;
        this.renderDistance = renderDistance;
        this.guiHidden = guiHidden;
        return this;
    }

    /** Host side: whether a screen is up. */
    public CgHostEnvironment screen(boolean open) {
        screenOpen = open;
        return this;
    }

    /** Host side: the effects on the player's sight. */
    public CgHostEnvironment sight(float nightVision, float blindness, float darkness) {
        this.nightVision = nightVision;
        this.blindness = blindness;
        this.darkness = darkness;
        return this;
    }

    /** Host side: the player's settings an effect should respect. */
    public CgHostEnvironment settings(int particles, int graphics, float screenEffects, float fovEffects) {
        this.particles = particles;
        this.graphics = graphics;
        this.screenEffects = screenEffects;
        this.fovEffects = fovEffects;
        return this;
    }

    /** Host side: the game's clock. */
    public CgHostEnvironment time(long gameTime, long dayTime, boolean paused, float tickRate, boolean frozen) {
        this.gameTime = gameTime;
        this.dayTime = dayTime;
        this.paused = paused;
        this.tickRate = tickRate;
        this.frozen = frozen;
        return this;
    }

    /** Host side: another frame's facts, copied in, as the transparent stage takes the opaque one's. */
    public CgHostEnvironment set(CgHostEnvironment o) {
        sun(o.celestialAngle, o.moonPhase, o.starBrightness, o.skyBrightness).weather(o.rain, o.thunder, o.flash)
                .sky(o.hasSky, o.hasCeiling, o.ultraWarm, o.skyRed, o.skyGreen, o.skyBlue, o.cloudHeight)
                .fog(o.fogRed, o.fogGreen, o.fogBlue, o.fogStart, o.fogEnd)
                .camera(o.cameraFluid, o.perspective, o.fov, o.renderDistance, o.guiHidden).screen(o.screenOpen)
                .sight(o.nightVision, o.blindness, o.darkness)
                .settings(o.particles, o.graphics, o.screenEffects, o.fovEffects);
        return time(o.gameTime, o.dayTime, o.paused, o.tickRate, o.frozen);
    }

    /** Host side: nothing known, as with no level. Call before filling a frame. */
    public CgHostEnvironment clear() {
        float n = Float.NaN;
        sun(n, -1, n, n).weather(n, n, n).sky(false, false, false, n, n, n, n).fog(n, n, n, n, n)
                .camera(-1, -1, n, n, false).screen(false).sight(n, n, n).settings(-1, -1, n, n);
        return time(-1L, -1L, false, n, false);
    }
}
