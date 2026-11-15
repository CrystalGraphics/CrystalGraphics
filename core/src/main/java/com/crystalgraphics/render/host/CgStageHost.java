package com.crystalgraphics.render.host;

import com.crystalgraphics.camera.CgCamera;
import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.platform.rhi.CgDeviceSection;
import com.crystalgraphics.platform.rhi.CgExternalTarget;
import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.render.view.CgView;
import com.crystalgraphics.render.scene.renderer.CgSceneRenderer;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.stage.CgStageFiring;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import org.joml.Matrix4f;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The hosts' side of the frame: the two callbacks into the host's world the scene renderer draws inside, the GUI stages
 * fired over the host's interface, and a mod's own stage fired from its own hook. A renderer never calls it; it
 * registers on a {@link CgRenderStage}.
 *
 * <pre>{@code
 * // A host's world: its opaque callback, then its translucent one
 * CgStageHost.frame(CgStageHost.WORLD_OPAQUE).set(partialTick, width, height, mainFbo).camera().set(x, y, z, view, projection);
 * CgStageHost.fire(CgStageHost.WORLD_OPAQUE);
 * // ... the host draws its translucent terrain ...
 * CgStageHost.fire(CgStageHost.WORLD_TRANSPARENT);
 *
 * // Its interface
 * CgStageHost.fireOverlay(CgRenderStage.HUD, window.getWidth(), window.getHeight(), guiScale, mainFbo);
 * }</pre>
 *
 * <pre>{@code
 * // A mod's own stage, fired from a hook of its own
 * public static final CgRenderStage AFTER_SKY = CgStageHost.define("mymod:after_sky");
 * CgStageHost.frame(AFTER_SKY).set(partialTick, width, height, mainFbo).camera().set(x, y, z, view, projection);
 * CgStageHost.fire(AFTER_SKY);
 * }</pre>
 *
 * <ul>
 *   <li>Render thread, from the host's own frame. Each fire opens a host section of its own, starts the engine if
 *       nothing has, and does nothing once the engine is torn down.</li>
 *   <li>The two world callbacks share one frame: {@code frame(WORLD_TRANSPARENT)} is {@code frame(WORLD_OPAQUE)}.</li>
 * </ul>
 */
public final class CgStageHost {

    /** The host's opaque world is drawn, its translucent terrain not; the scene's depth is in the host's target. */
    public static final CgRenderStage WORLD_OPAQUE = CgStageFiring.hostStage("crystalgraphics:world.opaque");

    /** The host's translucent terrain and particles are drawn: ours follow, then post. */
    public static final CgRenderStage WORLD_TRANSPARENT = CgStageFiring.hostStage("crystalgraphics:world.transparent");

    private static final Matrix4f OVERLAY_PROJECTION = new Matrix4f(), OVERLAY_VIEW = new Matrix4f();
    private static final Map<CgRenderStage, Fired> FIRED = new IdentityHashMap<>();
    /** Stages firing now; nested for a stage fired from another's renderer. */
    private static int firing;

    /** A fired stage's host frame, its firing and its GPU timer. */
    private static final class Fired {
        final CgHostFrame host;
        final CgStageFiring firing;
        final int gpuName;
        final int zone;

        Fired(CgRenderStage stage, CgHostFrame host) {
            this.host = host;
            String path = stage.id().substring(stage.id().indexOf(':') + 1);
            firing = new CgStageFiring(path);
            gpuName = CgGpuTrace.name(path);
            zone = CgTrace.name(path);
        }
    }

    private CgStageHost() {
    }

    private static Fired fired(CgRenderStage stage) {
        synchronized (FIRED) {
            Fired f = FIRED.get(stage);
            if (f != null) return f;
            // The two world callbacks are one host frame's: the transparent one shares the opaque one's camera and target
            CgHostFrame host = stage == WORLD_TRANSPARENT ? fired(WORLD_OPAQUE).host : new CgHostFrame();
            FIRED.put(stage, f = new Fired(stage, host));
            return f;
        }
    }

    /**
     * What the host fills before firing {@code stage}: its timing, target, camera, environment and textures. One per
     * stage, refilled every frame; readable after (a world camera from a HUD reads {@code CgView.main()} instead).
     */
    public static CgHostFrame frame(CgRenderStage stage) {
        return fired(stage).host;
    }

    /** Defines a stage of a mod's own, which it fires from its own hook with {@link #fire}. Once per id. */
    public static CgRenderStage define(String id) {
        return CgStageFiring.customStage(id);
    }

    /** Whether a stage is firing: what an asset swap or a synchronous load must never happen inside. Render thread. */
    public static boolean firing() {
        return firing > 0;
    }

