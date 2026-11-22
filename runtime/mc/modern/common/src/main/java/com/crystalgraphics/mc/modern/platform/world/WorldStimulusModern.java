package com.crystalgraphics.mc.modern.platform.world;

import com.crystalgraphics.mc.modern.platform.CgMinecraftClient;
import com.crystalgraphics.platform.world.CgWorldStimulus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
//? if >=1.14 {
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSourceStack;
//?}
//? if >=1.14.3 {
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
//?}

import java.util.Locale;

/**
 * {@link CgWorldStimulus} through the single-player server's console, 1.14 to 26.3: each request queued onto the
 * server's thread as commands. Client only. 1.13.2's server takes no queued task, so it answers false.
 */
public final class WorldStimulusModern implements CgWorldStimulus {

    @Override
    public void keepRunning() {
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        // A focus lost before this already paused it
        //? if >=1.14.3 {
        if (CgMinecraftClient.screen(mc) instanceof PauseScreen) CgMinecraftClient.setScreen(mc, null);
        //?}
    }

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

    @Override
    public boolean turnLook(float degrees) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return false;
        // As the mouse turns it: Entity.turn scales what it is given by 0.15.
        player.turn(degrees / 0.15, 0.0);
        return true;
    }

    @Override
    public boolean pressJump() {
        //? if >=1.15.2 {
        Minecraft.getInstance().options.keyJump.setDown(true);
        return true;
        //?} else {
        /*return false;
        *///?}
    }

    @Override
    public boolean jumpDown() {
        //? if >=1.14.3 {
        return Minecraft.getInstance().options.keyJump.isDown();
        //?} else {
        /*return false;
        *///?}
    }

    @Override
    public boolean kill() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && run("kill " + player.getScoreboardName());
    }

    @Override
    public boolean teleport(double x, double y, double z) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return false;
        String name = player.getScoreboardName();
        return run("gamemode creative " + name, String.format(Locale.ROOT, "tp %s %.2f %.2f %.2f", name, x, y, z));
    }

    // The respawn packet closes the death screen.
    @Override
    public boolean respawn() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || player.isAlive()) return false;
        player.respawn();
        return true;
    }

    @Override
    public boolean stand(double x, double y, double z) {
        return run(String.format(Locale.ROOT, "summon minecraft:armor_stand %.2f %.2f %.2f {NoGravity:1b}", x, y, z));
    }

    @Override
    public boolean dragon(double x, double y, double z) {
        return run(String.format(Locale.ROOT, "summon minecraft:ender_dragon %.2f %.2f %.2f", x, y, z));
    }

    // Every spelling each version has used: a hanging entity's block as TileX/Y/Z, then block_pos (1.21.5);
    // Facing, then facing; Motive, then variant. North is 2 in both the 2D and the 3D direction values.
    @Override
    public boolean painting(int x, int y, int z) {
        return run(String.format(Locale.ROOT, "setblock %d %d %d minecraft:stone", x, y, z + 1),
                String.format(Locale.ROOT, "summon minecraft:painting %d %d %d {TileX:%d,TileY:%d,TileZ:%d,"
                        + "block_pos:[I;%d,%d,%d],Facing:2b,facing:2b,Motive:\"minecraft:kebab\",variant:\"minecraft:kebab\"}",
                        x, y, z, x, y, z, x, y, z));
    }

    // The slot command was `replaceitem` until 1.17's `item`.
    @Override
    public boolean container(int x, int y, int z, String block) {
        //? if >=1.17 {
        String fill = "item replace block %d %d %d container.0 with minecraft:cobblestone 8";
        //?} else {
        /*String fill = "replaceitem block %d %d %d container.0 minecraft:cobblestone 8";
        *///?}
        if ("anvil".equals(block)) {
            // an anvil falls through air: a floor first
            return run(String.format(Locale.ROOT, "setblock %d %d %d minecraft:stone", x, y - 1, z),
                    String.format(Locale.ROOT, "setblock %d %d %d minecraft:anvil", x, y, z));
        }
        // a chest under a solid block will not open
        return run(String.format(Locale.ROOT, "setblock %d %d %d minecraft:air", x, y + 1, z),
                String.format(Locale.ROOT, "setblock %d %d %d minecraft:%s", x, y, z, block),
                String.format(Locale.ROOT, fill, x, y, z));
    }

    // The item's count key was Count (a byte) until 1.20.5's count: both are given, and an unknown key is ignored.
    @Override
    public boolean merchant(int x, int y, int z) {
        // On uneven ground a villager made inside terrain suffocates: a floor, and air for it to stand in.
        return run(String.format(Locale.ROOT, "setblock %d %d %d minecraft:stone", x, y - 1, z),
                String.format(Locale.ROOT, "setblock %d %d %d minecraft:air", x, y, z),
                String.format(Locale.ROOT, "setblock %d %d %d minecraft:air", x, y + 1, z),
                String.format(Locale.ROOT, "summon minecraft:villager %d %d %d {NoAI:1b,Offers:{Recipes:[{"
                + "buy:{id:\"minecraft:emerald\",Count:1b,count:1},sell:{id:\"minecraft:cobblestone\",Count:1b,count:1},"
                + "maxUses:9999,uses:0}]}}", x, y, z));
    }

    @Override
    public boolean useEntity(int id) {
        //? if >=1.14.3 {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        Entity target = mc.level == null ? null : mc.level.getEntity(id);
        if (player == null || mc.gameMode == null || target == null) return false;
        //? if >=26.1 {
        /*mc.gameMode.interact(player, target, new EntityHitResult(target), InteractionHand.MAIN_HAND);
        *///?} else {
        mc.gameMode.interact(player, target, InteractionHand.MAIN_HAND);
        //?}
        return true;
        //?} else {
        /*return false;
        *///?}
    }

    // useItemOn lost its level argument in 1.19; 1.14.3-1.14.4 spell the level MultiPlayerLevel.
    @Override
    public boolean use(int x, int y, int z) {
        //? if >=1.14.3 {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.gameMode == null) return false;
        BlockHitResult hit = new BlockHitResult(new Vec3(x + 0.5, y + 1.0, z + 0.5), Direction.UP, new BlockPos(x, y, z), false);
        //? if >=1.19 {
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        //?} else {
        /*mc.gameMode.useItemOn(player, mc.level, InteractionHand.MAIN_HAND, hit);
        *///?}
        return true;
        //?} else {
        /*return false;
        *///?}
    }

    @Override
    public boolean clear(double x, double y, double z, double radius) {
        return run(String.format(Locale.ROOT, "execute positioned %.2f %.2f %.2f run kill @e[type=!minecraft:player,distance=..%.1f]",
                x, y, z, radius));
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
