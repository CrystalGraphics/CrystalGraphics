package com.crystalgraphics.mc.modern.platform;

import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.mc.modern.platform.world.PlayerQueryModern;


import net.minecraft.server.MinecraftServer;

/**
 * Minecraft's server lifecycle turned into the engine's, for all three modern loaders. Both sides, and server-safe: it
 * names no client class. Each loader forwards its own server events here.
 *
 * <pre>{@code
 * // Forge, NeoForge: ServerAboutToStartEvent, ServerStoppingEvent, the server tick's end
 * // Fabric: ServerLifecycleEvents.SERVER_STARTING, SERVER_STOPPING, ServerTickEvents.END_SERVER_TICK
 * ServerModern.serverStarting(server);
 * ServerModern.serverTick();
 * ServerModern.serverStopping();
 * }</pre>
 */
public final class ServerModern {

    private ServerModern() {
    }

    /** The server is starting, its worlds loaded: the player query reads it from now on. */
    public static void serverStarting(MinecraftServer server) {
        PlayerQueryModern.INSTANCE.server(server);
        ServersModern.INSTANCE.server(server);
        CgEngineLifecycle.serverStarting(server);
    }

    /** The server's tick has ended. */
    public static void serverTick() {
        CgEngineLifecycle.serverTick();
    }

    /** The server is stopping. */
    public static void serverStopping() {
        CgEngineLifecycle.serverStopping();
        PlayerQueryModern.INSTANCE.server(null);
        ServersModern.INSTANCE.server(null);
    }
}
