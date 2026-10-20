package com.crystalgraphics.vfx.system;

/**
 * What a playing system instance is doing: Niagara's {@code ENiagaraExecutionState} ({@code NiagaraTypes.h:582}), plus
 * our sleep.
 *
 * <pre>{@code
 * if (beam.executionState() == CgVfxExecutionState.INACTIVE) hud.hide();   // emission over, particles finishing
 * }</pre>
 */
public enum CgVfxExecutionState {
    /** Running: its emitters spawn. */
    ACTIVE,
    /** No new spawns; what is alive finishes, then it completes. */
    INACTIVE,
    /** Its particles cleared at once: it completes in its system's next step. */
    INACTIVE_CLEAR,
    /** Finished: its system removes it. */
    COMPLETE,
    /** Culled by its scalability: freed, played on from its true age when wanted again. Ours; Niagara has none. */
    ASLEEP
}
