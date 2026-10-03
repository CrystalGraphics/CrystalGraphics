package com.crystalgraphics.probe;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgEntityQuery;
import com.crystalgraphics.platform.service.CgHostCamera;
import com.crystalgraphics.platform.service.CgWorldEvents;
import com.crystalgraphics.platform.service.CgWorldQuery;
import com.crystalgraphics.platform.service.CgWorldSound;
import com.crystalgraphics.platform.service.CgWorldStimulus;
import com.crystalgraphics.render.stage.CgHostEnvironment;
import com.crystalgraphics.render.stage.CgHostFrame;
import com.crystalgraphics.render.stage.CgHostTextures;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.world.CgCameraShake;
import com.crystalgraphics.world.CgWorldQueries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.AxisAngle4f;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/**
 * Proves, in a running game, that the host answers every world seam and that the answers agree with each other: the
 * entity query against the host's camera, the ground under the player against a raycast, the sky light against the
 * heightmap, the sun against the day time, a camera offset against the projection it changes, and the world events
 * against events the probe causes on the single-player server ({@link CgWorldStimulus}). For an unattended run, which
 * {@code prodSmoke} and a dev client's autotest both are.
 *
 * <pre>{@code
 * // a JVM flag; CgGraphicsLifecycle installs it with the engine
 * -Dcrystalgraphics.worldprobe=true
 * // what it logs, and what prodSmoke reads:
 * world probe: started
 * world probe entity.camera: true (eye at the view position)
 * world probe events.explosion: skipped (not reported on this version)
 * world probe: true (31 passed, 0 failed, 4 skipped)
 * }</pre>
 *
 * <ul>
 *   <li>Starts {@value #SETTLE_SECONDS} seconds after the first world frame with a local player, and finishes within
 *       {@value #EVENT_SECONDS} seconds more. {@link #running()} lets an autotest wait for it before quitting.</li>
 *   <li>A part a host does not answer on its version is skipped, never failed: {@code CgHostCamera.capabilities()} and
 *       {@code CgWorldEvents.declared()} say what each answers.</li>
 *   <li>It changes the world a little: a stone set and broken, and a TNT in the air beside the player, only where the
 *       air is clear. Never point it at a world that matters.</li>
 * </ul>
 */
public final class CgWorldProbe {

    /** {@code -Dcrystalgraphics.worldprobe=true}. */
    public static final boolean ENABLED = Boolean.getBoolean("crystalgraphics.worldprobe");

    private static final Logger LOGGER = LogManager.getLogger("CgWorldProbe");
    private static final int SETTLE_SECONDS = 3, EVENT_SECONDS = 12, PAUSED_SECONDS = 15;
    private static final float FOV_SCALE = 1.25f, YAW = 10f, ROLL = 10f;
    private static final int CAMERA_FRAMES = 4;
    private static final Matrix4fc IDENTITY = new Matrix4f();

    private enum Phase { WAITING, SETTLING, CAMERA, EVENTS, DONE }

    private static boolean installed;
    private static Phase phase = Phase.WAITING;
    private static long phaseNanos, settleNanos;
    private static int passed, failed, skipped, cameraFrames;
    private static int cameraBits, cameraStep;
    private static float baseFov;
    private static final Matrix3f baseView = new Matrix3f();
    private static long startGameTime;
    private static int startTextures, startEpoch;
    private static final double[] pose = new double[CgEntityQuery.POSE_LENGTH];
    private static final Events events = new Events();

    private CgWorldProbe() {
    }

    /** Called once with the engine; registers on the opaque world stage when {@link #ENABLED}. */
    public static void installIfEnabled() {
        if (!ENABLED || installed) return;
        installed = true;
        CgRenderStage.WORLD_OPAQUE.register(Integer.MAX_VALUE, CgWorldProbe::frame);
        LOGGER.info("world probe: armed");
    }

    /** True from the start of its checks to its result: an autotest that quits now loses the result. */
    public static boolean running() {
        return phase != Phase.WAITING && phase != Phase.DONE;
    }

