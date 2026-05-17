package com.crystalgraphics.mc.platform;

import com.crystalgraphics.CrystalGraphicsVersion;
import com.crystalgraphics.mc.platform.gl.Lwjgl2GLContext;
import com.crystalgraphics.mc.platform.gl.Lwjgl2GLBackend;
import com.crystalgraphics.mc.platform.service.LifecycleService1710;
import com.crystalgraphics.mc.platform.service.ReloadService1710;
import com.crystalgraphics.mc.platform.service.RenderingService1710;
import com.crystalgraphics.mc.platform.service.ResourceService1710;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.*;
import com.crystalgraphics.platform.gl.*;
import com.crystalgraphics.platform.CgPlatformService;
import lombok.Getter;

/**
 * Complete MC 1.7.10 platform bundle. Implements {@link CgPlatformService} by composing
 * the six mc1710 service adapters. Register via {@code CgPlatform.register(new PlatformService1710())}.
 *
 * <p>{@link RenderingService1710} and {@link LifecycleService1710} instances are exposed
 * via package-visible accessors if needed.</p>
 */
public final class PlatformService1710 implements CgPlatformService {
    
    // ── Singleton ─────────────────────────────────────────────────────────────
    private static PlatformService1710 INSTANCE;
    
    public static void init() { if (INSTANCE != null) return; INSTANCE = new PlatformService1710();}
    public static PlatformService1710 getInstance() { if (INSTANCE == null) init(); return INSTANCE;}
    
    // ── Services ─────────────────────────────────────────────────────────────

    public final RenderingService1710 renderingImpl = new RenderingService1710();
    public final LifecycleService1710 lifecycleImpl = new LifecycleService1710();
    public final ResourceService1710 resourceImpl = new ResourceService1710();
    public final ReloadService1710 reloadImpl = new ReloadService1710();
    public final Lwjgl2GLBackend glDispatchImpl = new Lwjgl2GLBackend();
    public final Lwjgl2GLContext glContextImpl = new Lwjgl2GLContext();

    @Override public CgGLBackend       gl()           { return glDispatchImpl; }
    @Override public CgGLContext         capabilities() { return glContextImpl; }
    @Override public CgResourceService  resources()    { return resourceImpl; }
    @Override public CgRenderingService rendering()    { return renderingImpl; }
    @Override public CgLifecycleService lifecycle()    { return lifecycleImpl; }
    @Override public CgReloadService    reload()       { return reloadImpl; }
    
    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * FML preInit phase. Constructs and registers all platform services with {@link CgPlatform}.
     * Safe to call before any GL context exists.
     */
    public static void onPreInit() {
        CgPlatform.register(PlatformService1710.getInstance());
    }

    /**
     * FML init phase. Performs wiring that requires Minecraft to be further along in
     * startup (resource manager available, version requirements processable).
     */
    public static void onInit() {
        CrystalGraphicsVersion.processAllRequirements();
        ReloadService1710.attachToResourceManager();
    }

}
