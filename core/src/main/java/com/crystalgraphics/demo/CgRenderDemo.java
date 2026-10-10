package com.crystalgraphics.demo;

import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgFontStyle;
import com.crystalgraphics.api.font.CgGenericFamily;
import com.crystalgraphics.api.font.CgSystemFontFace;
import com.crystalgraphics.api.font.CgSystemFonts;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.buffer.CgReadback;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.service.CgInputService;
import com.crystalgraphics.platform.service.CgWorldQuery;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.post.CgPostStack;
import com.crystalgraphics.render.post.bloom.CgBloom;
import com.crystalgraphics.render.stage.CgHostEnvironment;
import com.crystalgraphics.render.stage.CgHostFrame;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.text.render.CgTextRenderer;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceReport;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.camera.CgCameraShake;
import com.crystalgraphics.world.CgWorldQueries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * A development demo drawn through {@link CgWorldRenderer} under the host's own camera, on unless turned off: one scene
 * at a time, switched in game, with the harness's HUD and toggles in the top left.
 *
 * <pre>
 * blasts    CgVfxBlasts: about 360,000 GPU particles bursting over the ground
 * beams     CgVfxShowcase: sixteen effect spheres and three beams, under its sky
 * modules   CgVfxModules: X6's particle modules, a station each
 * blast     CgVfxBlastFlash: one wave at a time bursting on the ground, for its flash and impact frame
 *
 * H    the HUD on or off
 * N    the next scene, placed ahead of the camera; Shift+N the previous one
 * C    camera shake on or off (CgCameraShake.enabled: the saved setting is untouched)
 * L    bloom in the target's encoding, in linear light, then off
 * V    the VFX simulation on the CPU or the GPU; the scene starts over on it
 * G    the HDR scene on or off
 * [ ]  the HDR scene's glow gain down or up a quarter;  - =  bloom's intensity
 * F I B Y , .  CgVfxDemoControls, shared by every scene: flash, impact frame, billows, time (Shift+Y slower), the wait
 * </pre>
 *
 * <pre>{@code
 * -Dcrystalgraphics.demo=false                        // draw nothing
 * -Dcrystalgraphics.demo=modules                      // start on that scene, blasts by default
 * -Dcrystalgraphics.demo.blasts=240                   // how many blasts, 120 by default
 * -Dcrystalgraphics.demo.sky=false                    // beams: keep the world's own sky, not the showcase's
 * -Dcrystalgraphics.demo.capture=build/demo.png       // and write the world, with no GUI over it, to a PNG
 * -Dcrystalgraphics.demo.captureAt=300                // this many world frames after the demo was placed
 * -Dcrystalgraphics.demo.profile=300                  // profile that many frames of the scene, written to
 *                                                     // crystalgraphics/profile-<scene>-<n>f/ in the game directory
 * -Dcrystalgraphics.demo.profile.warmup=600           // after this many frames on the ground
 * }</pre>
 *
 * <p>A profile records only the channels {@code -Dcrystalgraphics.trace.channels} turns on, and keeps every profiled
 * frame's zones only with {@code -Dcrystalgraphics.trace.zones} sized for them.</p>
 *
 * <p>The scene shown is built at install and the others in the background once it is warmed, one at a time, each
 * drawing nothing until its programs and kernels are built, so no frame waits on a compile and a switch to any of them
 * waits on nothing; a scene switched away from is cleared, not deleted, so a later switch to it builds and compiles
 * nothing. A scene is placed on the first world frame, some
 * blocks ahead of the camera, standing on the world's ground there ({@link CgWorldQueries#groundBelow}); until the ground
 * answers (no world, or its chunk still loading) it floats a little above the eye, and settles onto the ground once it
 * does. It stays there however far the player walks or flies, and moves only when the scene changes, the level changes
 * or the camera jumps more than {@code TELEPORT} blocks in one frame: a teleport, or the server placing the player after
 * the first frames saw the default spawn. The keys do nothing while a screen (chat, a menu) is up or Ctrl is held; the
 * HUD hides with the GUI (F1).</p>
 */
