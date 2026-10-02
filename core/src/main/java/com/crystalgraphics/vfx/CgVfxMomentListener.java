package com.crystalgraphics.vfx;

/**
 * Hears the named moments an effect passes through in its life (a charge peaking, a release, an impact), each with a
 * point and a radius that frame it: what a capture tool photographs, so every moment can be looked at however briefly
 * it lasts.
 *
 * <pre>{@code
 * vfx.onMoment((effect, name, x, y, z, radius) -> {
 *     camera.frame(x, y, z, radius);        // look at it this frame
 *     artifacts.requestCapture(name);       // and photograph it
 * });
 * }</pre>
 *
 * <ul>
 *   <li>Called from {@link CgVfxSystem#update}, before the frame's draws are submitted: a capture requested here shows
 *       the moment itself.</li>
 *   <li>Each effect documents its moments as constants ({@code CgEnergyWave.MOMENT_IMPACT}); each fires once per effect.</li>
 *   <li>Positions are absolute, in the world the effect plays in.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgVfxMomentListener {

    void moment(CgVfxEffect effect, String name, double x, double y, double z, float radius);
}
