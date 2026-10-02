package com.crystalgraphics.mc.modern.platform.world;

import com.crystalgraphics.platform.service.CgWorldStimulus;
import net.minecraft.client.Minecraft;
//? if >=1.14 {
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSourceStack;
//?}

import java.util.Locale;

/**
 * {@link CgWorldStimulus} through the single-player server's console, 1.14 to 26.3: each request queued onto the
 * server's thread as commands. Client only. 1.13.2's server takes no queued task, so it answers false.
 */
public final class WorldStimulusModern implements CgWorldStimulus {

    @Override
    public boolean lightning(double x, double y, double z) {
        return run(String.format(Locale.ROOT, "summon minecraft:lightning_bolt %.2f %.2f %.2f", x, y, z));
    }

    @Override
    public boolean breakBlock(int x, int y, int z) {
        return run(String.format(Locale.ROOT, "setblock %d %d %d minecraft:stone", x, y, z),
                String.format(Locale.ROOT, "setblock %d %d %d minecraft:air destroy", x, y, z));
    }

    // TNT's fuse tag was "Fuse" and is "fuse" from 1.20.3: both are given, and an unknown key is ignored.
    @Override
    public boolean explode(double x, double y, double z) {
        return run(String.format(Locale.ROOT, "summon minecraft:pig %.2f %.2f %.2f", x, y, z),
                String.format(Locale.ROOT, "summon minecraft:tnt %.2f %.2f %.2f {Fuse:0,fuse:0}", x, y, z));
    }

    private static boolean run(String... commands) {
        //? if >=1.14 {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return false;
        server.execute(() -> perform(server, commands));
        return true;
        //?} else {
        /*return false;
        *///?}
    }

    //? if >=1.14 {
    private static void perform(IntegratedServer server, String[] commands) {
        CommandSourceStack source = server.createCommandSourceStack();
        for (String command : commands) perform(server, source, command);
    }
    //?}

    //? if >=1.19 {
    private static void perform(IntegratedServer server, CommandSourceStack source, String command) {
        server.getCommands().performPrefixedCommand(source, command);
    }
    //?} elif >=1.14 {
    /*private static void perform(IntegratedServer server, CommandSourceStack source, String command) {
        server.getCommands().performCommand(source, command);
    }
    *///?}
}
