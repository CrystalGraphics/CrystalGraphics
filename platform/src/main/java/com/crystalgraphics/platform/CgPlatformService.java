package com.crystalgraphics.platform;

import com.crystalgraphics.platform.gl.CgGLBackend;
import com.crystalgraphics.platform.gl.CgGLContext;

/**
 * What a platform registers whole: its GL backend and the context that backend reports. Every other platform
 * capability is a {@link CgService} slot its owner declares ({@code CgModLoader}, {@code CgHostResources},
 * {@code CgInput}, ...), provided one at a time.
 *
 * <pre>{@code
 * CgPlatform.register(new PlatformServiceModern());
 * }</pre>
 *
 * <ul>
 *   <li>Neither getter may answer null.</li>
 *   <li>Registering builds no graphics: {@code CgGL} takes {@link #gl()} at its first host section, on a client's
 *       render thread.</li>
 * </ul>
 */
public interface CgPlatformService {
    /** The GL dispatch. */
    CgGLBackend gl();

    /** The GL context's capabilities. */
    CgGLContext capabilities();
}
