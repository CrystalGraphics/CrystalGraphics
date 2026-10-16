package com.crystalgraphics.vfx.particle.spawn;

/**
 * What an emitter's live particles do once it stops: they finish their lives, or they go at once. Mirrors the Inactive
 * Response of Niagara's Emitter State module, and is part of {@link CgVfxEmitterState}; applied when its instance is
 * {@code stop()}ped and when its effect stops stepping it.
 *
 * <pre>{@code
 * .state(CgVfxEmitterState.DEFAULT.inactiveResponse(CgVfxInactiveResponse.KILL))   // a beam's core vanishes with the beam
 * .state(CgVfxEmitterState.ONCE)                                                     // COMPLETE: embers fall out after it
 * }</pre>
 *
 * <ul>
 *   <li>One emitter's particles killed on command, whatever its response, are {@code instance.kill()}.</li>
 *   <li>A killed particle fires no event: no death children, no rows.</li>
 * </ul>
 */
public enum CgVfxInactiveResponse {
    /** Live particles live out their lives: Niagara's {@code Complete}, the default. */
    COMPLETE,
    /** Live particles end in the next particle step: Niagara's {@code Kill}. */
    KILL
}
