package com.crystalgraphics.mc.v1710.platform;

import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.lwjgl2.JOrbisDecoder;
import com.crystalgraphics.lwjgl2.Lwjgl2AudioOutput;
import com.crystalgraphics.lwjgl2.Lwjgl2CursorService;
import com.crystalgraphics.lwjgl2.Lwjgl2GLContext;
import com.crystalgraphics.lwjgl2.Lwjgl2InputService;
import com.crystalgraphics.mc.v1710.CrystalGraphicsVersion;
import com.crystalgraphics.mc.v1710.platform.gl.GLBackend1710;
import com.crystalgraphics.mc.v1710.platform.gl.GLDevice1710;
import com.crystalgraphics.mc.v1710.platform.service.*;
import net.minecraftforge.common.MinecraftForge;
import cpw.mods.fml.common.FMLCommonHandler;

import com.crystalgraphics.mc.v1710.platform.state.AngelicaStateProvider;
import com.crystalgraphics.mc.v1710.platform.state.RawDriverProvider1710;
import com.crystalgraphics.mc.v1710.platform.world.Client1710;
import com.crystalgraphics.mc.v1710.platform.world.HostCamera1710;
import com.crystalgraphics.mc.v1710.platform.world.HostDebug1710;
import com.crystalgraphics.mc.v1710.platform.world.WorldEvents1710;
import com.crystalgraphics.mc.v1710.platform.world.HostDuck1710;
import com.crystalgraphics.mc.v1710.platform.world.HostVolume1710;
import com.crystalgraphics.mc.v1710.platform.world.WorldStimulus1710;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.mc.v1710.platform.world.HostFormats1710;
import com.crystalgraphics.mc.v1710.platform.world.HostShapes1710;
import com.crystalgraphics.mc.v1710.platform.world.Levels1710;
import com.crystalgraphics.mc.v1710.platform.world.MirrorHost1710;
import com.crystalgraphics.mc.v1710.platform.world.PlayerQuery1710;
import com.crystalgraphics.platform.service.CgProcessExit;
import com.crystalgraphics.platform.world.CgHostShapes;
import com.crystalgraphics.platform.world.CgLevels;
import com.crystalgraphics.platform.service.CgGameThreads;
import com.crystalgraphics.platform.input.CgInputStage;
import com.crystalgraphics.platform.input.CgKeyBindings;
import com.crystalgraphics.platform.service.CgHostPaint;
import com.crystalgraphics.platform.service.CgHostWindow;
import com.crystalgraphics.platform.service.CgServers;
import com.crystalgraphics.platform.world.CgPlayerQuery;
import com.crystalgraphics.collision.mirror.CgMirrorHost;
import com.crystalgraphics.serialization.format.nbt.CgHostFormats;
import com.crystalgraphics.platform.service.CgModLoader;
import com.crystalgraphics.platform.CgPlatformService;
import com.crystalgraphics.platform.CgService;
import com.crystalgraphics.platform.gl.*;
import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.platform.service.*;
import com.crystalgraphics.platform.world.CgHostCamera;
import com.crystalgraphics.platform.world.CgHostDebug;
import com.crystalgraphics.platform.world.CgWorldEvents;
import com.crystalgraphics.platform.audio.CgAudioDecoder;
import com.crystalgraphics.platform.audio.CgAudioOutput;
import com.crystalgraphics.platform.audio.CgHostDuck;
import com.crystalgraphics.platform.service.CgI18n;
import com.crystalgraphics.mc.v1710.platform.service.ModLoader1710;
import com.crystalgraphics.mc.v1710.platform.service.HostI18n1710;
import com.crystalgraphics.platform.audio.CgHostVolume;
import com.crystalgraphics.platform.world.CgWorldStimulus;

