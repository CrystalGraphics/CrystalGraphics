package com.crystalgraphics.mc.legacy.platform;

import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.lwjgl2.JOrbisDecoder;
import com.crystalgraphics.lwjgl2.Lwjgl2AudioOutput;
import com.crystalgraphics.lwjgl2.Lwjgl2CursorService;
import com.crystalgraphics.lwjgl2.Lwjgl2GLContext;
import com.crystalgraphics.lwjgl2.Lwjgl2InputService;
import com.crystalgraphics.mc.legacy.platform.service.ModLoaderLegacy;
import com.crystalgraphics.mc.legacy.platform.service.GameDirectoryService;
import com.crystalgraphics.mc.legacy.platform.service.LifecycleService;
import com.crystalgraphics.mc.legacy.platform.service.ReloadService;
import com.crystalgraphics.mc.legacy.platform.service.RenderingService;
import com.crystalgraphics.mc.legacy.platform.service.ResourceService;
import com.crystalgraphics.mc.legacy.platform.service.SoundService;
import com.crystalgraphics.mc.legacy.platform.world.ClientLegacy;
import com.crystalgraphics.mc.legacy.platform.world.HostCameraLegacy;
import com.crystalgraphics.mc.legacy.platform.world.HostDebugLegacy;
import com.crystalgraphics.mc.legacy.platform.world.WorldEventsLegacy;
import com.crystalgraphics.mc.legacy.platform.world.HostDuckLegacy;
import com.crystalgraphics.mc.legacy.platform.world.HostFormatsLegacy;
import com.crystalgraphics.mc.legacy.platform.world.HostShapesLegacy;
import com.crystalgraphics.mc.legacy.platform.world.HostVolumeLegacy;
import com.crystalgraphics.mc.legacy.platform.world.LevelsLegacy;
import com.crystalgraphics.mc.legacy.platform.world.MirrorHostLegacy;
import com.crystalgraphics.mc.legacy.platform.world.PlayerQueryLegacy;
import com.crystalgraphics.mc.legacy.platform.world.WorldStimulusLegacy;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgModLoader;
import com.crystalgraphics.platform.service.CgProcessExit;
import com.crystalgraphics.platform.world.CgHostShapes;
import com.crystalgraphics.platform.world.CgLevels;
import com.crystalgraphics.platform.service.CgGameThreads;
import com.crystalgraphics.platform.service.CgHostPaint;
import com.crystalgraphics.platform.service.CgHostWindow;
import com.crystalgraphics.platform.service.CgServers;
import com.crystalgraphics.platform.world.CgPlayerQuery;
import com.crystalgraphics.collision.mirror.CgMirrorHost;
import com.crystalgraphics.serialization.format.nbt.CgHostFormats;
import com.crystalgraphics.platform.CgPlatformService;
import com.crystalgraphics.platform.gl.CgGLBackend;
import com.crystalgraphics.platform.gl.CgGLContext;
import com.crystalgraphics.platform.service.CgCursorService;
import com.crystalgraphics.platform.service.CgGameDirectory;
import com.crystalgraphics.platform.service.CgInputService;
import com.crystalgraphics.platform.service.CgLifecycleService;
import com.crystalgraphics.platform.service.CgReloadService;
import com.crystalgraphics.platform.service.CgRenderingService;
import com.crystalgraphics.platform.service.CgResourceService;
import com.crystalgraphics.platform.service.CgSoundService;
import com.crystalgraphics.platform.world.CgHostCamera;
import com.crystalgraphics.platform.world.CgHostDebug;
import com.crystalgraphics.platform.world.CgWorldEvents;
import com.crystalgraphics.platform.audio.CgAudioDecoder;
import com.crystalgraphics.platform.audio.CgAudioOutput;
import com.crystalgraphics.platform.audio.CgHostDuck;
import com.crystalgraphics.platform.service.CgI18n;
import com.crystalgraphics.mc.legacy.platform.service.HostI18nLegacy;
import com.crystalgraphics.platform.audio.CgHostVolume;
import com.crystalgraphics.platform.world.CgWorldStimulus;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;

/**
 * The Forge 1.8–1.12.2 platform bundle: tier 1's LWJGL2 services, the {@link GlStateManagerGLBackend}, and
 * this era's Minecraft-facing services.
 *
 * <pre>
 * PlatformServiceLegacy.install(client);    // preInit, both sides
 * </pre>
 *
 * <ul>
 *   <li>Every service is built on first use and held as its SPI type: they name LWJGL and client classes
 *       a dedicated server does not have, and a field of such a type fails at class load.</li>
 *   <li>The cursor and the world slots are filled on a client only, for the same reason.</li>
 * </ul>
 */
public final class PlatformServiceLegacy implements CgPlatformService {

    private static PlatformServiceLegacy instance;

    private CgGLBackend        glBackend;
    private CgGLContext        glContext;
    private CgLifecycleService lifecycle;
    private CgReloadService    reload;
    private CgResourceService  resources;
    private CgRenderingService rendering;
    private CgInputService     input;
    private CgSoundService     sound;

