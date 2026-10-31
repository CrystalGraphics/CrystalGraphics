package com.crystalgraphics.lifecycle;

import com.crystalgraphics.asset.load.CgAssetMounts;
import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.platform.service.CgNetworkChannel;
import com.crystalgraphics.world.CgPlayers;

/**
 * The engine's start, on both sides, in the phases every loader has: what core loads and starts as the game
 * initialises, in one place, so a host only says when each phase is. Graphics, which need a context, start later in
 * {@code CgGraphicsLifecycle}.
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
 * <ul>
 *   <li>{@link #postInit} is the engine's own: it runs at the first client graphics start or server start, both after
 *       every mod's init on every loader. Everything mounted then starts loading.</li>
 *   <li>Each phase runs once; a second call does nothing. {@link #init} before {@link #preInit} throws.</li>
 *   <li>{@link #init} reads player ids through {@code CgPlayers}: provide {@code CgPlayerQuery} before it.</li>
 * </ul>
 */
public final class CgEngineLifecycle {

    private static boolean preInit, init, postInit;

    private CgEngineLifecycle() {
    }

    /** The platform registered and its both-sides slots filled, every mod discovered: mods' assets mounted. */
    public static synchronized void preInit() {
        if (preInit) return;
        preInit = true;
        CgAssetMounts.mount();
    }

    /** The host's channel registered and its world slots provided: the network installed over {@code channel}. */
    public static synchronized void init(CgNetworkChannel channel) {
        if (!preInit) throw new IllegalStateException("CgEngineLifecycle.init before preInit");
        if (init) return;
        init = true;
        CgNetwork.install(channel, CgPlayers::idOf);
    }

    /** Every mod initialised: what was mounted starts loading. The engine runs it at its first client or server start. */
    public static synchronized void postInit() {
        if (postInit) return;
        postInit = true;
        CgAssetMounts.loadAhead();
    }
}