    private static void frame(CgStageFrame stage) {
        if (phase == Phase.DONE) return;
        CgHostFrame host = CgRenderStage.WORLD_OPAQUE.host();
        CgEntityQuery entities = CgPlatform.get(CgEntityQuery.SERVICE);
        long now = System.nanoTime();
        switch (phase) {
            case WAITING:
                if (entities.localPlayer() < 0) break;
                CgPlatform.get(CgWorldStimulus.SERVICE).keepRunning();
                settleNanos = now;
                next(Phase.SETTLING, now);
                break;
            case SETTLING:
                // A paused game ticks no server: wait it out, for a while, rather than check a frozen world.
                if (host.environment().paused() && (now - settleNanos) / 1.0e9 < PAUSED_SECONDS) phaseNanos = now;
                if (seconds(now) < SETTLE_SECONDS) break;
                LOGGER.info("world probe: started");
                staticChecks(host);
                startCamera(host, now);
                break;
            case CAMERA:
                if (++cameraFrames < CAMERA_FRAMES) break;
                if (cameraStep == 0) {
                    checkTurn(host);
                    if (has(CgHostCamera.ROLL)) {
                        CgPlatform.get(CgHostCamera.SERVICE).offset(0f, 0f, 0f, 0f, 0f, ROLL, 1f);
                        cameraStep = 1;
                        cameraFrames = 0;
                        break;
                    }
                } else {
                    checkRoll(host);
                }
                if (cameraBits != 0) CgPlatform.get(CgHostCamera.SERVICE).offset(0f, 0f, 0f, 0f, 0f, 0f, 1f);
                startEvents(now);
                break;
            case EVENTS:
                if (events.complete() || seconds(now) >= EVENT_SECONDS) finish(host);
                break;
            default:
                break;
        }
    }

    // ── The checks made on one frame ─────────────────────────────────────────────

