package com.crystalgraphics.mc.legacy.platform;

import com.crystalgraphics.mc.fluid.CgFluids;
import com.crystalgraphics.mc.legacy.platform.world.EntitySavesLegacy;
import com.crystalgraphics.world.CgEntitySaves;
import com.crystalgraphics.mc.model.CgVanillaPoses;
import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.lwjgl2.JOrbisDecoder;
import com.crystalgraphics.lwjgl2.Lwjgl2AudioOutput;
import com.crystalgraphics.lwjgl2.Lwjgl2CursorService;
import com.crystalgraphics.lwjgl2.ClipboardLwjgl2;
import com.crystalgraphics.lwjgl2.InputLwjgl2;
import com.crystalgraphics.mc.legacy.platform.actor.ActorsLegacy;
import com.crystalgraphics.mc.legacy.platform.actor.ServerActorsLegacy;
import com.crystalgraphics.mc.legacy.platform.service.HostResourcesLegacy;
import com.crystalgraphics.platform.input.CgClipboard;
import com.crystalgraphics.platform.input.CgInput;
import com.crystalgraphics.platform.service.CgHostResources;
import com.crystalgraphics.mc.legacy.platform.service.GameClassesLegacy;
import com.crystalgraphics.mc.legacy.platform.service.ModLoaderLegacy;
import com.crystalgraphics.mc.legacy.platform.service.GameDirectoryService;
import com.crystalgraphics.mc.legacy.platform.world.ClientLegacy;
import com.crystalgraphics.mc.legacy.platform.world.HostCameraLegacy;
import com.crystalgraphics.mc.legacy.platform.world.HostDebugLegacy;
import com.crystalgraphics.mc.legacy.platform.world.WorldEventsLegacy;
import com.crystalgraphics.mc.legacy.platform.world.HostDuckLegacy;
import com.crystalgraphics.mc.legacy.platform.world.NbtLegacy;
import com.crystalgraphics.mc.legacy.platform.world.HostShapesLegacy;
import com.crystalgraphics.mc.legacy.platform.world.HostVolumeLegacy;
import com.crystalgraphics.mc.legacy.platform.world.LevelsLegacy;
import com.crystalgraphics.mc.legacy.platform.world.EntityMovementLegacy;
import com.crystalgraphics.mc.legacy.platform.world.MirrorHostLegacy;
import com.crystalgraphics.mc.legacy.platform.world.PlayersLegacy;
import com.crystalgraphics.mc.legacy.platform.world.WorldStimulusLegacy;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgGameClasses;
import com.crystalgraphics.platform.service.CgModLoader;
import com.crystalgraphics.platform.service.CgProcessExit;
import com.crystalgraphics.platform.world.CgHostShapes;
import com.crystalgraphics.platform.world.CgEntityMovement;
import com.crystalgraphics.platform.world.CgLevels;
import com.crystalgraphics.platform.service.CgGameThreads;
import com.crystalgraphics.platform.input.CgKeyBindings;
import com.crystalgraphics.mc.container.CgContainers;
import com.crystalgraphics.mc.block.CgBlocks;
import com.crystalgraphics.mc.item.CgItems;
import com.crystalgraphics.platform.service.CgScreens;
import com.crystalgraphics.platform.service.CgGameSession;
import com.crystalgraphics.platform.service.CgServers;
import com.crystalgraphics.platform.world.CgPlayers;
import com.crystalgraphics.collision.mirror.CgMirrorHost;
import com.crystalgraphics.serialization.nbt.CgNbt;
import com.crystalgraphics.platform.CgPlatformService;
import com.crystalgraphics.platform.service.CgCursorService;
import com.crystalgraphics.platform.service.CgGameDirectory;
import com.crystalgraphics.platform.world.CgCameraManager;
import com.crystalgraphics.platform.world.CgHostDebug;
import com.crystalgraphics.platform.world.CgWorldEvents;
import com.crystalgraphics.platform.audio.CgAudioDecoder;
import com.crystalgraphics.platform.audio.CgAudioOutput;
import com.crystalgraphics.platform.audio.CgHostDuck;
import com.crystalgraphics.platform.service.CgI18n;
import com.crystalgraphics.mc.legacy.platform.service.HostI18nLegacy;
import com.crystalgraphics.platform.audio.CgHostVolume;
import com.crystalgraphics.platform.world.CgWorldStimulus;
import com.crystalgraphics.mc.render.block.CgBlockPictures;
import com.crystalgraphics.mc.render.entity.CgEntityPictures;
import com.crystalgraphics.mc.render.CgHostPictures;
import com.crystalgraphics.mc.render.item.CgItemIcons;
import com.crystalgraphics.mc.render.tooltip.CgTooltips;
import com.crystalgraphics.probe.CgPicturesProbe;
import com.crystalgraphics.probe.CgValuesProbe;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;

/**
 * The Forge 1.8–1.12.2 platform bundle: tier 1's LWJGL2 services, the GL device, and this era's Minecraft-facing
 * services.
 *
 * <pre>
 * PlatformServiceLegacy.install(client);    // preInit, both sides
 * </pre>
 *
 * <ul>
 *   <li>The GL device is made at the graphics start ({@link #startDevice}): it names LWJGL, which a dedicated server
 *       does not have.</li>
 *   <li>Input, the clipboard, the resources and the rest are filled on a client only, for the same reason.</li>
 * </ul>
 */
public final class PlatformServiceLegacy implements CgPlatformService {

