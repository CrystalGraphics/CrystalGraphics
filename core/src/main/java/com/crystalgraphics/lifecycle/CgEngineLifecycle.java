package com.crystalgraphics.lifecycle;

import java.util.function.Consumer;

import com.crystalgraphics.asset.load.CgAssetMounts;
import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.platform.service.CgNetworkChannel;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.world.CgPlayers;

/**
 * The engine's lifecycle, and the one place a host starts and stops it: core's start on both sides, in the phases every
 * loader has, then its graphics context's start and stop ({@link CgGraphicsLifecycle}'s work, issued from here). A
 * host only says when each moment is; the frame tick and resize go to {@code CgGraphicsLifecycle} directly.
 *
 * <pre>{@code
 * // a host, as its loader constructs or pre-initialises CrystalGraphics
 * CgPlatform.register(platform);
 * CgPlatform.provide(CgModLoader.HOST, ModLoaderForge.INSTANCE);
 * CgEngineLifecycle.preInit();                 // every mod's assets mounted, its tags declared
 *
 * // where the host's network channel is registered, its world slots provided first
 * CgPlatform.provide(CgPlayerQuery.SERVICE, PlayerQueryModern.INSTANCE);
 * CgEngineLifecycle.init(channel);             // connections and networked objects
 * }</pre>
 *
 * <p>Code outside the engine hears each phase through a {@link CgLifecycleListener}:</p>
 *
 * <pre>{@code
 * CgEngineLifecycle.addListener(new CgLifecycleListener() {
 *     @Override public void onPostInit() {
 *         HIT = CgTagRegistry.global().require("mymod:cue.hit");
 *     }
 * });
 * }</pre>
 *
 * <ul>
 *   <li>{@link #postInit} is the engine's own: it runs at the first client graphics start or server start, both after
 *       every mod's init on every loader. Everything mounted then starts loading.</li>
 *   <li>Each phase runs once; a second call does nothing. {@link #init} before {@link #preInit} throws.</li>
 *   <li>{@link #init} reads player ids through {@code CgPlayers}: provide {@code CgPlayerQuery} before it.</li>
 *   <li>A listener hears a phase after the engine's own work in it, as Unreal broadcasts {@code OnPostEngineInit}
 *       after {@code UEngine::Init}; one added after a phase hears it from {@link #addListener}.</li>
 * </ul>
 */
public final class CgEngineLifecycle {

    private static final CgLifecycleListener.Registry listeners = new CgLifecycleListener.Registry();
    private static boolean preInit, init, postInit;

    private CgEngineLifecycle() {
    }

    /** The platform registered and its both-sides slots filled, every mod discovered: mods' assets mounted. */
    public static synchronized void preInit() {
        if (preInit) return;
        preInit = true;
        CgAssetMounts.mount();
        listeners.dispatch("onPreInit", CgLifecycleListener::onPreInit);
    }

    /** The host's channel registered and its world slots provided: the network installed over {@code channel}. */
    public static synchronized void init(CgNetworkChannel channel) {
        if (!preInit) throw new IllegalStateException("CgEngineLifecycle.init before preInit");
        if (init) return;
        init = true;
        CgNetwork.install(channel, CgPlayers::idOf);
        listeners.dispatch("onInit", CgLifecycleListener::onInit);
    }

    /** Every mod initialised: what was mounted starts loading. The engine runs it at its first client or server start. */
    public static synchronized void postInit() {
        if (postInit) return;
        postInit = true;
        CgAssetMounts.loadAhead();
        listeners.dispatch("onPostInit", CgLifecycleListener::onPostInit);
    }