    private static void staticChecks(CgHostFrame host) {
        CgWorldQuery world = CgPlatform.get(CgWorldQuery.SERVICE);
        CgEntityQuery entities = CgPlatform.get(CgEntityQuery.SERVICE);
        CgHostView view = host.view();
        CgHostEnvironment env = host.environment();

        // An identity projection is a view nobody filled: the host's capture never ran (on 1.21.6-1.21.11 it is a node
        // mixin). Every camera check below would then fail for that reason alone.
        check("view.captured", !view.projection().equals(IDENTITY, 1.0e-6f),
                String.format("projection m00 %.3f, m11 %.3f", view.projection().m00(), view.projection().m11()));
        int player = entities.localPlayer();
        boolean posed = entities.pose(player, host.partialTick(), pose);
        check("entity.pose", posed, "player " + player);
        if (!posed) return;
        double px = pose[CgEntityQuery.X], py = pose[CgEntityQuery.Y], pz = pose[CgEntityQuery.Z];
        double eye = pose[CgEntityQuery.EYE_HEIGHT];
        check("entity.camera-entity", entities.cameraEntity() == player, "camera " + entities.cameraEntity());
        // From 1.14 the view position is the eye; 1.13 and older put it at the feet and fold the eye into the matrix.
        double dy = view.y() - py;
        boolean atEye = Math.abs(dy - eye) < 0.05, atFeet = Math.abs(dy) < 0.05;
        check("entity.camera-position", Math.abs(view.x() - px) < 0.05 && Math.abs(view.z() - pz) < 0.05 && (atEye || atFeet),
                String.format("view %.3f %.3f %.3f, player %.3f %.3f %.3f, eye %.2f, %s", view.x(), view.y(), view.z(), px, py,
                        pz, eye, atEye ? "eye at the view position" : atFeet ? "feet at the view position" : "neither"));
        double w = pose[CgEntityQuery.WIDTH], h = pose[CgEntityQuery.HEIGHT];
        check("entity.size", w > 0.3 && w < 1.2 && h > 0.5 && h < 2.0 && eye > 0.3 && eye < 1.8,
                String.format("width %.2f, height %.2f, eye %.2f", w, h, eye));
        int flags = entities.flags(player);
        int required = CgEntityQuery.ALIVE | CgEntityQuery.LIVING | CgEntityQuery.PLAYER | CgEntityQuery.LOCAL_PLAYER;
        check("entity.flags", (flags & required) == required, "flags " + Integer.toBinaryString(flags));
        check("entity.kind", entities.kind(player) == CgEntityQuery.KIND_PLAYER, "kind " + entities.kind(player));
        boolean[] found = new boolean[1];
        entities.within(px - 1, py - 1, pz - 1, px + 1, py + 2, pz + 1, id -> found[0] |= id == player);
        check("entity.within", found[0], "box round the player");

        int bx = floor(px), bz = floor(pz);
        check("world.loaded", world.loaded(bx, bz), "column " + bx + " " + bz);
        check("world.height", world.minY() <= py && py < world.maxY() && world.minY() <= world.seaLevel()
                && world.seaLevel() < world.maxY(), "min " + world.minY() + ", max " + world.maxY() + ", sea " + world.seaLevel());
        startEpoch = world.levelEpoch();
        check("world.epoch", startEpoch > 0, "epoch " + startEpoch);

        boolean onGround = (flags & CgEntityQuery.ON_GROUND) != 0;
        double ground = CgWorldQueries.groundBelow(world, px, py + 0.5, pz, 64);
        if (onGround) {
            check("world.ground", Math.abs(ground - py) < 0.08, String.format("ground %.3f, feet %.3f", ground, py));
        } else {
            check("world.ground", Double.isNaN(ground) || ground <= py + 0.5,
                    String.format("ground %.3f below feet %.3f, not on the ground", ground, py));
        }
        if (!Double.isNaN(ground)) {
            CgWorldQueries.Hit hit = new CgWorldQueries.Hit();
            boolean struck = CgWorldQueries.raycast(world, px, py + eye, pz, 0, -1, 0, 80, hit);
            check("world.raycast", struck && Math.abs(hit.y - ground) < 0.08,
                    String.format("ray hit %s at %.3f, ground %.3f", struck, hit.y, ground));
            int by = floor(ground - 0.001);
            check("world.ground-block", !Float.isNaN(world.collisionTop(bx, by, bz))
                    && world.surface(bx, by, bz) != CgWorldQuery.SURFACE_NONE && !Float.isNaN(world.hardness(bx, by, bz)),
                    "block " + bx + " " + by + " " + bz + ", surface " + world.surface(bx, by, bz) + ", hardness "
                            + world.hardness(bx, by, bz));
            float[] uv = new float[4];
            boolean sprite = world.spriteRect(bx, by, bz, uv);
            check("world.sprite", sprite && uv[0] >= 0 && uv[0] < uv[2] && uv[2] <= 1 && uv[1] >= 0 && uv[1] < uv[3]
                    && uv[3] <= 1, String.format("sprite %s %.4f %.4f %.4f %.4f", sprite, uv[0], uv[1], uv[2], uv[3]));
        } else {
            skip("world.raycast", "no ground within 64 blocks");
        }

        int light = world.light(bx, floor(py + eye), bz);
        check("world.light", light >= 0 && light <= 0xFF, "block " + CgWorldQuery.blockLight(light) + ", sky "
                + CgWorldQuery.skyLight(light));
        int top = world.surfaceY(bx, bz, CgWorldQuery.HEIGHT_TOP);
        check("world.surface-y", top != Integer.MIN_VALUE && top >= world.minY() && top <= world.maxY(), "top " + top);
        if (env.hasSky() && top != Integer.MIN_VALUE && top < world.maxY()) {
            int skyAtTop = CgWorldQuery.skyLight(world.light(bx, top, bz));
            check("world.sky-light", skyAtTop == 15, "sky light " + skyAtTop + " at the top, y " + top);
        } else {
            skip("world.sky-light", "no sky in this dimension");
        }
        if (top != Integer.MIN_VALUE && top + 1 < world.maxY()) {
            check("world.air", Float.isNaN(world.collisionTop(bx, top + 1, bz)) && world.fluidKind(bx, top + 1, bz)
                    == CgWorldQuery.FLUID_NONE && Float.isNaN(world.fluidHeight(bx, top + 1, bz)), "y " + (top + 1));
        }
        int grass = world.biomeColor(bx, floor(py), bz, CgWorldQuery.BIOME_GRASS);
        int foliage = world.biomeColor(bx, floor(py), bz, CgWorldQuery.BIOME_FOLIAGE);
        check("world.biome-colors", (grass >>> 24) == 0xFF && (foliage >>> 24) == 0xFF,
                String.format("grass %08x, foliage %08x", grass, foliage));
        int rain = world.precipitation(bx, floor(py), bz);
        check("world.precipitation", rain >= CgWorldQuery.PRECIPITATION_NONE && rain <= CgWorldQuery.PRECIPITATION_SNOW,
                "precipitation " + rain);

        environmentChecks(env, view);

        CgHostTextures textures = host.textures();
        check("textures.block-atlas", textures.blockAtlas() != 0, "atlas " + textures.blockAtlas());
        info("textures.lightmap", textures.lightmap() != 0 ? "present" : "absent on this version");
        startTextures = textures.version();
        startGameTime = env.gameTime();

        boolean played = true;
        try {
            CgPlatform.get(CgWorldSound.SERVICE).play("minecraft:ui.button.click", px, py, pz, 0.2f, 1f);
        } catch (RuntimeException e) {
            played = false;
            LOGGER.warn("world probe sound threw", e);
        }
        check("sound.play", played, "played without an error; whether it was heard is not checked");
    }

