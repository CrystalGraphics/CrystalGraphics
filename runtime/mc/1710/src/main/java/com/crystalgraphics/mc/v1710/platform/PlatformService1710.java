package com.crystalgraphics.mc.v1710.platform;

import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.lwjgl2.JOrbisDecoder;
import com.crystalgraphics.lwjgl2.Lwjgl2AudioOutput;
import com.crystalgraphics.lwjgl2.Lwjgl2CursorService;
import com.crystalgraphics.lwjgl2.ClipboardLwjgl2;
import com.crystalgraphics.lwjgl2.InputLwjgl2;
import com.crystalgraphics.platform.input.CgClipboard;
import com.crystalgraphics.platform.input.CgInput;
import com.crystalgraphics.mc.v1710.CrystalGraphicsVersion;
import com.crystalgraphics.mc.v1710.platform.gl.GLDevice1710;
import com.crystalgraphics.mc.v1710.platform.service.*;
import net.minecraftforge.common.MinecraftForge;
import cpw.mods.fml.common.FMLCommonHandler;

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
import com.crystalgraphics.mc.v1710.platform.world.Players1710;
import com.crystalgraphics.platform.service.CgProcessExit;
import com.crystalgraphics.platform.world.CgHostShapes;
import com.crystalgraphics.platform.world.CgLevels;
import com.crystalgraphics.platform.service.CgGameThreads;
import com.crystalgraphics.platform.input.CgKeyBindings;
import com.crystalgraphics.platform.service.CgHostPaint;
import com.crystalgraphics.platform.service.CgHostWindow;
import com.crystalgraphics.platform.service.CgServers;
import com.crystalgraphics.platform.world.CgPlayers;
import com.crystalgraphics.collision.mirror.CgMirrorHost;
import com.crystalgraphics.serialization.format.nbt.CgHostFormats;
import com.crystalgraphics.platform.service.CgGameClasses;
import com.crystalgraphics.platform.service.CgModLoader;
import com.crystalgraphics.platform.CgPlatformService;
import com.crystalgraphics.platform.CgService;
import com.crystalgraphics.platform.gl.*;
import com.crystalgraphics.platform.service.*;
import com.crystalgraphics.platform.world.CgHostCamera;
import com.crystalgraphics.platform.world.CgHostDebug;
import com.crystalgraphics.platform.world.CgWorldEvents;
import com.crystalgraphics.platform.audio.CgAudioDecoder;
import com.crystalgraphics.platform.audio.CgAudioOutput;
import com.crystalgraphics.platform.audio.CgHostDuck;
import com.crystalgraphics.platform.service.CgI18n;
import com.crystalgraphics.mc.v1710.platform.service.GameClasses1710;
import com.crystalgraphics.mc.v1710.platform.service.ModLoader1710;
import com.crystalgraphics.mc.v1710.platform.service.HostI18n1710;
import com.crystalgraphics.platform.audio.CgHostVolume;
import com.crystalgraphics.platform.world.CgWorldStimulus;

/**
 * 1.7.10's platform: the GL bundle over tier 1, and {@link #install} filling every slot this era answers.
 */
public final class PlatformService1710 implements CgPlatformService {
    
    // ── Singleton ─────────────────────────────────────────────────────────────
    private static PlatformService1710 INSTANCE;
    
    public static void init() { if (INSTANCE != null) return; INSTANCE = new PlatformService1710();}
    public static PlatformService1710 getInstance() { if (INSTANCE == null) init(); return INSTANCE;}
    
    // Built on demand and typed as the SPI: both name LWJGL, which a dedicated server lacks.

    @Override
    public void startDevice() {
        CgEngineLifecycle.addListener(new GLDevice1710());
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
        CgPlatform.provide(CgGameClasses.HOST, new GameClasses1710());
        CgPlatform.provide(CgLevels.HOST, Levels1710.INSTANCE);
        CgPlatform.provide(CgPlayers.HOST, Players1710.INSTANCE);
        CgPlatform.provide(CgServers.HOST, Servers1710.INSTANCE);
        CgPlatform.provide(CgHostShapes.HOST, new HostShapes1710());
        CgPlatform.provide(CgMirrorHost.HOST, new MirrorHost1710());
        CgPlatform.provide(CgHostFormats.HOST, new HostFormats1710());
        // FML traps a mod's own Runtime.halt.
        CgPlatform.provide(CgProcessExit.HOST, status -> FMLCommonHandler.instance().exitJava(status, true));
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
            CgPlatform.provide(CgInput.HOST, InputLwjgl2.INSTANCE);
            CgPlatform.provide(CgClipboard.HOST, ClipboardLwjgl2.INSTANCE);
            CgPlatform.provide(CgHostResources.HOST, HostResources1710.INSTANCE);
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
        HostResources1710.listenForReloads();
    }

}