public final class CgRenderDemo {

    private static final Logger LOGGER = LogManager.getLogger("CgRenderDemo");

    private static final String MODE = System.getProperty("crystalgraphics.demo");
    private static final boolean ENABLED = !"false".equals(MODE);
    private static final boolean SKY = !"false".equals(System.getProperty("crystalgraphics.demo.sky"));
    private static final String CAPTURE = System.getProperty("crystalgraphics.demo.capture");
    private static final int CAPTURE_AT = Integer.getInteger("crystalgraphics.demo.captureAt", 300);
    private static final int PROFILE = Integer.getInteger("crystalgraphics.demo.profile", 0);
    private static final int PROFILE_WARMUP = Integer.getInteger("crystalgraphics.demo.profile.warmup", 600);

    private static final int ABOVE = 2;       // blocks up the screen the scene floats, until it stands on the ground
    private static final int TELEPORT = 32;   // blocks the camera may move in one frame before the scene follows
    private static final int SEARCH = 24;     // blocks over the eye the ground search starts from, down twice as far

    /** A scene of the demo: built when first shown, ended when switched away from, kept for the next switch. */
    private interface Scene {
        /** Warms every program and kernel it draws or simulates with. */
        void prepare();

        /** Whether what {@link #prepare} started compiling is built: it draws nothing until then. */
        boolean warmed();

        void submit(CgWorldRenderer world, CgHostView view, double x, double y, double z, float seconds);

        /** Ends what it plays at once, keeping what it built; its next submit starts over. */
        void clear();

        void delete();

        /** Whether it fires energy waves, so the HUD shows {@link CgVfxDemoControls}' wave switches as well as time. */
        default boolean waves() {
            return false;
        }
    }

    /** A scene by name: {@code ahead} blocks from the eye to its anchor, along the view. */
    private record Kind(String name, int ahead, Supplier<Scene> make) {
    }

    /** Every scene, in N's order. A new one is a line here. */
    private final Kind[] kinds = {
            new Kind("blast", 22, this::blast),
            new Kind("beams", 12, this::beams),
            new Kind("modules", 34, this::modules),
            new Kind("blasts", (int) CgVfxBlasts.REACH + 10, this::blasts),
    };

    private boolean installed;
    private final Scene[] scenes = new Scene[kinds.length];
    /** Frames each scene has waited for its programs since it was built; -1 once warmed. */
    private final int[] warmFrames = new int[kinds.length];
    private int current = -1, wanted;
    /** The scene {@link #prewarm} built and is waiting on, or -1. */
    private int prewarming = -1;
    /** V switched the simulation: the scene is cleared at the next frame, so all of it plays on the new one. */
    private boolean restart;
    private boolean anchored, grounded;
    private long anchorX, anchorZ;
    private double anchorY, eyeY;
    /** The view's horizontal direction when the scene was placed. */
    private double aheadX, aheadZ;
    private double lastX, lastY, lastZ;
    private int levelEpoch;
    private int worldFrames;
    /** Frames since the scene stood on the ground, and the trace frame the profile starts at. */
    private int profiled;
    private long profileFrom;

    /** The transparent stage's frame, for the capture callback, which runs inside that firing. */
    private CgHostFrame captured;
    private final Runnable capture = () -> capture(captured.mainFramebuffer(), captured.width(), captured.height());

    // The HUD: HUDRenderer's lines and look, 14 px where the window's shorter side is 600 pixels, in proportion above.
    private static final int HUD_COLOR = 0xFFFF0000, HUD_OUTLINE = 0xFF000000, HUD_PX = 14;
    private static final float HUD_SIDE = 600f, HUD_OUTLINE_EM = 0f;
    private static final long SAMPLE_WINDOW_NANOS = 500_000_000L;
    /** The demo's keys, then the scenes' own. */
    private static final int[] KEYS = keys(new int[]{CgKeyCodes.KEY_H, CgKeyCodes.KEY_N, CgKeyCodes.KEY_C,
            CgKeyCodes.KEY_L, CgKeyCodes.KEY_V, CgKeyCodes.KEY_G, CgKeyCodes.KEY_LBRACKET, CgKeyCodes.KEY_RBRACKET,
            CgKeyCodes.KEY_MINUS, CgKeyCodes.KEY_EQUALS}, CgVfxDemoControls.KEYS);
    private final boolean[] held = new boolean[KEYS.length];
    private static float bloomIntensity = 1f;
    private boolean hudShown = true;