    private static void environmentChecks(CgHostEnvironment env, CgHostView view) {
        float angle = env.celestialAngle();
        check("env.sun", angle >= 0f && angle <= 1f, "celestial angle " + angle);
        if (env.hasSky() && !env.hasCeiling() && env.dayTime() >= 0) {
            double d = frac(env.dayTime() / 24000.0 - 0.25);
            double expected = d * 2.0 / 3.0 + (0.5 - Math.cos(d * Math.PI) / 2.0) / 3.0;
            double off = Math.abs(angle - expected);
            check("env.sun-matches-day", Math.min(off, 1.0 - off) < 0.01,
                    String.format("angle %.4f, from day time %d %.4f", angle, env.dayTime(), expected));
        } else {
            skip("env.sun-matches-day", "no sky, or no day time");
        }
        check("env.weather", inUnit(env.rain()) && inUnit(env.thunder()), "rain " + env.rain() + ", thunder " + env.thunder());
        check("env.stars", Float.isNaN(env.starBrightness()) || inUnit(env.starBrightness()), "stars " + env.starBrightness());
        int moon = env.moonPhase();
        check("env.moon", moon == -1 || moon >= 0 && moon < 8, "moon " + moon);
        check("env.camera", env.fov() > 10f && env.fov() < 170f && env.renderDistance() >= 16f && env.perspective() >= 0
                && env.perspective() <= 2, "fov " + env.fov() + ", distance " + env.renderDistance() + ", perspective "
                + env.perspective());
        check("env.settings", env.particles() >= 0 && env.particles() <= 2 && env.graphics() >= 0 && env.graphics() <= 2,
                "particles " + env.particles() + ", graphics " + env.graphics());
        check("env.time", env.gameTime() >= 0 && env.dayTime() >= 0, "game " + env.gameTime() + ", day " + env.dayTime());
        info("env.fog", Float.isNaN(env.fogStart()) ? "absent on this version" : "start " + env.fogStart() + ", end " + env.fogEnd());
        info("env.paused", String.valueOf(env.paused()));
    }

    // ── The camera offset ────────────────────────────────────────────────────────

    private static void startCamera(CgHostFrame host, long now) {
        CgHostCamera camera = CgPlatform.get(CgHostCamera.SERVICE);
        cameraBits = camera.capabilities();
        if (CgCameraShake.active()) {
            skip("camera", "a shake owns the camera");
            cameraBits = 0;
        } else if (cameraBits == 0) {
            skip("camera", "this version applies no camera offset");
        } else {
            baseFov = host.environment().fov();
            baseView.set(host.view().view());
            camera.offset(0f, 0f, 0f, has(CgHostCamera.ROTATION) ? YAW : 0f, 0f, 0f, has(CgHostCamera.FOV) ? FOV_SCALE : 1f);
        }
        cameraStep = 0;
        cameraFrames = 0;
        next(Phase.CAMERA, now);
    }