    /**
     * Makes sure there is a graphics context to draw into: the engine's graphics start once, then {@link #postInit}, and a
     * resize when the viewport changed. Safe every frame; GL thread only.
     *
     * <pre>{@code
     * // a host, at each frame's end, the title screen's included: the engine starts before a world exists
     * CgEngineLifecycle.ensureGraphicsContext(window.getWidth(), window.getHeight());
     *
     * // a UI drawing before that first frame end, in its render() before painting
     * CgEngineLifecycle.ensureGraphicsContext(width, height);
     * }</pre>
     *
     * <ul>
     *   <li>Does nothing after {@link #destroyContext} or {@link #shutdown}, or once the engine stood down.</li>
     *   <li>Post-init follows the graphics start on a client because what loads is prepared by the effects it installs.</li>
     * </ul>
     */
    public static void ensureGraphicsContext(int width, int height) {
        boolean was = CgGraphicsLifecycle.isInitialized();
        CgGraphicsLifecycle.ensureContext(width, height);
        if (!was && CgGraphicsLifecycle.isInitialized()) postInit();
    }

    /**
     * Frees every GL resource the engine holds, listeners' first, then stops it: for a host whose rendering has stopped
     * for good. Terminal: no context starts again in this process. GL thread only.
     *
     * <pre>{@code
     * CgEngineLifecycle.destroyContext();   // the game window closing
     * }</pre>
     */
    public static void destroyContext() {
        CgGraphicsLifecycle.destroyContext();
    }

    /**
     * Stops the engine and frees nothing: for a host still dispatching frames after its shutdown signal, as Minecraft
     * does. Every engine entry becomes a no-op; listeners hear {@code onShutdown} once. Terminal.
     *
     * <pre>{@code
     * CgEngineLifecycle.shutdown();   // the game is closing
     * }</pre>
     */
    public static void shutdown() {
        CgGraphicsLifecycle.shutdown();
    }

    /**
     * Registers {@code listener} for every moment of the engine's lifecycle: its start on both sides and its graphics
     * context. Idempotent: a listener added twice still hears each moment once.
     *
     * <pre>{@code
     * CgEngineLifecycle.addListener(new CgLifecycleListener() {
     *     @Override public void onContextDestroy() { myTarget.delete(); }
     * });
     * }</pre>
     *
     * <ul>
     *   <li>What it missed is delivered from this call, in order: the phases already run, then
     *       {@code onContextInit} if a context is live. So each fires exactly once, whenever it registered.</li>
     *   <li>Phases run on the loader's thread, the context's moments on the GL thread: what a listener touches in
     *       each must be safe from it.</li>
     * </ul>
     */
    public static synchronized void addListener(CgLifecycleListener listener) {
        if (!listeners.add(listener)) return;
        if (preInit) listeners.fire(listener, "onPreInit", CgLifecycleListener::onPreInit);
        if (init) listeners.fire(listener, "onInit", CgLifecycleListener::onInit);
        if (postInit) listeners.fire(listener, "onPostInit", CgLifecycleListener::onPostInit);
        if (CgGraphicsLifecycle.isInitialized()) {
            int w = CgGraphicsLifecycle.getCurrentWidth(), h = CgGraphicsLifecycle.getCurrentHeight();
            listeners.fire(listener, "onContextInit", l -> l.onContextInit(w, h));
        }
    }

    /** Unregisters {@code listener}. Safe from inside a callback. */
    public static boolean removeListener(CgLifecycleListener listener) {
        return listeners.remove(listener);
    }

    // The graphics context's moments, signalled by CgGraphicsLifecycle at the point each belongs in its work.

    static void contextInitialized(int width, int height) {
        listeners.dispatch("onContextInit", l -> l.onContextInit(width, height));
    }

    static void frame() {
        listeners.dispatch("onFrame", FRAME);
    }

    static void contextDestroying() {
        listeners.dispatchReverse("onContextDestroy", CgLifecycleListener::onContextDestroy);
    }

    static void stopped() {
        listeners.dispatch("onShutdown", CgLifecycleListener::onShutdown);
    }

    // Non-capturing, so a frame's dispatch allocates nothing.
    private static final Consumer<CgLifecycleListener> FRAME = l -> l.onFrame(CgFrameClock.frameIndex());
}
