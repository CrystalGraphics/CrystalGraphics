package io.github.somehussar.crystalgraphics.gl.state;

import io.github.somehussar.crystalgraphics.api.state.CgGlSlot;

/**
 * Holds a set of captured GL state slots and restores them on {@link #close()}.
 *
 * <p>Use with try-with-resources for automatic, exception-safe state restore:</p>
 * <pre>{@code
 * try (CgGlScope scope = CgGlState.save(CgGlSlot.FBO, CgGlSlot.PROGRAM)) {
 *     // GL operations here
 * }
 * // captured state restored here
 * }</pre>
 *
 * <p>Slots are indexed by {@link CgGlSlot#ordinal()} in the internal array.
 * A {@code null} entry means that slot was not captured and will not be restored.
 * Adding a new slot constant to {@link CgGlSlot} requires zero changes here.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>Not thread-safe. Must be used on the render thread that owns the GL context.</p>
 */
public final class CgGlScope implements AutoCloseable {

    /**
     * A no-op scope that does nothing when closed.
     */
    public static final CgGlScope NOOP_SCOPE = CgGlState.save();
    
    /** Indexed by CgGlSlot.ordinal(). Null entries are skipped on restore. */
    private final SlotState[] states;
    private boolean closed;

    /**
     * Package-private — constructed only by {@link CgGlState}.
     *
     * @param states array of length {@code CgGlSlot.values().length}; null entries skipped on restore
     */
    CgGlScope(SlotState[] states) {
        this.states = states.clone();
    }

    /**
     * Restores all captured GL state slots to the values at save time.
     * Idempotent — only the first call takes effect; subsequent calls are no-ops.
     */
    public void restore() {
        if (closed) return;
        closed = true;
        for (SlotState s : states) {
            if (s != null) s.restore();
        }
    }

    @Override
    public void close() {
        restore();
    }
}