    /** After every other static: the constructor reads {@code MODE} and {@code KEYS}. */
    public static final CgRenderDemo INSTANCE = new CgRenderDemo();
    private CgTextRenderer hud;
    private CgFont hudFont, labelFont;
    private int hudPx, hudSide;
    private boolean hudFailed, hudDirty = true;
    private CgTextLayout hudLayout;
    private int[] particleSamples = new int[256];
    private int particleSampleCount, particles, frames;
    private long windowStart;
    private double fps;
    private final Runnable drawHud = this::drawHud;

    private CgRenderDemo() {
        for (int i = 0; i < kinds.length; i++) {
            if (kinds[i].name().equals(MODE)) wanted = i;
        }
    }

    /** Submits the demo every frame, once, unless {@code -Dcrystalgraphics.demo=false}. */
    public void install() {
        if (installed || !ENABLED) return;
        installed = true;
        CgWorldRenderer.get().onFrame(this::frame);
        // After the post stack's composite, so the HUD lands on the host's target and not in the HDR scene.
        CgRenderStage.WORLD_TRANSPARENT.register(CgPostStack.ORDER + 1000, frame -> {
            CgHostEnvironment world = frame.host().environment();
            keys(world.screenOpen());
            if (world.guiHidden() || !hudShown) return;
            hudSide = Math.min(frame.host().width(), frame.host().height());
            frame.callback("demo.hud", drawHud);
        });
        if (CAPTURE != null) {
            // After the composite: with the HDR scene on, the host's target holds the world only from then.
            CgRenderStage.WORLD_TRANSPARENT.register(CgPostStack.ORDER + 1, frame -> {
                if (++worldFrames != CAPTURE_AT) return;
                captured = frame.host();
                frame.callback("demo.capture", capture);
            });
        }
        // The shown scene alone: building all three cost the first world frame a quarter second (26.2, Vulkan). The rest
        // are prewarmed a frame each, after it.
        build(wanted);
    }

    /** Builds scene {@code i} and starts warming it: its textures, meshes, programs and kernels. */
    private void build(int i) {
        long start = System.nanoTime();
        scenes[i] = kinds[i].make().get();
        scenes[i].prepare();
        warmFrames[i] = 0;
        LOGGER.info("[CgRenderDemo] {} built in {} ms", kinds[i].name(), (System.nanoTime() - start) / 1_000_000);
    }

    /** Releases GPU resources. Call on context destroy. */
    public void dispose() {
        for (int i = 0; i < scenes.length; i++) {
            if (scenes[i] != null) scenes[i].delete();
            scenes[i] = null;
        }
        current = -1;
        prewarming = -1;
        if (hud != null && !hud.isDeleted()) hud.delete();
        hud = null;
    }

    // ── scenes ────────────────────────────────────────────────────────────────

    private Scene blasts() {
        CgVfxBlasts blasts = new CgVfxBlasts(Integer.getInteger("crystalgraphics.demo.blasts", 120));
        return new Scene() {
            @Override
            public void submit(CgWorldRenderer world, CgHostView view, double x, double y, double z, float seconds) {
                blasts.submit(world, x, y, z, seconds);
            }

            @Override
            public void prepare() {
                blasts.prepare();
            }

            @Override
            public boolean warmed() {
                return blasts.warmed();
            }

            @Override
            public void clear() {
                blasts.clear();
            }

            @Override
            public void delete() {
                blasts.delete();
            }
        };
    }

