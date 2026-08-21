package com.crystalgraphics.platform;

import cpw.mods.fml.common.FMLCommonHandler;
import com.crystalgraphics.CrystalGraphicsVersion;
import com.crystalgraphics.platform.gl.Lwjgl2GLContext;
import com.crystalgraphics.platform.gl.Lwjgl2GLBackend;
import com.crystalgraphics.platform.service.LifecycleService1710;
import com.crystalgraphics.platform.service.ReloadService1710;
import com.crystalgraphics.platform.service.RenderingService1710;
import com.crystalgraphics.platform.service.ResourceService1710;
import com.crystalgraphics.platform.service.*;
import com.crystalgraphics.platform.gl.*;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.platform.state.AngelicaStateProvider;

/**
 * Complete MC 1.7.10 platform bundle. Implements {@link CgPlatformService} by composing
 * the mc1710 service adapters. Register via {@code CgPlatform.register(new PlatformService1710())}.
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

    // BUILT ON DEMAND, and typed as the SPI interfaces rather than the implementations.
    //
    // These used to be eager `public final` fields, which meant registering the platform at preInit
    // constructed all nine -- and on a DEDICATED SERVER that dies at the first one:
    // NoClassDefFoundError: org/lwjgl/LWJGLException, from Lwjgl2GLBackend. Every service here imports
    // either org.lwjgl or net.minecraft.client, neither of which exists server-side, so CrystalGraphics
    // could not load on a server at all -- and every mod depending on it was marked errored with it.
    //
    // Two halves to the fix and both are needed. LAZY, so a server that asks for no rendering
    // constructs none; and the fields are declared as the INTERFACE, so the LWJGL-touching class is
    // named only inside a method body. A field descriptor is resolved eagerly enough to matter, which
    // is the same rule that keeps JOML and Taffy on CrystalGUI's headless classpath -- a method-body
    // reference is not, which is why `input()` on a server is fine right up until somebody calls it.
    //
    // Nothing outside this class referenced the fields, so the public API is unchanged.
    private CgRenderingService renderingImpl;
    private CgLifecycleService lifecycleImpl;
    private CgResourceService  resourceImpl;
    private CgReloadService    reloadImpl;
    private CgGLBackend        glDispatchImpl;
    private CgGLContext        glContextImpl;
    private CgInputService     inputImpl;
    private CgSoundService     soundImpl;
    private CgCursorService    cursorImpl;

    @Override public CgGLBackend gl() {
        if (glDispatchImpl == null) glDispatchImpl = new Lwjgl2GLBackend();
        return glDispatchImpl;
    }

    @Override public CgGLContext capabilities() {
        if (glContextImpl == null) glContextImpl = new Lwjgl2GLContext();
        return glContextImpl;
    }

    @Override public CgResourceService resources() {
        if (resourceImpl == null) resourceImpl = new ResourceService1710();
        return resourceImpl;
    }

    @Override public CgRenderingService rendering() {
        if (renderingImpl == null) renderingImpl = new RenderingService1710();
        return renderingImpl;
    }

    @Override public CgLifecycleService lifecycle() {
        if (lifecycleImpl == null) lifecycleImpl = new LifecycleService1710();
        return lifecycleImpl;
    }

    @Override public CgReloadService reload() {
        if (reloadImpl == null) reloadImpl = new ReloadService1710();
        return reloadImpl;
    }

    @Override public CgInputService input() {
        if (inputImpl == null) inputImpl = new InputService1710();
        return inputImpl;
    }

    @Override public CgSoundService sound() {
        if (soundImpl == null) soundImpl = new SoundService1710();
        return soundImpl;
    }

    @Override public CgCursorService cursor() {
        if (cursorImpl == null) cursorImpl = new CursorService1710();
        return cursorImpl;
    }
    
    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * FML preInit phase. Constructs and registers all platform services with {@link CgPlatform}.
     * Safe to call before any GL context exists.
     */
    public static void onPreInit() {
        CgPlatform.register(PlatformService1710.getInstance());

        // Prefer Angelica's mirror over the driver for GL state reads.
        //
        // Angelica redirects ~200 GL call sites process-wide, so its mirror observes writes from Minecraft
        // and every other mod — coverage we could not obtain ourselves, and the reason our own redirector
        // was scrapped rather than extended. Reading it costs plain field access instead of a glGet, and a
        // glGet is a driver synchronisation point.
        //
        // Registered before any GL context exists, which is fine: the provider is only consulted when a
        // scope adopts. If Angelica is absent, or a future version renames something, every read falls back
        // to the glGet base — so this can cost performance, never correctness.
        if (AngelicaStateProvider.isAvailable()) {
            CgGlState.setProvider(new AngelicaStateProvider());
        }
    }

    /**
     * FML init phase. Performs wiring that requires Minecraft to be further along in
     * startup (resource manager available, version requirements processable).
     */
    public static void onInit() {
        // CLIENT ONLY, and the guard belongs here rather than at the caller.
        //
        // Both of these are client concepts: a GL version requirement is meaningless without a driver,
        // and IResourceManager is a client type. CrystalGraphics.onInit already returns early on a
        // server -- but it does so AFTER calling this, so the guard protected the two lines below it and
        // not the two inside it. On a dedicated server that was NoClassDefFoundError:
        // OpenGLVersionMismatchException, which extends FML's client-only
        // CustomModLoadingErrorDisplayException and so cannot even be loaded there.
        //
        // Note processAllRequirements() is itself documented as "a no-op on dedicated server" -- true of
        // what it DOES and irrelevant to whether its class can be loaded, which is the trap.
        if (!FMLCommonHandler.instance().getSide().isClient()) return;

        CrystalGraphicsVersion.processAllRequirements();
        ReloadService1710.attachToResourceManager();
    }

}
