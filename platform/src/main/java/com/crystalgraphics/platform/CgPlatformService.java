package com.crystalgraphics.platform;


/**
 * A platform's bundle, registered once by its host: each capability is a {@link CgService} slot its owner declares
 * ({@code CgModLoader}, {@code CgHostResources}, {@code CgInput}, ...), provided one at a time, and the host's device
 * is made as its context starts.
 *
 * <pre>{@code
 * CgPlatform.register(new PlatformServiceModern());
 * }</pre>
 */
public interface CgPlatformService {

    /**
     * Makes the host's device, on the render thread with its context current, as the graphics start begins and before
     * anything asks the device for its capabilities. Nothing where the host has none of its own to make (a harness
     * that installed one already). A dedicated server never reaches it.
     */
    default void startDevice() {}
}
