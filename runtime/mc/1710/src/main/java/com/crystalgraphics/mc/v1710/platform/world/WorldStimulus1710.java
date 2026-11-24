package com.crystalgraphics.mc.v1710.platform.world;

import com.crystalgraphics.platform.world.CgWorldStimulus;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.village.MerchantRecipe;
import net.minecraft.village.MerchantRecipeList;
import net.minecraft.util.Vec3;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.EntityLightningBolt;
import net.minecraft.entity.item.EntityPainting;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.DamageSource;
import net.minecraft.world.WorldServer;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

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
    private static final Logger LOGGER = LogManager.getLogger("CrystalGraphics");


    private final Queue<Consumer<MinecraftServer>> work = new ConcurrentLinkedQueue<Consumer<MinecraftServer>>();
    private boolean listening;

    @Override
    public void keepRunning() {
        Minecraft mc = Minecraft.getMinecraft();
        mc.gameSettings.pauseOnLostFocus = false;
        // A focus lost before this already paused it
        if (mc.currentScreen instanceof GuiIngameMenu) mc.displayGuiScreen(null);
        // A death's screen tints everything: respawned, as its Respawn button does
        if (mc.thePlayer != null && mc.thePlayer.getHealth() <= 0f) {
            mc.thePlayer.respawnPlayer();
            mc.displayGuiScreen(null);
        }
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

    @Override
    public boolean turnLook(float yaw, float pitch) {
        EntityClientPlayerMP player = Minecraft.getMinecraft().thePlayer;
        if (player == null) return false;
        // As the mouse turns it: setAngles scales what it is given by 0.15.
        player.setAngles(yaw / 0.15f, -pitch / 0.15f);
        return true;
    }

    @Override
    public boolean pressJump() {
        KeyBinding.setKeyBindState(Minecraft.getMinecraft().gameSettings.keyBindJump.getKeyCode(), true);
        return true;
    }

    @Override
    public boolean jumpDown() {
        return Minecraft.getMinecraft().gameSettings.keyBindJump.getIsKeyPressed();
    }

    // 1.7.10's /kill takes no target: this is what it does to its sender.
    @Override
    public boolean kill() {
        EntityClientPlayerMP player = Minecraft.getMinecraft().thePlayer;
        if (player == null) return false;
        String name = player.getCommandSenderName();
        return run(server -> {
            EntityPlayerMP body = server.getConfigurationManager().func_152612_a(name);
            if (body != null) body.attackEntityFrom(DamageSource.outOfWorld, Float.MAX_VALUE);
        });
    }

    @Override
    public boolean teleport(double x, double y, double z) {
        EntityClientPlayerMP player = Minecraft.getMinecraft().thePlayer;
        if (player == null) return false;
        String name = player.getCommandSenderName();
        return run(command("gamemode 1 " + name), command(String.format(Locale.ROOT, "tp %s %.2f %.2f %.2f", name, x, y, z)));
    }

    @Override
    public boolean time(long ticks) {
        return run(command("time set " + ticks));
    }

    @Override
    public boolean survival() {
        EntityClientPlayerMP player = Minecraft.getMinecraft().thePlayer;
        return player != null && run(command("gamemode 0 " + player.getCommandSenderName()));
    }

    // 1.7.10 has no replaceitem: the server's player filled directly, then its menu synced
    @Override
    public boolean inventory(String... stacks) {
        EntityClientPlayerMP me = Minecraft.getMinecraft().thePlayer;
        if (me == null) return false;
        String name = me.getCommandSenderName();
        return run(server -> {
            EntityPlayerMP player = server.getConfigurationManager().func_152612_a(name);
            if (player == null) return;
            player.inventory.clearInventory(null, -1);
            for (int i = 0; i < stacks.length; i++) {
                if (stacks[i].isEmpty()) continue;
                String[] parts = stacks[i].trim().split("\\s+");
                Item item = (Item) Item.itemRegistry.getObject(parts[0]);
                if (item == null) continue;
                player.inventory.setInventorySlotContents(i, new ItemStack(item, parts.length > 1 ? Integer.parseInt(parts[1]) : 1));
            }
            player.inventoryContainer.detectAndSendChanges();
        });
    }

    // As the death screen's button does: nothing closes the screen for it.
    @Override
    public boolean respawn() {
        EntityClientPlayerMP player = Minecraft.getMinecraft().thePlayer;
        if (player == null || player.isEntityAlive()) return false;
        player.respawnPlayer();
        Minecraft.getMinecraft().displayGuiScreen(null);
        return true;
    }

    // 1.7.10 has no armor stand: a pig wanders a little, which a probe's hits tolerate.
    @Override
    public boolean stand(double x, double y, double z) {
        return run(command(String.format(Locale.ROOT, "summon Pig %.2f %.2f %.2f", x, y, z)));
    }

    @Override
    public boolean dragon(double x, double y, double z) {
        return run(command(String.format(Locale.ROOT, "summon EnderDragon %.2f %.2f %.2f", x, y, z)));
    }

    // Built as an item places one, not summoned: 1.7.10's /summon sets the position after loading, which gives a
    // hanging entity a plain 0.5 box. Its block is the one it hangs on; direction 2 is that block's north face.
    @Override
    public boolean painting(int x, int y, int z) {
        if (Minecraft.getMinecraft().thePlayer == null) return false;
        int dimension = Minecraft.getMinecraft().thePlayer.dimension;
        return run(command(String.format(Locale.ROOT, "setblock %d %d %d minecraft:stone", x, y, z + 1)), server -> {
            WorldServer world = server.worldServerForDimension(dimension);
            world.spawnEntityInWorld(new EntityPainting(world, x, y, z + 1, 2, "Kebab"));
        });
    }

    // No replaceitem or blockdata on 1.7.10: the tile entity is filled by hand.
    @Override
    public boolean container(int x, int y, int z, String block) {
        if (Minecraft.getMinecraft().thePlayer == null) return false;
        int dimension = Minecraft.getMinecraft().thePlayer.dimension;
        return run(server -> {
            WorldServer world = server.worldServerForDimension(dimension);
            world.setBlockToAir(x, y + 1, z);   // a chest under a solid block will not open
            if ("anvil".equals(block)) world.setBlock(x, y - 1, z, Blocks.stone);   // an anvil falls through air
            world.setBlock(x, y, z, "chest".equals(block) ? Blocks.chest : "anvil".equals(block) ? Blocks.anvil : Blocks.furnace);
            if ("anvil".equals(block)) return;
            TileEntity tile = world.getTileEntity(x, y, z);
            if (tile instanceof IInventory) ((IInventory) tile).setInventorySlotContents(0, new ItemStack(Blocks.cobblestone, 8));
        });
    }

    // A villager with a trade, its profession set so it is not a zombie's curiosity: 1.7.10 has no NoAI command tag.
    @Override
    public boolean merchant(int x, int y, int z) {
        if (Minecraft.getMinecraft().thePlayer == null) return false;
        int dimension = Minecraft.getMinecraft().thePlayer.dimension;
        return run(server -> {
            WorldServer world = server.worldServerForDimension(dimension);
            // On uneven ground a villager made inside terrain suffocates: a floor, and air for it to stand in.
            world.setBlock(x, y - 1, z, Blocks.stone);
            world.setBlockToAir(x, y, z);
            world.setBlockToAir(x, y + 1, z);
            EntityVillager villager = new EntityVillager(world, 0);
            villager.setPosition(x + 0.5, y, z + 0.5);
            MerchantRecipeList offers = villager.getRecipes(null);
            offers.clear();
            offers.add(new MerchantRecipe(new ItemStack(Items.emerald), new ItemStack(Blocks.cobblestone)));
            world.spawnEntityInWorld(villager);
        });
    }

    @Override
    public boolean useEntity(int id) {
        Minecraft mc = Minecraft.getMinecraft();
        Entity target = mc.theWorld == null ? null : mc.theWorld.getEntityByID(id);
        if (mc.thePlayer == null || mc.playerController == null || target == null) return false;
        mc.playerController.interactWithEntitySendPacket(mc.thePlayer, target);
        return true;
    }

    // Side 1 is the top face.
    @Override
    public boolean use(int x, int y, int z) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.playerController == null) return false;
        mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, mc.thePlayer.getHeldItem(), x, y, z, 1,
                Vec3.createVectorHelper(x + 0.5, y + 1.0, z + 0.5));
        return true;
    }

    // No @e selector on 1.7.10: removed by hand, in the player's dimension.
    @Override
    public boolean clear(double x, double y, double z, double radius) {
        if (Minecraft.getMinecraft().thePlayer == null) return false;
        int dimension = Minecraft.getMinecraft().thePlayer.dimension;
        double limit = radius * radius;
        return run(server -> {
            for (Entity e : new ArrayList<Entity>(server.worldServerForDimension(dimension).loadedEntityList)) {
                if (!(e instanceof EntityPlayer) && e.getDistanceSq(x, y, z) <= limit) e.setDead();
            }
        });
    }

    private static Consumer<MinecraftServer> command(String command) {
        return server -> {
            // the server is the sender, so its refusal reaches no chat: said here, or a probe waits blind
            if (server.getCommandManager().executeCommand(server, command) == 0) {
                LOGGER.warn("[crystalgraphics] a stimulus command did nothing: /{}", command);
            }
        };
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
