package com.crystalgraphics.platform.world;

import com.crystalgraphics.platform.CgService;

/**
 * The host's own debug views, so an engine overlay can follow the switch a player already knows: Minecraft's F3+B
 * shows CrystalGraphics' hitboxes beside its own.
 *
 * <pre>{@code
 * // core, on the render thread
 * boolean show = SHOW.get() || CgPlatform.get(CgHostDebug.SERVICE).hitboxes();
 *
 * // a host, once, on a client only
 * CgPlatform.provide(CgHostDebug.SERVICE, new HostDebugModern());
 * }</pre>
 *
 * <ul>
 *   <li>{@link #NONE} answers false: the harness, a dedicated server.</li>
 *   <li>Render thread only.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgHostDebug {

    /** No host debug views. */
    CgHostDebug NONE = () -> false;

    CgService<CgHostDebug> SERVICE = CgService.of("crystalgraphics:host_debug", NONE);

    /** Whether the host is drawing its entities' hitboxes. */
    boolean hitboxes();
}