    private Scene beams() {
        CgVfxShowcase showcase = new CgVfxShowcase();
        return new Scene() {
            @Override
            public void submit(CgWorldRenderer world, CgHostView view, double x, double y, double z, float seconds) {
                showcase.submit(world, x, y, z, seconds);
                if (SKY) showcase.submitSky(world, view.x(), view.y(), view.z());
            }

            @Override
            public void prepare() {
                showcase.prepare();
            }

            @Override
            public boolean warmed() {
                return showcase.warmed();
            }

            @Override
            public void clear() {
                showcase.clear();
            }

            @Override
            public void delete() {
                showcase.delete();
            }

            @Override
            public boolean waves() {
                return true;
            }
        };
    }

    private Scene modules() {
        if (labelFont == null) labelFont = sansSerif(48);
        CgVfxModules modules = new CgVfxModules(labelFont);
        return new Scene() {
            @Override
            public void submit(CgWorldRenderer world, CgHostView view, double x, double y, double z, float seconds) {
                modules.submit(world, x, y, z, seconds);
            }

            @Override
            public void prepare() {
                modules.prepare();
            }

            @Override
            public boolean warmed() {
                return modules.warmed();
            }

            @Override
            public void clear() {
                modules.clear();
            }

            @Override
            public void delete() {
                modules.delete();
            }
        };
    }

    private Scene blast() {
        CgVfxBlastFlash blast = new CgVfxBlastFlash();
        return new Scene() {
            @Override
            public void submit(CgWorldRenderer world, CgHostView view, double x, double y, double z, float seconds) {
                blast.submit(world, x, y, z, aheadX, aheadZ, seconds);
            }

            @Override
            public void prepare() {
                blast.prepare();
            }

            @Override
            public boolean warmed() {
                return blast.warmed();
            }

            @Override
            public void clear() {
                blast.clear();
            }

            @Override
            public void delete() {
                blast.delete();
            }

            @Override
            public boolean waves() {
                return true;
            }
        };
    }

    // ── frame ─────────────────────────────────────────────────────────────────

    private void frame(CgHostView view) {
        // Here, before the pools record: a key is read mid-frame, after they have.
        if (restart && current >= 0) scenes[current].clear();
        restart = false;
        if (wanted != current) {
            if (current >= 0) scenes[current].clear();
            if (scenes[wanted] == null) build(wanted);
            current = wanted;
            anchored = false;
            LOGGER.info("[CgRenderDemo] scene {}", kinds[current].name());
        }
        // Again after a jump: the first world frames can see the default spawn, before the server places the player.
        int epoch = CgPlatform.get(CgWorldQuery.SERVICE).levelEpoch();
        if (!anchored || jumped(view) || epoch != levelEpoch) {
            levelEpoch = epoch;
            anchor(view);
        }
        if (!grounded) ground();
        if (PROFILE > 0 && grounded) profile();
        lastX = view.x();
        lastY = view.y();
        lastZ = view.z();
        // Nothing drawn until its programs are built, so no frame waits on a compile at its first draw.
        if (warmFrames[current] >= 0) {
            if (!scenes[current].warmed()) {
                warmFrames[current]++;
                return;
            }
            LOGGER.info("[CgRenderDemo] {} warmed after {} frames", kinds[current].name(), warmFrames[current]);
            warmFrames[current] = -1;
        }
        prewarm();
        // Through the time switch, whichever scene shows, so its value carries across N.
        float seconds = CgVfxDemoControls.get().time(CgFrameClock.seconds());
        scenes[current].submit(CgWorldRenderer.get(), view, anchorX + 0.5, anchorY, anchorZ + 0.5, seconds);
    }

    /**
     * Builds the scenes not yet shown, one at a time once the shown one is warmed, each after the last has warmed, so
     * a first switch to one waits on nothing and no frame builds more than one.
     */
    private void prewarm() {
        if (prewarming >= 0) {
            if (!scenes[prewarming].warmed()) return;
            LOGGER.info("[CgRenderDemo] {} prewarmed", kinds[prewarming].name());
            prewarming = -1;
        }
        for (int i = 0; i < kinds.length; i++) {
            if (scenes[i] != null) continue;
            build(i);
            prewarming = i;
            return;
        }
    }

