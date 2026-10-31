package com.crystalgraphics.lifecycle;

import com.crystalgraphics.asset.load.CgAssetMounts;
import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.platform.service.CgNetworkChannel;
import com.crystalgraphics.world.CgPlayers;

/**
 * The engine's start, on both sides, in the phases every loader has: what core loads and starts as the game
 * initialises, in one place, so a host only says when each phase is. Graphics, which need a context, start later in
 * {@link CgGraphicsLifecycle}.
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
 *     @Override public void onEnginePostInit() {
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
        listeners.dispatch("onEnginePreInit", CgLifecycleListener::onEnginePreInit);
    }

    /** The host's channel registered and its world slots provided: the network installed over {@code channel}. */
    public static synchronized void init(CgNetworkChannel channel) {
        if (!preInit) throw new IllegalStateException("CgEngineLifecycle.init before preInit");
        if (init) return;
        init = true;
        CgNetwork.install(channel, CgPlayers::idOf);
        listeners.dispatch("onEngineInit", CgLifecycleListener::onEngineInit);
    }

    /** Every mod initialised: what was mounted starts loading. The engine runs it at its first client or server start. */
    public static synchronized void postInit() {
        if (postInit) return;
        postInit = true;
        CgAssetMounts.loadAhead();
        listeners.dispatch("onEnginePostInit", CgLifecycleListener::onEnginePostInit);
    }

    /**
     * Registers {@code listener} for every moment of the engine's start and of its graphics context: the same as
     * {@link CgGraphicsLifecycle#addListener}. Idempotent.
     *
     * <ul>
     *   <li>A phase already run is delivered from this call, in phase order, so each fires exactly once.</li>
     *   <li>Phases run on the loader's thread: what a listener touches there must be safe from it.</li>
     * </ul>
     */
    public static void addListener(CgLifecycleListener listener) {
        CgGraphicsLifecycle.addListener(listener);
    }

    /** Unregisters {@code listener} from both lifecycles. Safe from inside a callback. */
    public static boolean removeListener(CgLifecycleListener listener) {
        return CgGraphicsLifecycle.removeListener(listener);
    }

    // The engine phases' half of a registration; CgGraphicsLifecycle.addListener calls it first, since these come first.
    static synchronized void register(CgLifecycleListener listener) {
        if (!listeners.add(listener)) return;
        if (preInit) listeners.fire(listener, "onEnginePreInit", CgLifecycleListener::onEnginePreInit);
        if (init) listeners.fire(listener, "onEngineInit", CgLifecycleListener::onEngineInit);
        if (postInit) listeners.fire(listener, "onEnginePostInit", CgLifecycleListener::onEnginePostInit);
    }

    static boolean unregister(CgLifecycleListener listener) {
        return listeners.remove(listener);
    }
}
