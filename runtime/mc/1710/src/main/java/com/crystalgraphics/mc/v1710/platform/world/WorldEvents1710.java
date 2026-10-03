package com.crystalgraphics.mc.v1710.platform.world;

import com.crystalgraphics.platform.service.CgWorldEvents;
import com.crystalgraphics.platform.service.CgWorldQuery;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.effect.EntityLightningBolt;
import net.minecraft.network.play.server.S27PacketExplosion;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The client's world events on Minecraft 1.7.10, into {@link CgWorldEvents}. Hurt, death and lightning are read off
 * the client's own state once a game tick ({@link #poll}, at each client tick's end through {@link Ticks}): a hurt time
 * rising, a death time starting, a new bolt among the weather effects. An explosion and a block broken arrive through mixins
 * ({@link #explosion}, {@link #levelEvent}).
 */
public final class WorldEvents1710 {

    private static final int LEVEL_EVENT_BLOCK_BROKEN = 2001;
    private static final int PRUNE_EVERY = 600;

    private static final Map<Integer, Integer> HURT = new HashMap<Integer, Integer>();
    private static final Set<Integer> DEAD = new HashSet<Integer>(), BOLTS = new HashSet<Integer>(),
            PRESENT = new HashSet<Integer>();
    private static WorldClient seen;
    private static long tick = Long.MIN_VALUE;
    private static int ticks;

    private WorldEvents1710() {
    }

    /** The client tick, on FML's bus: {@code FMLCommonHandler.instance().bus().register(new WorldEvents1710.Ticks())}. */
    public static final class Ticks {
        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END) poll();
        }
    }

    /**
     * At each client tick's end, and at the opaque pass: the hurts, deaths and lightning since the last game tick polled.
     * A frame alone would miss what lasts less than one: a hurt is half a second, a bolt a few ticks.
     */
    public static void poll() {
        WorldClient world = Minecraft.getMinecraft().theWorld;
        if (world != seen) {
            seen = world;
            HURT.clear();
            DEAD.clear();
            BOLTS.clear();
            tick = Long.MIN_VALUE;
        }
        if (world == null || world.getTotalWorldTime() == tick) return;
        tick = world.getTotalWorldTime();
        boolean prune = ++ticks % PRUNE_EVERY == 0;
        if (prune) PRESENT.clear();
        List<Entity> entities = world.loadedEntityList;
        for (int i = 0; i < entities.size(); i++) {
            Entity e = entities.get(i);
            if (!(e instanceof EntityLivingBase)) continue;
            EntityLivingBase living = (EntityLivingBase) e;
            int id = e.getEntityId();
            if (prune) PRESENT.add(id);
            Integer last = HURT.put(id, living.hurtTime);
            if (living.hurtTime > (last == null ? 0 : last)) CgWorldEvents.entityHurt(id, e.posX, e.posY, e.posZ);
            if (living.deathTime > 0 && DEAD.add(id)) CgWorldEvents.entityDied(id, e.posX, e.posY, e.posZ);
        }
        List<Entity> weather = world.weatherEffects;
        for (int i = 0; i < weather.size(); i++) {
            Entity e = weather.get(i);
            if (prune) PRESENT.add(e.getEntityId());
            if (e instanceof EntityLightningBolt && BOLTS.add(e.getEntityId())) CgWorldEvents.lightning(e.posX, e.posY, e.posZ);
        }
        if (prune) {
            HURT.keySet().retainAll(PRESENT);
            DEAD.retainAll(PRESENT);
            BOLTS.retainAll(PRESENT);
        }
    }

    /** {@code ExplosionHook}, after the client handled an explosion packet: its centre and its power. */
    public static void explosion(S27PacketExplosion packet) {
        CgWorldEvents.explosion(packet.func_149148_f(), packet.func_149143_g(), packet.func_149145_h(), packet.func_149146_i());
    }

    /** {@code LevelEventHook}: a block broken is event 2001, its data the block id with the metadata above bit 12. */
    public static void levelEvent(int type, int x, int y, int z, int data) {
        if (type != LEVEL_EVENT_BLOCK_BROKEN) return;
        Block block = Block.getBlockById(data & 4095);
        boolean air = block.getMaterial() == Material.air;
        CgWorldEvents.blockBroken(x, y, z, air ? CgWorldQuery.SURFACE_NONE : WorldQuery1710.surfaceOf(block),
                air ? 0 : WorldQuery1710.mapColorOf(block, data >> 12 & 255));
    }
}
