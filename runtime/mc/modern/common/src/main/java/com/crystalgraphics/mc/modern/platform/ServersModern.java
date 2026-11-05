package com.crystalgraphics.mc.modern.platform;

import java.nio.file.Path;
import java.nio.file.Paths;

import com.crystalgraphics.platform.service.CgServers;

import net.minecraft.server.MinecraftServer;
//? if >=1.16 {
import net.minecraft.world.level.storage.LevelResource;
//?}

/**
 * {@link CgServers.Host} over the running {@link MinecraftServer}, 1.13.2 to 26.3. Server-safe; provided by
 * {@code PlatformServiceModern.install}, given the server by {@code ServerModern} at its start.
 */
public final class ServersModern implements CgServers.Host {

    public static final ServersModern INSTANCE = new ServersModern();

    private volatile MinecraftServer server;

    private ServersModern() {
    }

    /** The running server, at its start; null once it stopped. */
    public void server(MinecraftServer server) {
        this.server = server;
    }

    /** The directory a world is saved in. */
    public static Path worldRoot(MinecraftServer server) {
        // LevelResource is 1.16's; before it the save is a folder of the storage source's base.
        //? if >=1.16 {
        return server.getWorldPath(LevelResource.ROOT);
        //?} elif >=1.14 {
        /*return server.getStorageSource().getBaseDir().resolve(server.getLevelIdName());
        *///?} else {
        /*return server.getStorageSource().getLevelPath(server.getLevelIdName());
        *///?}
    }

    @Override
    public boolean running() {
        return server != null;
    }

    @Override
    public boolean dedicated() {
        MinecraftServer s = server;
        return s != null && s.isDedicatedServer();
    }

    @Override
    public Path directory() {
        MinecraftServer s = server;
        if (s == null) return null;
        // getServerDirectory() is a File before 1.21 and a Path from it; toString is the one spelling both have.
        return s.isDedicatedServer() ? Paths.get(s.getServerDirectory().toString()) : worldRoot(s);
    }

    @Override
    public String version() {
        MinecraftServer s = server;
        return s == null ? "?" : s.getServerVersion();
    }

    @Override
    public void halt() {
        MinecraftServer s = server;
        if (s == null) return;
        // 1.14 added halt's wait-for-the-thread flag.
        //? if >=1.14 {
        s.halt(false);
        //?} else {
        /*s.halt();
        *///?}
    }
}
