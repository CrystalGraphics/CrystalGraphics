package com.crystalgraphics.mc.legacy.platform.world;

import com.crystalgraphics.platform.service.CgWorldEvents;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.effect.EntityLightningBolt;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
//? if >=1.9 {
import net.minecraft.network.play.server.SPacketExplosion;
import net.minecraft.util.math.BlockPos;
//?} else {
/*import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.util.BlockPos;
*///?}

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The client's world events on Forge 1.8.9 to 1.12.2, into {@link CgWorldEvents}. Hurt, death and lightning are read
 * off the client's own state once a game tick ({@link #poll}, at each client tick's end through {@link Ticks}): a hurt
 * time rising, a death time starting, a new bolt among the weather effects. An explosion and a block broken arrive through SRG-named mixins
 * ({@link #explosion}, {@link #levelEvent}).
 */
public final class WorldEventsLegacy {

    private static final int LEVEL_EVENT_BLOCK_BROKEN = 2001;
    private static final int PRUNE_EVERY = 600;

    private static final Map<Integer, Integer> HURT = new HashMap<>();
    private static final Set<Integer> DEAD = new HashSet<>(), BOLTS = new HashSet<>(), PRESENT = new HashSet<>();
    private static WorldClient seen;
    private static long tick = Long.MIN_VALUE;
    private static int ticks;

    private WorldEventsLegacy() {
    }

    /** The client tick, on FML's bus: {@code FMLCommonHandler.instance().bus().register(new WorldEventsLegacy.Ticks())}. */
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
        WorldClient world = ClientLegacy.world();
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
        List<?> entities = world.loadedEntityList;
        for (int i = 0; i < entities.size(); i++) {
            Entity e = (Entity) entities.get(i);
            if (!(e instanceof EntityLivingBase)) continue;
            EntityLivingBase living = (EntityLivingBase) e;
            int id = e.getEntityId();
            if (prune) PRESENT.add(id);
            Integer last = HURT.put(id, living.hurtTime);
            if (living.hurtTime > (last == null ? 0 : last)) CgWorldEvents.entityHurt(id, e.posX, e.posY, e.posZ);
            if (living.deathTime > 0 && DEAD.add(id)) CgWorldEvents.entityDied(id, e.posX, e.posY, e.posZ);
        }
        List<?> weather = world.weatherEffects;
        for (int i = 0; i < weather.size(); i++) {
            Entity e = (Entity) weather.get(i);
            if (prune) PRESENT.add(e.getEntityId());
            if (e instanceof EntityLightningBolt && BOLTS.add(e.getEntityId())) CgWorldEvents.lightning(e.posX, e.posY, e.posZ);
        }
        if (prune) {
            HURT.keySet().retainAll(PRESENT);
            DEAD.retainAll(PRESENT);
            BOLTS.retainAll(PRESENT);
        }
    }

    /** {@code ExplosionHook}, after the client handled an explosion packet. */
    //? if >=1.9 {
    public static void explosion(SPacketExplosion packet) {
    //?} else {
    /*public static void explosion(S27PacketExplosion packet) {
    *///?}
        CgWorldEvents.explosion(packet.getX(), packet.getY(), packet.getZ(), packet.getStrength());
    }

    /** {@code LevelEventHook}, as the client level's renderer handles a level event: a block broken is 2001. */
    public static void levelEvent(int type, BlockPos pos, int data) {
        WorldClient world = ClientLegacy.world();
        if (type != LEVEL_EVENT_BLOCK_BROKEN || world == null) return;
        IBlockState state = Block.getStateById(data);
        CgWorldEvents.blockBroken(pos.getX(), pos.getY(), pos.getZ(), WorldQueryLegacy.surfaceOf(state, world, pos),
                WorldQueryLegacy.mapColorOf(state, world, pos));
    }
}