    /** Counts the frames since the scene stood on the ground, and writes the profile once its frames are in. */
    private void profile() {
        profiled++;
        if (profiled == PROFILE_WARMUP) {
            profileFrom = CgTrace.currentFrameIndex();
            LOGGER.info("[CgRenderDemo] profiling {} frames of {}", PROFILE, kinds[current].name());
        } else if (profiled == PROFILE_WARMUP + PROFILE) {
            CgTraceReport report = CgTraceReport.of(CgTrace.snapshot().between(profileFrom, CgTrace.currentFrameIndex()));
            Path folder = Paths.get("crystalgraphics", "profile-" + kinds[current].name() + "-" + PROFILE + "f");
            try {
                Files.createDirectories(folder);
                Files.write(folder.resolve("report.txt"), report.breakdown().getBytes(StandardCharsets.UTF_8));
                Files.write(folder.resolve("report-full.txt"),
                        report.render(CgTraceReport.Tier.FULL).getBytes(StandardCharsets.UTF_8));
                LOGGER.info("[CgRenderDemo] profile written to {}", folder.toAbsolutePath());
            } catch (IOException e) {
                LOGGER.warn("[CgRenderDemo] could not write the profile to {}", folder.toAbsolutePath(), e);
            }
        }
    }

    private boolean jumped(CgHostView view) {
        double dx = view.x() - lastX, dy = view.y() - lastY, dz = view.z() - lastZ;
        return dx * dx + dy * dy + dz * dz > TELEPORT * TELEPORT;
    }

    /**
     * Puts the scene ahead of the eye, whatever the host folds into its view matrix: along the view and up the screen
     * while it floats, and level with the eye where it will stand on the ground.
     */
    private void anchor(CgHostView view) {
        int ahead = kinds[current].ahead();
        Matrix4f toWorld = new Matrix4f(view.view()).invert();
        Vector3f eye = toWorld.transformPosition(new Vector3f());
        Vector3f forward = toWorld.transformDirection(new Vector3f(0f, 0f, -1f)).normalize();
        Vector3f up = toWorld.transformDirection(new Vector3f(0f, 1f, 0f)).normalize();
        double level = Math.hypot(forward.x, forward.z);
        aheadX = level > 1.0e-3 ? forward.x / level : forward.x;
        aheadZ = level > 1.0e-3 ? forward.z / level : forward.z;
        anchorX = (long) Math.floor(view.x() + eye.x + aheadX * ahead);
        anchorZ = (long) Math.floor(view.z() + eye.z + aheadZ * ahead);
        eyeY = view.y() + eye.y;
        anchorY = Math.floor(eyeY + forward.y * ahead + up.y * ABOVE);
        anchored = true;
        grounded = false;
        worldFrames = 0;
        LOGGER.info("[CgRenderDemo] {} around ({}, {}, {}), camera at ({}, {}, {})", kinds[current].name(),
                anchorX, anchorY, anchorZ, view.x(), view.y(), view.z());
    }

    /** Stands the scene on the ground under it, once the world answers. */
    private void ground() {
        double floor = CgWorldQueries.groundBelow(anchorX + 0.5, eyeY + SEARCH, anchorZ + 0.5, SEARCH * 3);
        if (Double.isNaN(floor)) return;
        anchorY = floor;
        grounded = true;
        LOGGER.info("[CgRenderDemo] {} on the ground at y {}", kinds[current].name(), floor);
    }

    // ── HUD and keys ──────────────────────────────────────────────────────────

    /** Acts on each key as it goes down, unless a screen has the keys or Ctrl is held. */
    private void keys(boolean screenOpen) {
        CgInputService input = CgPlatform.input();
        boolean ctrl = input.isKeyDown(CgKeyCodes.KEY_LCONTROL) || input.isKeyDown(CgKeyCodes.KEY_RCONTROL);
        boolean shift = input.isKeyDown(CgKeyCodes.KEY_LSHIFT) || input.isKeyDown(CgKeyCodes.KEY_RSHIFT);
        for (int i = 0; i < KEYS.length; i++) {
            boolean down = input.isKeyDown(KEYS[i]);
            if (down && !held[i] && !screenOpen && !ctrl) press(KEYS[i], shift);
            held[i] = down;
        }
    }

