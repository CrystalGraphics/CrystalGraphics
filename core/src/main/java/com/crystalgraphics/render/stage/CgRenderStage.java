package com.crystalgraphics.render.stage;

import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;
import com.crystalgraphics.api.framebuffer.CgFrameBufferFormat;
import com.crystalgraphics.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlCensus;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.render.graph.CgExecutor;
import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.render.graph.CgRehearsal;

import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A point in a host's frame where renderers draw. A renderer registers on a stage; whoever owns the hook fills the
 * stage's {@link #host() frame} and fires it, and every renderer records into one frame on the host's target, which
 * executes there and then. CrystalGraphics defines the world's two and its hosts fire them; a mod defines its own and
 * fires it from a hook of its own.
 *
 * <pre>{@code
 * // Drawing at a stage
 * CgRenderStage.Registration drawing = CgRenderStage.WORLD_OPAQUE.register(0, frame -> {
 *     CgPassConstants camera = frame.defaults(new CgPassConstants());
 *     camera.view.set(view);
 *     camera.projection.set(projection);
 *     CgRasterPass pass = frame.pass(camera, CgOrder.SORTED);
 *     // ... chunks into pass ...
 *     pass.end();
 * });
 * drawing.close();   // stops it
 * }</pre>
 *
 * <pre>{@code
// A stage of your own: defined once, filled and fired from your hook on the render thread
 * public static final CgRenderStage AFTER_SKY = CgRenderStage.define("mymod:after_sky");
 * AFTER_SKY.host().set(partialTick, width, height, mainFramebufferId)
 *         .view().set(camX, camY, camZ, viewRotation, projection);
 * AFTER_SKY.fire();
 *
 * // Anywhere on the render thread: the world's camera as of its latest frame
 * CgHostView world = CgRenderStage.WORLD_OPAQUE.host().view();
 * }</pre>
 *
 * <p>A stage may fire more than once in a host frame: 1.7.10's anaglyph draws the world once per eye, and a portal
 * mod once per portal. A renderer that advances state, a simulation stepping its particles, records on the first
 * firing only, and every later firing and stage reads what it wrote:</p>
 * <pre>{@code
 * CgRenderStage.WORLD_OPAQUE.registerOncePerFrame(CgWorldRenderer.ORDER - 1, frame -> {
 *     CgComputePass step = frame.recording().compute("sparks.step");
 *     step.dispatch(simulate, capacity).bind("IN", sparks).bind("OUT", sparks);   // a history buffer
 *     step.end();
 * });
 * CgRenderStage.WORLD_OPAQUE.register(CgWorldRenderer.ORDER - 1, frame -> cullAndSort(frame));   // per view
 * }</pre>
 *
 * <ul>
 *   <li>An id is {@code namespace:path}, defined once; a second {@link #define} of it throws. Its path names the
 *       stage's trace zone and GPU timer.</li>
 *   <li>What a once-per-frame renderer writes is read by later firings, so it lives past the firing: a persistent or
 *       history buffer, never a transient or the blackboard.</li>
 *   <li>Renderers record in ascending order, ties in the order they registered. Any thread may register.</li>
 *   <li>{@link #fire} on the render thread only, from the host's own frame: it opens the host section itself, starts
 *       the engine if nothing has, and does nothing after the engine is torn down.</li>
 * </ul>
 */
public final class CgRenderStage {

    private static final Map<String, CgRenderStage> BY_ID = new LinkedHashMap<>();

    /** The opaque world is drawn and translucent terrain is not; the scene's depth is in the host's target. */
    public static final CgRenderStage WORLD_OPAQUE = define("crystalgraphics:world.opaque");

    /** Translucent terrain and particles are drawn. */
    public static final CgRenderStage WORLD_TRANSPARENT = define("crystalgraphics:world.transparent");

    /**
     * Over the in-game HUD, once it has drawn: under any screen. A GUI stage: its frame's camera is orthographic over
     * the window's device pixels, origin top left; {@code frame.guiScaledCanvas()} draws GUI-scaled instead
     * ({@link #fireOverlay}). Not fired while the HUD is hidden (F1).
     *
     * <pre>{@code
     * CgRenderStage.HUD.register(0, frame -> frame.callback("mymod:badge", this::drawBadge));
     * }</pre>
     */
    public static final CgRenderStage HUD = define("crystalgraphics:hud");

    /** Over a screen, once it has drawn: an inventory, a menu, another mod's. A GUI stage, as {@link #HUD}. */
    public static final CgRenderStage SCREEN = define("crystalgraphics:screen");

    /**
     * The order on a GUI stage for what follows a point in the world (a damage number, a name plate, a marker): under
     * the {@link #VIEWPORT_WIDGETS}, so a menu or a window covers it. Unreal's screen-space widget components, in their
     * shared layer at {@code LayerZOrder} -100 under the viewport's widgets ({@code UWidgetComponent},
     * {@code SGameLayerManager::AddLayerForPlayer}).
     *
     * <pre>{@code
     * CgRenderStage.HUD.register(CgRenderStage.WORLD_WIDGETS, frame -> drawDamageNumbers(frame));
     * CgRenderStage.HUD.register(CgRenderStage.WORLD_WIDGETS + 1, frame -> drawNamePlates(frame));   // over the numbers
     * }</pre>
     */
    public static final int WORLD_WIDGETS = -100;

    /**
     * The order on a GUI stage of the game's own interface: windows, menus, the HUD's panels, as Unreal adds a widget
     * to the viewport at ZOrder 0. Lower draws under them, higher over them.
     *
     * <pre>{@code
     * CgRenderStage.HUD.register(CgRenderStage.VIEWPORT_WIDGETS + 1, frame -> drawCursorTrail(frame));   // over every window
     * }</pre>
     */
    public static final int VIEWPORT_WIDGETS = 0;

    /** The GUI stages' projection, refilled each firing. Render thread. */
    private static final Matrix4f OVERLAY_PROJECTION = new Matrix4f();
    private static final Matrix4f OVERLAY_VIEW = new Matrix4f();

    /** A renderer registered on a stage; closing it unregisters it. */
    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    /** A renderer and where it records; {@code lastFrame} is the host frame a once-per-frame one last recorded in. */
    private static final class Entry {
        final int order;
        final long sequence;
        final CgStageRenderer renderer;
        final boolean oncePerFrame;
        /** Its record's zone: the stage, the class that made the renderer (a lambda's declaring class) and its order. */
        final int zone;
        long lastFrame = -1;

        Entry(String stage, int order, long sequence, CgStageRenderer renderer, boolean oncePerFrame) {
            this.order = order;
            this.sequence = sequence;
            this.renderer = renderer;
            this.oncePerFrame = oncePerFrame;
            String type = renderer.getClass().getSimpleName();
            int lambda = type.indexOf("$$");
            this.zone = CgTrace.name(stage + ".record/" + (lambda > 0 ? type.substring(0, lambda) : type) + "@" + order);
        }
    }

    private final String id;
    private final String path;
    private final CgHostFrame host = new CgHostFrame();
    private final int gpuName;
    private final CgStageFrame frame = new CgStageFrame(this);
    private volatile List<Entry> renderers = List.of();
    private long sequence;
    /** Stages firing now on the render thread; nested for a stage fired from another's renderer. */
    private static int firing;

    /** Whether a stage is firing: what an asset swap or a synchronous load must never happen inside. Render thread. */
    public static boolean firing() {
        return firing > 0;
    }

    private CgRenderStage(String id) {
        this.id = id;
        this.path = id.substring(id.indexOf(':') + 1);
        this.gpuName = CgGpuTrace.name(path);
    }

    /**
     * Defines a stage. Once per id, which is {@code namespace:path} — your mod's id, then the stage's.
     *
     * @throws IllegalArgumentException if {@code id} is malformed or already defined
     */
    public static CgRenderStage define(String id) {
        int colon = id.indexOf(':');
        if (colon <= 0 || colon == id.length() - 1 || id.indexOf(':', colon + 1) >= 0) {
            throw new IllegalArgumentException("a stage id is namespace:path, got " + id);
        }
        synchronized (BY_ID) {
            if (BY_ID.containsKey(id)) throw new IllegalArgumentException("stage " + id + " is already defined");
            CgRenderStage stage = new CgRenderStage(id);
            BY_ID.put(id, stage);
            return stage;
        }
    }

    /** The stage defined as {@code id}, or null. */
    @Nullable
    public static CgRenderStage byId(String id) {
        synchronized (BY_ID) {
            return BY_ID.get(id);
        }
    }

    /** Every stage defined, in the order they were. */
    public static List<CgRenderStage> all() {
        synchronized (BY_ID) {
            return List.copyOf(BY_ID.values());
        }
    }

    /** {@code namespace:path}. */
    public String id() {
        return id;
    }

    /** Registers {@code renderer} at order 0. */
    public Registration register(CgStageRenderer renderer) {
        return register(0, renderer);
    }

    /** Registers {@code renderer}, recording after every lower {@code order} and after this order's earlier ones. */
    public Registration register(int order, CgStageRenderer renderer) {
        return add(order, renderer, false);
    }

    /**
     * Registers {@code renderer} to record on this stage's first firing of each host frame only, ordered as
     * {@link #register(int, CgStageRenderer)} orders: a simulation, which a second firing of the frame must not step
     * again. Work that depends on the view (culling, sorting by depth) registers per firing instead.
     */
    public Registration registerOncePerFrame(int order, CgStageRenderer renderer) {
        return add(order, renderer, true);
    }

    private Registration add(int order, CgStageRenderer renderer, boolean oncePerFrame) {
        Entry entry;
        synchronized (this) {
            entry = new Entry(path, order, sequence++, renderer, oncePerFrame);
            List<Entry> next = new ArrayList<>(renderers);
            next.add(entry);
            next.sort(Comparator.comparingInt((Entry e) -> e.order).thenComparingLong(e -> e.sequence));
            renderers = List.copyOf(next);
        }
        return () -> {
            synchronized (this) {
                List<Entry> next = new ArrayList<>(renderers);
                next.remove(entry);
                renderers = List.copyOf(next);
            }
        };
    }

    /** Whether any renderer is registered. */
    public boolean hasRenderers() {
        return !renderers.isEmpty();
    }

    /**
     * What the host said about this stage's latest frame: filled before {@link #fire}, readable by anything on the
     * render thread after — the world's camera from a HUD, say. One instance, refilled every frame.
     */
    public CgHostFrame host() {
        return host;
    }

    /**
     * Lets every renderer record into one frame on the host's target under {@link #host()}, and executes it, inside a
     * host section of its own. Render thread, from the host's frame. Nothing happens after the engine is torn down.
     */
    public void fire() {
        // Nothing runs after a teardown. Minecraft keeps dispatching render stages for a frame or two after
        // GameShuttisngDownEvent, and by then every registry is deleted: a stage reaching CgMaterial.load threw
        // "CgMaterialRegistry has been deleted" out of a render event, which surfaced as a crash on quitting.
        if (CgGraphicsLifecycle.isContextDestroyed() || CgGraphicsLifecycle.isStoodDown()) return;
        // Stage entry: the host and every mod hooking the same point drew just before this, through APIs the state
        // manager cannot see. Inside a section already open, only our own code ran since it did: what it set (the
        // main target and its viewport) stands.
        if (!CgGL.inHostSection()) CgGlState.invalidateAllIfPresent();
        // What the host handed us, off unless -Dcrystalgraphics.host.census.
        CgGlCensus.at(path);
        CgGL.fromHost();
        try {
            // The engine starts on the first stage a host fires if the host never announced its context.
            CgEngineLifecycle.ensureGraphicsContext(host.width(), host.height());
            if (renderers.isEmpty() || !CgGraphicsLifecycle.isInitialized()) return;
            CgGpuTrace.begin(gpuName, CgGpuTrace.WATCH);   // timed always: a slow firing is logged by name
            firing++;
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, path)) {
                recordAndExecute();
            } finally {
                firing--;
                CgGpuTrace.end();
            }
        } finally {
            CgGL.toHost();
        }
    }

    /**
     * {@link #fire} into {@code target} rather than the host's bound target: a view of the world of its own, as an
     * editor's viewport draws one. It is the firing's current target, and nothing is bound. Fill {@link #host()} for the
     * target first, its size and id included, and put the host's frame back after.
     *
     * <pre>{@code
     * stage.host().set(0f, target.getWidth(), target.getHeight(), target.getId()).view().set(x, y, z, view, projection);
     * stage.fireInto(target);
     * }</pre>
     */
    public void fireInto(CgFrameBuffer target) {
        CgGL.fromHost();
        try (CgExecutor.Into ignored = CgExecutor.into(target)) {
            fire();
        } finally {
            CgGL.toHost();
        }
    }

    /**
     * Fills this stage's frame as a GUI's and fires it: the world's partial tick, the host's {@code mainFramebuffer} of
     * {@code width x height} device pixels, a camera orthographic over those pixels, origin top left, and {@code scale},
     * the host GUI's, as {@link CgHostFrame#guiScale()}. What a host's GUI hook calls for {@link #HUD} and
     * {@link #SCREEN}. The camera is the window's, as Unreal's canvas is; a renderer wanting the GUI's takes
     * {@code frame.guiScaledCanvas()}.
     *
     * <pre>{@code
     * CgRenderStage.HUD.fireOverlay(window.getWidth(), window.getHeight(), (float) window.getGuiScale(), mainFbo);
     * }</pre>
     */
    public void fireOverlay(int width, int height, float scale, int mainFramebuffer) {
        host.set(WORLD_OPAQUE.host.partialTick(), width, height, mainFramebuffer).guiScale(scale).view().set(0d, 0d, 0d,
                OVERLAY_VIEW.identity(), OVERLAY_PROJECTION.setOrtho(0f, width, height, 0f, -1f, 1f));
        fire();
    }

    /**
     * Records every renderer into the stage's frame under the host's latest frame and executes it into a target of the
     * rehearsals' own, the host's size and depth, which every stage's rehearsal shares: a {@link CgRehearsal}'s pass, at
     * the frame boundary, after the frame's last firing. It is bound as the current target, while the host frame still
     * names the host's, so what renderers decide from the host's target (a depth to draw beside, an attachment it takes)
     * they decide as the cast will. Once-per-frame renderers are skipped: they ran this frame, and their state is the
     * real frame's. Render thread.
     */
    public void rehearse() {
        if (renderers.isEmpty() || !CgGraphicsLifecycle.isInitialized()) return;
        if (!CgRehearsal.active()) throw new IllegalStateException(path + " rehearses only inside a rehearsal's pass");
        if (host.width() <= 0 || host.height() <= 0) {
            // Never fired (a loading screen): the window's size, and a camera at the origin looking down -Z, which the
            // next real firing replaces.
            int w = CgGraphicsLifecycle.getCurrentWidth(), h = CgGraphicsLifecycle.getCurrentHeight();
            if (w <= 0 || h <= 0) return;
            host.set(0f, w, h, host.mainFramebuffer()).view().set(0d, 0d, 0d, REHEARSAL_VIEW,
                    REHEARSAL_PROJECTION.setPerspective((float) Math.toRadians(70.0), (float) w / h, 0.05f, 512f));
        }
        int hostTarget = host.mainFramebuffer();
        if (rehearsalTarget == null || rehearsalTarget.isDeleted() || hostTarget != rehearsedHost
                || rehearsalTarget.getWidth() != host.width() || rehearsalTarget.getHeight() != host.height()) {
            if (rehearsalTarget != null && !rehearsalTarget.isDeleted()) rehearsalTarget.delete();
            // The host's depth type and size: copies of the current target are cached by both and taken at the host's
            // viewport, so the cast reuses the rehearsal's and a copy never reads past the target.
            CgTextureType depth = CgFrameBuffer.depthTypeOf(hostTarget);
            rehearsalTarget = CgFrameBuffer.createOwned("cg_rehearsal", host.width(), host.height(),
                    CgFrameBufferFormat.builder("cg_rehearsal").color(0, CgTextureType.RGBA8)
                            .depth(depth != null ? depth : CgTextureType.DEPTH24_STENCIL8).build());
            rehearsedHost = hostTarget;
        }
        firing++;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, REHEARSE)) {
            frame.beginRehearsal(host);
            record(CgFrameRing.frame(), true);
            frame.executeInto(rehearsalTarget);
        } finally {
            firing--;
        }
    }

    private static final int REHEARSE = CgTrace.name("stage.rehearse");
    /** A stage that never fired is rehearsed under these: identity at the origin, and a 70-degree perspective. */
    private static final Matrix4f REHEARSAL_VIEW = new Matrix4f(), REHEARSAL_PROJECTION = new Matrix4f();
    /** What a rehearsal draws into: the host target's colour and depth, hidden; and the host target it is shaped by. */
    @Nullable
    private static CgFrameBuffer rehearsalTarget;
    private static int rehearsedHost = -1;

    /** One firing's work once the engine runs: the renderers record into the stage's frame, which executes. */
    void recordAndExecute() {
        frame.begin(host);
        record(CgFrameRing.frame(), false);
        frame.execute();
    }

    /** Lets every renderer due in host frame {@code hostFrame} record into the stage's frame, in order. */
    void record(long hostFrame) {
        record(hostFrame, false);
    }

    /** As {@link #record(long)}; a rehearsal's skips the once-per-frame renderers. */
    void record(long hostFrame, boolean rehearsal) {
        List<Entry> due = renderers;
        for (int i = 0; i < due.size(); i++) {
            Entry entry = due.get(i);
            if (entry.oncePerFrame) {
                if (rehearsal) continue;
                if (entry.lastFrame == hostFrame) continue;
                entry.lastFrame = hostFrame;
            }
            try (CgTrace.Zone z = CgTrace.zone(CgChannels.WORLD, entry.zone)) {
                frame.turnBegins();
                try {
                    entry.renderer.render(frame);
                } finally {
                    frame.turnEnds();
                }
            }
        }
    }

    @Override
    public String toString() {
        return id;
    }
}
