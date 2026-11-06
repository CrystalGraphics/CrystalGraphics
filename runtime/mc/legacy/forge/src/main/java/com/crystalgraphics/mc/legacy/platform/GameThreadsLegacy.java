package com.crystalgraphics.mc.legacy.platform;

import com.crystalgraphics.platform.service.CgGameThreads;

import net.minecraft.server.MinecraftServer;

/**
 * {@link CgGameThreads.Host} for Forge 1.8-1.12.2: the server's own scheduled-task queue ({@code IThreadListener}).
 * The client thread is the one frames are drawn on. Provided by {@code PlatformServiceLegacy.install} on a client.
 */
public final class GameThreadsLegacy implements CgGameThreads.Host {

    private static final CgGameThreads.Binding SERVER = new CgGameThreads.Binding() {
        @Override
        public void execute(Runnable work) {
            MinecraftServer server = ServersLegacy.server();
            if (server != null) server.addScheduledTask(work);
        }

        @Override
        public boolean isCurrent() {
            MinecraftServer server = ServersLegacy.server();
            return server != null && server.isCallingFromMinecraftThread();
        }

        @Override
        public boolean isAvailable() {
            return ServersLegacy.server() != null;
        }
    };

    @Override
    public CgGameThreads.Binding client() {
        return null;
    }

    @Override
    public CgGameThreads.Binding server() {
        return SERVER;
    }
}
