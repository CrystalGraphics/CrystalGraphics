package com.crystalgraphics.platform.gl.state;

import com.crystalgraphics.platform.gl.CgGL;

/**
 * A GL state restore point. Close it — ideally via try-with-resources — to put the captured state back.
 *
 * <pre>{@code
 * try (CgGlScope scope = CgGlState.save(CgGlSlot.FBO, CgGlSlot.PROGRAM)) {
 *     // GL operations here
 * }
 * }</pre>
 *
 * <p>Implemented by {@code CgGlStateManager.Frame}, which is pooled — entering a scope allocates nothing.
 * Restoring re-issues through {@link CgGL}, so a domain nobody disturbed inside the scope issues no GL call
 * at all on the way out.</p>
 *
 * <p>Must be closed on the thread that opened it, and in order: scopes are pooled by nesting depth, so
 * closing out of order is an error rather than merely odd.</p>
 */
public interface CgGlScope extends AutoCloseable {

    /** Restores the captured state. Idempotent — a second call does nothing. */
    void restore();

    /** Equivalent to {@link #restore()}. Declared without {@code throws} so callers need no catch. */
    @Override
    void close();

    /**
     * A scope that captured nothing and restores nothing.
     *
     * <p>Deliberately a standalone object rather than an empty pooled frame: a permanently-open frame would
     * occupy a pool slot and hold the manager's depth counter above zero forever.
     */
    CgGlScope NOOP_SCOPE = new CgGlScope() {
        @Override public void restore() { }
        @Override public void close()   { }
        @Override public String toString() { return "CgGlScope.NOOP"; }
    };
}