/**
 * Complete MC 1.7.10 platform bundle. Implements {@link CgPlatformService} by composing
 * the 1.7.10 service adapters. Register via {@code CgPlatform.register(new PlatformService1710())}.
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

    @Override public CgGLBackend gl() {
        if (glDispatchImpl == null) {
            glDispatchImpl = new GLBackend1710();
            CgEngineLifecycle.addListener(new GLDevice1710());
        }
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
        if (inputImpl == null) inputImpl = new Lwjgl2InputService();
        return inputImpl;
    }

    @Override public CgSoundService sound() {
        if (soundImpl == null) soundImpl = new SoundService1710();
        return soundImpl;
    }
    
    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Installs 1.7.10's platform at preInit, before {@code CgEngineLifecycle.preInit}: the bundle, the mod loader, the
     * world slots and process exit on both sides, the engine's ticks, the GL state provider, and on a client its own
     * slots. Safe before any GL context exists.
     *
     * <pre>{@code
     * PlatformService1710.install(FMLCommonHandler.instance().getSide().isClient());
     * CgEngineLifecycle.preInit();
     * }</pre>
     */
    public static void install(boolean client) {
        CgPlatform.register(PlatformService1710.getInstance());
        CgPlatform.provide(CgModLoader.HOST, new ModLoader1710());
        CgPlatform.provide(CgLevels.SERVICE, Levels1710.INSTANCE);
        CgPlatform.provide(CgPlayerQuery.SERVICE, PlayerQuery1710.INSTANCE);
        CgPlatform.provide(CgServers.HOST, Servers1710.INSTANCE);
        CgPlatform.provide(CgHostShapes.SERVICE, new HostShapes1710());
        CgPlatform.provide(CgMirrorHost.SERVICE, new MirrorHost1710());
        CgPlatform.provide(CgHostFormats.SERVICE, new HostFormats1710());
        // FML traps a mod's own Runtime.halt.
        CgPlatform.provide(CgProcessExit.SERVICE, status -> FMLCommonHandler.instance().exitJava(status, true));
        FMLCommonHandler.instance().bus().register(new Ticks1710());

        // The cursor, filled here so no consumer has to. It is a CgService SLOT rather than a bundle
        // method because an unpresented cursor is cosmetic and this engine runs where there is nothing
        // to present to; whoever owns a cursor VOCABULARY resolves its keyword to a picture and hands
        // the picture over, so this side names none. LWJGL2 needs no window handle -- Mouse is bound
        // to the single display it owns.
        //
        // CLIENT ONLY. Lwjgl2CursorService names org.lwjgl.input.Mouse, so CONSTRUCTING it on a
        // dedicated server is NoClassDefFoundError: org/lwjgl/LWJGLException -- thrown out of preInit,
        // which errors every mod that depends on this one. CgPlatform.register builds no GL backend for
        // the same reason. The slot's absent value is CursorService.NONE, so a
        // server that never fills it is the supported case rather than a degraded one.
        if (client) {
            CgPlatform.provide(CgCursorService.SERVICE, new Lwjgl2CursorService());
            CgPlatform.provide(CgShaderPacks.HOST, CgShaderPacks.irisApi());   // Angelica
            // The client's levels and player, client only for the same reason.
            Client1710.install();
            CgPlatform.provide(CgHostCamera.SERVICE, new HostCamera1710());
            CgPlatform.provide(CgHostDebug.SERVICE, new HostDebug1710());
            CgPlatform.provide(CgAudioOutput.SERVICE, new Lwjgl2AudioOutput());
            CgPlatform.provide(CgHostVolume.SERVICE, new HostVolume1710());
            CgPlatform.provide(CgHostDuck.SERVICE, new HostDuck1710());
            CgPlatform.provide(CgI18n.HOST, new HostI18n1710());
            CgPlatform.provide(CgAudioDecoder.SERVICE, new JOrbisDecoder());
            CgPlatform.provide(CgWorldStimulus.SERVICE, new WorldStimulus1710());
            CgPlatform.provide(CgGameDirectory.HOST, new GameDirectoryService1710());
            CgPlatform.provide(CgGameThreads.HOST, new GameThreads1710());
            CgPlatform.provide(CgHostWindow.HOST, new HostWindow1710());
            CgPlatform.provide(CgHostPaint.HOST, new HostPaint1710());
            CgPlatform.provide(CgInputStage.HOST, new InputHost1710());
            CgPlatform.provide(CgKeyBindings.HOST, KeyBindings1710.INSTANCE);
            CgKeyBindings.hostProvided();
            CgEngineLifecycle.onClientTick(KeyBindings1710.INSTANCE::tick);
            // WorldEvents1710 polls the hurts, deaths and lightning; ExplosionHook and LevelEventHook the rest.
            CgWorldEvents.declare(CgWorldEvents.EXPLOSION | CgWorldEvents.BLOCK_BROKEN | CgWorldEvents.ENTITY_HURT
                    | CgWorldEvents.ENTITY_DIED | CgWorldEvents.LIGHTNING);
            FMLCommonHandler.instance().bus().register(new WorldEvents1710.Ticks());
            FMLCommonHandler.instance().bus().register(new HostCamera1710.Ticks());
            MinecraftForge.EVENT_BUS.register(new HostCamera1710.Renders());
            MinecraftForge.EVENT_BUS.register(new OverlayStages1710());
            // The render tick is on FML's bus; the mixin hook (MixinGuiScreen) takes the tick-time drain.
            FMLCommonHandler.instance().bus().register(new ScreenInput1710());
            MinecraftForge.EVENT_BUS.register(new LoadingScreen1710());
            // The graphics start's GL-free work, begun behind mod loading rather than at the first frame
            CgEngineLifecycle.clientStarting();
        }

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
            // Angelica also answers glGet, so only a reader past it can check its cache against the driver.
            // It binds programs of its own behind glUseProgram(0) (fixed-function emulation, shader packs),
            // so the driver's program is not expected to match.
            if (CgGlStateManager.ROUND_TRIP.get()) {
                RawDriverProvider1710 raw = RawDriverProvider1710.create();
                if (raw != null) CgGlState.setDriverReader(raw, CgGlSlot.PROGRAM);
            }
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
