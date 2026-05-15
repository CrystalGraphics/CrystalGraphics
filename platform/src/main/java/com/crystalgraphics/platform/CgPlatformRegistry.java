package com.crystalgraphics.platform;

import java.util.Objects;

/**
 * Central registry for all platform service implementations.
 *
 * <p>Call {@link #register} once during platform initialisation (e.g. from
 * {@code CrystalGraphics.onPreInit()} in the mc1710 module). All getters throw
 * {@link IllegalStateException} if invoked before registration.</p>
 *
 * <p>The registry uses direct argument passing rather than {@code ServiceLoader}
 * because FML's classloader makes {@code ServiceLoader} unreliable in the
 * MC 1.7.10 environment.</p>
 */
public final class CgPlatformRegistry {

    private static CgGlDispatch glDispatch;
    private static CgCapabilityProbe capabilityProbe;
    private static CgResourceService resourceService;
    private static CgRenderingService renderingService;
    private static CgLifecycleService lifecycleService;
    private static CgReloadService reloadService;

    private CgPlatformRegistry() {}

    /**
     * Register all platform services. Must be called exactly once, before any
     * core engine code runs. Calling more than once replaces all existing registrations.
     *
     * @param gl          the GL dispatch implementation
     * @param caps        the capability probe implementation
     * @param res         the resource service implementation
     * @param rendering   the rendering service implementation
     * @param lifecycle   the lifecycle service implementation
     * @param reload      the reload service implementation
     */
    public static void register(
            CgGlDispatch gl,
            CgCapabilityProbe caps,
            CgResourceService res,
            CgRenderingService rendering,
            CgLifecycleService lifecycle,
            CgReloadService reload) {
        glDispatch = Objects.requireNonNull(gl, "CgGlDispatch must not be null");
        capabilityProbe = Objects.requireNonNull(caps, "CgCapabilityProbe must not be null");
        resourceService = Objects.requireNonNull(res, "CgResourceService must not be null");
        renderingService = Objects.requireNonNull(rendering, "CgRenderingService must not be null");
        lifecycleService = Objects.requireNonNull(lifecycle, "CgLifecycleService must not be null");
        reloadService = Objects.requireNonNull(reload, "CgReloadService must not be null");
        CgGlDispatch.setInstance(gl);
    }

    /**
     * Returns the GL dispatch, or {@code null} if called before {@link #register}.
     * The null-returning variant is intentional for early-boot paths (e.g. {@code CgIO.openStream})
     * that gracefully fall back to classpath loading when the platform is not yet initialised.
     */
    public static CgGlDispatch glOrNull() {
        return glDispatch;
    }

    /** Returns the registered GL dispatch. @throws IllegalStateException if not yet registered. */
    public static CgGlDispatch gl() {
        if (glDispatch == null) throw new IllegalStateException("CgPlatformRegistry: gl dispatch not registered");
        return glDispatch;
    }

    /**
     * Returns the capability probe, or {@code null} if called before {@link #register}.
     * Null-returning variant used by CgCapabilities during early bootstrap.
     */
    public static CgCapabilityProbe capabilitiesOrNull() {
        return capabilityProbe;
    }

    /** Returns the capability probe. @throws IllegalStateException if not yet registered. */
    public static CgCapabilityProbe capabilities() {
        if (capabilityProbe == null) throw new IllegalStateException("CgPlatformRegistry: capability probe not registered");
        return capabilityProbe;
    }

    /**
     * Returns the resource service, or {@code null} if called before {@link #register}.
     * Null-returning variant used by {@code CgIO.openStream} to gracefully fall back to
     * classpath loading before the platform is initialised.
     */
    public static CgResourceService resourcesOrNull() {
        return resourceService;
    }

    /** Returns the resource service. @throws IllegalStateException if not yet registered. */
    public static CgResourceService resources() {
        if (resourceService == null) throw new IllegalStateException("CgPlatformRegistry: resource service not registered");
        return resourceService;
    }

    /** Returns the rendering service. @throws IllegalStateException if not yet registered. */
    public static CgRenderingService rendering() {
        if (renderingService == null) throw new IllegalStateException("CgPlatformRegistry: rendering service not registered");
        return renderingService;
    }

    /** Returns the lifecycle service. @throws IllegalStateException if not yet registered. */
    public static CgLifecycleService lifecycle() {
        if (lifecycleService == null) throw new IllegalStateException("CgPlatformRegistry: lifecycle service not registered");
        return lifecycleService;
    }

    /** Returns the reload service. @throws IllegalStateException if not yet registered. */
    public static CgReloadService reload() {
        if (reloadService == null) throw new IllegalStateException("CgPlatformRegistry: reload service not registered");
        return reloadService;
    }
}
