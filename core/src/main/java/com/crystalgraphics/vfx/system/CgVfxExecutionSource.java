package com.crystalgraphics.vfx.system;

/**
 * Who asked for an execution state, lowest precedence first: Niagara's {@code ENiagaraExecutionStateSource}
 * ({@code NiagaraTypes.h:569}). A request from a source below the one that made the standing request is refused, so a
 * scalability cull never revives what its owner stopped.
 *
 * <pre>{@code
 * beam.request(CgVfxExecutionState.INACTIVE, CgVfxExecutionSource.OWNER);        // accepted
 * beam.request(CgVfxExecutionState.ACTIVE, CgVfxExecutionSource.SCALABILITY);    // refused: below the owner's
 * }</pre>
 */
public enum CgVfxExecutionSource {
    /** Its scalability's culls. */
    SCALABILITY,
    /** Its own logic: an emitter's loops ending, its behaviour. */
    INTERNAL,
    /** Whoever played it: {@code stop()}, {@code kill()}. */
    OWNER,
    /** Its completion: everything finished. Nothing outranks it. */
    COMPLETION;

    /** Whether a request from this source may replace one from {@code standing}. */
    public boolean outranks(CgVfxExecutionSource standing) {
        return ordinal() >= standing.ordinal();
    }
}
