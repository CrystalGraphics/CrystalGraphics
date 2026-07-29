package com.crystalgraphics.gl.lifecycle;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Hook for code that owns GL resources but lives <em>outside</em> CrystalGraphics, so it can be
 * driven by the same context lifecycle {@link CgGraphicsLifecycle} already coordinates.
 *
 * <p>CrystalGraphics' own subsystems are enumerated directly inside {@code CgGraphicsLifecycle} —
 * they are known at compile time and their teardown order is load-bearing. This interface exists for
 * everything the engine cannot know about: a consuming library (CrystalGUI), a mod's own renderer, a
 * harness scene. Those need the same three moments, but must not be hard-wired into the engine.</p>
 *
 * <p>Every method is a default no-op, so an implementer overrides only the moments it cares about.</p>
 *
 * <h3>Ordering guarantees</h3>
 * <ul>
 *   <li>{@link #onInit} fires <b>after</b> CrystalGraphics has finished initialising, so the render
 *       pipeline, fallback textures and capability probes are all usable from it. It fires
 *       <b>exactly once per context</b> regardless of when you registered: register before the
 *       context exists and it arrives during {@code initContext}; register while one is already live
 *       and {@code CgGraphicsLifecycle.addListener} delivers it immediately. Lazily-loaded consumers
 *       depend on that second path — see {@code CgGraphicsLifecycle#addListener}.</li>
 *   <li>{@link #onDestroy} fires <b>before</b> CrystalGraphics tears anything down, while every GL
 *       object is still valid. This is the only point at which a listener can safely delete its own
 *       framebuffers, renderers and buffers — afterwards the registries have swept the context and a
 *       listener's handles refer to objects that no longer exist.</li>
 * </ul>
 *
 * <p>Because both are guaranteed per-context, a listener that survives a destroy/recreate cycle sees
 * a clean {@code onDestroy} → {@code onInit} pair and can rebuild against the new context.</p>
 *
 * <h3>Failure isolation</h3>
 * <p>An exception thrown by one listener is logged and swallowed: it must not prevent the other
 * listeners from running, and it must never abort engine teardown partway through, which would leak
 * the entire remainder of the GL context.</p>
 *
 * <p>Registration order is dispatch order for {@link #onInit}/{@link #onFrame}. {@link #onDestroy}
 * dispatches in <b>reverse</b> registration order, so a listener registered later — and therefore
 * potentially built on top of an earlier one — releases first.</p>
 */
public interface CgLifecycleListener {

    /**
     * The GL context has been created and CrystalGraphics is fully initialised.
     *
     * @param width  viewport width in pixels
     * @param height viewport height in pixels
     */
    default void onInit(int width, int height) {
    }

    /**
     * One real rendered frame has been ticked, via {@link CgGraphicsLifecycle#tickFrame()}.
     *
     * <p>This is the engine's authoritative frame cadence — the same tick that advances the glyph
     * atlas LRU — not a per-window or per-scene paint call. Do per-frame bookkeeping here, not work
     * proportional to what is on screen.</p>
     *
     * @param frame the current authoritative frame number
     */
    default void onFrame(long frame) {
    }

    /**
     * The GL context is about to be destroyed. Release GL resources you own here.
     *
     * <p>Fires while everything is still valid — see the ordering guarantees above. Do not assume
     * anything about whether a new context will follow; implementations should leave themselves in a
     * state where a subsequent {@link #onInit} works.</p>
     */
    default void onDestroy() {
    }

    /**
     * Storage and dispatch for a set of {@link CgLifecycleListener}s.
     *
     * <p>Owns the <em>mechanism</em> — holding listeners, iterating them in the right direction, and
     * isolating a failing one — and deliberately none of the <em>policy</em>. It does not know what
     * {@code onInit} means, when a context becomes live, or that late registrants need catching up;
     * that all stays in {@link CgGraphicsLifecycle}, which owns the lifecycle it is dispatching for.
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
         *         just-registered work (e.g. delivering a late {@code onInit}) without having to
         *         re-check membership, and without doing it twice for a duplicate registration.
         *         A double-fire of {@code onDestroy} would be a double free.
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
         * escaping exception would abort the rest of {@link CgGraphicsLifecycle#destroyContext()}
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
