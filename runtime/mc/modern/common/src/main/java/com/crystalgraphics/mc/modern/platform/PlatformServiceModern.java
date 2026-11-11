package com.crystalgraphics.mc.modern.platform;

import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.mc.modern.platform.gl.GLDeviceModern;
// 26.3 ships SDL3 and no GLFW, so a 26.3 node cannot even load the GLFW pair.
//? if >=26.3 {
/*import com.crystalgraphics.sdl.ClipboardSdl;
import com.crystalgraphics.sdl.InputSdl;
import com.crystalgraphics.sdl.SdlCursorService;
*///?} else {
import com.crystalgraphics.lwjgl3.ClipboardGlfw;
import com.crystalgraphics.lwjgl3.GlfwCursorService;
import com.crystalgraphics.lwjgl3.InputGlfw;
//?}
import com.crystalgraphics.lwjgl3.Lwjgl3AudioOutput;
import com.crystalgraphics.lwjgl3.StbVorbisDecoder;

import com.crystalgraphics.mc.modern.platform.service.GameClassesModern;
import com.crystalgraphics.mc.modern.platform.service.HostResourcesModern;
import com.crystalgraphics.platform.input.CgClipboard;
import com.crystalgraphics.platform.input.CgInput;
import com.crystalgraphics.platform.service.CgHostResources;
import com.crystalgraphics.mc.modern.platform.world.ClientModern;
import com.crystalgraphics.mc.modern.platform.world.HostCameraModern;
import com.crystalgraphics.mc.modern.platform.world.HostDebugModern;
import com.crystalgraphics.mc.modern.platform.world.HostDuckModern;
import com.crystalgraphics.mc.modern.platform.world.HostFormatsModern;
import com.crystalgraphics.mc.modern.platform.world.HostShapesModern;
import com.crystalgraphics.mc.modern.platform.world.HostVolumeModern;
import com.crystalgraphics.mc.modern.platform.world.LevelsModern;
import com.crystalgraphics.mc.modern.platform.world.MirrorHostModern;
import com.crystalgraphics.mc.modern.platform.world.PlayersModern;

import com.crystalgraphics.mc.modern.platform.world.WorldStimulusModern;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgPlatformService;
import com.crystalgraphics.collision.mirror.CgMirrorHost;
import com.crystalgraphics.serialization.format.nbt.CgHostFormats;
import com.crystalgraphics.platform.service.CgCursorService;
import com.crystalgraphics.platform.service.CgGameClasses;
import com.crystalgraphics.platform.service.CgGameDirectory;
import com.crystalgraphics.platform.service.CgGameSession;
import com.crystalgraphics.platform.service.CgModLoader;
import com.crystalgraphics.platform.command.CgCommands;
import com.crystalgraphics.mc.modern.platform.command.CommandsModern;
import com.crystalgraphics.platform.service.CgShaderPacks;
import com.crystalgraphics.platform.world.CgHostCamera;
import com.crystalgraphics.platform.world.CgHostDebug;
import com.crystalgraphics.platform.world.CgHostShapes;
import com.crystalgraphics.platform.world.CgLevels;
import com.crystalgraphics.platform.world.CgPlayers;
import com.crystalgraphics.platform.service.CgGameThreads;
import com.crystalgraphics.platform.input.CgKeyBindings;
import com.crystalgraphics.platform.service.CgScreens;
import com.crystalgraphics.platform.service.CgHostWindow;
import com.crystalgraphics.platform.service.CgServers;
import com.crystalgraphics.platform.world.CgModelSpace;
import com.crystalgraphics.platform.world.CgWorldEvents;
import com.crystalgraphics.platform.audio.CgAudioDecoder;
import com.crystalgraphics.platform.audio.CgAudioOutput;
import com.crystalgraphics.platform.audio.CgHostDuck;
import com.crystalgraphics.platform.service.CgI18n;
import com.crystalgraphics.mc.modern.platform.service.HostI18nModern;
import com.crystalgraphics.platform.audio.CgHostVolume;
import com.crystalgraphics.platform.world.CgWorldStimulus;

import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.nio.file.Paths;
//? if >=26.2 {
/*import com.crystalgraphics.mc.modern.platform.vulkan.Blaze3dVulkanHost;
*///?}

/**
 * The modern platform: the GL bundle, and every slot this era answers, through {@link #install} (both sides) and
 * {@link #installClient} (the client's).
 *
 * <p>An assembler: each slot is this era's own class or a tier-1 one ({@code runtime/lwjgl/3}, {@code sdl}) handed
 * the one Minecraft fact it needs, {@link #windowHandle()}, as a supplier.</p>
 *
 * <p>{@link #startDevice()} is the exception worth naming: the device's host state is tier 2
 * ({@code HostStateModern}), because telling Minecraft what state we changed is the one thing tier 1 must not know
 * how to do.</p>
 */
public final class PlatformServiceModern implements CgPlatformService {

    private static PlatformServiceModern instance;

    public static synchronized PlatformServiceModern getInstance() {
        if (instance == null) instance = new PlatformServiceModern();
        return instance;
    }

