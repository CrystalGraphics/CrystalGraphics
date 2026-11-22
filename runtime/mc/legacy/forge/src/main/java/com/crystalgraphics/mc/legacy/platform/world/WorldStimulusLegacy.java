package com.crystalgraphics.mc.legacy.platform.world;

import com.crystalgraphics.platform.world.CgWorldStimulus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.EnumFacing;
//? if >=1.10 {
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
//?} else {
/*import net.minecraft.util.BlockPos;
import net.minecraft.util.Vec3;
*///?}

import java.util.Locale;

/**
 * {@link CgWorldStimulus} through the single-player server's console on Forge 1.8.9 to 1.12.2, queued onto the
 * server's thread. Entity ids were renamed in 1.11 ({@code LightningBolt} to {@code lightning_bolt}). Client only.
 */
public final class WorldStimulusLegacy implements CgWorldStimulus {

    //? if >=1.11 {
    private static final String LIGHTNING = "lightning_bolt", TNT = "tnt", PIG = "pig", STAND = "armor_stand",
            DRAGON = "ender_dragon", PAINTING = "painting", PLAYER = "player";
    //?} else {
    /*private static final String LIGHTNING = "LightningBolt", TNT = "PrimedTnt", PIG = "Pig", STAND = "ArmorStand",
            DRAGON = "EnderDragon", PAINTING = "Painting", PLAYER = "Player";
    *///?}

    @Override
    public void keepRunning() {
        Minecraft mc = Minecraft.getMinecraft();
        mc.gameSettings.pauseOnLostFocus = false;
        // A focus lost before this already paused it
        if (mc.currentScreen instanceof GuiIngameMenu) mc.displayGuiScreen(null);
        // A death's screen tints everything: respawned, as its Respawn button does
        EntityPlayerSP p = player();
        if (p != null && p.getHealth() <= 0f) {
            p.respawnPlayer();
            mc.displayGuiScreen(null);
        }
    }

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

    @Override
    public boolean kill() {
        EntityPlayerSP player = player();
        return player != null && run("kill " + player.getName());
    }

    @Override
    public boolean teleport(double x, double y, double z) {
        EntityPlayerSP player = player();
        if (player == null) return false;
        String name = player.getName();
        return run("gamemode 1 " + name, String.format(Locale.ROOT, "tp %s %.2f %.2f %.2f", name, x, y, z));
    }

    // As the death screen's button does: nothing closes the screen for it.
    @Override
    public boolean respawn() {
        EntityPlayerSP player = player();
        if (player == null || player.isEntityAlive()) return false;
        player.respawnPlayer();
        Minecraft.getMinecraft().displayGuiScreen(null);
        return true;
    }

    @Override
    public boolean stand(double x, double y, double z) {
        return run(String.format(Locale.ROOT, "summon %s %.2f %.2f %.2f {NoGravity:1b}", STAND, x, y, z));
    }

    // Sitting and scanning (6): the hover a dragon starts in targets its own position, which 1.9-1.12.2's flight
    // divides by its distance to, so outside the End it turns NaN at once. 1.8.9 has no phases and ignores the tag.
    @Override
    public boolean dragon(double x, double y, double z) {
        return run(String.format(Locale.ROOT, "summon %s %.2f %.2f %.2f {DragonPhase:6}", DRAGON, x, y, z));
    }

    // A hanging entity takes its block from TileX/Y/Z, not from where it is summoned. Facing 2 is north.
    @Override
    public boolean painting(int x, int y, int z) {
        return run(String.format(Locale.ROOT, "setblock %d %d %d stone", x, y, z + 1),
                String.format(Locale.ROOT, "summon %s %d %d %d {TileX:%d,TileY:%d,TileZ:%d,Facing:2b,Motive:\"Kebab\"}",
                        PAINTING, x, y, z, x, y, z));
    }

    // Selector coordinates and radius are whole numbers before 1.13.
    @Override
    public boolean clear(double x, double y, double z, double radius) {
        return run(String.format(Locale.ROOT, "kill @e[type=!%s,x=%d,y=%d,z=%d,r=%d]", PLAYER,
                (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z), (int) Math.ceil(radius)));
    }

    @Override
    public boolean turnLook(float yaw, float pitch) {
        EntityPlayerSP player = player();
        if (player == null) return false;
        // As the mouse turns it: the entity scales what it is given by 0.15.
        //? if >=1.10 {
        player.turn(yaw / 0.15f, -pitch / 0.15f);
        //?} else {
        /*player.setAngles(yaw / 0.15f, -pitch / 0.15f);
        *///?}
        return true;
    }

    @Override
    public boolean pressJump() {
        KeyBinding.setKeyBindState(Minecraft.getMinecraft().gameSettings.keyBindJump.getKeyCode(), true);
        return true;
    }

    @Override
    public boolean jumpDown() {
        return Minecraft.getMinecraft().gameSettings.keyBindJump.isKeyDown();
    }

    @Override
    public boolean container(int x, int y, int z, String block) {
        if ("anvil".equals(block)) return run(String.format(Locale.ROOT, "setblock %d %d %d minecraft:anvil", x, y, z));
        // a chest under a solid block will not open
        return run(String.format(Locale.ROOT, "setblock %d %d %d minecraft:air", x, y + 1, z),
                String.format(Locale.ROOT, "setblock %d %d %d minecraft:%s", x, y, z, block),
                String.format(Locale.ROOT, "replaceitem block %d %d %d slot.container.0 minecraft:cobblestone 8", x, y, z));
    }

    // processRightClickBlock: 1.8.9 onPlayerRightClick, 1.10 adds the hand, 1.12 drops the stack.
    @Override
    public boolean use(int x, int y, int z) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = player();
        if (player == null || mc.playerController == null) return false;
        BlockPos pos = new BlockPos(x, y, z);
        //? if >=1.12 {
        mc.playerController.processRightClickBlock(player, mc.world, pos, EnumFacing.UP, new Vec3d(x + 0.5, y + 1.0, z + 0.5), EnumHand.MAIN_HAND);
        //?} elif >=1.10 {
        /*mc.playerController.processRightClickBlock(player, mc.world, player.getHeldItemMainhand(), pos, EnumFacing.UP,
                new Vec3d(x + 0.5, y + 1.0, z + 0.5), EnumHand.MAIN_HAND);
        *///?} else {
        /*mc.playerController.onPlayerRightClick(player, mc.theWorld, player.getHeldItem(), pos, EnumFacing.UP,
                new Vec3(x + 0.5, y + 1.0, z + 0.5));
        *///?}
        return true;
    }

    private static EntityPlayerSP player() {
        //? if >=1.10 {
        return Minecraft.getMinecraft().player;
        //?} else {
        /*return Minecraft.getMinecraft().thePlayer;
        *///?}
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
