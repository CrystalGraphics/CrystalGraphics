package com.crystalgraphics.platform;

import com.crystalgraphics.platform.gl.CgCapabilities;

import java.util.List;
import java.util.Objects;

/**
 * The platform registry: the bundle a platform {@linkplain #register registers}, and the {@link CgService} slots each
 * capability's owner declares and a host {@linkplain #provide provides}.
 *
 * <pre>{@code
 * CgPlatform.register(new PlatformServiceModern());                         // once, the host's bundle
 * CgPlatform.provide(CgHostResources.HOST, HostResourcesModern.INSTANCE);   // one slot
 * }</pre>
 *
 * <ul>
 *   <li>{@link #ensureCreated} throws before {@link #register}; a slot never throws, answering its absent-value until
 *       provided.</li>
 *   <li>Arguments, not {@code ServiceLoader}: FML's class loader makes it unreliable on 1.7.10.</li>
 * </ul>
 */
public final class CgPlatform {

    private static CgPlatformService service;

    private CgPlatform() {}

    /**
     * Register a complete platform bundle. Must be called exactly once, before any
     * core engine code runs. Calling more than once replaces all existing registrations.
     *
     * <p>Builds no graphics, so a host registers from code that runs on both sides; its device is made on a client's
     * render thread, as its context starts.</p>
     *
     * @param platform the platform bundle providing the services; must not be {@code null}
     */
    public static void register(CgPlatformService platform) {
        service = Objects.requireNonNull(platform, "CgPlatformService must not be null");
        // What a previous bundle's device answered
        CgCapabilities.clearCache();
    }

    /** The registered platform's {@link CgPlatformService#startDevice}: the graphics start's, once. */
    public static void startDevice() {
        ensureCreated();
        service.startDevice();
    }

    public static void ensureCreated() {
        if (service == null) throw new IllegalStateException("CgPlatform not yet registered — call register() first");
    }

    // ── The open half: services this framework does not own ─────────────────────────────────────
    //
    // One registry for every slot, CrystalGraphics' and its consumers' alike: a second registry is one a loader can
    // forget to fill, leaving a dead service with nothing to report it. `services()` lists what is carried.

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
