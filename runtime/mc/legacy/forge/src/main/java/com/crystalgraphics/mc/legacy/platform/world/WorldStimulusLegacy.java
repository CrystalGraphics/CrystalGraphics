package com.crystalgraphics.mc.legacy.platform.world;

import com.crystalgraphics.platform.service.CgWorldStimulus;
import net.minecraft.client.Minecraft;
import net.minecraft.server.integrated.IntegratedServer;

import java.util.Locale;

/**
 * {@link CgWorldStimulus} through the single-player server's console on Forge 1.8.9 to 1.12.2, queued onto the
 * server's thread. Entity ids were renamed in 1.11 ({@code LightningBolt} to {@code lightning_bolt}). Client only.
 */
public final class WorldStimulusLegacy implements CgWorldStimulus {

    //? if >=1.11 {
    private static final String LIGHTNING = "lightning_bolt", TNT = "tnt", PIG = "pig";
    //?} else {
    /*private static final String LIGHTNING = "LightningBolt", TNT = "PrimedTnt", PIG = "Pig";
    *///?}

    @Override
    public boolean lightning(double x, double y, double z) {
        return run(String.format(Locale.ROOT, "summon %s %.2f %.2f %.2f", LIGHTNING, x, y, z));
    }

    @Override
    public boolean breakBlock(int x, int y, int z) {
        return run(String.format(Locale.ROOT, "setblock %d %d %d stone", x, y, z),
                String.format(Locale.ROOT, "setblock %d %d %d air 0 destroy", x, y, z));
    }

    @Override
    public boolean explode(double x, double y, double z) {
        return run(String.format(Locale.ROOT, "summon %s %.2f %.2f %.2f", PIG, x, y, z),
                String.format(Locale.ROOT, "summon %s %.2f %.2f %.2f {Fuse:0}", TNT, x, y, z));
    }

    private static boolean run(String... commands) {
        IntegratedServer server = Minecraft.getMinecraft().getIntegratedServer();
        if (server == null) return false;
        server.addScheduledTask(() -> {
            for (String command : commands) server.getCommandManager().executeCommand(server, command);
        });
        return true;
    }
}