    /**
     * Installs the modern platform, both sides, as the loader constructs CrystalGraphics: the bundle, the loader's
     * {@code CgModLoader} host, and the world slots every side reads (levels, players, host shapes, the collision
     * mirror, host formats). Before {@code CgEngineLifecycle.preInit}.
     *
     * <pre>{@code
     * PlatformServiceModern.install(ModLoaderForge.INSTANCE);
     * if (client) PlatformServiceModern.installClient();
     * CgEngineLifecycle.preInit();
     * }</pre>
     */
    public static void install(CgModLoader.Host loader) {
        CgPlatform.register(getInstance());
        CgPlatform.provide(CgModLoader.HOST, loader);
        CgPlatform.provide(CgGameClasses.HOST, new GameClassesModern());
        CgPlatform.provide(CgLevels.HOST, LevelsModern.INSTANCE);
        CgPlatform.provide(CgPlayers.HOST, PlayersModern.INSTANCE);
        CgPlatform.provide(CgServers.HOST, ServersModern.INSTANCE);
        CgPlatform.provide(CgHostShapes.HOST, new HostShapesModern());
        CgPlatform.provide(CgMirrorHost.HOST, new MirrorHostModern());
        CgPlatform.provide(CgHostFormats.HOST, new HostFormatsModern());
        CgPlatform.provide(CgCommands.HOST, CommandsModern.INSTANCE);
    }

    /**
     * Installs the client's slots, after {@link #install}, from the loader's client side only: the client halves of the
     * levels and players, the camera, debug view, audio, language, stimulus, game directory and cursor. They name client
     * and LWJGL classes a dedicated server does not ship, so nothing on a server path may call it.
     */
    public static void installClient() {
        ClientModern.install();
        CgPlatform.provide(CgHostCamera.SERVICE, new HostCameraModern());
        CgPlatform.provide(CgHostDebug.SERVICE, new HostDebugModern());
        CgPlatform.provide(CgModelSpace.SERVICE, HostViewModern::modelSpace);
        CgPlatform.provide(CgAudioOutput.SERVICE, new Lwjgl3AudioOutput(HostVolumeModern::device));
        CgPlatform.provide(CgHostVolume.SERVICE, new HostVolumeModern());
        CgPlatform.provide(CgHostDuck.SERVICE, new HostDuckModern());
        CgPlatform.provide(CgI18n.HOST, new HostI18nModern());
        CgPlatform.provide(CgAudioDecoder.SERVICE, new StbVorbisDecoder());
        CgPlatform.provide(CgWorldStimulus.SERVICE, new WorldStimulusModern());
        CgPlatform.provide(CgGameDirectory.HOST, new GameDirectoryModern());
        CgPlatform.provide(CgGameThreads.HOST, new GameThreadsModern());
        CgPlatform.provide(CgHostWindow.HOST, new HostWindowModern());
        CgPlatform.provide(CgScreens.HOST, ScreensModern.INSTANCE);
        CgPlatform.provide(CgGameSession.HOST, GameSessionModern.INSTANCE);
        CgPlatform.provide(CgKeyBindings.HOST, KeyBindingsModern.INSTANCE);
        CgKeyBindings.hostProvided();
        CgEngineLifecycle.onClientTick(KeyBindingsModern.INSTANCE::tick);
        CgPlatform.provide(CgShaderPacks.HOST, CgShaderPacks.irisApi());   // Iris, Oculus
        // What WorldEventsModern.poll reports; the explosion and the broken block are the loaders' mixins'.
        //? if >=1.14 {
        CgWorldEvents.declare(CgWorldEvents.ENTITY_HURT | CgWorldEvents.ENTITY_DIED);
        //?}
        //? if >=1.16.5 {
        CgWorldEvents.declare(CgWorldEvents.LIGHTNING);
        //?}

        // The window arrives as a SUPPLIER, not a handle: GLFW's window outlives no recreation and
        // is not open yet when the backend is first built, so a captured long would be stale
        // exactly when it mattered. That supplier is the only Minecraft fact the adapter needs,
        // which is what lets it sit in tier 1 knowing nothing about this era.
        //? if >=26.3 {
        /*CgPlatform.provide(CgCursorService.SERVICE, new SdlCursorService());
        CgPlatform.provide(CgInput.HOST, new InputModern(InputSdl.INSTANCE));
        CgPlatform.provide(CgClipboard.HOST, ClipboardSdl.INSTANCE);
        *///?} else {
        CgPlatform.provide(CgCursorService.SERVICE,
                new GlfwCursorService(PlatformServiceModern::windowHandle));
        CgPlatform.provide(CgInput.HOST, new InputModern(new InputGlfw(PlatformServiceModern::windowHandle)));
        CgPlatform.provide(CgClipboard.HOST, new ClipboardGlfw(PlatformServiceModern::windowHandle));
        //?}
        CgPlatform.provide(CgHostResources.HOST, HostResourcesModern.INSTANCE);
        // The graphics start's GL-free work, begun behind mod loading rather than at the first frame
        CgEngineLifecycle.clientStarting();
    }

    // On the render thread at the graphics start, never at registration, so naming Blaze3D cannot reach a server; and
    // Minecraft's device exists by then, which is what says which API this session renders through.
    @Override
    public void startDevice() {
        if (GraphicsApi.vulkan()) {
            startVulkan();
        } else {
            HostStateVerifier.announceIfEnabled();
            CgEngineLifecycle.addListener(new GLDeviceModern());
        }
    }

    // Ours over Minecraft's own Vulkan device, which hosts it: 26.2 and later.
    private static void startVulkan() {
        //? if >=26.2 {
        /*if (Blaze3dVulkanHost.current() != null) return;
        Minecraft mc = Minecraft.getInstance();
        Blaze3dVulkanHost.start(Windows.of(mc).getWidth(), Windows.of(mc).getHeight());
        *///?} else {
        throw new IllegalStateException("Minecraft renders through Vulkan only from 26.2");
        //?}
    }

    /** The GLFW window, or 0 before one exists. The value tier 1 is handed rather than reaching for. */
    static long windowHandle() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || Windows.of(mc) == null) return 0L;
        return Windows.handle(mc);
    }
}