    private static int[] keys(int[] own, int[] scenes) {
        int[] all = Arrays.copyOf(own, own.length + scenes.length);
        System.arraycopy(scenes, 0, all, own.length, scenes.length);
        return all;
    }

    private void press(int key, boolean shift) {
        switch (key) {
            case CgKeyCodes.KEY_H -> hudShown = !hudShown;
            case CgKeyCodes.KEY_N -> wanted = (wanted + (shift ? kinds.length - 1 : 1)) % kinds.length;
            case CgKeyCodes.KEY_C -> CgCameraShake.enabled(!CgCameraShake.enabled());
            case CgKeyCodes.KEY_L -> cycleBloom();
            case CgKeyCodes.KEY_V -> {
                CgVfxSystem.simulation(CgVfxSystem.simulation() == CgVfxSystem.Simulation.CPU
                        ? CgVfxSystem.Simulation.GPU : CgVfxSystem.Simulation.CPU);
                // What is in flight stays on the simulation it started on: a CPU batch would hold the frame until
                // it played out.
                restart = true;
            }
            case CgKeyCodes.KEY_G -> CgWorldRenderer.get().hdrScene(!CgWorldRenderer.get().hdrScene());
            // A quarter down or up, as the harness's.
            case CgKeyCodes.KEY_LBRACKET -> CgWorldRenderer.get().sceneEmission(CgWorldRenderer.get().sceneEmission() / 1.25f);
            case CgKeyCodes.KEY_RBRACKET -> CgWorldRenderer.get().sceneEmission(CgWorldRenderer.get().sceneEmission() * 1.25f);
            case CgKeyCodes.KEY_MINUS -> scaleBloom(1f / 1.25f);
            case CgKeyCodes.KEY_EQUALS -> scaleBloom(1.25f);
            default -> CgVfxDemoControls.get().press(key, shift);
        }
        hudDirty = true;
    }

    /** Bloom's intensity times {@code by}, while it is on. */
    private static void scaleBloom(float by) {
        CgBloom bloom = CgPostStack.get().bloom();
        if (bloom.intensity() > 0f) bloom.intensity(bloom.intensity() * by);
    }

    /** Blend, then linear, then off, then back at the intensity it had. */
    private static void cycleBloom() {
        CgBloom bloom = CgPostStack.get().bloom();
        if (bloom.intensity() == 0f) {
            bloom.intensity(bloomIntensity).linear(false);
        } else if (bloom.linear()) {
            bloomIntensity = bloom.intensity();
            bloom.intensity(0f);
        } else {
            bloom.linear(true);
        }
    }

    /**
     * Samples this frame for the FPS and the particles drawn (the median over the latest half second), and draws the
     * HUD; its text is laid out again only when a line changed.
     */
    private void drawHud() {
        if (hudFailed) return;
        long now = System.nanoTime();
        frames++;
        if (particleSampleCount == particleSamples.length) {
            particleSamples = Arrays.copyOf(particleSamples, particleSampleCount * 2);
        }
        particleSamples[particleSampleCount++] = CgVfxSystem.particlesDrawn();
        if (now - windowStart >= SAMPLE_WINDOW_NANOS) {
            fps = frames / ((now - windowStart) / 1.0e9);
            Arrays.sort(particleSamples, 0, particleSampleCount);
            particles = particleSamples[particleSampleCount / 2];
            particleSampleCount = 0;
            frames = 0;
            windowStart = now;
            hudDirty = true;
        }
        try {
            int px = Math.round(HUD_PX * Math.max(1f, hudSide / HUD_SIDE));
            if (px != hudPx) {
                hudFont = sansSerif(px);
                hudPx = px;
                hudDirty = true;
            }
            if (hudFont == null) {
                hudFailed = true;
                return;
            }
            if (hudDirty) {
                hudLayout = CgTextLayout.of(hudText(), hudFont).build();
                hudDirty = false;
            }
            if (hud == null) hud = CgTextRenderer.create();
            // Drawn in a world pass with the host's depth bound: its z = 0 glyphs put on the nearest depth (+1 reversed,
            // -1 standard) pass the test against every pixel, the showcase's sky seal included. The depth row stays
            // non-zero, or the culler's far plane is all zeros and culls every glyph. Again each frame, since a resize
            // rebuilds the ortho.
            hud.context().projection().m22(-1.0e-4f).m32(CgGL.isDepthReversed() ? 1f : -1f);
            hud.beginBatch();
            hud.draw().layout(hudLayout).font(hudFont).at(4f, 4f).color(HUD_COLOR).stroke(HUD_OUTLINE_EM, HUD_OUTLINE).submit();
            hud.endBatch();
        } catch (RuntimeException e) {
            hudFailed = true;
            LOGGER.error("[CgRenderDemo] the HUD failed; it stays off", e);
        }
    }

