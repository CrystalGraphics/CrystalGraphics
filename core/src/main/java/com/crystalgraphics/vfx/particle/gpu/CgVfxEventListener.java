package com.crystalgraphics.vfx.particle.gpu;

/**
 * Hears the rows of events marked {@link CgVfxEvent#readback}: decals, sounds, gameplay hooks. On the GPU path rows
 * arrive a few frames after their step, on the CPU path at its end; a step with no rows is not delivered.
 *
 * <pre>{@code
 * CgVfxParticlePool.listen((definition, event, rows) -> {
 *     for (int i = 0; i < rows.count(); i++) sounds.play(HISS, rows.x(i), rows.y(i), rows.z(i));
 * });
 * }</pre>
 *
 * <p>{@code event} is the event's index in {@code definition.events()}. Render thread; {@code rows} is valid only
 * during the call.</p>
 */
@FunctionalInterface
public interface CgVfxEventListener {

    void events(CgVfxGpuEmitter definition, int event, CgVfxEventRows rows);
}
