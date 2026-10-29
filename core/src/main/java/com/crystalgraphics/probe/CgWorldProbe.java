package com.crystalgraphics.probe;

import java.nio.file.Paths;

import com.crystalgraphics.audio.CgAudioEngine;
import com.crystalgraphics.audio.CgAudioTone;
import com.crystalgraphics.audio.CgHostSoundEvents;
import com.crystalgraphics.audio.CgKitSounds;
import com.crystalgraphics.audio.CgSoundRef;
import com.crystalgraphics.audio.CgSound;
import com.crystalgraphics.audio.CgSoundAttenuation;
import com.crystalgraphics.audio.CgSoundClass;
import com.crystalgraphics.audio.CgSoundMix;
import com.crystalgraphics.audio.CgSoundStream;
import com.crystalgraphics.camera.CgCameraModifier;
import com.crystalgraphics.camera.CgCameraSystem;
import com.crystalgraphics.collision.CgCollisionWorld;
import com.crystalgraphics.collision.hit.CgHit;
import com.crystalgraphics.frame.CgTickGroup;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.audio.CgHostDuck;
import com.crystalgraphics.platform.audio.CgHostVolume;
import com.crystalgraphics.api.text.CgI18n;
import com.crystalgraphics.settings.CgGraphicsSettings;
import com.crystalgraphics.platform.service.CgGameDirectory;
import com.crystalgraphics.platform.world.CgEntityQuery;
import com.crystalgraphics.platform.world.CgHostCamera;
import com.crystalgraphics.platform.world.CgWorldBegin;
import com.crystalgraphics.platform.world.CgWorldEvents;
import com.crystalgraphics.platform.world.CgWorldQuery;
import com.crystalgraphics.platform.world.CgWorldStimulus;
import com.crystalgraphics.render.stage.CgHostEnvironment;
import com.crystalgraphics.render.stage.CgHostFrame;
import com.crystalgraphics.render.stage.CgHostTextures;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.settings.CgGraphicsSettings;
import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.camera.CgCameraShake;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.vfx.particle.spawn.CgVfxEmitterState;
import com.crystalgraphics.world.CgCamera;
import com.crystalgraphics.world.CgEntityQueries;
import com.crystalgraphics.world.CgWorldQueries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.AxisAngle4f;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import java.nio.file.Files;
import java.nio.file.Path;

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
 *   <li>It writes CrystalGraphics' settings file if there is none, and sets the density for one check, putting it back
 *       after.</li>
 * </ul>
 */
public final class CgWorldProbe {

    public static final boolean ENABLED = CgVar.bool("crystalgraphics.worldprobe", false, CgVar.DEV | CgVar.RESTART,
            "Prove in a running game that the host answers every world seam and that the answers agree, logging a verdict"
                    + " per check for prodSmoke and a dev client's autotest.").get();

    private static final Logger LOGGER = LogManager.getLogger("CgWorldProbe");
    private static final int SETTLE_SECONDS = 3, MIN_EVENT_SECONDS = 4, EVENT_SECONDS = 12, PAUSED_SECONDS = 15;
    private static final float FOV_SCALE = 1.25f, YAW = 10f, ROLL = 10f;
    private static final int CAMERA_FRAMES = 4;
    /** The hold: frames held, the frame the probe turns and presses, the frame it measures, frames to release. */
    private static final int HOLD_FRAMES = 40, HOLD_EARLY = 3, HOLD_STIMULUS = 10, HOLD_MEASURE = 20, HOLD_RELEASE = 4;
    private static final float HOLD_TURN = 30f;
    private static final Matrix4fc IDENTITY = new Matrix4f();

    private enum Phase { WAITING, SETTLING, CAMERA, HOLD, EVENTS, DONE }