    private String hudText() {
        CgBloom bloom = CgPostStack.get().bloom();
        boolean waves = scenes[wanted] != null && scenes[wanted].waves();
        return String.format(Locale.ROOT, "FPS: %.1f", fps)
                + "\nScene [N]: " + kinds[wanted].name() + " - Hide [H]"
                + String.format(Locale.ROOT, "\nParticles: %,d", particles)
                + "\n\nVFX sim [V]: " + (CgVfxSystem.simulation() == CgVfxSystem.Simulation.CPU ? "cpu" : "gpu")
                + (CgCameraShake.enabled() ? String.format(Locale.ROOT, " - Shake [C]: on, trauma %.2f", CgCameraShake.trauma())
                : " - Shake [C]: off")
                + "\nHDR [G]: " + (CgWorldRenderer.get().hdrScene() ? "on" : "off")
                + String.format(Locale.ROOT, " | Glow [ ]: %.2f", CgWorldRenderer.get().sceneEmission())
                + "\nBloom [L]: " + (bloom.intensity() == 0f ? "off" : bloom.linear() ? "linear" : "blend")
                + String.format(Locale.ROOT, " | Intensity [- =]: %.2f", bloom.intensity())
                + "\n" + CgVfxDemoControls.get().hudLines(waves);
    }

    /** The installed sans-serif at {@code px}, or null, logged, when there is none. */
    private static CgFont sansSerif(int px) {
        CgSystemFonts fonts = CgSystemFonts.get();
        CgSystemFontFace face = fonts.generic(CgGenericFamily.SANS_SERIF, CgFontStyle.REGULAR);
        if (face == null) {
            LOGGER.warn("[CgRenderDemo] no sans-serif font installed: no HUD or labels");
            return null;
        }
        return fonts.load(face, CgFontStyle.REGULAR, px);
    }

    // ── capture ───────────────────────────────────────────────────────────────

    /**
     * The host's target as it stands after the transparent stage, written once the GPU has copied it, frames later: a
     * hosted Vulkan device refuses a read that waits.
     */
    private static void capture(int framebuffer, int w, int h) {
        CgReadback.pixels(Math.max(framebuffer, 0), 0, 0, w, h, CgTextureType.RGBA8, pixels -> write(pixels, w, h));
    }

    private static void write(ByteBuffer pixels, int w, int h) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int at = ((h - 1 - y) * w + x) * 4;
                image.setRGB(x, y, (pixels.get(at) & 0xFF) << 16 | (pixels.get(at + 1) & 0xFF) << 8
                        | (pixels.get(at + 2) & 0xFF));
            }
        }
        File out = new File(CAPTURE).getAbsoluteFile();
        try {
            if (out.getParentFile() != null) out.getParentFile().mkdirs();
            ImageIO.write(image, "png", out);
            LOGGER.info("[CgRenderDemo] wrote {}x{} capture to {}", w, h, out);
        } catch (IOException e) {
            LOGGER.error("[CgRenderDemo] could not write {}", out, e);
        }
    }
}