    // Yaw first, with the FOV, then roll alone: each is then one rotation between the views before and after, measured
    // whatever the player's pitch. A host may turn about the view's vertical or the world's; both pass.
    private static void checkTurn(CgHostFrame host) {
        if (cameraBits == 0) return;
        int applied = CgPlatform.get(CgHostCamera.SERVICE).applied();
        if (has(CgHostCamera.FOV)) {
            float ratio = host.environment().fov() / baseFov;
            check("camera.fov", Math.abs(ratio - FOV_SCALE) < 0.03f, String.format("fov %.2f to %.2f, ratio %.3f, %s",
                    baseFov, host.environment().fov(), ratio, ran(applied, CgHostCamera.FOV)));
        } else {
            skip("camera.fov", "not applied on this version");
        }
        if (has(CgHostCamera.ROTATION)) {
            AxisAngle4f turn = turnSinceBase(host.view().view());
            Vector3f worldUp = baseView.transform(new Vector3f(0f, 1f, 0f));
            float about = Math.max(Math.abs(turn.y), Math.abs(turn.x * worldUp.x + turn.y * worldUp.y + turn.z * worldUp.z));
            float degrees = (float) Math.toDegrees(turn.angle);
            check("camera.yaw", Math.abs(degrees - YAW) < 1.5f && about > 0.98f, String.format(
                    "turned %.2f degrees about an axis %.3f vertical, %s", degrees, about, ran(applied, CgHostCamera.ROTATION)));
        } else {
            skip("camera.yaw", "not applied on this version");
        }
        if (!has(CgHostCamera.ROLL)) skip("camera.roll", "not applied on this version");
    }

    private static void checkRoll(CgHostFrame host) {
        AxisAngle4f turn = turnSinceBase(host.view().view());
        float degrees = (float) Math.toDegrees(turn.angle);
        check("camera.roll", Math.abs(degrees - ROLL) < 1.5f && Math.abs(turn.z) > 0.98f, String.format(
                "rolled %.2f degrees about an axis %.3f along the view, %s", degrees, Math.abs(turn.z),
                ran(CgPlatform.get(CgHostCamera.SERVICE).applied(), CgHostCamera.ROLL)));
    }

    /** The rotation from the base view to this one, in view space: an axis there and an angle. */
    private static AxisAngle4f turnSinceBase(Matrix4fc view) {
        Matrix3f delta = new Matrix3f().set(view).mul(new Matrix3f(baseView).transpose());
        return new AxisAngle4f().set(delta);
    }

    /** Whether the host's hook for {@code part} has ever run: what tells an unfired hook from an ignored one. */
    private static String ran(int applied, int part) {
        return (applied & part) != 0 ? "its hook ran" : "its hook never ran";
    }

    private static boolean has(int bit) {
        return (cameraBits & bit) != 0;
    }

    // ── World events, caused on the server ───────────────────────────────────────

