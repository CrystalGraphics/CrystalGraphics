package com.crystalgraphics.render.stage;

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
 * A point in a host's frame where renderers draw. A renderer registers on a stage; whoever owns the hook fires it, and
 * every renderer records into one frame on the host's target, which executes there and then. CrystalGraphics defines
 * the world's two and its hosts fire them; a mod defines its own and fires it from a hook of its own.
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
 * // A stage of your own: defined once, fired from your hook on the render thread
 * public static final CgRenderStage AFTER_SKY = CgRenderStage.define("mymod:after_sky");
 * AFTER_SKY.fire(new CgHostFrame(partialTick, width, height, mainFramebufferId));
 * }</pre>
 *
 * <ul>
 *   <li>An id is {@code namespace:path}, defined once; a second {@link #define} of it throws. Its path names the
 *       stage's trace zone and GPU timer.</li>
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

    private record Entry(int order, long sequence, CgStageRenderer renderer) {}

    private final String id;
    private final String path;
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
        Entry entry;
        synchronized (this) {
            entry = new Entry(order, sequence++, renderer);
            List<Entry> next = new ArrayList<>(renderers);
            next.add(entry);
            next.sort(Comparator.comparingInt(Entry::order).thenComparingLong(Entry::sequence));
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
     * Lets every renderer record into one frame on the host's target and executes it, inside a host section of its
     * own. Render thread, from the host's frame. Nothing happens after the engine is torn down.
     */
    public void fire(CgHostFrame host) {
        // NOTHING RUNS AFTER A TEARDOWN. Minecraft keeps dispatching render stages for a frame or two after
        // GameShuttisngDownEvent, and by then every registry is deleted: a stage reaching CgMaterial.load threw
        // "CgMaterialRegistry has been deleted" out of a render event, which surfaced as a crash on quitting.
        if (CgGraphicsLifecycle.isContextDestroyed() || CgGraphicsLifecycle.isStoodDown()) return;
        // Stage entry: the host and every mod hooking the same point drew just before this, through APIs the state
        // manager cannot see.
        CgGlState.invalidateAllIfPresent();
        // What the host handed us, off unless -Dcrystalgraphics.host.census.
        CgGlCensus.at(path);
        CgGL.fromHost();
        try {
            // The engine starts on the first stage a host fires if the host never announced its context.
            CgGraphicsLifecycle.ensureContext(host.width(), host.height());
            List<Entry> current = renderers;
            if (current.isEmpty() || !CgGraphicsLifecycle.isInitialized()) return;
            CgGpuTrace.begin(gpuName);
            try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, path)) {
                frame.begin(host);
                for (Entry entry : current) entry.renderer().render(frame);
                frame.execute();
            } finally {
                CgGpuTrace.end();
            }
        } finally {
            CgGL.toHost();
        }
    }

    @Override
    public String toString() {
        return id;
    }
}
