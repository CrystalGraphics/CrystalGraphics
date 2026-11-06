package com.crystalgraphics.mc.v1710.platform;

import com.crystalgraphics.job.CgHostThread;
import com.crystalgraphics.platform.service.CgGameThreads;

import net.minecraft.server.MinecraftServer;

/**
 * {@link CgGameThreads.Host} for 1.7.10. The server has no task queue of its own, so work for it waits in a
 * {@link CgHostThread.Queue} that {@link Ticks1710} drains once a server tick. The client thread is the one frames are
 * drawn on. Provided by {@code PlatformService1710.install} on a client.
 */
public final class GameThreads1710 implements CgGameThreads.Host {

    /** The queue the server tick drains. */
    static final CgHostThread.Queue SERVER = new CgHostThread.Queue(() -> MinecraftServer.getServer() != null);

    @Override
    public CgGameThreads.Binding client() {
        return null;
    }

    @Override
    public CgGameThreads.Binding server() {
        return SERVER;
    }
}