    private static boolean installed;
    private static Phase phase = Phase.WAITING;
    private static long phaseNanos, settleNanos;
    private static int passed, failed, skipped, cameraFrames;
    private static int cameraBits, cameraStep;
    /** The same-frame check: armed for the next SHAKE, then the ring frame it turned the camera in. */
    private static volatile boolean sameFrameArmed;
    private static long sameFrameAt = -1, lateAtStart;
    /** {@link CgCamera#writes()} after the probe's own last offset. */
    private static int ownWrite;
    private static float baseFov;
    private static final Matrix3f baseView = new Matrix3f();
    private static long startGameTime;
    private static int startTextures, startEpoch;
    private static final double[] pose = new double[CgEntityQuery.POSE_LENGTH];
    private static final double[] holdAt = new double[3];
    private static final Vector3f holdLook = new Vector3f();
    private static int holdFrames, holdPerspective;
    private static float holdYaw;
    private static boolean holdPressed;
    private static CgCameraSystem holdCameras;
    /** The probe's hold: a modifier setting the whole pose, through the camera system's own hand-off. */
    private static final CgCameraModifier HOLD = (p, dt) -> {
        p.location(holdAt[0], holdAt[1], holdAt[2]).fov(70f);
        p.rotation().rotationTo(1f, 0f, 0f, holdLook.x, holdLook.y, holdLook.z);
    };
    private static final Events events = new Events();
    /** The audio check: a quiet tone through the engine's own output, from the static checks to the result. */
    private static CgAudioEngine audio;
    private static final CgAudioTone TONE = new CgAudioTone(440, 0.05f, 0);
    private static long audioStart;
    /** The stream check: a long vanilla track streamed through the host's decoder, the frame stalled once. */
    /** The one long track in every era's assets, 1.7.10 to 26.x. */
    private static final String MUSIC = "minecraft:sounds/music/game/end/boss.ogg";
    private static final int STALL_MILLIS = 200;
    private static long musicId;
    /** Frames the game was paused while the music streamed: it holds through them. */
    private static int musicPausedFrames;
    private static final String CLICK = "minecraft:sounds/random/click.ogg";
    private static final float MUFFLED_HZ = 900;
    private static final CgSoundAttenuation MUFFLED = new CgSoundAttenuation().occlusion(true).occlusionLowPass(MUFFLED_HZ);
    /** Two quiet clicks, one a block into the ground under the camera and one six above it: the ground occludes one. */
    private static long buried, open;
    private static int occlusionFrames;
    /** A cinematic's hush on the host's blocks, pushed with the audio, popped 30 frames on. */
    private static final CgSoundMix HUSH = new CgSoundMix(0f, 0f).duckHost(CgHostVolume.Category.BLOCKS, 0.25f);
    private static int hushFrames = -1;
    private static float hushHeld = Float.NaN;
    /** The explosion's subtitle: the host's, where its event has one, else one the probe queues; then what showed. */
    private static String subtitleWanted, subtitleSource, subtitleShown;
    private static int subtitleDrawn = -1;
    private static long explosionId;
    private static String subtitleState = "";
    /** How often the HUD stage fired: none while a screen hides the HUD (CrystalGUI's desktop does from 1.21.6). */
    private static int hudFirings;
    private static CgRenderStage.Registration hudCount;
    private static CgSoundStream music;
    private static long musicStart;
    private static double stalledMillis = -1;

    private CgWorldProbe() {
    }

    /** Called once with the engine; registers on the opaque world stage when {@link #ENABLED}. */
    public static void installIfEnabled() {
        if (!ENABLED || installed) return;
        installed = true;
        CgRenderStage.WORLD_OPAQUE.register(Integer.MAX_VALUE, CgWorldProbe::frame);
        // Where a shake is summed: an offset set here must turn the camera of this same frame.
        CgWorld.client().schedule().add(CgTickGroup.SHAKE, "crystalgraphics:world-probe", dt -> turnInShake()).whilePaused(true);
        LOGGER.info("world probe: armed");
    }

    /** True from the start of its checks to its result: an autotest that quits now loses the result. */
    public static boolean running() {
        return phase != Phase.WAITING && phase != Phase.DONE;
    }

    private static void frame(CgStageFrame stage) {
        if (phase == Phase.DONE) return;
        CgHostFrame host = CgRenderStage.WORLD_OPAQUE.host();
        CgEntityQuery entities = CgEntityQueries.client();
        long now = System.nanoTime();
        switch (phase) {
            case WAITING:
                if (entities.localPlayer() < 0) break;
                CgPlatform.get(CgWorldStimulus.SERVICE).keepRunning();
                // The first read writes a missing file within a frame: there by the time settingsChecks looks.
                CgGraphicsSettings.DENSITY.get();
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
                if (cameraStep == 3) {
                    if (sameFrameAt >= 0) checkSameFrame(host, now);
                    break;
                }
                if (++cameraFrames < CAMERA_FRAMES) break;
                if (cameraStep == 2) {
                    sameFrameArmed = true;
                    cameraStep = 3;
                    break;
                }
                if (cameraStep == 0) {
                    checkTurn(host);
                    if (has(CgHostCamera.ROLL)) {
                        offset(0f, ROLL, 1f);
                        cameraStep = 1;
                        cameraFrames = 0;
                        break;
                    }
                } else {
                    checkRoll(host);
                }
                if (cameraBits != 0) offset(0f, 0f, 1f);
                if (has(CgHostCamera.ROTATION)) {
                    // Settle at zero, then turn from SHAKE and look in the same frame.
                    cameraStep = 2;
                    cameraFrames = 0;
                    break;
                }
                skip("camera.same-frame", "no rotation applied on this version");
                startHold(host, now);
                break;
            case HOLD:
                hold(host, now);
                break;
            case EVENTS:
                // At least a few seconds, though the events land sooner: the server corrects the client's game time
                // once a second, which can step it back across a shorter window.
                events.watch();
                stallOnce();
                occlusionOnce();
                hushOnce();
                double inEvents = seconds(now);
                if ((events.complete() && inEvents >= MIN_EVENT_SECONDS) || inEvents >= EVENT_SECONDS) finish(host);
                break;
            default:
                break;
        }
    }

