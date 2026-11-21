package com.crystalgraphics.platform.service;

import com.crystalgraphics.platform.CgService;

/**
 * The scale the operating system asks of content in the host's window: real pixels per logical pixel, 1.5 on a
 * display set to 150 %. Read each frame, so a window dragged to another monitor follows it.
 *
 * <pre>{@code
 * // a consumer
 * float scale = CgPlatform.get(CgDisplayScale.SERVICE).scale();
 *
 * // a loader, once
 * CgPlatform.provide(CgDisplayScale.SERVICE, new GlfwDisplayScale(window::handle));
 * }</pre>
 *
 * <p>A slot, not a bundle method: a server, a test and a toolkit that cannot tell register nothing and read
 * {@link #NONE}, which is 1.</p>
 *
 * <ul>
 *   <li>Called on the render thread every frame: an implementation caches what is costly and never throws.</li>
 *   <li>Never 0 or NaN: a toolkit with no answer returns 1.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgDisplayScale {

    CgDisplayScale NONE = () -> 1f;

    CgService<CgDisplayScale> SERVICE = CgService.of("crystalgraphics:display-scale", NONE);

    /** Real pixels per logical pixel for the host's window, now. */
    float scale();
}
