package com.crystalgraphics.mc.v1710.platform.world;

import com.crystalgraphics.platform.service.CgWorldStimulus;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.effect.EntityLightningBolt;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;

import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * {@link CgWorldStimulus} through the single-player server on Minecraft 1.7.10. Its server takes no queued task, so the
 * work waits here and runs at the start of its next tick, on its own thread. Client only.
 *
 * <ul>
 *   <li>Lightning is added as a weather effect, not summoned: 1.7.10's {@code /summon} spawns a bolt the client is
 *       never sent.</li>
 * </ul>
 */
public final class WorldStimulus1710 implements CgWorldStimulus {

    private final Queue<Consumer<MinecraftServer>> work = new ConcurrentLinkedQueue<Consumer<MinecraftServer>>();
    private boolean listening;

    @Override
    public void keepRunning() {
        Minecraft.getMinecraft().gameSettings.pauseOnLostFocus = false;
    }

    @Override
    public boolean lightning(double x, double y, double z) {
        if (Minecraft.getMinecraft().thePlayer == null) return false;
        int dimension = Minecraft.getMinecraft().thePlayer.dimension;
        return run(server -> {
            WorldServer world = server.worldServerForDimension(dimension);
            world.addWeatherEffect(new EntityLightningBolt(world, x, y, z));
        });
    }

    @Override
    public boolean breakBlock(int x, int y, int z) {
        return run(command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:stone", x, y, z)),
                command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:air 0 destroy", x, y, z)));
    }

    @Override
    public boolean explode(double x, double y, double z) {
        return run(command(String.format(Locale.ROOT, "summon Pig %.2f %.2f %.2f", x, y, z)),
                command(String.format(Locale.ROOT, "summon PrimedTnt %.2f %.2f %.2f {Fuse:0}", x, y, z)));
    }

    private static Consumer<MinecraftServer> command(String command) {
        return server -> server.getCommandManager().executeCommand(server, command);
    }

    @SafeVarargs
    private final boolean run(Consumer<MinecraftServer>... queued) {
        if (Minecraft.getMinecraft().getIntegratedServer() == null) return false;
        if (!listening) {
            listening = true;
            FMLCommonHandler.instance().bus().register(this);
        }
        for (Consumer<MinecraftServer> step : queued) work.add(step);
        return true;
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) return;
        for (Consumer<MinecraftServer> step; (step = work.poll()) != null; ) step.accept(server);
    }
}