    private static void startEvents(long now) {
        CgWorldQuery world = CgPlatform.get(CgWorldQuery.SERVICE);
        CgWorldStimulus stimulus = CgPlatform.get(CgWorldStimulus.SERVICE);
        double px = pose[CgEntityQuery.X], py = pose[CgEntityQuery.Y], pz = pose[CgEntityQuery.Z];
        events.reset(CgWorldEvents.declared());
        events.player = CgPlatform.get(CgEntityQuery.SERVICE).localPlayer();
        CgWorldEvents.listen(events);

        events.lightningX = px + 20;
        events.lightningZ = pz;
        boolean asked = stimulus.lightning(events.lightningX, py, events.lightningZ);
        if (!asked) {
            events.unavailable = "no single-player server here";
            next(Phase.EVENTS, now);
            return;
        }
        // A clear block above the player, so setting it to stone and breaking it leaves the world as it was.
        int bx = floor(px) + 4, bz = floor(pz), by = Integer.MIN_VALUE;
        for (int y = floor(py) + 6; y < floor(py) + 18 && y < world.maxY(); y++) {
            if (Float.isNaN(world.collisionTop(bx, y, bz)) && world.fluidKind(bx, y, bz) == CgWorldQuery.FLUID_NONE) {
                by = y;
                break;
            }
        }
        if (by != Integer.MIN_VALUE) {
            events.blockX = bx;
            events.blockY = by;
            events.blockZ = bz;
            stimulus.breakBlock(bx, by, bz);
        } else {
            events.blockSkipped = "no clear block above the player";
        }
        // Eight blocks over whatever stands in that column, trees included, with clear air round it: the TNT breaks
        // nothing.
        double ex = px - 14, ez = pz;
        int columnTop = world.surfaceY(floor(ex), floor(ez), CgWorldQuery.HEIGHT_TOP);
        double ey = Math.max(py + 10, columnTop == Integer.MIN_VALUE ? py + 10 : columnTop + 8);
        if (ey + 8 < world.maxY() && Double.isNaN(CgWorldQueries.ceilingAbove(world, ex, ey - 6, ez, 14))
                && Double.isNaN(CgWorldQueries.groundBelow(world, ex, ey, ez, 6))) {
            events.blastX = ex;
            events.blastY = ey;
            events.blastZ = ez;
            stimulus.explode(ex, ey, ez);
        } else {
            events.blastSkipped = "no clear air for a blast beside the player";
        }
        next(Phase.EVENTS, now);
    }

    private static void finish(CgHostFrame host) {
        CgWorldEvents.stopListening(events);
        events.report();
        CgWorldQuery world = CgPlatform.get(CgWorldQuery.SERVICE);
        CgHostEnvironment env = host.environment();
        if (env.paused()) {
            skip("env.time-advances", "the game is paused");
        } else {
            check("env.time-advances", env.gameTime() > startGameTime, "game time " + startGameTime + " to " + env.gameTime());
        }
        check("world.epoch-stable", world.levelEpoch() == startEpoch, "epoch " + startEpoch + " to " + world.levelEpoch());
        check("textures.stable", host.textures().version() == startTextures,
                "version " + startTextures + " to " + host.textures().version());
        boolean ok = failed == 0;
        LOGGER.info("world probe: {} ({} passed, {} failed, {} skipped)", ok, passed, failed, skipped);
        phase = Phase.DONE;
    }

    /** What the probe hears from {@link CgWorldEvents}, against what it caused. */
    private static final class Events implements CgWorldEvents.Listener {
        int declared, player;
        String unavailable, blockSkipped, blastSkipped;
        double lightningX, lightningZ, blastX, blastY, blastZ;
        int blockX, blockY, blockZ;
        boolean lightning, block, explosion, hurt, died;
        String blockDetail = "none", explosionDetail = "none", heardFar = "none";

        void reset(int kinds) {
            declared = kinds;
            unavailable = blockSkipped = blastSkipped = null;
            lightning = block = explosion = hurt = died = false;
            heardFar = "none";
        }

        boolean complete() {
            if (unavailable != null) return true;
            return (lightning || !wants(CgWorldEvents.LIGHTNING))
                    && (block || blockSkipped != null || !wants(CgWorldEvents.BLOCK_BROKEN))
                    && (explosion || blastSkipped != null || !wants(CgWorldEvents.EXPLOSION))
                    && (hurt || blastSkipped != null || !wants(CgWorldEvents.ENTITY_HURT))
                    && (died || blastSkipped != null || !wants(CgWorldEvents.ENTITY_DIED));
        }

        boolean wants(int kind) {
            return (declared & kind) != 0;
        }

        @Override
        public void lightning(double x, double y, double z) {
            if (Math.hypot(x - lightningX, z - lightningZ) < 24) lightning = true;
        }

        @Override
        public void blockBroken(int x, int y, int z, int surface, int mapColor) {
            if (x != blockX || y != blockY || z != blockZ) return;
            block = surface == CgWorldQuery.SURFACE_STONE && mapColor != 0;
            blockDetail = String.format("surface %d, map colour %08x", surface, mapColor);
        }

