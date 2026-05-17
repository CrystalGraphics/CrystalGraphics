package com.crystalgraphics.platform;

import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.CgGLContext;
import com.crystalgraphics.platform.gl.CgGLBackend;
import com.crystalgraphics.platform.service.CgLifecycleService;
import com.crystalgraphics.platform.service.CgReloadService;
import com.crystalgraphics.platform.service.CgRenderingService;
import com.crystalgraphics.platform.service.CgResourceService;

import java.util.Objects;

/**
 * Central registry for all platform service implementations.
 *
 * <p>Call {@link #register(CgPlatformService)} once during platform initialisation
 * (e.g. from {@code PlatformRegistry1710.onPreInit()} in the mc1710 module).
 * All getters except {@link #resources()} throw {@link IllegalStateException} if
 * invoked before registration. {@link #resources()} returns {@code null} before
 * registration so that {@code CgIO.openStream} can fall back to classpath loading
 * during early boot without exception overhead.</p>
 *
 * <p>The registry uses direct argument passing rather than {@code ServiceLoader}
 * because FML's classloader makes {@code ServiceLoader} unreliable in the
 * MC 1.7.10 environment.</p>
 */
public final class CgPlatform {

    private static CgPlatformService service;

    private CgPlatform() {}

    /**
     * Register a complete platform bundle. Must be called exactly once, before any
     * core engine code runs. Calling more than once replaces all existing registrations.
     *
     * @param platform the platform bundle providing all six services; must not be {@code null}
     */
    public static void register(CgPlatformService platform) {
        service = Objects.requireNonNull(platform, "CgPlatformService must not be null");
        
        CgGL.init(platform.gl());
        CgCapabilities.init(platform.capabilities());
    }

    /** Returns the GL dispatch. @throws IllegalStateException if called before {@link #register}. */
    public static CgGLBackend gl() {
        ensureCreated();
        return service.gl();
    }

    /** Returns the GL context. @throws IllegalStateException if called before {@link #register}. */
    public static CgGLContext capabilities() {
        ensureCreated();
        return service.capabilities();
    }

    /**
     * Returns the resource service, or {@code null} if {@link #register} has not yet been called.
     * Safe to call during early boot — {@code CgIO.openStream} uses this to fall through to
     * classpath loading before the platform is initialised.
     */
    public static CgResourceService resources() {
        ensureCreated();
        return service.resources();
    }

    /** Returns the rendering service. @throws IllegalStateException if called before {@link #register}. */
    public static CgRenderingService rendering() {
        ensureCreated();
        return service.rendering();
    }

    /** Returns the lifecycle service. @throws IllegalStateException if called before {@link #register}. */
    public static CgLifecycleService lifecycle() {
        ensureCreated();
        return service.lifecycle();
    }

    /** Returns the reload service. @throws IllegalStateException if called before {@link #register}. */
    public static CgReloadService reload() {
        ensureCreated();
        return service.reload();
    }

    public static void ensureCreated() {
        if (service == null) throw new IllegalStateException("CgPlatform not yet registered — call register() first");
    }
}
