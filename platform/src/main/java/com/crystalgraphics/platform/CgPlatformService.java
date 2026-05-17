package com.crystalgraphics.platform;

import com.crystalgraphics.platform.gl.*;
import com.crystalgraphics.platform.service.*;

/**
 * API contract for a complete CrystalGraphics platform bundle.
 *
 * <p>Implement this interface to provide all six platform services as a single
 * cohesive unit. Register the bundle via {@link CgPlatform#register(CgPlatformService)}.</p>
 *
 * <p>Each getter is called once during registration and the result is cached
 * inside {@link CgPlatform} — implementations may return new instances
 * per call or cached singletons; either is correct.</p>
 */
public interface CgPlatformService {
    /** @return the GL dispatch implementation; must not be {@code null} */
    CgGLBackend gl();
    /** @return the GL context capability implementation; must not be {@code null} */
    CgGLContext capabilities();
    /** @return the resource loading service; must not be {@code null} */
    CgResourceService resources();
    /** @return the rendering service; must not be {@code null} */
    CgRenderingService rendering();
    /** @return the lifecycle service; must not be {@code null} */
    CgLifecycleService lifecycle();
    /** @return the reload service; must not be {@code null} */
    CgReloadService reload();
}
