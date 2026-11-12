package com.crystalgraphics.demo;

import com.crystalgraphics.platform.rhi.resource.CgTextureView;
import com.crystalgraphics.platform.rhi.CgExternalTarget;
import com.crystalgraphics.loading.CgLoadingScreen;
import com.crystalgraphics.api.font.CgFont;
import com.crystalgraphics.api.font.CgDefaultFont;
import com.crystalgraphics.api.text.CgTextLayout;
import com.crystalgraphics.text.style.CgTextLayer;
import com.crystalgraphics.text.style.CgTextStyle;
import com.crystalgraphics.collision.CgCollisionWorld;
import com.crystalgraphics.demo.collision.CgCollisionDemo;
import com.crystalgraphics.frame.CgTickGroup;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.gpu.resource.CgReadback;
import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.lifecycle.CgLifecycleListener;
import com.crystalgraphics.loading.task.CgLoadPlan;
import com.crystalgraphics.loading.watchdog.CgLoadWatchdog;
import com.crystalgraphics.platform.service.CgProcessExit;
import com.crystalgraphics.loading.task.CgLoadTask;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.platform.input.CgInput;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.post.CgPostStack;
import com.crystalgraphics.render.post.bloom.CgBloom;
import com.crystalgraphics.render.stage.CgHostEnvironment;
import com.crystalgraphics.render.stage.CgHostFrame;
import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.render.canvas.CgCanvas;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.trace.CgTraceReport;
import com.crystalgraphics.trace.CgTraceRun;
import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.vfx.CgVfx;
import com.crystalgraphics.vfx.CgVfxOwner;
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
 * collision CgCollisionDemo: every collision shape, a cast, rays and a hitbox against the world, as debug lines
 * lights    CgLightsDemo: a light show of every kind of world light, particle lights and lightning; best at night
 * shadows   CgShadowsDemo: every caster on every receiver, our objects, the player, mobs and Minecraft's blocks and torches
 *
 * H    the HUD on or off
 * N    the next scene, placed ahead of the camera; Shift+N the previous one
 * C    camera shake on or off (CgCameraShake.enabled: the saved setting is untouched)
 * L    bloom on or off
 * X    kit looks on or off: each kit's effects graded by their game's post, or by the frame's
 * [ ]  emission, the HDR scene's glow gain, down or up a quarter;  - =  bloom's intensity
 * O    every world shadow off or on (crystalgraphics.world.lightsShadows), to compare
 * K J U  the shadows' temporal accumulation, their spatial filter, their rays' screen trace, off or on
 * M    the shadow rays' resolution: full, checkerboard, half
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

    private static final String MODE = CgVar.text("crystalgraphics.demo", "", CgVar.DEV | CgVar.RESTART,
            "false draws no world demo; a scene's name (blasts, beams, modules, fluid, blast, collision, lights, shadows, glass, text) starts on it;"
                    + " empty on"
                    + " blasts.").get();
    private static final boolean ENABLED = !"false".equals(MODE);
    private static final boolean SKY = CgVar.bool("crystalgraphics.demo.sky", true, CgVar.DEV | CgVar.RESTART,
            "The demo's beams scene under the showcase's sky; false keeps the world's own.").get();
    private static final String CAPTURE = CgVar.text("crystalgraphics.demo.capture", "", CgVar.DEV | CgVar.RESTART,
            "A PNG path: the world, with no GUI over it, written there captureAt world frames after the demo was placed.").get();
    private static final int CAPTURE_AT = CgVar.integer("crystalgraphics.demo.captureAt", 300, 1, Integer.MAX_VALUE,
            CgVar.DEV | CgVar.RESTART, "World frames after the demo was placed that its capture is written.").get();
    private static final int PROFILE = CgVar.integer("crystalgraphics.demo.profile", 0, 0, Integer.MAX_VALUE,
            CgVar.DEV | CgVar.RESTART, "Frames of the demo's scene to profile, written to"
                    + " crystalgraphics/profile-<scene>-<n>f/ in the game directory; 0 for none.").get();
    private static final int CYCLE = CgVar.integer("crystalgraphics.demo.cycle", 0, 0, 3600, CgVar.DEV | CgVar.RESTART,
            "Seconds a scene before the demo moves to the next, twice round every scene, with the loading watchdog armed"
                    + " to name what holds a frame (crystalgraphics.loading.stallMs), then exits the process; 0 stays on one.").get();
    private static final int PROFILE_WARMUP =CgVar.integer("crystalgraphics.demo.profile.warmup", 600, 0, Integer.MAX_VALUE,
            CgVar.DEV | CgVar.RESTART, "Frames on the ground before the demo's profile starts.").get();
    private static final int BLASTS = CgVar.integer("crystalgraphics.demo.blasts", 120, 1, 100_000, CgVar.DEV | CgVar.RESTART,
            "Blasts in the demo's blasts scene; 120 keep about 360,000 particles alive.").get();
    /** The scene's point {@code (x, z)} under the eye instead of the scene ahead of it, or null. */
    private static final double[] UNDER = under(CgVar.text("crystalgraphics.demo.under", "", CgVar.DEV | CgVar.RESTART,
            "x,z: the scene placed with its point (x, z) under the eye, for a profile looking down at one part of it"
                    + " (lights: -11,4 the fireflies, -20,-12 a firework); empty, ahead of the eye.").get());

    /** Every scene plays on the world's system, as a mod's effects do. */
    private static final CgVfxOwner VFX = CgVfx.owner("crystalgraphics");

    private static final int ABOVE = 2;       // blocks up the screen the scene floats, until it stands on the ground
    private static final int TELEPORT = 32;   // blocks the camera may move in one frame before the scene follows
    private static final int SEARCH = 24;     // blocks over the eye the ground search starts from, down twice as far

    /** A scene of the demo: built when first shown, ended when switched away from, kept for the next switch. */
    private interface Scene {
        /** Warms every program and kernel it draws or simulates with. */
        void precache();

        /** Whether what {@link #precache} started compiling is built: it draws nothing until then. */
        boolean ready();

        void submit(CgWorldRenderer world, CgCamera view, double x, double y, double z, double seconds);

        /** Ends what it plays at once, keeping what it built; its next submit starts over. */
        void clear();

        void delete();

        /** Whether it fires energy waves, so the HUD shows {@link CgVfxDemoControls}' wave switches as well as time. */
        default boolean waves() {
            return false;
        }

        /** Lines of its own for the HUD, each starting with a line break; none by default. */
        default String hud() {
            return "";
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
            new Kind("fluid", 90, this::fluid),
            new Kind("blasts", (int) CgVfxBlasts.REACH + 10, this::blasts),
            new Kind("collision", 24, this::collision),
            new Kind("lights", 30, this::lights),
            new Kind("shadows", 22, this::shadows),
            new Kind("glass", 22, this::glass),
            new Kind("text", 14, this::text),
    };

    private boolean installed;
    private final Scene[] scenes = new Scene[kinds.length];
    /** When each scene was built, in nanoseconds; -1 once warmed. Polled many times a frame, so timed, not counted. */
    private final long[] warmFrom = new long[kinds.length];
    private int current = -1, wanted;
    /** The scene {@link #prewarmRest} built and is waiting on, or -1; {@code allWarm} once every scene is warmed. */
    private int prewarming = -1;
    private boolean allWarm;
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
    private final Runnable capture = () -> capture(captured.target(), captured.width(), captured.height());

    // The HUD: HUDRenderer's lines and look, 14 px where the window's shorter side is 600 pixels, in proportion above.
    private static final int HUD_PX = 14;
    private static final float HUD_SIDE = 600f;
    /** Red, outlined 0.12 em in black: at 75 pt, 100 px, a width of 12. */
    private static final CgTextStyle HUD_LOOK = CgTextStyle.builder("crystalgraphics:demo_hud").size(75f)
            .layer(CgTextLayer.outline("outline").width(12f).paint(0xFF000000))
            .layer(CgTextLayer.fill("fill").paint(0xFFFF4F5E))
            .build();
    private static final long SAMPLE_WINDOW_NANOS = 500_000_000L;
    /** The demo's keys, then the scenes' own. */
    private static final int[] KEYS = keys(new int[]{CgKeyCodes.KEY_H, CgKeyCodes.KEY_N, CgKeyCodes.KEY_C,
            CgKeyCodes.KEY_L, CgKeyCodes.KEY_V, CgKeyCodes.KEY_G, CgKeyCodes.KEY_LBRACKET, CgKeyCodes.KEY_RBRACKET,
            CgKeyCodes.KEY_MINUS, CgKeyCodes.KEY_EQUALS, CgKeyCodes.KEY_O, CgKeyCodes.KEY_K, CgKeyCodes.KEY_J,
            CgKeyCodes.KEY_U, CgKeyCodes.KEY_M, CgKeyCodes.KEY_R, CgKeyCodes.KEY_Z, CgKeyCodes.KEY_X},
            CgVfxDemoControls.KEYS);
    private final boolean[] held = new boolean[KEYS.length];
    private static float bloomIntensity = 1f;
    private boolean hudShown = true;
    /** Whether the host hides its GUI (F1), as the last world frame read it. */
    private boolean guiHidden;

    /** After every other static: the constructor reads {@code MODE} and {@code KEYS}. */
    public static final CgRenderDemo INSTANCE = new CgRenderDemo();
    /** The HUD's canvas, recorded into the stage's target under a screen camera of the target's pixels. */
    private final CgCanvas hud = new CgCanvas();
    private final CgPassConstants hudCamera = new CgPassConstants();
    private CgFont hudFont, labelFont;
    private int hudPx, hudSide;
    private boolean hudFailed, hudDirty = true;
    private CgTextLayout hudLayout;
    private int[] particleSamples = new int[256];
    private int particleSampleCount, particles, frames;
    private long windowStart;
    private double fps;

    private CgRenderDemo() {
        for (int i = 0; i < kinds.length; i++) {
            if (kinds[i].name().equals(MODE)) wanted = i;
        }
    }

    /** Submits the demo every frame, once, unless {@code -Dcrystalgraphics.demo=false}. */
    public void install() {
        if (installed || !ENABLED) return;
        installed = true;
        // PLAYS: what the scenes play starts this frame, in EFFECTS after.
        CgWorld.client().schedule().add(CgTickGroup.PLAYS, "crystalgraphics:demo", dt -> frame(CgWorld.client().view()))
                .whilePaused(true);
        // The keys and whether the GUI is hidden, from the world's frame, which carries the host's environment
        CgRenderStage.WORLD_TRANSPARENT.register(CgPostStack.ORDER + 1000, frame -> {
            CgHostEnvironment world = frame.host().environment();
            keys(world.screenOpen());
            guiHidden = world.guiHidden();
            // Behind the loading screen, into its warm target at the window's size: the real HUD's glyphs made there
            if (CgLoadingScreen.shown() && CgLoadingScreen.windowHeight() > 0 && hudShown) {
                hudSide = Math.min(CgLoadingScreen.windowWidth(), CgLoadingScreen.windowHeight());
                drawHud(frame);
            }
        });
        // The HUD on the HUD stage, over the finished world: Minecraft draws clouds and weather after the world stages
        CgRenderStage.HUD.register(CgRenderStage.VIEWPORT_WIDGETS - 1, frame -> {
            if (guiHidden || !hudShown) return;
            hudSide = Math.min(frame.host().width(), frame.host().height());
            drawHud(frame);
        });
        if (!CAPTURE.isEmpty()) {
            // After the composite: with the HDR scene on, the host's target holds the world only from then.
            CgRenderStage.WORLD_TRANSPARENT.register(CgPostStack.ORDER + 1, frame -> {
                if (++worldFrames != CAPTURE_AT) return;
                captured = frame.host();
                frame.callback("demo.capture", capture);
            });
        }
        // The shown scene alone: building all three cost the first world frame a quarter second (26.2, Vulkan). It warms
        // and rehearses behind the loading screen as a tracked task; the rest warm after the screen hides.
        build(wanted);
        CgEngineLifecycle.addListener(new CgLifecycleListener() {
            @Override
            public void onLoad(CgLoadPlan plan) {
                plan.task("crystalgraphics:demo").render(task -> {
                    try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.MISC, WARM_AHEAD_ZONE)) {
                        // Each wait is on a warm-up or a rehearsal frame: polled once a frame
                        if (warmAhead(task)) return true;
                        task.nextFrame();
                        return false;
                    }
                }).start();
            }
        });
    }

    /** Builds scene {@code i} and starts warming it: its textures, meshes, programs and kernels. */
    private void build(int i) {
        long start = System.nanoTime();
        scenes[i] = kinds[i].make().get();
        scenes[i].precache();
        warmFrom[i] = System.nanoTime();
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
        allWarm = shownReady = false;
    }

    // ── scenes ────────────────────────────────────────────────────────────────

    private Scene blasts() {
        CgVfxBlasts blasts = new CgVfxBlasts(BLASTS, VFX);
        return new Scene() {
            @Override
            public void submit(CgWorldRenderer world, CgCamera view, double x, double y, double z, double seconds) {
                blasts.submit(world, x, y, z, seconds);
            }

            @Override
            public void precache() {
                blasts.precache();
            }

            @Override
            public boolean ready() {
                return blasts.ready();
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
        CgVfxShowcase showcase = new CgVfxShowcase(VFX);
        return new Scene() {
            @Override
            public void submit(CgWorldRenderer world, CgCamera view, double x, double y, double z, double seconds) {
                showcase.submit(world, x, y, z, seconds);
                if (SKY) showcase.submitSky(world, view.x(), view.y(), view.z());
            }

            @Override
            public void precache() {
                showcase.precache();
            }

            @Override
            public boolean ready() {
                return showcase.ready();
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

    private Scene fluid() {
        if (labelFont == null) labelFont = CgDefaultFont.regular(48);
        CgVfxFluidDemo fluid = new CgVfxFluidDemo(labelFont, VFX);
        return new Scene() {
            @Override
            public void submit(CgWorldRenderer world, CgCamera view, double x, double y, double z, double seconds) {
                fluid.submit(world, x, y, z, seconds);
            }

            @Override
            public void precache() {
                fluid.precache();
            }

            @Override
            public boolean ready() {
                return fluid.ready();
            }

            @Override
            public void clear() {
                fluid.clear();
            }

            @Override
            public void delete() {
                fluid.delete();
            }
        };
    }

    private Scene modules() {
        if (labelFont == null) labelFont = CgDefaultFont.regular(48);
        // Built a station a poll: built whole, it held the loading screen 200 ms and more
        CgVfxModules modules = CgVfxModules.stepped(labelFont, VFX);
        return new Scene() {
            private boolean precached;

            @Override
            public void submit(CgWorldRenderer world, CgCamera view, double x, double y, double z, double seconds) {
                if (precached) modules.submit(world, x, y, z, seconds);
            }

            @Override
            public void precache() {
                // At the first ready() past its last station
            }

            @Override
            public boolean ready() {
                if (!modules.build()) return false;
                if (!precached) {
                    modules.precache();
                    precached = true;
                }
                return modules.ready();
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
        CgVfxBlastFlash blast = new CgVfxBlastFlash(VFX);
        return new Scene() {
            @Override
            public void submit(CgWorldRenderer world, CgCamera view, double x, double y, double z, double seconds) {
                blast.submit(world, x, y, z, aheadX, aheadZ, seconds);
            }

            @Override
            public void precache() {
                blast.precache();
            }

            @Override
            public boolean ready() {
                return blast.ready();
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

    private Scene collision() {
        CgCollisionDemo demo = new CgCollisionDemo();
        return new Scene() {
            @Override
            public void submit(CgWorldRenderer world, CgCamera view, double x, double y, double z, double seconds) {
                float partialTick = CgRenderStage.WORLD_OPAQUE.host().partialTick();
                demo.submit(CgCollisionWorld.client(partialTick), x, y, z, seconds);
            }

            @Override
            public void precache() {
            }

            @Override
            public boolean ready() {
                return true;
            }

            @Override
            public void clear() {
                demo.clear();
            }

            @Override
            public void delete() {
                demo.clear();
            }
        };
    }

    private Scene lights() {
        CgLightsDemo lights = new CgLightsDemo(VFX);
        return new Scene() {
            @Override
            public void submit(CgWorldRenderer world, CgCamera view, double x, double y, double z, double seconds) {
                lights.submit(world, x, y, z, seconds);
            }

            @Override
            public void precache() {
                lights.precache();
            }

            @Override
            public boolean ready() {
                return lights.ready();
            }

            @Override
            public void clear() {
                lights.clear();
            }

            @Override
            public void delete() {
                lights.delete();
            }

            @Override
            public String hud() {
                return CgShadowsDemo.lightingLine();
            }
        };
    }

    private Scene shadows() {
        CgShadowsDemo shadows = new CgShadowsDemo();
        return new Scene() {
            @Override
            public void submit(CgWorldRenderer world, CgCamera view, double x, double y, double z, double seconds) {
                shadows.submit(world, x, y, z, seconds);
            }

            @Override
            public void precache() {
                shadows.precache();
            }

            @Override
            public boolean ready() {
                return shadows.ready();
            }

            @Override
            public void clear() {
            }

            @Override
            public void delete() {
            }

            @Override
            public String hud() {
                return CgShadowsDemo.hud();
            }
        };
    }

    private Scene glass() {
        if (labelFont == null) labelFont = CgDefaultFont.regular(48);
        CgGlassDemo glass = new CgGlassDemo(labelFont);
        return new Scene() {
            @Override
            public void submit(CgWorldRenderer world, CgCamera view, double x, double y, double z, double seconds) {
                glass.submit(world, view, x, y, z, seconds);
            }

            @Override
            public void precache() {
                glass.precache();
            }

            @Override
            public boolean ready() {
                return glass.ready();
            }

            @Override
            public void clear() {
                glass.clear();
            }

            @Override
            public void delete() {
                glass.delete();
            }
        };
    }

    private Scene text() {
        if (labelFont == null) labelFont = CgDefaultFont.regular(48);
        CgTextDemo text = new CgTextDemo(labelFont);
        return new Scene() {
            @Override
            public void submit(CgWorldRenderer world, CgCamera view, double x, double y, double z, double seconds) {
                text.submit(world, view, x, y, z, seconds);
            }

            @Override
            public void precache() {
                text.precache();
            }

            @Override
            public boolean ready() {
                return text.ready();
            }

            @Override
            public void clear() {
                text.clear();
            }

            @Override
            public void delete() {
                text.delete();
            }
        };
    }

    // ── frame ─────────────────────────────────────────────────────────────────

    private void frame(CgCamera view) {
        if (CYCLE > 0 && grounded) cycle();
        // Here, before the pools record: a key is read mid-frame, after they have.
        if (wanted != current) {
            if (current >= 0) scenes[current].clear();
            if (scenes[wanted] == null) build(wanted);
            current = wanted;
            anchored = false;
            LOGGER.info("[CgRenderDemo] scene {}", kinds[current].name());
        }
        // Again after a jump: the first world frames can see the default spawn, before the server places the player.
        int epoch = CgWorldQueries.client().levelEpoch();
        if (!anchored || jumped(view) || epoch != levelEpoch) {
            levelEpoch = epoch;
            anchor(view);
        }
        if (!grounded) ground();
        if (PROFILE > 0 && grounded) profile();
        prewarmRest();
        lastX = view.x();
        lastY = view.y();
        lastZ = view.z();
        // Nothing drawn until its programs are built, so no frame waits on a compile at its first draw.
        if (!warm(current)) return;
        // Through the time switch, whichever scene shows, so its value carries across N.
        double seconds = CgVfxDemoControls.get().time(CgFrameClock.seconds());
        // The scenes play on the world's system, which keeps the host's clock: the time switch reaches it as a scale.
        CgWorld.client().timeDilation(CgVfxDemoControls.get().speed());
        scenes[current].submit(CgWorldRenderer.get(), view, anchorX + 0.5, anchorY, anchorZ + 0.5, seconds);
        shownFrames++;
        long work = CgLoadingScreen.firstUseWork();
        quietFrames = work == lastWork ? quietFrames + 1 : 0;
        lastWork = work;
    }

    /**
     * Under {@code crystalgraphics.demo.cycle}: every scene in turn, {@link #CYCLE} seconds each, twice round, with the
     * watchdog armed, so a switch that hitches is logged with what held the frame; then the process exits.
     */
    private void cycle() {
        long now = System.nanoTime();
        if (cycleFrom == 0L) {
            cycleFrom = now;
            firstSwitch[current] = CgTrace.currentFrameIndex();
            CgLoadWatchdog.arm(true);
            LOGGER.info("[CgRenderDemo] cycle: {} s a scene, twice round", CYCLE);
            return;
        }
        if (cycled < 0) return;
        // Frame times: the first 2 s after a switch against the scene's last 2 s, its steady state
        if (lastCycleFrame != 0L) {
            float ms = (now - lastCycleFrame) / 1.0e6f;
            long into = now - cycleFrom;
            if (into < 2_000_000_000L) {
                earlyMax = Math.max(earlyMax, ms);
                earlySum += ms;
                earlyFrames++;
                if (ms > 2f * Math.max(lateMean, 4f)) earlySlow++;
            } else if (into > (CYCLE - 2) * 1_000_000_000L) {
                lateSum += ms;
                lateFrames++;
            }
        }
        lastCycleFrame = now;
        if (now - cycleFrom < CYCLE * 1_000_000_000L) return;
        lateMean = lateFrames > 0 ? lateSum / lateFrames : 0f;
        LOGGER.info(String.format(Locale.ROOT, "[CgRenderDemo] cycle %s: first 2 s %d frames, mean %.1f ms, worst %.1f ms, %d over twice"
                        + " steady; steady %.1f ms", kinds[current].name(), earlyFrames, earlyFrames > 0 ? earlySum / earlyFrames : 0f,
                earlyMax, earlySlow, lateMean));
        earlyMax = earlySum = lateSum = 0f;
        earlyFrames = earlySlow = lateFrames = 0;
        cycleFrom = now;
        if (++cycled > 2 * kinds.length) {
            cycled = -1;
            CgLoadWatchdog.arm(false);
            LOGGER.info("[CgRenderDemo] cycle done");
            // Each first switch's frames, from the trace's ring once nothing is measured: written during the cycle,
            // a dump's reports took the frames they were meant to show
            for (int i = 0; i < kinds.length; i++) {
                if (firstSwitch[i] > 0) CgTraceRun.dump("switch-" + kinds[i].name(), firstSwitch[i], firstSwitch[i] + 300);
            }
            // A diagnostic run: it ends itself, so nothing has to find and stop the client
            CgProcessExit.halt(0);
            return;
        }
        wanted = (current + 1) % kinds.length;
        if (cycled <= kinds.length && firstSwitch[wanted] == 0L) firstSwitch[wanted] = CgTrace.currentFrameIndex();
    }

    /** The trace frame each scene was first switched to by the cycle, 0 for none. */
    private final long[] firstSwitch = new long[kinds.length];
    private long cycleFrom, lastCycleFrame;
    /** Switches the cycle made; -1 once done. */
    private int cycled;
    /** The current scene's frame times: its first 2 s and its last; the last scene's steady mean, what counts as slow. */
    private float earlyMax, earlySum, lateSum, lateMean;
    private int earlyFrames, earlySlow, lateFrames;

    /** Whether scene {@code i}'s programs are built, polling them. */
    private boolean warm(int i) {
        if (warmFrom[i] < 0) return true;
        if (!scenes[i].ready()) return false;
        LOGGER.info("[CgRenderDemo] {} warmed {} ms after its build", kinds[i].name(), (System.nanoTime() - warmFrom[i]) / 1_000_000);
        warmFrom[i] = -1;
        return true;
    }

    /**
     * The load task's render part: the scene to show warmed, then shown behind the loading screen until its first plays'
     * one-time work is done (its meshes placed, its effects' renderers and materials made, its labels captured). True
     * once it has; the other scenes warm after the screen hides ({@link #prewarmRest}).
     */
    private boolean warmAhead(CgLoadTask task) {
        if (shownReady) return true;
        if (scenes[wanted] == null) return false;
        // Inside the frame's host section (CgLoadPlan), whose close sets back what a compile commit bound
        if (!warm(wanted)) return false;
        task.progress(0.5f + 0.5f * Math.min(1f, shownFrames / (float) REHEARSE_MIN));
        if (current != wanted || !settled()) return false;
        LOGGER.info("[CgRenderDemo] {} rehearsed", kinds[current].name());
        return shownReady = true;
    }

    /**
     * Once the shown scene is ready and the loading screen has hidden: the others built one at a time, each after the last
     * has warmed, in the frame's warm budget, so a first switch to one finds it warm and no frame builds more than one. A
     * switch to one not warm yet draws it once it is. Their first plays' one-time work lands in their first frames shown.
     */
    private void prewarmRest() {
        // After the hide: warming pipelines and effects keep a showing screen up, but never raise a hidden one
        if (!shownReady || allWarm || CgLoadingScreen.shown()) return;
        if (prewarming >= 0) {
            if (!warm(prewarming)) return;
            prewarming = -1;
        }
        for (int i = 0; i < kinds.length; i++) {
            if (scenes[i] != null) continue;
            build(i);
            prewarming = i;
            return;
        }
        allWarm = true;
        LOGGER.info("[CgRenderDemo] every scene warmed");
    }

    /**
     * Whether the scene showing has had its first plays' one-time work: shown {@link #REHEARSE_MIN} frames, the last
     * {@link #REHEARSE_QUIET} doing no first-use work ({@link CgLoadingScreen#firstUseWork}), or {@link #REHEARSE_MOST}.
     */
    private boolean settled() {
        if (shownFrames >= REHEARSE_MOST) return true;
        return shownFrames >= REHEARSE_MIN && quietFrames >= REHEARSE_QUIET;
    }

    private static final int WARM_AHEAD_ZONE = CgTrace.name("demo.warmAhead");

    /** Frames a scene is shown behind the loading screen at least: its meshes placed and labels captured. */
    private static final int REHEARSE_MIN = 8;
    /** Frames in a row with no first-use work that end a scene's rehearsal. */
    private static final int REHEARSE_QUIET = 4;
    /** Frames a scene is shown at most, its first-use work done or not. */
    private static final int REHEARSE_MOST = 40;
    /** Whether the scene shown first has warmed and rehearsed: the load task's end, and the other scenes' start. */
    private boolean shownReady;
    /** Frames the scene showing has submitted since it was switched to, and of them the last in a row with no first-use work. */
    private int shownFrames, quietFrames;
    /** {@link CgLoadingScreen#firstUseWork} at the last frame shown. */
    private long lastWork = -1;

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

    private boolean jumped(CgCamera view) {
        double dx = view.x() - lastX, dy = view.y() - lastY, dz = view.z() - lastZ;
        return dx * dx + dy * dy + dz * dz > TELEPORT * TELEPORT;
    }

    /**
     * Puts the scene ahead of the eye, whatever the host folds into its view matrix: along the view and up the screen
     * while it floats, and level with the eye where it will stand on the ground.
     */
    private void anchor(CgCamera view) {
        int ahead = kinds[current].ahead();
        Matrix4f toWorld = new Matrix4f(view.view()).invert();
        Vector3f eye = toWorld.transformPosition(new Vector3f());
        Vector3f forward = toWorld.transformDirection(new Vector3f(0f, 0f, -1f)).normalize();
        Vector3f up = toWorld.transformDirection(new Vector3f(0f, 1f, 0f)).normalize();
        double level = Math.hypot(forward.x, forward.z);
        aheadX = level > 1.0e-3 ? forward.x / level : forward.x;
        aheadZ = level > 1.0e-3 ? forward.z / level : forward.z;
        anchorX = (long) Math.floor(view.x() + eye.x + (UNDER != null ? -UNDER[0] : aheadX * ahead));
        anchorZ = (long) Math.floor(view.z() + eye.z + (UNDER != null ? -UNDER[1] : aheadZ * ahead));
        eyeY = view.y() + eye.y;
        anchorY = Math.floor(eyeY + forward.y * ahead + up.y * ABOVE);
        anchored = true;
        grounded = false;
        worldFrames = 0;
        LOGGER.info("[CgRenderDemo] {} around ({}, {}, {}), camera at ({}, {}, {})", kinds[current].name(),
                anchorX, anchorY, anchorZ, view.x(), view.y(), view.z());
    }

    private static double[] under(String xz) {
        if (xz.isEmpty()) return null;
        String[] parts = xz.split(",");
        return new double[] {Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim())};
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
        boolean ctrl = CgInput.isKeyDown(CgKeyCodes.KEY_LCONTROL) || CgInput.isKeyDown(CgKeyCodes.KEY_RCONTROL);
        boolean shift = CgInput.isKeyDown(CgKeyCodes.KEY_LSHIFT) || CgInput.isKeyDown(CgKeyCodes.KEY_RSHIFT);
        for (int i = 0; i < KEYS.length; i++) {
            boolean down = CgInput.isKeyDown(KEYS[i]);
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
            case CgKeyCodes.KEY_L -> toggleBloom();
            case CgKeyCodes.KEY_X -> CgWorldRenderer.get().gradeDraws(!CgWorldRenderer.get().gradeDraws());
            // A quarter down or up, as the harness's.
            case CgKeyCodes.KEY_LBRACKET -> CgWorldRenderer.get().sceneEmission(CgWorldRenderer.get().sceneEmission() / 1.25f);
            case CgKeyCodes.KEY_RBRACKET -> CgWorldRenderer.get().sceneEmission(CgWorldRenderer.get().sceneEmission() * 1.25f);
            case CgKeyCodes.KEY_MINUS -> scaleBloom(1f / 1.25f);
            case CgKeyCodes.KEY_EQUALS -> scaleBloom(1.25f);
            case CgKeyCodes.KEY_O -> CgShadowsDemo.toggleShadows();
            case CgKeyCodes.KEY_K -> CgShadowsDemo.toggleTemporal();
            case CgKeyCodes.KEY_J -> CgShadowsDemo.toggleSpatial();
            case CgKeyCodes.KEY_U -> CgShadowsDemo.toggleScreenTrace();
            case CgKeyCodes.KEY_M -> CgShadowsDemo.cycleResolution();
            case CgKeyCodes.KEY_R -> CgShadowsDemo.cycleShadeScale();
            case CgKeyCodes.KEY_Z -> CgShadowsDemo.cycleQuality();
            default -> CgVfxDemoControls.get().press(key, shift);
        }
        hudDirty = true;
    }

    /** Bloom's intensity times {@code by}, while it is on. */
    private static void scaleBloom(float by) {
        CgBloom bloom = CgPostStack.get().bloom();
        if (bloom.intensity() > 0f) bloom.intensity(bloom.intensity() * by);
    }

    /** Off, its intensity put aside, or back on at the intensity it had. */
    private static void toggleBloom() {
        CgBloom bloom = CgPostStack.get().bloom();
        if (bloom.intensity() == 0f) {
            bloom.intensity(bloomIntensity);
        } else {
            bloomIntensity = bloom.intensity();
            bloom.intensity(0f);
        }
    }

    /**
     * Samples this frame for the FPS and the particles drawn (the median over the latest half second), and draws the
     * HUD; its text is laid out again only when a line changed.
     */
    private void drawHud(CgStageFrame frame) {
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
                // The engine's default font, as the harness HUD: shared, so never disposed
                hudFont = CgDefaultFont.regular(px);
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
            int w = Math.max(1, frame.host().width()), h = Math.max(1, frame.host().height());
            hudCamera.view.identity();
            hudCamera.projection.setOrtho(0f, w, h, 0f, -1f, 1f);
            hudCamera.resolution(w, h);
            hud.recordInto(frame.recording(), frame.target(), CgLoad.load(), hudCamera);
            try {
                hud.text().style(HUD_LOOK).layout(hudLayout).font(hudFont).at(4f, 4f).submit();
            } finally {
                hud.stop();
            }
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
                + ("lights".equals(kinds[wanted].name()) ? String.format(Locale.ROOT, " | Lights dropped: %,d, budget %d a cell",
                        CgWorldRenderer.get().lights().cellsDropped(), CgWorldRenderer.get().lights().budgetPerCell()) : "")
                + (scenes[wanted] != null ? scenes[wanted].hud() : "")
                + (CgCameraShake.enabled() ? String.format(Locale.ROOT, "\n\nShake [C]: on, trauma %.2f", CgCameraShake.trauma())
                : "\n\nShake [C]: off")
                + " | Kit looks [X]: " + (CgWorldRenderer.get().gradeDraws() ? "on" : "off")
                + (bloom.intensity() == 0f ? "\nBloom [L]: off"
                        : String.format(Locale.ROOT, "\nBloom [L]: on, %.2f [- =]", bloom.intensity()))
                + String.format(Locale.ROOT, " | Emission [ ]: %.2f", CgWorldRenderer.get().sceneEmission())
                + "\n" + CgVfxDemoControls.get().hudLines(waves);
    }

    // ── capture ───────────────────────────────────────────────────────────────

    /**
     * The host's target as it stands after the transparent stage, written once the GPU has copied it, frames later: a
     * hosted Vulkan device refuses a read that waits.
     */
    private static void capture(CgExternalTarget target, int w, int h) {
        CgTextureView color = target.color();
        if (color == null) return;
        CgReadback.texture(color.texture(), CgReadback.region(color, 0, 0, w, h), pixels -> write(pixels, w, h));
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
