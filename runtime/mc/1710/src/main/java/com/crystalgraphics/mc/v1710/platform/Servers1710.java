package com.crystalgraphics.mc.v1710.platform;

import java.io.File;
import java.nio.file.Path;

import com.crystalgraphics.platform.service.CgServers;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

/** {@link CgServers.Host} over 1.7.10's running server; provided by {@code PlatformService1710.install}. */
public final class Servers1710 implements CgServers.Host {

    public static final Servers1710 INSTANCE = new Servers1710();

    private Servers1710() {
    }

    @Override
    public boolean running() {
        return MinecraftServer.getServer() != null;
    }

    @Override
    public boolean dedicated() {
        MinecraftServer s = MinecraftServer.getServer();
        return s != null && s.isDedicatedServer();
    }

    @Override
    public Path directory() {
        MinecraftServer s = MinecraftServer.getServer();
        if (s == null) return null;
        File base;
        if (s.isDedicatedServer()) {
            base = s.getFile("");
        } else {
            // The overworld's save is the world's own directory; null between "a server exists" and "it has loaded".
            WorldServer[] worlds = s.worldServers;
            if (worlds == null || worlds.length == 0 || worlds[0] == null) return null;
            base = worlds[0].getSaveHandler().getWorldDirectory();
        }
        return base == null ? null : base.toPath();
    }

    @Override
    public String version() {
        MinecraftServer s = MinecraftServer.getServer();
        return s == null ? "?" : s.getMinecraftVersion();
    }

    @Override
    public void halt() {
        MinecraftServer s = MinecraftServer.getServer();
        if (s != null) s.initiateShutdown();
    }
}
