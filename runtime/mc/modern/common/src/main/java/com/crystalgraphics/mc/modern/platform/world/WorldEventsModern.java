package com.crystalgraphics.mc.modern.platform.world;

import com.crystalgraphics.platform.service.CgWorldEvents;
import com.crystalgraphics.platform.service.CgWorldQuery;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
//? if >=1.16.5 {
import net.minecraft.world.entity.LightningBolt;
//?}
//? if >=1.21.3 {
/*import net.minecraft.world.phys.Vec3;
*///?}

/**
 * The client's world events, into {@link CgWorldEvents}, 1.13.2 to 26.3. Hurt, death and lightning are read off state
 * the client already holds, once a game tick ({@link #tick}, each loader's client tick): an entity's hurt time rising,
 * its death time starting, a new lightning bolt. Once a frame ({@link #poll}) until a first tick arrives. An explosion and a block broken arrive only as packets and level events, so a node
 * mixin hands them in ({@link #explosion}, {@link #levelEvent}) where its loader's names allow one.
 *
 * <ul>
 *   <li>Lightning from 1.16.5, where the bolt is an ordinary client entity; 1.13.2 polls nothing.</li>
 *   <li>An explosion's power before 1.21.3 and from 1.21.10 (its radius); NaN on 1.21.3 to 1.21.8, whose packet
 *       carries none.</li>
 * </ul>
 */
public final class WorldEventsModern {

    private static final int LEVEL_EVENT_BLOCK_BROKEN = 2001;
    private static final int PRUNE_EVERY = 600;

    private static final Int2IntOpenHashMap HURT = new Int2IntOpenHashMap();
    private static final IntOpenHashSet DEAD = new IntOpenHashSet(), BOLTS = new IntOpenHashSet(), PRESENT = new IntOpenHashSet();
    private static Level seen;
    private static int scans;
    private static boolean ticked;

    private WorldEventsModern() {
    }

    /** Once a client tick, after it: the hurts, deaths and lightning since the last. */
    public static void tick(Minecraft mc) {
        ticked = true;
        scan(mc);
    }

    /** Once a frame, at the opaque pass: what {@link #tick} reads, while no loader has ticked it. */
    public static void poll(Minecraft mc) {
        if (!ticked) scan(mc);
    }

    private static void scan(Minecraft mc) {
        Level level = mc.level;
        if (level != seen) {
            seen = level;
            HURT.clear();
            DEAD.clear();
            BOLTS.clear();
        }
        //? if >=1.14 {
        if (level == null) return;
        boolean prune = ++scans % PRUNE_EVERY == 0;
        if (prune) PRESENT.clear();
        for (Entity e : mc.level.entitiesForRendering()) {
            int id = e.getId();
            if (prune) PRESENT.add(id);
            if (e instanceof LivingEntity) {
                LivingEntity living = (LivingEntity) e;
                int last = HURT.put(id, living.hurtTime);
                if (living.hurtTime > last) CgWorldEvents.entityHurt(id, x(e), y(e), z(e));
                if (living.deathTime > 0 && DEAD.add(id)) CgWorldEvents.entityDied(id, x(e), y(e), z(e));
            }
            else if (isBolt(e) && BOLTS.add(id)) CgWorldEvents.lightning(x(e), y(e), z(e));
        }
        if (prune) {
            HURT.keySet().retainAll(PRESENT);
            DEAD.retainAll(PRESENT);
            BOLTS.retainAll(PRESENT);
        }
        //?}
    }

    private static boolean isBolt(Entity e) {
        //? if >=1.16.5 {
        return e instanceof LightningBolt;
        //?} else {
        /*return false;
        *///?}
    }

    private static double x(Entity e) {
        //? if >=1.15 {
        return e.getX();
        //?} else {
        /*return e.x;
        *///?}
    }

    private static double y(Entity e) {
        //? if >=1.15 {
        return e.getY();
        //?} else {
        /*return e.y;
        *///?}
    }

    private static double z(Entity e) {
        //? if >=1.15 {
        return e.getZ();
        //?} else {
        /*return e.z;
        *///?}
    }

    /** A node mixin, after the client handled an explosion packet. */
    public static void explosion(ClientboundExplodePacket packet) {
        //? if >=1.21.10 {
        /*Vec3 at = packet.center();
        CgWorldEvents.explosion(at.x, at.y, at.z, packet.radius());
        *///?} elif >=1.21.3 {
        /*Vec3 at = packet.center();
        CgWorldEvents.explosion(at.x, at.y, at.z, Float.NaN);
        *///?} else {
        CgWorldEvents.explosion(packet.getX(), packet.getY(), packet.getZ(), packet.getPower());
        //?}
    }

    /** A node mixin, as the client level handles a level event: a block broken is event 2001, its state the data. */
    public static void levelEvent(int type, BlockPos pos, int data) {
        if (type != LEVEL_EVENT_BLOCK_BROKEN) return;
        Level level = Minecraft.getInstance().level;
        BlockState state = Block.stateById(data);
        //? if >=1.14 {
        int surface = WorldQueryModern.surfaceOf(state.getSoundType());
        //?} else {
        /*int surface = CgWorldQuery.SURFACE_OTHER;
        *///?}
        int col = level == null ? 0 : state.getMapColor(level, pos).col;
        CgWorldEvents.blockBroken(pos.getX(), pos.getY(), pos.getZ(), surface, col == 0 ? 0 : 0xFF000000 | col);
    }
}