        @Override
        public void explosion(double x, double y, double z, float power) {
            if (Math.abs(x - blastX) > 2 || Math.abs(y - blastY) > 3 || Math.abs(z - blastZ) > 2) return;
            explosion = Float.isNaN(power) || Math.abs(power - 4f) < 0.5f;
            explosionDetail = String.format("at %.1f %.1f %.1f, power %s", x, y, z, power);
        }

        @Override
        public void entityHurt(int id, double x, double y, double z) {
            if (id == player) return;
            if (near(x, y, z)) hurt = true;
            else heardFar = String.format("hurt %d at %.1f %.1f %.1f", id, x, y, z);
        }

        @Override
        public void entityDied(int id, double x, double y, double z) {
            if (id == player) return;
            if (near(x, y, z)) died = true;
            else heardFar = String.format("died %d at %.1f %.1f %.1f", id, x, y, z);
        }

        // Wide: a pig on a fuseless TNT is thrown several blocks before a slow frame polls it.
        private boolean near(double x, double y, double z) {
            return Math.abs(x - blastX) < 24 && Math.abs(y - blastY) < 24 && Math.abs(z - blastZ) < 24;
        }

        void report() {
            event("events.lightning", CgWorldEvents.LIGHTNING, null, lightning, "within 24 blocks of the bolt");
            event("events.block-broken", CgWorldEvents.BLOCK_BROKEN, blockSkipped, block, blockDetail);
            event("events.explosion", CgWorldEvents.EXPLOSION, blastSkipped, explosion, explosionDetail);
            event("events.entity-hurt", CgWorldEvents.ENTITY_HURT, blastSkipped, hurt, "the pig beside the blast");
            event("events.entity-died", CgWorldEvents.ENTITY_DIED, blastSkipped, died, "the pig beside the blast");
            if (blastSkipped == null && unavailable == null && (!hurt || !died)) {
                info("events.beside-blast", livingNearBlast() + "; heard elsewhere: " + heardFar);
            }
        }

        /** What a missed pig left: the living things the client holds round the blast now. */
        private String livingNearBlast() {
            CgEntityQuery entities = CgPlatform.get(CgEntityQuery.SERVICE);
            StringBuilder out = new StringBuilder();
            int[] count = new int[1];
            entities.within(blastX - 48, blastY - 48, blastZ - 48, blastX + 48, blastY + 48, blastZ + 48, id -> {
                if (id == player || (entities.flags(id) & CgEntityQuery.LIVING) == 0 || count[0]++ >= 4) return;
                entities.pose(id, 1f, pose);
                out.append(String.format(" [%d kind %d at %.1f %.1f %.1f, alive %b]", id, entities.kind(id),
                        pose[CgEntityQuery.X], pose[CgEntityQuery.Y], pose[CgEntityQuery.Z],
                        (entities.flags(id) & CgEntityQuery.ALIVE) != 0));
            });
            return count[0] + " living within 48 blocks" + out;
        }

        private void event(String name, int kind, String skippedBecause, boolean heard, String detail) {
            if (unavailable != null) skip(name, unavailable);
            else if (!wants(kind)) skip(name, "not reported on this version");
            else if (skippedBecause != null) skip(name, skippedBecause);
            else check(name, heard, detail);
        }
    }

    // ── Reporting ────────────────────────────────────────────────────────────────

    private static void check(String name, boolean ok, String detail) {
        if (ok) passed++;
        else failed++;
        LOGGER.info("world probe {}: {} ({})", name, ok, detail);
    }

    private static void skip(String name, String reason) {
        skipped++;
        LOGGER.info("world probe {}: skipped ({})", name, reason);
    }

    // "is", never ": ": a reader takes "name: false" for a failed check.
    private static void info(String name, String detail) {
        LOGGER.info("world probe {} is {}", name, detail);
    }

    private static void next(Phase to, long now) {
        phase = to;
        phaseNanos = now;
    }

    private static double seconds(long now) {
        return (now - phaseNanos) / 1.0e9;
    }

    private static boolean inUnit(float v) {
        return v >= 0f && v <= 1f;
    }

    private static double frac(double v) {
        return v - Math.floor(v);
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }
}