    public static synchronized PlatformServiceLegacy getInstance() {
        if (instance == null) instance = new PlatformServiceLegacy();
        return instance;
    }

    /**
     * Installs Forge 1.8–1.12.2's platform at preInit, before {@code CgEngineLifecycle.preInit}: the bundle, the mod
     * loader, the world slots and process exit on both sides, the engine's ticks, and on a client its own slots.
     *
     * <pre>{@code
     * PlatformServiceLegacy.install(FMLCommonHandler.instance().getSide().isClient());
     * CgEngineLifecycle.preInit();
     * }</pre>
     */
    public static void install(boolean client) {
        CgPlatform.register(getInstance());
        CgPlatform.provide(CgModLoader.HOST, new ModLoaderLegacy());
        CgPlatform.provide(CgLevels.SERVICE, LevelsLegacy.INSTANCE);
        CgPlatform.provide(CgPlayerQuery.SERVICE, PlayerQueryLegacy.INSTANCE);
        CgPlatform.provide(CgServers.HOST, ServersLegacy.INSTANCE);
        CgPlatform.provide(CgHostShapes.SERVICE, new HostShapesLegacy());
        CgPlatform.provide(CgMirrorHost.SERVICE, new MirrorHostLegacy());
        CgPlatform.provide(CgHostFormats.SERVICE, new HostFormatsLegacy());
        // FML traps a mod's own Runtime.halt.
        CgPlatform.provide(CgProcessExit.SERVICE, status -> FMLCommonHandler.instance().exitJava(status, true));
        FMLCommonHandler.instance().bus().register(new TicksLegacy());
        if (!client) return;
        CgPlatform.provide(CgCursorService.SERVICE, new Lwjgl2CursorService());
        ClientLegacy.install();
        CgPlatform.provide(CgAudioOutput.SERVICE, new Lwjgl2AudioOutput());
        CgPlatform.provide(CgHostVolume.SERVICE, new HostVolumeLegacy());
        CgPlatform.provide(CgHostDuck.SERVICE, new HostDuckLegacy());
        CgPlatform.provide(CgI18n.HOST, new HostI18nLegacy());
        CgPlatform.provide(CgAudioDecoder.SERVICE, new JOrbisDecoder());
        CgPlatform.provide(CgWorldStimulus.SERVICE, new WorldStimulusLegacy());
        CgPlatform.provide(CgGameDirectory.HOST, new GameDirectoryService());
        CgPlatform.provide(CgGameThreads.HOST, new GameThreadsLegacy());
        CgPlatform.provide(CgHostWindow.HOST, new HostWindowLegacy());
        CgPlatform.provide(CgHostPaint.HOST, new HostPaintLegacy());
        // WorldEventsLegacy polls the hurts, deaths and lightning; ExplosionHook and LevelEventHook the rest.
        CgWorldEvents.declare(CgWorldEvents.EXPLOSION | CgWorldEvents.BLOCK_BROKEN | CgWorldEvents.ENTITY_HURT
                | CgWorldEvents.ENTITY_DIED | CgWorldEvents.LIGHTNING);
        HostCameraLegacy camera = new HostCameraLegacy();
        CgPlatform.provide(CgHostCamera.SERVICE, camera);
        CgPlatform.provide(CgHostDebug.SERVICE, new HostDebugLegacy());
        MinecraftForge.EVENT_BUS.register(camera);
        FMLCommonHandler.instance().bus().register(new WorldEventsLegacy.Ticks());
        FMLCommonHandler.instance().bus().register(new HostCameraLegacy.Ticks());
        MinecraftForge.EVENT_BUS.register(new OverlayStagesLegacy());
        MinecraftForge.EVENT_BUS.register(new LoadingScreenLegacy());
        // The graphics start's GL-free work, begun behind mod loading rather than at the first frame
        CgEngineLifecycle.clientStarting();
    }

    @Override public CgGLBackend gl() {
        if (glBackend == null) {
            glBackend = new GlStateManagerGLBackend();
            HostStateLegacy.install();
            CgEngineLifecycle.addListener(new GLDeviceLegacy());
        }
        return glBackend;
    }

    @Override public CgGLContext capabilities() {
        if (glContext == null) glContext = new Lwjgl2GLContext();
        return glContext;
    }

    @Override public CgLifecycleService lifecycle() {
        if (lifecycle == null) lifecycle = new LifecycleService();
        return lifecycle;
    }

    @Override public CgReloadService reload() {
        if (reload == null) reload = new ReloadService();
        return reload;
    }

    @Override public CgResourceService resources() {
        if (resources == null) resources = new ResourceService();
        return resources;
    }

    @Override public CgRenderingService rendering() {
        if (rendering == null) rendering = new RenderingService();
        return rendering;
    }

    @Override public CgInputService input() {
        if (input == null) input = new Lwjgl2InputService();
        return input;
    }

    @Override public CgSoundService sound() {
        if (sound == null) sound = new SoundService();
        return sound;
    }
}
