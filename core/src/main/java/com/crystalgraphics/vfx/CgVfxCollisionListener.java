package com.crystalgraphics.vfx;

/**
 * Hears what effects and their particles strike ({@link CgVfxCollision}).
 *
 * <pre>{@code
 * wave.onCollide(c -> { if (c.hit() == CgVfxCollision.Hit.ENTITY) damage(c.entity()); });
 * }</pre>
 *
 * <ul>
 *   <li>Render thread. The collision is valid only during the call.</li>
 *   <li>One that throws is logged and the others still hear.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgVfxCollisionListener {

    void collided(CgVfxCollision collision);
}
