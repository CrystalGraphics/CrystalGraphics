package com.crystalgraphics.render.stage;

import com.crystalgraphics.render.scene.CgScene;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A named point in the frame where renderers draw, named for the phase it joins (Unreal's scene view extension hooks).
 * The world stages run once per view of the scenes they are registered for, at their place in the scene renderer's
 * order; the GUI stages once a frame, over the host's interface.
 *
 * <pre>{@code
 * // Compute what the frame's draws read, before anything of ours draws
 * CgRenderStage.Registration sim = CgRenderStage.PREPARE.register(frame -> {
 *     CgComputePass step = frame.recording().compute("mymod.sparks.step");
 *     step.dispatch(simulate, capacity).bind("IN", sparks).bind("OUT", sparks);
 *     step.end();
 * });
 *
 * // On the lit opaque scene, before anything translucent: an outline from custom depth
 * CgRenderStage.SURFACES.register(frame -> {
 *     CgRasterPass pass = frame.recording().raster("mymod.outline")
 *             .color(frame.target(), CgLoad.load())
 *             .reads(frame.view().textures().customDepth())
 *             .camera(frame.camera());
 *     pass.draw(FULL_SCREEN, outlineMaterial);              // CgMesh.vertices(3, TRIANGLES): one triangle over it
 *     pass.end();
 * });
 *
 * // The HUD: the window's pixels, or the host GUI's
 * CgRenderStage.HUD.register(CgRenderStage.WORLD_WIDGETS, frame -> {
 *     if (frame.project(x, y, z, at)) frame.canvas().text(damage, at.x, at.y);
 * });
 *
 * sim.close();                                                     // unregisters
 * }</pre>
 *
 * <pre>{@code
 * // A lab's pass for its own scene, and a debug view in every scene
 * CgRenderStage.OVERLAY.register(frame -> drawGizmos(frame)).scene(lab);
 * CgRenderStage.OVERLAY.register(frame -> drawBounds(frame)).allScenes();
 * }</pre>
 *
 * <ul>
 *   <li>Within a stage, ascending {@code order} (0 by default), ties in registration order. The engine's own work sits
 *       between stages, never at a number inside one.</li>
 *   <li>A world stage reaches the client's scene unless its registration names others: a mod's pass reaches the game's
 *       views and captures, never an editor's scene unasked.</li>
 *   <li>A world stage runs once per view: work that must happen once a frame whatever the views (a simulation's step)
 *       keeps its own frame stamp.</li>
 *   <li>Any thread may register; renderers run on the render thread.</li>
 *   <li>Firing a stage is the hosts' ({@code CgStageHost}); a mod defines and fires a stage of its own there.</li>
 * </ul>
 */
public final class CgRenderStage {

    /** Where a stage runs and who fires it. */
    enum Kind {
        /** Per view, at its place in the scene renderer's order. */
        WORLD,
        /** Once a frame, over the host's interface: fired by the host. */
        GUI,
        /** A host's callback into the world, which the scene renderer runs inside: nothing registers on it. */
        HOST,
        /** A mod's own, fired from its own hook. */
        CUSTOM
    }

    private static final Map<String, CgRenderStage> BY_ID = new LinkedHashMap<>();

    /**
     * Per view, before anything of ours draws, in the host's opaque callback: compute what the frame's draws read (a
     * simulation, a cull, a count). Its target is the host's, as the host drew it.
     */
    public static final CgRenderStage PREPARE = define("crystalgraphics:prepare", Kind.WORLD);

    /** Per view, after our opaque draws and before the host's translucent ones: opaque work of your own. */
    public static final CgRenderStage OPAQUE = define("crystalgraphics:opaque", Kind.WORLD);

    /**
     * Per view, on the lit opaque scene before anything translucent: decals, outlines, effects on surfaces, post
     * materials before translucency (Unreal's {@code BL_BeforeTranslucency}). Its target is the HDR scene.
     */
    public static final CgRenderStage SURFACES = define("crystalgraphics:surfaces", Kind.WORLD);

    /** Per view, after our translucent draws and distortion, before post: what post should grade and bloom. */
    public static final CgRenderStage TRANSLUCENT = define("crystalgraphics:translucent", Kind.WORLD);

    /** Per view, on the final picture before the HUD: world-space overlays post must not touch. */
    public static final CgRenderStage OVERLAY = define("crystalgraphics:overlay", Kind.WORLD);

    /**
     * Over the in-game HUD, once it has drawn, once a frame: under any screen. Its canvas is in the window's device
     * pixels, origin top left; {@code frame.scaled()} draws in the host GUI's. Not fired while the HUD is hidden (F1).
     */
    public static final CgRenderStage HUD = define("crystalgraphics:hud", Kind.GUI);

    /** Over a screen, once it has drawn: an inventory, a menu, another mod's. Once a frame, as {@link #HUD}. */
    public static final CgRenderStage SCREEN = define("crystalgraphics:screen", Kind.GUI);

    /**
     * The order on a GUI stage for what follows a point in the world (a damage number, a name plate): under the
     * {@link #SCREEN_WIDGETS}, so a menu covers it. Unreal's screen-space widget components' layer, at
     * {@code LayerZOrder} -100 ({@code SGameLayerManager::AddLayerForPlayer}).
     *
     * <pre>{@code
     * CgRenderStage.HUD.register(CgRenderStage.WORLD_WIDGETS, frame -> drawDamageNumbers(frame));
     * CgRenderStage.HUD.register(CgRenderStage.WORLD_WIDGETS + 1, frame -> drawNamePlates(frame));   // over the numbers
     * }</pre>
     */
    public static final int WORLD_WIDGETS = -100;

    /**
     * The order on a GUI stage of the game's own interface (windows, menus, panels), over {@link #WORLD_WIDGETS}:
     * Unreal's viewport ZOrder 0.
     */
    public static final int SCREEN_WIDGETS = 0;

    /** A renderer registered on a stage: which scenes it reaches, and closing it unregisters it. */
    public interface Registration extends AutoCloseable {

        /** Reaches {@code scene}'s views as well: a lab's pass for its own scene. World stages only. */
        Registration scene(CgScene scene);

        /** Reaches every scene's views: a debug view, a pass that must draw in every capture and viewport. */
        Registration allScenes();

        @Override
        void close();
    }

    private final class Entry implements Registration {
        final int order;
        final long sequence;
        final CgStageRenderer renderer;
        final int zone;
        /** The scenes it reaches besides none; null for the client's alone. */
        @Nullable
        volatile Set<CgScene> scenes;
        volatile boolean all;

        Entry(int order, long sequence, CgStageRenderer renderer) {
            this.order = order;
            this.sequence = sequence;
            this.renderer = renderer;
            String type = renderer.getClass().getSimpleName();
            int lambda = type.indexOf("$$");
            this.zone = CgTrace.name(path + ".record/" + (lambda > 0 ? type.substring(0, lambda) : type) + "@" + order);
        }

        boolean reaches(@Nullable CgScene scene) {
            if (kind != Kind.WORLD || all) return true;
            Set<CgScene> s = scenes;
            return s == null ? scene == CgScene.client() : s.contains(scene);
        }

        @Override
        public Registration scene(CgScene scene) {
            if (kind != Kind.WORLD) throw new IllegalStateException(id + " runs once a frame: it has no scenes");
            synchronized (CgRenderStage.this) {
                Set<CgScene> next = Collections.newSetFromMap(new IdentityHashMap<>());
                if (scenes != null) next.addAll(scenes);
                next.add(scene);
                scenes = next;
            }
            return this;
        }

        @Override
        public Registration allScenes() {
            if (kind != Kind.WORLD) throw new IllegalStateException(id + " runs once a frame: it has no scenes");
            all = true;
            return this;
        }

        @Override
        public void close() {
            synchronized (CgRenderStage.this) {
                List<Entry> next = new ArrayList<>(renderers);
                next.remove(this);
                renderers = List.copyOf(next);
            }
        }
    }

    private final String id;
    private final String path;
    final Kind kind;
    private volatile List<Entry> renderers = List.of();
    private long sequence;

    private CgRenderStage(String id, Kind kind) {
        this.id = id;
        this.path = id.substring(id.indexOf(':') + 1);
        this.kind = kind;
    }

    /** Defines a stage once per id ({@code namespace:path}); a second definition throws. */
    static CgRenderStage define(String id, Kind kind) {
        int colon = id.indexOf(':');
        if (colon <= 0 || colon == id.length() - 1 || id.indexOf(':', colon + 1) >= 0) {
            throw new IllegalArgumentException("a stage id is namespace:path, got " + id);
        }
        synchronized (BY_ID) {
            if (BY_ID.containsKey(id)) throw new IllegalArgumentException("stage " + id + " is already defined");
            CgRenderStage stage = new CgRenderStage(id, kind);
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

    /** {@code namespace:path}. */
    public String id() {
        return id;
    }

    /** Its path: its trace zone's and GPU timer's name. */
    String path() {
        return path;
    }

    /** Whether it runs per view in the scene renderer's order. */
    public boolean perView() {
        return kind == Kind.WORLD;
    }

    /** Registers {@code renderer} at order 0. */
    public Registration register(CgStageRenderer renderer) {
        return register(0, renderer);
    }

    /** Registers {@code renderer}, recording after every lower {@code order} and after this order's earlier ones. */
    public Registration register(int order, CgStageRenderer renderer) {
        if (kind == Kind.HOST) {
            throw new IllegalStateException(id + " is a host's callback: register on PREPARE, OPAQUE, SURFACES,"
                    + " TRANSLUCENT or OVERLAY");
        }
        Entry entry;
        synchronized (this) {
            entry = new Entry(order, sequence++, renderer);
            List<Entry> next = new ArrayList<>(renderers);
            next.add(entry);
            next.sort(Comparator.comparingInt((Entry e) -> e.order).thenComparingLong(e -> e.sequence));
            renderers = List.copyOf(next);
        }
        return entry;
    }

    /** Whether any renderer is registered. */
    public boolean hasRenderers() {
        return !renderers.isEmpty();
    }

    /** Whether a renderer reaching {@code scene} is registered. */
    public boolean hasRenderers(@Nullable CgScene scene) {
        List<Entry> due = renderers;
        for (int i = 0; i < due.size(); i++) if (due.get(i).reaches(scene)) return true;
        return false;
    }

    /** Lets every renderer reaching the frame's scene record into it, in order, each a turn of its canvas. */
    void record(CgStageFrame frame) {
        List<Entry> due = renderers;
        CgScene scene = frame.sceneOrNull();
        for (int i = 0; i < due.size(); i++) {
            Entry entry = due.get(i);
            if (!entry.reaches(scene)) continue;
            try (CgTrace.Zone z = CgTrace.zone(CgChannels.WORLD, entry.zone)) {
                frame.turnBegins(this);
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
