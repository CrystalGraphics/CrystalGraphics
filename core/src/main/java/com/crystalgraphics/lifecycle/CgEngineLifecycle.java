package com.crystalgraphics.lifecycle;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.asset.load.CgAssetMounts;
import com.crystalgraphics.loading.task.CgLoadPlan;
import com.crystalgraphics.mc.CgAssetReloader;
import com.crystalgraphics.mc.CgReloadListener;
import com.crystalgraphics.net.CgNetwork;
import com.crystalgraphics.platform.service.CgNetworkChannel;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceRun;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.world.CgPlayers;

/**
 * The engine's lifecycle, and the one place a host starts and stops it: core's start on both sides, in the phases every
 * loader has; the game's ticks and its server's start and stop; then its graphics context's start and stop
 * ({@link CgGraphicsLifecycle}'s work, issued from here). A host only says when each moment is; the frame tick and
 * resize go to {@code CgGraphicsLifecycle} directly.
 *
 * <pre>{@code
 * // a host, as its loader constructs or pre-initialises CrystalGraphics: its platform and every slot installed first
 * PlatformServiceModern.install(ModLoaderForge.INSTANCE);
 * CgEngineLifecycle.preInit();                 // every mod's assets mounted, its tags declared
 * CgEngineLifecycle.init(channel);             // connections and networked objects, over the host's channel
 *
 * // the host's game events
 * CgEngineLifecycle.serverStarting(server);    // and serverTick(), serverStopping(); clientTick() on a client
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

    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");
    private static final CgLifecycleListener.Registry listeners = new CgLifecycleListener.Registry();
    private static boolean preInit, init, postInit;

    private CgEngineLifecycle() {
    }

    /**
     * The host is a client, and knows it now: the graphics start's work that touches no GL (the text natives, the
     * built-in effects' definitions) begins on a worker, so the start that comes later only joins it. Optional; any
     * thread; once.
     *
     * <pre>{@code
     * PlatformServiceModern.install(ModLoaderForge.INSTANCE);
     * if (FMLEnvironment.dist == Dist.CLIENT) CgEngineLifecycle.clientStarting();   // seconds of mod loading to hide behind
     * CgEngineLifecycle.preInit();
     * }</pre>
     *
     * <p>Never on a dedicated server: it initializes classes only a client draws with.</p>
     */
    public static void clientStarting() {
        addDiscovered(CgListenerDiscovery.Side.CLIENT);
        CgGraphicsLifecycle.startAhead();
    }

    /**
     * The platform registered and its both-sides slots filled, every mod discovered: mods' assets mounted, and the
     * {@code common} listeners every mod declares registered.
     */
    public static synchronized void preInit() {
        if (preInit) return;
        preInit = true;
        CgTraceRun.open();
        CgAssetMounts.mount();
        addDiscovered(CgListenerDiscovery.Side.COMMON);
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
     *   <li>Does nothing after {@link #shutdown}, or once the engine stood down.</li>
     *   <li>Post-init follows the graphics start on a client because what loads is precached by the effects it installs.</li>
     * </ul>
     */
    public static void ensureGraphicsContext(int width, int height) {
        graphicsContext(width, height, false);
    }

    /**
     * {@link #ensureGraphicsContext}, the graphics start a stage a call: a host's frame end calls it, so the host's frames
     * keep drawing while the engine starts rather than holding for all of it. Once started, the same.
     *
     * <pre>{@code
     * // a host, at each frame's end
     * CgEngineLifecycle.advanceGraphicsContext(window.getWidth(), window.getHeight());
     * }</pre>
     */
    public static void advanceGraphicsContext(int width, int height) {
        graphicsContext(width, height, true);
    }

    /** The start's span, open from its first stage to its last: tracked, so the timeline has no gap before the first task. */
    @Nullable private static CgLoadPlan.Span starting;

    private static void graphicsContext(int width, int height, boolean oneStage) {
        if (CgGraphicsLifecycle.isInitialized()) {
            CgGraphicsLifecycle.ensureContext(width, height);
            return;
        }
        if (starting == null) {
            // A host that never said it is a client says so here: the client listeners hear the context start
            addDiscovered(CgListenerDiscovery.Side.CLIENT);
            starting = CgLoadPlan.get().begin("crystalgraphics:start");
        }
        CgGraphicsLifecycle.ensureContext(width, height, oneStage);
        boolean started = CgGraphicsLifecycle.isInitialized();
        if (started) {
            postInit();
            CgStartSteps.step("post-init");
            load();
            CgStartSteps.step("onLoad");
            CgStartSteps.end();
        }
        if (started || CgGraphicsLifecycle.isStoodDown()) {
            starting.close();
            starting = null;
        }
    }

    private static boolean loaded;

    /** Every listener names what it loads into the plan, which runs behind the loading screen. Client, once. */
    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        listeners.dispatch("onLoad", l -> l.onLoad(CgLoadPlan.get()));
        CgLoadPlan.get().seal();
    }

    /**
     * Stops the engine for good, the one way a host does: on the thread that owns the context it first frees every
     * resource the engine holds, listeners' first ({@code onContextDestroy}), then listeners hear {@code onShutdown}
     * and the run's trace files are written. Terminal; a second call does nothing.
     *
     * <pre>{@code
     * CgEngineLifecycle.shutdown();   // the game is closing, from its render thread with the context still whole
     * }</pre>
     *
     * <ul>
     *   <li>Called off that thread, it frees nothing: the OS reclaims it at exit.</li>
     *   <li>A host still dispatching frames after it (Minecraft does) reaches nothing: every engine entry is a no-op
     *       from here.</li>
     * </ul>
     */
    public static void shutdown() {
        if (CgGL.mayIssueGl()) CgGraphicsLifecycle.destroyContext();
        CgGraphicsLifecycle.shutdown();
    }

    private static List<CgListenerDiscovery.Entry> discovered;
    private static final EnumSet<CgListenerDiscovery.Side> sidesAdded = EnumSet.noneOf(CgListenerDiscovery.Side.class);

    /**
     * Registers the listeners mods declare for {@code side}, once: each hears every phase already run as
     * {@link #addListener} delivers it, and a {@link CgReloadListener} among them hears reloads too.
     */
    private static synchronized void addDiscovered(CgListenerDiscovery.Side side) {
        if (!sidesAdded.add(side)) return;
        if (discovered == null) discovered = CgListenerDiscovery.find();
        for (CgListenerDiscovery.Entry entry : discovered) {
            if (entry.side() != side) continue;
            CgLifecycleListener listener = CgListenerDiscovery.instantiate(entry);
            if (listener == null) continue;
            addListener(listener);
            if (listener instanceof CgReloadListener reload) CgAssetReloader.addListener(reload);
            LOGGER.info("[crystalgraphics] listeners: {} {} (from {})", side.name().toLowerCase(Locale.ROOT),
                    entry.className(), entry.from());
        }
    }
    
    // -- The game's ticks, and its server's start and stop ---------------------------------------------------------

    private static final List<Runnable> SERVER_TICK = new CopyOnWriteArrayList<>(), CLIENT_TICK = new CopyOnWriteArrayList<>();
    private static final int ZONE_CLIENT_TICK = CgTrace.name("client.tick"),
            ZONE_CLIENT_LISTENERS = CgTrace.name("client.tick.listeners"), ZONE_CLIENT_NET = CgTrace.name("net.client.tick");
    private static volatile long serverTicks;

    /**
     * Runs {@code listener} once a server tick, on the server thread, before networked objects send the tick's changes:
     * a change made here reaches clients this tick. At init.
     *
     * <pre>{@code
     * CgEngineLifecycle.onServerTick(MyMachines::tickAll);
     * }</pre>
     */
    public static void onServerTick(Runnable listener) {
        SERVER_TICK.add(listener);
    }

    /**
     * Runs {@code listener} once a client tick, on the client thread, before the tick's arrivals are handled; on the
     * title screen too, with no server to talk to. At init.
     */
    public static void onClientTick(Runnable listener) {
        CLIENT_TICK.add(listener);
    }

    /** Server ticks since this process first ticked a server: a server thread's clock, and what an age on the wire is in. */
    public static long serverTicks() {
        return serverTicks;
    }

    /**
     * The server is starting, its worlds loaded: persisted networked objects are loaded before any player, and post-init
     * runs if it has not, since every mod has initialised by then. {@code server} is anything
     * {@code CgWorldQueries.saveDirectory} accepts, or null.
     */
    public static void serverStarting(@Nullable Object server) {
        CgNetwork.serverStarting(server);
        postInit();
        listeners.dispatch("onServerStarting", l -> l.onServerStarting(server));
    }

    /** The server's tick has run: the tick's listeners, then the network's sending. At its end, on the server thread. */
    public static void serverTick() {
        CgNetwork.tickStarted(CgNetwork.Side.SERVER);
        serverTicks++;
        run(SERVER_TICK, "server");
        CgNetwork.serverTick();
    }

    /** The server is stopping: every connection closed and the persisted networked objects saved. */
    public static void serverStopping() {
        listeners.dispatch("onServerStopping", CgLifecycleListener::onServerStopping);
        CgNetwork.closeAll("server stopping");
    }

    /** The client's tick has run: the tick's listeners, then what arrived. At its end, on the client thread. */
    public static void clientTick() {
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.MISC, ZONE_CLIENT_TICK)) {
            CgNetwork.tickStarted(CgNetwork.Side.CLIENT);
            try (CgTrace.Zone listeners = CgTrace.zone(CgChannels.MISC, ZONE_CLIENT_LISTENERS)) {
                run(CLIENT_TICK, "client");
            }
            // What arrived: a cue's effect is built and played here.
            try (CgTrace.Zone arrivals = CgTrace.zone(CgChannels.NET, ZONE_CLIENT_NET)) {
                CgNetwork.clientTick();
            }
        }
    }

    private static void run(List<Runnable> listeners, String side) {
        // Indexed: a copy-on-write list's iterator is an allocation every tick.
        for (int i = 0; i < listeners.size(); i++) {
            try {
                listeners.get(i).run();
            } catch (RuntimeException failed) {
                LOGGER.error("[cg] a {}-tick listener failed", side, failed);
            }
        }
    }

    /** The tick listeners and the server tick count back to none. Tests only. */
    public static void resetTicksForTesting() {
        SERVER_TICK.clear();
        CLIENT_TICK.clear();
        serverTicks = 0;
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
        if (loaded) listeners.fire(listener, "onLoad", l -> l.onLoad(CgLoadPlan.get()));
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
        // After the listeners, while the mod loader's classes still load: the JVM's exit is too late for that.
        CgTraceRun.writeExitFiles();
    }

    // Non-capturing, so a frame's dispatch allocates nothing.
    private static final Consumer<CgLifecycleListener> FRAME = l -> l.onFrame(CgFrameClock.frameIndex());
}
