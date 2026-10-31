package com.crystalgraphics.lifecycle;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Hears the engine's lifecycle from outside CrystalGraphics: its start on both sides, then its graphics context's.
 * For a consuming library (CrystalGUI), a mod's own renderer or a harness scene, which need the same moments but must
 * not be hard-wired into the engine. Registered with {@link CgEngineLifecycle#addListener}, which dispatches every
 * moment.
 *
 * <pre>{@code
 * CgEngineLifecycle.addListener(new CgLifecycleListener() {
 *     @Override public void onInit() {
 *         CgEngineLifecycle.onServerTick(MyMod::tick);          // the network is installed by now
 *     }
 *     @Override public void onPostInit() {
 *         HIT = CgTagRegistry.global().require("mymod:cue.hit");   // every mod's tags are declared
 *     }
 * });
 * }</pre>
 *
 * <pre>{@code
 * // a renderer: GL objects made with the context and freed before it goes
 * CgEngineLifecycle.addListener(new CgLifecycleListener() {
 *     @Override public void onContextInit(int width, int height) {
 *         target = CgFrameBuffer.createOwned("mymod:glow", width, height, CgFrameBufferFormat.RGBA8_WITH_DEPTH);
 *     }
 *     @Override public void onContextDestroy() { target.delete(); }
 * });
 * }</pre>
 *
 * <p>Every method is a default no-op, so an implementer overrides only the moments it cares about.</p>
 *
 * <h3>Ordering guarantees</h3>
 * <ul>
 *   <li>{@link #onPreInit}, {@link #onInit} and {@link #onPostInit} fire on both sides, each <b>after</b> the
 *       engine's own work in that phase. They run on the loader's thread, which on Forge may be a mod-loading
 *       worker.</li>
 *   <li>{@link #onContextInit} fires <b>after</b> CrystalGraphics has finished building its context, so the render
 *       pipeline, fallback textures and capability probes are all usable from it. On a client, {@link #onPostInit}
 *       follows it.</li>
 *   <li>{@link #onContextDestroy} fires <b>before</b> CrystalGraphics tears anything down, while every GL
 *       object is still valid. This is the only point at which a listener can safely delete its own
 *       framebuffers, renderers and buffers — afterwards the registries have swept the context and a
 *       listener's handles refer to objects that no longer exist.</li>
 *   <li><b>Each fires exactly once</b>, whenever you registered: a listener added after a phase, or while a context
 *       is live, hears what it missed from {@code addListener}, in order. Lazily-loaded consumers depend on it.</li>
 * </ul>
 *
 * <h3>Failure isolation</h3>
 * <p>An exception thrown by one listener is logged and swallowed: it must not prevent the other
 * listeners from running, and it must never abort engine teardown partway through, which would leak
 * the entire remainder of the GL context.</p>
 *
 * <p>Registration order is dispatch order, except {@link #onContextDestroy}, which dispatches in <b>reverse</b>
 * registration order, so a listener registered later — and therefore potentially built on top of an earlier one —
 * releases first.</p>
 */
public interface CgLifecycleListener {

    /** Every mod discovered and its assets mounted, its tags declared: {@link CgEngineLifecycle#preInit}. */
    default void onPreInit() {
    }

    /** The network installed over the host's channel: {@link CgEngineLifecycle#init}. */
    default void onInit() {
    }

    /**
     * Every mod initialised, and what was mounted loading: {@link CgEngineLifecycle#postInit}, at the first client
     * graphics start or server start. Mirrors Unreal's {@code FCoreDelegates::OnPostEngineInit}.
     */
    default void onPostInit() {
    }

    /**
     * The graphics context has been created and CrystalGraphics is fully initialised on it. GL thread.
     *
     * @param width  viewport width in pixels
     * @param height viewport height in pixels
     */
    default void onContextInit(int width, int height) {
    }

    /**
     * The host has drawn its frame, GUI included, and the engine is ending it via
     * {@link CgGraphicsLifecycle#tickFrame()}: once per host frame, a world frame and a title-screen frame
     * alike, and <b>the last point to draw over the host's picture</b>. The GL state shadow was just
     * invalidated, so whatever the host left bound is not trusted.
     *
     * <pre>{@code
     * CgEngineLifecycle.addListener(new CgLifecycleListener() {
     *     @Override public void onFrame(long frame) {
     *         if (overlayUp) paintOverlay();   // lands over the host's GUI, inside this frame
     *     }
     * });
     * }</pre>
     *
     * <p>It is also the engine's frame cadence — the same tick that advances the glyph atlas LRU. Never
     * called after {@link CgGraphicsLifecycle#standDown}.</p>
     *
     * @param frame the current authoritative frame number
     */
    default void onFrame(long frame) {
    }

    /**
     * The graphics context is about to be destroyed. Release GL resources you own here.
     *
     * <p>Fires while everything is still valid — see the ordering guarantees above. Do not assume
     * anything about whether a new context will follow; implementations should leave themselves in a
     * state where a subsequent {@link #onContextInit} works.</p>
     */
    default void onContextDestroy() {
    }

    /**
     * The engine has stopped for good: the game is closing. Once, on the thread that stopped it, and after
     * {@link #onContextDestroy} when there was one.
     *
     * <p>For work a JVM shutdown hook cannot do: on a mod loader the class loader closes before those hooks
     * run, so anything that loads a class then fails. Writing a report is the case. Nothing here may draw.</p>
     */
    default void onShutdown() {
    }

    /**
     * Storage and dispatch for a set of {@link CgLifecycleListener}s.
     *
     * <p>Owns the <em>mechanism</em> — holding listeners, iterating them in the right direction, and
     * isolating a failing one — and deliberately none of the <em>policy</em>. It does not know what
     * a moment means, when a context becomes live, or that late registrants need catching up;
     * that all stays in {@link CgEngineLifecycle}, which owns the lifecycle it is dispatching for.
     * The split is what keeps this reusable: any subsystem wanting its own listener set can hold a
     * {@code Registry} without inheriting the engine's context semantics.</p>
     *
     * <p>Backed by a {@code CopyOnWriteArrayList}: dispatch happens on the GL thread while
     * registration can plausibly come from elsewhere (mod init, a scene's setup), and a listener that
     * unregisters itself from inside its own callback must not blow up the in-flight iteration.</p>
     */
    final class Registry {

        private static final Logger LOGGER = Logger.getLogger(Registry.class.getName());

        private final List<CgLifecycleListener> listeners = new CopyOnWriteArrayList<>();

        /**
         * Adds {@code listener} unless it is null or already present.
         *
         * @return {@code true} if it was actually added — lets the caller run one-time
         *         just-registered work (e.g. delivering a late {@code onContextInit}) without having to
         *         re-check membership, and without doing it twice for a duplicate registration.
         *         A double-fire of {@code onContextDestroy} would be a double free.
         */
        public boolean add(CgLifecycleListener listener) {
            if (listener == null || listeners.contains(listener)) return false;
            return listeners.add(listener);
        }

        /** Removes {@code listener}. Safe to call from inside a callback. */
        public boolean remove(CgLifecycleListener listener) {
            return listeners.remove(listener);
        }

        /** How many listeners are currently registered. */
        public int size() {
            return listeners.size();
        }

        /**
         * Invokes one listener, isolating failure.
         *
         * <p>A listener that throws is logged and skipped rather than propagating: during teardown an
         * escaping exception would abort the rest of {@link CgEngineLifecycle#destroyContext()}
         * partway through and leak everything after it, which is far worse than whatever the listener
         * got wrong.</p>
         *
         * <p>Public because a caller dispatching to a <em>single</em> listener outside a full sweep —
         * catching up a late registrant, say — needs the same isolation.</p>
         *
         * @param what name of the callback, for the log message only
         */
        public void fire(CgLifecycleListener listener, String what, Consumer<CgLifecycleListener> action) {
            try {
                action.accept(listener);
            } catch (Throwable t) {
                LOGGER.log(Level.WARNING, "CgLifecycleListener." + what + " failed for "
                        + listener.getClass().getName(), t);
            }
        }

        /** Dispatches to every listener in registration order. */
        public void dispatch(String what, Consumer<CgLifecycleListener> action) {
            for (CgLifecycleListener listener : listeners) {
                fire(listener, what, action);
            }
        }

        /**
         * Dispatches to every listener in <b>reverse</b> registration order, so a listener built on
         * top of an earlier one runs first. Used by teardown.
         *
         * <p>Iterates an explicit snapshot rather than {@code listIterator(size())}, which would read
         * the size and take its snapshot in two steps and could miss a listener registered between
         * them.</p>
         */
        public void dispatchReverse(String what, Consumer<CgLifecycleListener> action) {
            Object[] snapshot = listeners.toArray();
            for (int i = snapshot.length - 1; i >= 0; i--) {
                fire((CgLifecycleListener) snapshot[i], what, action);
            }
        }
    }
}
