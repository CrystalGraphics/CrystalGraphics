package com.crystalgraphics.render.stage;

import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlCensus;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

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
        long lastFrame = -1;

        Entry(int order, long sequence, CgStageRenderer renderer, boolean oncePerFrame) {
            this.order = order;
            this.sequence = sequence;
            this.renderer = renderer;
            this.oncePerFrame = oncePerFrame;
        }
    }

    private final String id;
    private final String path;
    private final CgHostFrame host = new CgHostFrame();
    private final int gpuName;
    private final CgStageFrame frame = new CgStageFrame(this);
    private volatile List<Entry> renderers = List.of();
    private long sequence;

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
            entry = new Entry(order, sequence++, renderer, oncePerFrame);
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
        // NOTHING RUNS AFTER A TEARDOWN. Minecraft keeps dispatching render stages for a frame or two after
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
            CgGraphicsLifecycle.ensureContext(host.width(), host.height());
            if (renderers.isEmpty() || !CgGraphicsLifecycle.isInitialized()) return;
            CgGpuTrace.begin(gpuName);
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, path)) {
                frame.begin(host);
                record(CgFrameRing.frame());
                frame.execute();
            } finally {
                CgGpuTrace.end();
            }
        } finally {
            CgGL.toHost();
        }
    }

    /** Lets every renderer due in host frame {@code hostFrame} record into the stage's frame, in order. */
    void record(long hostFrame) {
        for (Entry entry : renderers) {
            if (entry.oncePerFrame) {
                if (entry.lastFrame == hostFrame) continue;
                entry.lastFrame = hostFrame;
            }
            entry.renderer.render(frame);
        }
    }

    @Override
    public String toString() {
        return id;
    }
}
