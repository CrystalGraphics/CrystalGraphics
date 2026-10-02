package com.crystalgraphics.mc.v1710.platform.world;

import com.crystalgraphics.platform.service.CgWorldStimulus;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;

import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * {@link CgWorldStimulus} through the single-player server's console on Minecraft 1.7.10. Its server takes no queued
 * task, so commands wait here and run at the start of its next tick, on its own thread. Client only.
 */
public final class WorldStimulus1710 implements CgWorldStimulus {

    private final Queue<String> commands = new ConcurrentLinkedQueue<String>();
    private boolean listening;

    @Override
    public boolean lightning(double x, double y, double z) {
        return run(String.format(Locale.ROOT, "summon LightningBolt %.2f %.2f %.2f", x, y, z));
    }

    @Override
    public boolean breakBlock(int x, int y, int z) {
        return run(String.format(Locale.ROOT, "setblock %d %d %d minecraft:stone", x, y, z),
                String.format(Locale.ROOT, "setblock %d %d %d minecraft:air 0 destroy", x, y, z));
    }

    @Override
    public boolean explode(double x, double y, double z) {
        return run(String.format(Locale.ROOT, "summon Pig %.2f %.2f %.2f", x, y, z),
                String.format(Locale.ROOT, "summon PrimedTnt %.2f %.2f %.2f {Fuse:0}", x, y, z));
    }

    private boolean run(String... queued) {
        if (Minecraft.getMinecraft().getIntegratedServer() == null) return false;
        if (!listening) {
            listening = true;
            FMLCommonHandler.instance().bus().register(this);
        }
        for (String command : queued) commands.add(command);
        return true;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) return;
        for (String command; (command = commands.poll()) != null; ) server.getCommandManager().executeCommand(server, command);
    }
}