    // ── The checks made on one frame ─────────────────────────────────────────────

    private static void staticChecks(CgHostFrame host) {
        CgWorldQuery world = CgWorldQueries.client();
        CgEntityQuery entities = CgEntityQueries.client();
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
            // The block's real shapes, against the scan's spans.
            CgHit hit = new CgHit();
            boolean struck = CgCollisionWorld.of(world, CgEntityQuery.NONE).ray().at(px, py + eye, pz)
                    .along(0, -1, 0, 80).blocksOnly().first(hit) && hit.kind() == CgHit.Kind.BLOCK;
            check("world.raycast", struck && Math.abs(hit.y() - ground) < 0.08,
                    String.format("ray hit %s at %.3f, ground %.3f", struck, hit.y(), ground));
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
        settingsChecks(env);

        CgHostTextures textures = host.textures();
        check("textures.block-atlas", textures.blockAtlas() != 0, "atlas " + textures.blockAtlas());
        info("textures.lightmap", textures.lightmap() != 0 ? "present" : "absent on this version");
        startTextures = textures.version();
        startGameTime = env.gameTime();

        startAudio(px, py, pz);
    }

    private static void startAudio(double px, double py, double pz) {
        audio = CgWorld.client().subsystem(CgAudioEngine.class);
        if (audio == null) audio = CgWorld.client().add(new CgAudioEngine());
        if (!audio.active()) {
            skip("audio.plays", "no audio output on this host");
            audio = null;
            return;
        }
        audio.play(TONE, 1f);
        // The HUD fires from the first frame; the probe's own frames may begin after a short sound has ended.
        if (hudCount == null) hudCount = CgRenderStage.HUD.register(0, frame -> {
            hudFirings++;
            if (subtitleShown == null && audio != null) subtitleShown = audio.subtitles().text();
        });
        hostEvent(px, py, pz);
        audio.push(HUSH);
        hushFrames = 0;
        kitCue(px, py, pz);
        audioStart = System.nanoTime();
        try {
            CgSound track = audio.sounds().load(MUSIC, true);
            musicId = audio.play(track, CgSoundClass.MUSIC, 0.05f);
            musicStart = System.nanoTime();
            info("audio.stream-source", track.id() + ", " + String.format("%.1f", track.seconds()) + " s at "
                    + track.sampleRate() + " Hz, " + track.channels() + " channel(s)");
        } catch (RuntimeException e) {
            skip("audio.stream", "the track did not load: " + e.getMessage());
        }
    }

    /** Minecraft's explosion through our mixer: its {@code sounds.json} read, a variant picked, loaded and placed. */
    private static void hostEvent(double px, double py, double pz) {
        CgHostSoundEvents events = CgHostSoundEvents.current();
        String event = events.has("minecraft:entity.generic.explode") ? "minecraft:entity.generic.explode" : "minecraft:random.explode";
        int count = 0;
        for (String ignored : events.events()) count++;
        try {
            CgHostSoundEvents.Variant v = events.pick(event);
            long id = audio.playEvent(event, px, py, pz, 0.05f, 1f);
            explosionId = id;
            CgSound file = audio.sounds().load(v.path(), v.stream());
            String key = events.subtitle(event);
            subtitleWanted = key != null ? CgI18n.translate(key) : null;
            subtitleSource = key != null ? "the host's " + key : "queued by the probe (this version's sounds.json has none)";
            if (subtitleWanted == null) {
                subtitleWanted = "Probe explosion";
                audio.subtitles().queue(id, subtitleWanted, file.seconds());
            }
            subtitleState = audio.subtitles().count() + " queued at the play of " + String.format("%.2f", file.seconds()) + " s";
            check("audio.host-event", id != 0 && file.seconds() > 0, count + " events read; " + event + " played "
                    + v.path() + ", " + String.format("%.2f", file.seconds()) + " s");
        } catch (RuntimeException e) {
            check("audio.host-event", false, count + " events read; " + event + " threw " + e);
        }
    }

    /**
     * A game's sound by its cue name, through its kit's table, where {@code -Dcrystalgraphics.worldprobe.kitCue} names
     * {@code <kit folder>|<cue>}: the table read, the cue resolved to its file, decoded and played.
     */
    private static void kitCue(double px, double py, double pz) {
        String spec = System.getProperty("crystalgraphics.worldprobe.kitCue");
        if (spec == null || spec.indexOf('|') < 0) {
            skip("audio.kit-cue", "no -Dcrystalgraphics.worldprobe.kitCue=<kit folder>|<cue>");
            return;
        }
        String folder = spec.substring(0, spec.indexOf('|')), cue = spec.substring(spec.indexOf('|') + 1);
        try {
            CgKitSounds.registerKit(Paths.get(folder));
            CgSoundRef ref = CgSoundRef.of(cue, "the probe's kit cue");
            long id = audio.play(ref, CgSoundClass.SFX, px, py, pz, 0.05f, 1f, false, Double.NaN, 0);
            CgSound file = audio.sounds().load(ref.id(), ref.file(), ref.stream());
            check("audio.kit-cue", id != 0 && file.seconds() > 0, cue + " played " + ref.file().getFileName() + ", "
                    + String.format("%.2f", file.seconds()) + " s at " + file.sampleRate() + " Hz");
        } catch (RuntimeException e) {
            check("audio.kit-cue", false, cue + " threw " + e);
        }
    }