    /**
     * Fires {@code stage} under its {@link #frame}: a world callback draws its view's scene through the scene renderer;
     * a GUI or custom stage lets its renderers record. Executes there and then, in a host section of its own.
     */
    public static void fire(CgRenderStage stage) {
        // Minecraft keeps dispatching render stages for a frame or two after it shuts down, every registry gone by then
        if (CgGraphicsLifecycle.isContextDestroyed() || CgGraphicsLifecycle.isStoodDown()) return;
        Fired f = fired(stage);
        boolean world = stage == WORLD_OPAQUE || stage == WORLD_TRANSPARENT;
        // Its scene's captures draw first, each a view of its own, before this view's firing is begun
        if (stage == WORLD_OPAQUE && CgGraphicsLifecycle.isInitialized()) f.host.view().scene().primitives().updateCaptures();
        CgDeviceSection.begin();
        try {
            CgEngineLifecycle.ensureGraphicsContext(f.host.width(), f.host.height());
            if (!CgGraphicsLifecycle.isInitialized() || !world && !stage.hasRenderers()) return;
            boolean gui = stage == CgRenderStage.HUD || stage == CgRenderStage.SCREEN;
            try (CgExecutor.Into ignored = CgExecutor.hasCurrentTarget() ? null
                    : CgExecutor.into(gui ? f.host.target().colorOnly() : f.host.target())) {
                CgGpuTrace.begin(f.gpuName, CgGpuTrace.WATCH);   // timed always: a slow firing is logged by name
                firing++;
                try (CgTrace.Zone z = CgTrace.zone(CgChannels.WORLD, f.zone)) {
                    if (world) {
                        f.firing.beginWorld(f.host, stage == WORLD_OPAQUE);
                        CgSceneRenderer renderer = f.firing.view().scene().renderer();
                        if (stage == WORLD_OPAQUE) renderer.opaque(f.firing);
                        else renderer.translucent(f.firing);
                    } else {
                        f.firing.beginOnce(f.host);
                        f.firing.run(stage);
                    }
                    f.firing.execute();
                } finally {
                    firing--;
                    CgGpuTrace.end();
                }
            }
        } finally {
            CgDeviceSection.end();
        }
    }

    /**
     * Fills a GUI stage's frame and fires it: the world's partial tick, the host's {@code mainFramebuffer} of
     * {@code width x height} device pixels, a camera orthographic over those pixels, origin top left, and the host GUI's
     * {@code scale}. What a host's GUI hook calls for {@link CgRenderStage#HUD} and {@link CgRenderStage#SCREEN}.
     */
    public static void fireOverlay(CgRenderStage stage, int width, int height, float scale, int mainFramebuffer) {
        frame(stage).set(frame(WORLD_OPAQUE).partialTick(), width, height, mainFramebuffer);
        overlay(stage, width, height, scale);
    }

    /** {@link #fireOverlay(CgRenderStage, int, int, float, int)} over a target given whole: a framebuffer of ours. */
    public static void fireOverlay(CgRenderStage stage, int width, int height, float scale, CgExternalTarget target) {
        frame(stage).set(frame(WORLD_OPAQUE).partialTick(), width, height, target);
        overlay(stage, width, height, scale);
    }

    /** What a view of the engine's own fires through: its host frame (the target) and firing. */
    private static final Fired VIEWS = new Fired(define("crystalgraphics:view"), new CgHostFrame());

    /**
     * Engine side, {@code CgView.renderInto}'s: {@code view}'s whole frame now, both world callbacks and post, into
     * {@code target} at its size, through the view's own camera. A view of the client scene takes the game's sky and
     * host textures; a view of a scene of its own, that scene's environment. Render thread; inside a firing too (a
     * viewport drawn from the HUD), the target its own.
     */
    public static void renderView(CgView view, CgExternalTarget target) {
        if (CgGraphicsLifecycle.isContextDestroyed() || CgGraphicsLifecycle.isStoodDown()) return;
        // Its scene's captures first: each is a renderView of its own, and the shared firing below is begun after
        if (CgGraphicsLifecycle.isInitialized()) view.scene().primitives().updateCaptures();
        CgHostFrame game = frame(WORLD_OPAQUE);
        VIEWS.host.set(game.partialTick(), target.width(), target.height(), target);
        view.size(target.width(), target.height());
        if (view.scene() == CgScene.client()) {
            view.environment().set(game.environment());
            view.hostTextures().set(game.textures());
        } else {
            view.environment().set(view.scene().environment());
        }
        CgDeviceSection.begin();
        try {
            CgEngineLifecycle.ensureGraphicsContext(target.width(), target.height());
            if (!CgGraphicsLifecycle.isInitialized()) return;
            try (CgExecutor.Into ignored = CgExecutor.into(target)) {
                CgGpuTrace.begin(VIEWS.gpuName, CgGpuTrace.WATCH);
                firing++;
                try (CgTrace.Zone z = CgTrace.zone(CgChannels.WORLD, VIEWS.zone)) {
                    VIEWS.firing.beginView(VIEWS.host, view);
                    CgSceneRenderer renderer = view.scene().renderer();
                    renderer.opaque(VIEWS.firing);
                    renderer.translucent(VIEWS.firing);
                    VIEWS.firing.execute();
                } finally {
                    firing--;
                    CgGpuTrace.end();
                }
            }
        } finally {
            CgDeviceSection.end();
        }
    }

    private static void overlay(CgRenderStage stage, int width, int height, float scale) {
        frame(stage).guiScale(scale).camera().set(0d, 0d, 0d, OVERLAY_VIEW.identity(),
                CgCamera.orthographicMatrix(OVERLAY_PROJECTION, 0f, width, height, 0f, -1f, 1f));
        fire(stage);
    }
}