    private static PlatformServiceLegacy instance;

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
        CgPlatform.provide(CgGameClasses.HOST, new GameClassesLegacy());
        CgPlatform.provide(CgLevels.HOST, LevelsLegacy.INSTANCE);
        CgPlatform.provide(CgEntityMovement.HOST, EntityMovementLegacy.INSTANCE);
        CgPlatform.provide(CgEntitySaves.HOST, EntitySavesLegacy.INSTANCE);
        // both sides: entities joining a server's levels, for their actors there
        MinecraftForge.EVENT_BUS.register(new ServerActorsLegacy());
        CgPlatform.provide(CgPlayers.HOST, PlayersLegacy.INSTANCE);
        CgPlatform.provide(CgServers.HOST, ServersLegacy.INSTANCE);
        CgPlatform.provide(CgHostShapes.HOST, new HostShapesLegacy());
        CgPlatform.provide(CgMirrorHost.HOST, new MirrorHostLegacy());
        CgPlatform.provide(CgNbt.HOST, new NbtLegacy());
        CgPlatform.provide(CgItems.HOST, ItemsLegacy.INSTANCE);
        CgPlatform.provide(CgBlocks.HOST, BlocksLegacy.INSTANCE);
        CgPlatform.provide(CgValuesProbe.Host.SERVICE, ValuesProbeLegacy.INSTANCE);
        // FML traps a mod's own Runtime.halt.
        CgPlatform.provide(CgProcessExit.HOST, status -> FMLCommonHandler.instance().exitJava(status, true));
        FMLCommonHandler.instance().bus().register(new TicksLegacy());
        if (!client) return;
        CgPlatform.provide(CgCursorService.SERVICE, new Lwjgl2CursorService());
        CgPlatform.provide(CgInput.HOST, InputLwjgl2.INSTANCE);
        CgPlatform.provide(CgClipboard.HOST, ClipboardLwjgl2.INSTANCE);
        CgPlatform.provide(CgHostResources.HOST, HostResourcesLegacy.INSTANCE);
        ClientLegacy.install();
        CgPlatform.provide(CgAudioOutput.SERVICE, new Lwjgl2AudioOutput());
        CgPlatform.provide(CgHostVolume.SERVICE, new HostVolumeLegacy());
        CgPlatform.provide(CgHostDuck.SERVICE, new HostDuckLegacy());
        CgPlatform.provide(CgI18n.HOST, new HostI18nLegacy());
        CgPlatform.provide(CgAudioDecoder.SERVICE, new JOrbisDecoder());
        CgPlatform.provide(CgWorldStimulus.SERVICE, new WorldStimulusLegacy());
        CgPlatform.provide(CgGameDirectory.HOST, new GameDirectoryService());
        CgPlatform.provide(CgGameThreads.HOST, new GameThreadsLegacy());
        CgPlatform.provide(CgScreens.HOST, ScreensLegacy.INSTANCE);
        CgPlatform.provide(CgContainers.HOST, ContainersLegacy.INSTANCE);
        CgPlatform.provide(CgGameSession.HOST, GameSessionLegacy.INSTANCE);
        CgPlatform.provide(CgHostPictures.HOST, new PicturesLegacy());
        CgPlatform.provide(CgItemIcons.HOST, new ItemIconsLegacy());
        CgPlatform.provide(CgTooltips.HOST, new TooltipsLegacy());
        CgPlatform.provide(CgFluids.HOST, new FluidsLegacy());
        CgPlatform.provide(CgEntityPictures.HOST, new EntityPicturesLegacy());
        CgPlatform.provide(CgBlockPictures.HOST, new BlockPicturesLegacy());
        CgPlatform.provide(CgPicturesProbe.Host.SERVICE, PicturesProbeLegacy.create());
        CgPlatform.provide(CgKeyBindings.HOST, KeyBindingsLegacy.INSTANCE);
        CgKeyBindings.hostProvided();
        CgEngineLifecycle.onClientTick(KeyBindingsLegacy.INSTANCE::tick);
        // WorldEventsLegacy polls the hurts, deaths and lightning; ExplosionHook and LevelEventHook the rest.
        CgWorldEvents.declare(CgWorldEvents.EXPLOSION | CgWorldEvents.BLOCK_BROKEN | CgWorldEvents.ENTITY_HURT
                | CgWorldEvents.ENTITY_DIED | CgWorldEvents.LIGHTNING);
        HostCameraLegacy camera = new HostCameraLegacy();
        CgPlatform.provide(CgCameraManager.HOST, camera);
        CgPlatform.provide(CgHostDebug.SERVICE, new HostDebugLegacy());
        MinecraftForge.EVENT_BUS.register(camera);
        FMLCommonHandler.instance().bus().register(new WorldEventsLegacy.Ticks());
        FMLCommonHandler.instance().bus().register(new HostCameraLegacy.Ticks());
        MinecraftForge.EVENT_BUS.register(new OverlayStagesLegacy());
        MinecraftForge.EVENT_BUS.register(new ActorsLegacy.Joins());
        // Forge's bus for the screen's input events, FML's for the render tick; on 1.12 they are one, which ignores the second.
        ScreenInputLegacy screenInput = new ScreenInputLegacy();
        MinecraftForge.EVENT_BUS.register(screenInput);
        FMLCommonHandler.instance().bus().register(screenInput);
        MinecraftForge.EVENT_BUS.register(new LoadingScreenLegacy());
        CgVanillaPoses.hosted();   // VanillaPoseHook
        // The graphics start's GL-free work, begun behind mod loading rather than at the first frame
        CgEngineLifecycle.clientStarting();
    }

    @Override
    public void startDevice() {
        CgEngineLifecycle.addListener(new GLDeviceLegacy());
    }

}
