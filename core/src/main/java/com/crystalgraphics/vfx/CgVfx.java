package com.crystalgraphics.vfx;

import com.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle;
import com.crystalgraphics.gl.lifecycle.CgLifecycleListener;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgWorldQuery;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.vfx.net.CgVfxCueClient;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The client world's VFX: one {@link CgVfxSystem} every mod plays into, through an owner handle. The engine updates and
 * draws it each world frame on every host, and clears it when the world goes: logout, disconnect, a dimension change.
 *
 * <pre>{@code
 * static final CgVfxOwner VFX = CgVfx.owner("mymod");   // once
 * VFX.play(new HitSpark(HitSpark.LOOK, x, y, z));       // anywhere in the client
 *
 * // A preview viewport, a test or a harness scene keeps a system of its own, updated and drawn by its caller:
 * CgVfxSystem preview = new CgVfxSystem();
 * CgVfxOwner lab = CgVfx.owner("mymod", preview);
 * }</pre>
 *
 * <ul>
 *   <li>Played effects start at the next world frame, after the frame's {@code onFrame} listeners that ran before the
 *       system's: a play from an {@code onFrame} listener registered later starts a frame after.</li>
 *   <li>A dimension change or a new level clears it; warmed programs and meshes stay. Effects played in the tick that
 *       changed the level are kept.</li>
 *   <li>{@link #world()} is for what the engine owns (a HUD, a dev tool); a mod plays through its owner.</li>
 * </ul>
 */
public final class CgVfx {

    private static final ConcurrentHashMap<String, CgVfxOwner> OWNERS = new ConcurrentHashMap<>();
    /** {@link #OWNERS}' values, walked by index each frame. */
    private static final CopyOnWriteArrayList<CgVfxOwner> WORLD_OWNERS = new CopyOnWriteArrayList<>();
    private static CgVfxSystem world;
    private static boolean installed;
    private static int epoch;

    private CgVfx() {
    }

    /** The owner {@code namespace} of the world's system: the same handle each call. */
    public static CgVfxOwner owner(String namespace) {
        CgVfxOwner owner = OWNERS.get(namespace);
        return owner != null ? owner : OWNERS.computeIfAbsent(namespace, ns -> {
            CgVfxOwner made = new CgVfxOwner(ns, null);
            WORLD_OWNERS.add(made);
            return made;
        });
    }

    /** An owner over a system of the caller's, which the caller updates, draws and deletes. */
    public static CgVfxOwner owner(String namespace, CgVfxSystem system) {
        if (system == null) throw new IllegalArgumentException("system");
        return new CgVfxOwner(namespace, system);
    }

    /** The client world's system, made on first ask. Render thread. */
    public static CgVfxSystem world() {
        if (world == null) world = new CgVfxSystem();
        return world;
    }

    /** Updates and draws the world's system each world frame from now on, and clears it with the level. Once. */
    public static void install() {
        if (installed) return;
        installed = true;
        CgWorldRenderer.get().onFrame(CgVfx::frame);
        CgVfxCueClient.install();
        // Every frame's end, the title screen's too: a logout clears before the next world.
        CgGraphicsLifecycle.addListener(new CgLifecycleListener() {
            @Override
            public void onFrame(long frame) {
                if (world != null) followLevel();
            }
        });
    }

    /** Frees the world's system with the context; the next one makes another. */
    public static void release() {
        if (world != null) world.delete();
        world = null;
    }

    private static void frame(CgHostView view) {
        CgVfxSystem vfx = world();
        followLevel();
        for (int i = 0; i < WORLD_OWNERS.size(); i++) WORLD_OWNERS.get(i).drain(vfx);
        vfx.update(CgFrameClock.seconds());
        vfx.submit(CgWorldRenderer.get());
    }

    /** Clears the world's system when the level changed; when there is none any more, what waits to play too. */
    private static void followLevel() {
        int now = CgPlatform.get(CgWorldQuery.SERVICE).levelEpoch();
        if (now == epoch) return;
        epoch = now;
        world.clear();
        if (now == 0) {
            for (int i = 0; i < WORLD_OWNERS.size(); i++) WORLD_OWNERS.get(i).dropPending();
        }
    }

    /** Whether a play into the world system can ever start: not on a dedicated server, which has no context. */
    static boolean mayPlay() {
        return CgPlatform.get(CgWorldQuery.SERVICE) != CgWorldQuery.NONE || CgGraphicsLifecycle.isInitialized();
    }
}
