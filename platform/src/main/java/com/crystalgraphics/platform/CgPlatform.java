package com.crystalgraphics.platform;

import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.CgGLContext;
import com.crystalgraphics.platform.gl.CgGLBackend;
import com.crystalgraphics.platform.service.CgCursorService;
import com.crystalgraphics.platform.service.CgInputService;
import com.crystalgraphics.platform.service.CgLifecycleService;
import com.crystalgraphics.platform.service.CgReloadService;
import com.crystalgraphics.platform.service.CgRenderingService;
import com.crystalgraphics.platform.service.CgResourceService;
import com.crystalgraphics.platform.service.CgSoundService;

import java.util.List;
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
     * @param platform the platform bundle providing the services; must not be {@code null}
     */
    public static void register(CgPlatformService platform) {
        service = Objects.requireNonNull(platform, "CgPlatformService must not be null");

        // GL IS OPTIONAL AT REGISTRATION, because a DEDICATED SERVER has no LWJGL on its classpath.
        //
        // These two calls used to run unconditionally, and they are the reason CrystalGraphics could not
        // load on a server at all: asking the platform for its GL backend constructs a class that names
        // org.lwjgl.LWJGLException, so preInit died with NoClassDefFoundError and every mod depending on
        // CrystalGraphics was marked errored alongside it. Nothing had noticed, because nothing had ever
        // run a dedicated server.
        //
        // Asked by TRYING, because this module may import neither FML nor LWJGL and so has no other way
        // to know which side it is on -- and "can this classpath produce a GL backend" is exactly the
        // question, rather than a proxy for it. A client that genuinely fails here is not silenced: the
        // absence is reported, and every later CgGL call fails on a null backend as it always would.
        try {
            CgGL.init(platform.gl());
            CgCapabilities.init(platform.capabilities());
        } catch (NoClassDefFoundError | UnsatisfiedLinkError noGraphics) {
            // Said out loud rather than swallowed. "Live" and "inert" look identical otherwise, and a
            // client that lost its GL backend to a packaging mistake would look exactly like a server.
            System.err.println("[CrystalGraphics] no GL backend on this classpath (" + noGraphics
                    + ") — registering the platform without one. This is expected on a dedicated server "
                    + "and is a packaging fault anywhere else; rendering is unavailable either way.");
        }
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

    /** Returns the input and clipboard service. @throws IllegalStateException if called before {@link #register}. */
    public static CgInputService input() {
        ensureCreated();
        return service.input();
    }

    /** Returns the UI sound service. @throws IllegalStateException if called before {@link #register}. */
    public static CgSoundService sound() {
        ensureCreated();
        return service.sound();
    }

    /** Returns the cursor presentation service. @throws IllegalStateException if called before {@link #register}. */
    public static CgCursorService cursor() {
        ensureCreated();
        return service.cursor();
    }

    public static void ensureCreated() {
        if (service == null) throw new IllegalStateException("CgPlatform not yet registered — call register() first");
    }

    // ── The open half: services this framework does not own ─────────────────────────────────────
    //
    // Everything above is the CLOSED bundle -- nine methods with no defaults, so the compiler forces a
    // new loader to answer all of them. That enforcement is why it is a bundle, and it can only work for
    // services this project owns. A consumer of CrystalGraphics has services of its own that must not be
    // named here, and the only way to have one used to be a second static registry beside this class.
    //
    // Two registries is a failure this project has already paid for: a loader has to find every registry
    // there is, so it can wire up one and forget another, leaving a working backend beside a dead service
    // with nothing to report it. A downstream mod declaring its own rebuilds that hazard one layer out.
    //
    // So a consumer DECLARES a `CgService` slot in its own module, and fills and reads it through here.
    // Registration then looks like what it is -- going into the platform stack -- and `services()` can
    // enumerate what this platform is actually carrying. @see CgService

    /**
     * Fills a service slot, or clears it when given null.
     *
     * <p>Deliberately independent of {@link #register}: a slot may be provided before or after the core
     * bundle, because two mods initialise in whatever order the loader picks and neither can wait for the
     * other. Nothing here throws when the bundle is absent.</p>
     */
    public static <T> void provide(CgService<T> slot, T implementation) {
        Objects.requireNonNull(slot, "service slot must not be null").provide(implementation);
    }

    /**
     * The implementation in {@code slot}, or that slot's absent-value. <b>Never null.</b>
     *
     * <p>The fallback belongs to the slot and is stated once where the contract is declared, so no caller
     * chooses it and no two callers can disagree about what absence means.</p>
     */
    public static <T> T get(CgService<T> slot) {
        return Objects.requireNonNull(slot, "service slot must not be null").get();
    }

    /**
     * Whether {@code slot} holds a real implementation rather than its absent-value.
     *
     * <p>For the rare caller that must behave differently rather than merely degrade — reporting, mostly.
     * Ordinary consumers should call {@link #get} and let the absent-value do its job.</p>
     */
    public static boolean isProvided(CgService<?> slot) {
        return Objects.requireNonNull(slot, "service slot must not be null").isProvided();
    }

    /**
     * Every slot declared so far, provided or not — the platform stack, printable.
     *
     * <p>The question nothing could answer before: a loader, or anyone reading a log, can enumerate what
     * is installed instead of knowing in advance which static holders exist. A slot whose declaring class
     * has not loaded is not here, which is why an unprovided slot reports itself on first use rather than
     * being swept for.</p>
     */
    public static List<CgService<?>> services() {
        return CgService.declared();
    }
}
