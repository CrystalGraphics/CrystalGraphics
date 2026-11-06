package com.crystalgraphics.mc.modern.platform;

import com.crystalgraphics.platform.service.CgGameThreads;

import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

/**
 * {@link CgGameThreads.Host} for the modern client: Minecraft's own task queues, which 1.14.3 made public. Provided by
 * {@code PlatformServiceModern.installClient}; before 1.14.3 neither thread is reachable.
 */
public final class GameThreadsModern implements CgGameThreads.Host {

    //? if >=1.14.3 {
    private static final CgGameThreads.Binding CLIENT = new CgGameThreads.Binding() {
        @Override
        public void execute(Runnable work) {
            Minecraft.getInstance().execute(work);
        }

        @Override
        public boolean isCurrent() {
            Minecraft mc = Minecraft.getInstance();
            return mc != null && mc.isSameThread();
        }

        @Override
        public boolean isAvailable() {
            return Minecraft.getInstance() != null;
        }
    };

    private static final CgGameThreads.Binding SERVER = new CgGameThreads.Binding() {
        @Override
        public void execute(Runnable work) {
            MinecraftServer server = ServersModern.INSTANCE.minecraftServer();
            if (server != null) server.execute(work);
        }

        @Override
        public boolean isCurrent() {
            MinecraftServer server = ServersModern.INSTANCE.minecraftServer();
            return server != null && server.isSameThread();
        }

        @Override
        public boolean isAvailable() {
            return ServersModern.INSTANCE.minecraftServer() != null;
        }
    };
    //?}

    @Override
    public CgGameThreads.Binding client() {
        //? if >=1.14.3 {
        return CLIENT;
        //?} else {
        /*return null;
        *///?}
    }

    @Override
    public CgGameThreads.Binding server() {
        //? if >=1.14.3 {
        return SERVER;
        //?} else {
        /*return null;
        *///?}
    }
}