    /** Thirty frames into the hush: what the host holds for its blocks, then the hush popped. */
    private static void hushOnce() {
        if (audio == null || hushFrames < 0) return;
        hushFrames++;
        // The first line shown, on whichever frame: a world's first frames can take seconds, a short sound's whole life.
        if (subtitleShown == null && hushFrames <= 30) {
            subtitleShown = audio.subtitles().text();
            if (subtitleShown != null || hushFrames == 1) {
                subtitleState += ", " + audio.subtitles().count() + " at frame " + hushFrames + " (the explosion "
                        + (audio.activeSounds().playing(explosionId) ? "at " + String.format("%.2f",
                        audio.activeSounds().time(explosionId)) + " s" : "stopped") + ")";
            }
        }
        if (hushFrames != 30) return;
        hushHeld = CgPlatform.get(CgHostDuck.SERVICE).factor(CgHostVolume.Category.BLOCKS);
        audio.pop(HUSH);
    }

    /** Once the listener is the camera: the clicks placed below and above it. */
    private static void occlusionOnce() {
        if (audio == null || buried != 0 || occlusionFrames++ < 2) return;
        try {
            CgSound click = audio.sounds().load(CLICK);
            double x = audio.listener().x(), y = audio.listener().y(), z = audio.listener().z();
            CgHit ground = new CgHit();
            if (!CgCollisionWorld.client(1f).ray().at(x, y, z).along(0, -1, 0, 64).blocksOnly().first(ground)) {
                skip("audio.occlusion", "no ground within 64 blocks under the camera");
                buried = -1;
                return;
            }
            buried = audio.play(click, CgSoundClass.SFX, 0.01f, 1f, true, Double.NaN);
            open = audio.play(click, CgSoundClass.SFX, 0.01f, 1f, true, Double.NaN);
            audio.activeSounds().at(buried, x, ground.y() - 1, z).attenuation(buried, MUFFLED);
            audio.activeSounds().at(open, x, y + 6, z).attenuation(open, MUFFLED);
        } catch (RuntimeException e) {
            skip("audio.occlusion", "the click did not load: " + e.getMessage());
            buried = -1;
        }
    }

