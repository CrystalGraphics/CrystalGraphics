package com.crystalgraphics.mc.legacy.platform;

import java.io.File;
import java.nio.file.Path;

import com.crystalgraphics.platform.service.CgServers;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.fml.common.FMLCommonHandler;

/**
 * {@link CgServers.Host} over Forge 1.8-1.12.2's running server; provided by {@code PlatformServiceLegacy.install}.
 * Forge holds the server, so nothing hands it over.
 */
public final class ServersLegacy implements CgServers.Host {

    public static final ServersLegacy INSTANCE = new ServersLegacy();

    private ServersLegacy() {
    }

    static MinecraftServer server() {
        return FMLCommonHandler.instance().getMinecraftServerInstance();
    }

    @Override
    public boolean running() {
        return server() != null;
    }

    @Override
    public boolean dedicated() {
        MinecraftServer s = server();
        return s != null && s.isDedicatedServer();
    }

    @Override
    public Path directory() {
        MinecraftServer s = server();
        if (s == null) return null;
        File base;
        if (s.isDedicatedServer()) {
            base = s.getFile("");
        } else {
            // The overworld's save is the world's own directory; null between "a server exists" and "it has loaded".
            WorldServer overworld = DimensionManager.getWorld(0);
            base = overworld == null ? null : overworld.getSaveHandler().getWorldDirectory();
        }
        return base == null ? null : base.toPath();
    }

    @Override
    public String version() {
        MinecraftServer s = server();
        return s == null ? "?" : s.getMinecraftVersion();
    }

    @Override
    public void halt() {
        MinecraftServer s = server();
        if (s != null) s.initiateShutdown();
    }
}