    /** Once, a second into the stream: the render thread held, as a hitch holds it. */
    private static void stallOnce() {
        if (music != null && CgWorld.client().paused()) musicPausedFrames++;
        // Its voice is given on the frame after the play.
        if (music == null && musicId != 0) music = (CgSoundStream) audio.activeSounds().voice(musicId);
        if (music == null || stalledMillis >= 0 || (System.nanoTime() - musicStart) / 1.0e9 < 1) return;
        long t = System.nanoTime();
        try {
            Thread.sleep(STALL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        stalledMillis = (System.nanoTime() - t) / 1.0e6;
    }

    private static void audioChecks() {
        if (audio == null) return;
        audio.stop(TONE);
        double seconds = (System.nanoTime() - audioStart) / 1.0e9;
        long expected = (long) (seconds / CgAudioEngine.FORMAT.bufferSeconds());
        check("audio.plays", audio.opens() >= 1 && audio.buffers() >= expected * 8 / 10,
                audio.buffers() + " buffers in " + String.format("%.1f", seconds) + " s, " + expected + " at real time, "
                        + audio.opens() + " stream(s) opened; whether it was heard is not checked");
        // Ordinary play's pauses, not a forced collection: the queue is sized to what play does.
        check("audio.underruns", audio.underruns() == 0, audio.underruns() + " underruns; the audio thread's longest stall "
                + String.format("%.1f", audio.longestStallMillis()) + " ms against a queue of "
                + String.format("%.0f", CgAudioEngine.FORMAT.buffers() * CgAudioEngine.FORMAT.bufferSeconds() * 1000) + " ms");
        boolean stillPlaying = audio.activeSounds().playing(musicId), stillVirtual = audio.activeSounds().virtual(musicId);
        boolean sameVoice = music != null && audio.activeSounds().voice(musicId) == music;
        if (music != null && musicPausedFrames > 0) {
            audio.activeSounds().stop(musicId);
            skip("audio.stream", "the game paused for " + musicPausedFrames + " frames while it streamed, and music holds through a pause");
        } else if (music != null) {
            audio.activeSounds().stop(musicId);
            double streamed = (System.nanoTime() - musicStart) / 1.0e9;
            // A sleep on Windows can wake a millisecond early.
            check("audio.stream", music.starved() == 0 && music.played() >= streamed * 0.8 && stalledMillis >= STALL_MILLIS * 0.98,
                    String.format("%.1f s played of %.1f s; dry in %d buffers; the frame stalled %.0f ms", music.played(),
                            streamed, music.starved(), stalledMillis));
            info("audio.stream-state", "id playing " + stillPlaying + ", virtual " + stillVirtual + ", same voice " + sameVoice);

        }
        if (buried > 0) {
            float under = audio.activeSounds().occlusionHz(buried), over = audio.activeSounds().occlusionHz(open);
            audio.activeSounds().stop(buried);
            audio.activeSounds().stop(open);
            // A camera with no ground six blocks under it, or a roof six above, cannot tell.
            check("audio.occlusion", under <= MUFFLED_HZ + 1 && over >= CgSoundAttenuation.MAX_FILTER_HZ - 1,
                    "a sound in the ground low-passed to " + String.format("%.0f", under) + " Hz, one in the air "
                            + String.format("%.0f", over) + " Hz; the listener at " + String.format("%.1f, %.1f, %.1f",
                            audio.listener().x(), audio.listener().y(), audio.listener().z()));
        }
        if (hushFrames >= 30) {
            float now = CgPlatform.get(CgHostDuck.SERVICE).factor(CgHostVolume.Category.BLOCKS);
            check("audio.host-duck", Math.abs(hushHeld - 0.25f) < 1e-3f && now == 1f, "the host's blocks held at "
                    + hushHeld + " while hushed, " + now + " after; its sliders untouched at "
                    + CgPlatform.get(CgHostVolume.SERVICE).category(CgHostVolume.Category.BLOCKS));
        } else {
            skip("audio.host-duck", "the probe ended " + hushFrames + " frames into the hush");
        }
        if (hushFrames < 1) {
            skip("audio.subtitles", "the probe ended before the subtitle was read");
        } else {
            subtitleDrawn = audio.subtitleFramesDrawn();
            boolean drawn = !CgGraphicsSettings.SUBTITLES.get() || hudFirings == 0 || subtitleDrawn > 0;
            check("audio.subtitles", subtitleWanted != null && subtitleWanted.equals(subtitleShown) && drawn, "showing '" + subtitleShown
                    + "', wanted '" + subtitleWanted + "', " + subtitleSource + "; " + subtitleState + "; drawn over the HUD on " + subtitleDrawn
                    + " frames of the HUD stage's " + hudFirings + (CgGraphicsSettings.SUBTITLES.get() ? "" : " (subtitles off: not drawn)")
                    + (hudFirings == 0 ? " (the HUD hidden: a screen is up)" : ""));
        }
        float master = CgPlatform.get(CgHostVolume.SERVICE).master();
        check("audio.follows-master", audio.main() == master, "main gain " + audio.main() + ", master slider " + master);
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

    // ── Our settings ─────────────────────────────────────────────────────────────

    private static void settingsChecks(CgHostEnvironment env) {
        Path file = CgGraphicsSettings.FILE.path();
        Path game = CgPlatform.get(CgGameDirectory.SERVICE).get();
        check("settings.file", file.startsWith(game) && Files.isRegularFile(file), file + " under " + game);

        // At density 0.5 an emitter spawns half its particles, whatever Minecraft's Particles says.
        float before = CgGraphicsSettings.DENSITY.get();
        CgGraphicsSettings.DENSITY.set(0.5f);
        CgVfxSystem vfx = new CgVfxSystem();
        vfx.update(0.0);
        float share = vfx.spawnShare(CgVfxEmitter.builder("probe").state(CgVfxEmitterState.ONCE)
                .capacity(1000).burst(0f, 1000).build());
        vfx.delete();
        CgGraphicsSettings.DENSITY.set(before);
        boolean followed = CgGraphicsSettings.FOLLOW_MINECRAFT_PARTICLES.get();
        check("vfx.density", followed || share == 0.5f, "a share of " + share + " at density 0.5; Minecraft's Particles "
                + env.particles() + ", followed " + followed);

        float pace = CgVfxSystem.pace(env);
        boolean still = env.paused() || env.frozen();
        check("vfx.pace", still ? pace == 0f : pace > 0f, "pace " + pace + ", paused " + env.paused() + ", tick rate "
                + env.tickRate() + ", frozen " + env.frozen());
    }

    // ── The camera offset ────────────────────────────────────────────────────────

    private static void startCamera(CgHostFrame host, long now) {
        cameraBits = CgCamera.capabilities();
        if (CgCameraShake.active()) {
            skip("camera", "a shake owns the camera");
            cameraBits = 0;
        } else if (cameraBits == 0) {
            skip("camera", "this version applies no camera offset");
        } else {
            baseFov = host.environment().fov();
            baseView.set(host.view().view());
            offset(has(CgHostCamera.ROTATION) ? YAW : 0f, 0f, has(CgHostCamera.FOV) ? FOV_SCALE : 1f);
        }
        cameraStep = 0;
        cameraFrames = 0;
        sameFrameAt = -1;
        lateAtStart = CgWorld.client().beganLate();
        next(Phase.CAMERA, now);
    }

    // Yaw first, with the FOV, then roll alone: each is then one rotation between the views before and after, measured
    // whatever the player's pitch. A host may turn about the view's vertical or the world's; both pass.
    private static void checkTurn(CgHostFrame host) {
        if (cameraBits == 0 || shakeTook("camera.fov", "camera.yaw")) return;
        int applied = CgCamera.applied();
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

    /** In SHAKE, once armed: the base is the view BEGIN sees, last frame's, and the yaw goes to the host now. */
    private static void turnInShake() {
        if (!sameFrameArmed) return;
        sameFrameArmed = false;
        baseView.set(CgRenderStage.WORLD_OPAQUE.host().view().view());
        offset(YAW, 0f, 1f);
        sameFrameAt = CgFrameRing.frame();
    }

    /** The opaque firing after that SHAKE: its frame's view carries the whole turn, or the offset landed a frame late. */
    private static void checkSameFrame(CgHostFrame host, long now) {
        if (!shakeTook("camera.same-frame")) {
            boolean same = CgFrameRing.frame() == sameFrameAt;
            float degrees = (float) Math.toDegrees(turnSinceBase(host.view().view()).angle);
            check("camera.same-frame", same && Math.abs(degrees - YAW) < 1.5f, String.format(
                    "turned %.2f degrees in the frame the offset was set, %s", degrees, same ? "the same ring frame" : "a later ring frame"));
        }
        offset(0f, 0f, 1f);
        startHold(host, now);
    }

    // ── The camera hold ──────────────────────────────────────────────────────────

    /** Holds a pose 6 blocks up and 4 ahead of the player, turned 30 degrees from its look. */
    private static void startHold(CgHostFrame host, long now) {
        int bits = CgCamera.capabilities();
        info("camera-hold.capabilities", Integer.toBinaryString(bits));
        CgEntityQuery entities = CgEntityQueries.client();
        int player = entities.localPlayer();
        if ((bits & CgHostCamera.POSITION) == 0 || !entities.pose(player, host.partialTick(), pose)) {
            skip("camera-hold", (bits & CgHostCamera.POSITION) == 0 ? "this version holds no camera" : "no player pose");
            startEvents(now);
            return;
        }
        holdYaw = (float) pose[CgEntityQuery.YAW];
        double yaw = Math.toRadians(holdYaw), turned = Math.toRadians(holdYaw + HOLD_TURN);
        // Minecraft's yaw: 0 faces +z, growing to the west.
        holdAt[0] = pose[CgEntityQuery.X] - Math.sin(yaw) * 4.0;
        holdAt[1] = pose[CgEntityQuery.Y] + pose[CgEntityQuery.EYE_HEIGHT] + 6.0;
        holdAt[2] = pose[CgEntityQuery.Z] + Math.cos(yaw) * 4.0;
        holdLook.set((float) -Math.sin(turned), 0f, (float) Math.cos(turned));
        holdPerspective = host.environment().perspective();
        holdCameras = CgWorld.client().subsystem(CgCameraSystem.class);
        if (holdCameras == null) holdCameras = CgWorld.client().add(new CgCameraSystem());
        holdCameras.add(HOLD);
        holdFrames = 0;
        holdPressed = false;
        next(Phase.HOLD, now);
    }

    private static void hold(CgHostFrame host, long now) {
        holdFrames++;
        CgWorldStimulus stimulus = CgPlatform.get(CgWorldStimulus.SERVICE);
        CgEntityQuery entities = CgEntityQueries.client();
        int player = entities.localPlayer();
        if (holdFrames == HOLD_EARLY) {
            // Inside the first ticks, while the host's camera eases its eye height toward the held entity's.
            CgHostView view = host.view();
            double dx = view.x() - holdAt[0], dy = view.y() - holdAt[1], dz = view.z() - holdAt[2];
            boolean flat = Math.abs(dx) < 1e-3 && Math.abs(dz) < 1e-3;
            check("camera-hold.position-at-take", flat && (Math.abs(dy) < 1e-3 || dy < -0.5 && dy > -2.0),
                    String.format("view %.4f %.4f %.4f, pose %.4f %.4f %.4f, %.4f in height", view.x(), view.y(), view.z(),
                            holdAt[0], holdAt[1], holdAt[2], dy));
        } else if (holdFrames == HOLD_STIMULUS) {
            stimulus.turnLook(20f);
            holdPressed = stimulus.pressJump();
        } else if (holdFrames == HOLD_MEASURE) {
            CgHostView view = host.view();
            double dx = view.x() - holdAt[0], dy = view.y() - holdAt[1], dz = view.z() - holdAt[2];
            // 1.13 puts the view at the camera entity's feet and its eye in the matrix: a whole eye height below the pose.
            boolean flat = Math.abs(dx) < 1e-3 && Math.abs(dz) < 1e-3;
            boolean atEye = Math.abs(dy) < 1e-3, atFeet = dy < -0.5 && dy > -2.0;
            check("camera-hold.position", flat && (atEye || atFeet), String.format(
                    "view %.4f %.4f %.4f, pose %.4f %.4f %.4f, %s", view.x(), view.y(), view.z(), holdAt[0], holdAt[1], holdAt[2],
                    !flat ? "off across" : atEye ? "at the eye" : atFeet ? "eye in the matrix" : "off in height"));
            // A shake's offset turns a held camera on top of its pose, as it should: the turn is measured without one.
            if (!shakeTook("camera-hold.turn")) {
                Matrix4fc m = view.view();
                Vector3f forward = new Vector3f(-m.m02(), -m.m12(), -m.m22()).normalize();
                float apart = (float) Math.toDegrees(forward.angle(holdLook));
                check("camera-hold.turn", apart < 0.5f, String.format("the rendered forward is %.3f degrees from the pose's, "
                        + "no shake playing", apart));
                // A wrong turn either way: where it was drawn against the pose, the player and the camera entity, in
                // Minecraft's yaw (0 faces +z).
                if (apart >= 0.5f) {
                    double cameraYaw = entities.pose(entities.cameraEntity(), host.partialTick(), pose) ? pose[CgEntityQuery.YAW] : Double.NaN;
                    double playerYaw = entities.pose(player, host.partialTick(), pose) ? pose[CgEntityQuery.YAW] : Double.NaN;
                    info("camera-hold.turn-yaws", String.format("drawn %.2f, pose %.2f, player %.2f, camera entity %.2f",
                            Math.toDegrees(Math.atan2(-forward.x, forward.z)), Math.toDegrees(Math.atan2(-holdLook.x, holdLook.z)),
                            playerYaw, cameraYaw));
                }
            }
            if (entities.pose(player, host.partialTick(), pose)) {
                float yaw = (float) pose[CgEntityQuery.YAW];
                check("camera-hold.look-locked", Math.abs(yaw - holdYaw) < 1e-3f,
                        String.format("the player's yaw %.3f, %.3f when the hold began", yaw, holdYaw));
            }
            if (holdPressed) {
                check("camera-hold.keys-locked", !stimulus.jumpDown(), "jump pressed during the hold, read at a later tick");
            } else {
                skip("camera-hold.keys-locked", "this version presses no key for the probe");
            }
        } else if (holdFrames == HOLD_FRAMES) {
            holdCameras.remove(HOLD);
        } else if (holdFrames == HOLD_FRAMES + HOLD_RELEASE) {
            check("camera-hold.released", entities.cameraEntity() == player && !CgCamera.held()
                    && host.environment().perspective() == holdPerspective, String.format("camera entity %d, player %d, held %b, "
                    + "perspective %d, %d before", entities.cameraEntity(), player, CgCamera.held(),
                    host.environment().perspective(), holdPerspective));
            startEvents(now);
        }
    }

    /**
     * A shake playing, or one that started and ended since the probe's offset (its end writes zeros), leaves a view that
     * measures the shake: skipped, not failed.
     */
    private static boolean shakeTook(String... names) {
        if (!CgCameraShake.active() && CgCamera.writes() == ownWrite) return false;
        for (String name : names) skip(name, "a shake took the camera during the check");
        return true;
    }

    private static void offset(float yaw, float roll, float fovScale) {
        CgCamera.offset(0f, 0f, 0f, yaw, 0f, roll, fovScale);
        ownWrite = CgCamera.writes();
    }

    private static void checkRoll(CgHostFrame host) {
        if (shakeTook("camera.roll")) return;
        AxisAngle4f turn = turnSinceBase(host.view().view());
        float degrees = (float) Math.toDegrees(turn.angle);
        check("camera.roll", Math.abs(degrees - ROLL) < 1.5f && Math.abs(turn.z) > 0.98f, String.format(
                "rolled %.2f degrees about an axis %.3f along the view, %s", degrees, Math.abs(turn.z),
                ran(CgCamera.applied(), CgHostCamera.ROLL)));
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
        CgWorldQuery world = CgWorldQueries.client();
        CgWorldStimulus stimulus = CgPlatform.get(CgWorldStimulus.SERVICE);
        double px = pose[CgEntityQuery.X], py = pose[CgEntityQuery.Y], pz = pose[CgEntityQuery.Z];
        events.reset(CgWorldEvents.declared());
        events.player = CgEntityQueries.client().localPlayer();
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
        CgWorldQuery world = CgWorldQueries.client();
        CgHostEnvironment env = host.environment();
        if (env.paused()) {
            skip("env.time-advances", "the game is paused");
        } else {
            check("env.time-advances", env.gameTime() > startGameTime, "game time " + startGameTime + " to " + env.gameTime());
        }
        check("world.epoch-stable", world.levelEpoch() == startEpoch, "epoch " + startEpoch + " to " + world.levelEpoch());
        check("textures.stable", host.textures().version() == startTextures,
                "version " + startTextures + " to " + host.textures().version());
        if (CgWorldBegin.isAtStage()) {
            skip("frame.begin", "this host has no camera hook");
        } else {
            long late = CgWorld.client().beganLate() - lateAtStart;
            check("frame.begin", late == 0, late + " frames ran their world before the camera hook since the probe started");
        }
        audioChecks();
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
        String blockDetail = "none", explosionDetail = "none", heardFar = "none", boltFar = "none";
        // What the client held, watched each frame apart from the events: did the pig and the bolt reach it at all.
        boolean pigSeen, pigDead, boltSeen;
        String boltSeenAs = "";

        void reset(int kinds) {
            declared = kinds;
            unavailable = blockSkipped = blastSkipped = null;
            lightning = block = explosion = hurt = died = false;
            heardFar = boltFar = "none";
            pigSeen = pigDead = boltSeen = false;
            boltSeenAs = "";
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
            else boltFar = String.format("a bolt at %.1f %.1f %.1f", x, y, z);
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
            if (!lightning && unavailable == null && wants(CgWorldEvents.LIGHTNING)) {
                info("events.lightning-elsewhere", boltFar + String.format(", asked at %.1f %.1f", lightningX, lightningZ)
                        + "; an entity at the bolt on the client: " + boltSeen + " " + boltSeenAs);
            }
            event("events.block-broken", CgWorldEvents.BLOCK_BROKEN, blockSkipped, block, blockDetail);
            event("events.explosion", CgWorldEvents.EXPLOSION, blastSkipped, explosion, explosionDetail);
            event("events.entity-hurt", CgWorldEvents.ENTITY_HURT, blastSkipped, hurt, "the pig beside the blast");
            event("events.entity-died", CgWorldEvents.ENTITY_DIED, blastSkipped, died, "the pig beside the blast");
            if (blastSkipped == null && unavailable == null && (!hurt || !died)) {
                info("events.beside-blast", livingNearBlast() + "; heard elsewhere: " + heardFar
                        + "; a living thing at the blast on the client: " + pigSeen + ", seen dead: " + pigDead);
            }
        }

        /** Once a frame in the event phase: the client's own entities round the blast and the bolt. */
        void watch() {
            CgEntityQuery entities = CgEntityQueries.client();
            if (blastSkipped == null) {
                entities.within(blastX - 3, blastY - 3, blastZ - 3, blastX + 3, blastY + 3, blastZ + 3, id -> {
                    int flags = entities.flags(id);
                    if (id == player || (flags & CgEntityQuery.LIVING) == 0) return;
                    pigSeen = true;
                    if ((flags & CgEntityQuery.ALIVE) == 0) pigDead = true;
                });
            }
            double ly = pose[CgEntityQuery.Y];
            entities.within(lightningX - 3, ly - 8, lightningZ - 3, lightningX + 3, ly + 8, lightningZ + 3, id -> {
                if (id == player || (entities.flags(id) & CgEntityQuery.LIVING) != 0 || boltSeen) return;
                boltSeen = true;
                boltSeenAs = "id " + id + " kind " + entities.kind(id);
            });
        }

        /** What a missed pig left: the nearest living thing the client holds round the blast now. */
        private String livingNearBlast() {
            CgEntityQuery entities = CgEntityQueries.client();
            int[] count = new int[1], nearest = {-1};
            double[] best = {Double.MAX_VALUE};
            entities.within(blastX - 48, blastY - 48, blastZ - 48, blastX + 48, blastY + 48, blastZ + 48, id -> {
                if (id == player || (entities.flags(id) & CgEntityQuery.LIVING) == 0) return;
                count[0]++;
                entities.pose(id, 1f, pose);
                double d = Math.sqrt(sq(pose[CgEntityQuery.X] - blastX) + sq(pose[CgEntityQuery.Y] - blastY)
                        + sq(pose[CgEntityQuery.Z] - blastZ));
                if (d < best[0]) {
                    best[0] = d;
                    nearest[0] = id;
                }
            });
            String at = String.format("blast at %.1f %.1f %.1f, %d living within 48 blocks", blastX, blastY, blastZ, count[0]);
            if (nearest[0] < 0) return at;
            return at + String.format(", nearest %d kind %d at %.1f blocks, alive %b", nearest[0],
                    entities.kind(nearest[0]), best[0], (entities.flags(nearest[0]) & CgEntityQuery.ALIVE) != 0);
        }

        private static double sq(double v) {
            return v * v;
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
