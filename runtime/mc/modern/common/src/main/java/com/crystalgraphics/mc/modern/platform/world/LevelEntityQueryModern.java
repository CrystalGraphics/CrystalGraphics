package com.crystalgraphics.mc.modern.platform.world;

import com.crystalgraphics.platform.world.CgBoxSink;
import com.crystalgraphics.platform.world.CgEntityQuery;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
//? if >=1.20.3 {
/*import net.minecraft.world.entity.vehicle.VehicleEntity;
*///?} else {
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.entity.vehicle.Boat;
//?}
//? if >=1.14 {
import net.minecraft.world.phys.Vec3;
//?}
//? if >=1.21.11 {
/*import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
*///?} elif >=1.14 {
import net.minecraft.world.entity.boss.EnderDragonPart;
//?}
//? if >=1.14 {
import net.minecraft.server.level.ServerLevel;
//?}
//? if >=1.19.3 {
import net.minecraft.world.level.entity.EntityTypeTest;
//?}

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.IntConsumer;
import java.util.function.Predicate;

/**
 * {@link CgEntityQuery} over one level, 1.13.2 to 26.3, a server's included. Server-safe; {@link LevelsModern}
 * hands one out per level, and the client's {@link EntityQueryModern} extends it with its local and camera entity. The
 * level's thread.
 *
 * <ul>
 *   <li>1.13.2 answers no velocity (zero), and a dragon part is its own parent there: its class has no name to
 *       import.</li>
 *   <li>Vehicles are minecarts and boats, as Minecraft's own vehicle class has them from 1.20.3.</li>
 *   <li>{@link #within} walks the level's own box query, parts included: into a reused list from 1.19.3, a list per
 *       call before.</li>
 *   <li>A part's id resolves through {@code getEntityOrPart} on a 1.17+ server; anywhere else, through the last parts
 *       {@link #within} handed out. A modded part entity is its own parent: its parent is loader API.</li>
 * </ul>
 */
public class LevelEntityQueryModern implements CgEntityQuery {

    private static final Predicate<Entity> ANY = e -> true;
    //? if >=1.19.3 {
    private static final EntityTypeTest<Entity, Entity> ALL = EntityTypeTest.forClass(Entity.class);
    //?}
    /** Parts {@link #within} handed out, overwritten in turn: what a part's id resolves through off a server. */
    private static final int PARTS = 16;

    /** Weak: the host's cache maps the level to this, and would otherwise keep it forever. */
    private final WeakReference<Level> bound;
    private final List<Entity> found = new ArrayList<>();
    private boolean walking;
    @SuppressWarnings("unchecked")
    private final WeakReference<Entity>[] parts = new WeakReference[PARTS];
    private int nextPart;
    private WeakReference<Level> partsLevel = new WeakReference<>(null);

    LevelEntityQueryModern(Level bound) {
        this.bound = new WeakReference<>(bound);
    }

    /** The level it answers for; null for none. */
    Level level() {
        return bound.get();
    }

    /** Whether {@code e} is the player at this client's keyboard. */
    boolean isLocal(Entity e) {
        return false;
    }

    @Override
    public int localPlayer() {
        return -1;
    }

    @Override
    public int cameraEntity() {
        return -1;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <V> V entity(int id) {
        return (V) find(id);
    }

    /**
     * The entity with this UUID in its level: Minecraft's own lookup on a 1.14.3+ server and on any 1.21.5+ level; a
     * client before 1.21.5 is {@link EntityQueryModern}'s. 1.13.2 answers null: its server level is unmapped.
     */
    @SuppressWarnings("unchecked")
    @Override
    public <V> V entity(UUID id) {
        Level level = level();
        if (level == null || id == null) return null;
        //? if >=1.21.5 {
        /*return (V) level.getEntity(id);
        *///?} elif >=1.14 {
        return level instanceof ServerLevel ? (V) ((ServerLevel) level).getEntity(id) : null;
        //?} else {
        /*return null;
        *///?}
    }

    /** The entity or part with network id {@code id} in its level; null for none. */
    Entity find(int id) {
        Level level = level();
        if (level == null || id < 0) return null;
        Entity e = level.getEntity(id);
        if (e != null) return e;
        //? if >=1.17 {
        if (level instanceof ServerLevel) {
            e = ((ServerLevel) level).getEntityOrPart(id);
            if (e != null) return e;
        }
        //?}
        return rememberedPart(level, id);
    }

    private Entity rememberedPart(Level level, int id) {
        if (level != partsLevel.get()) return null;
        for (int i = 0; i < PARTS; i++) {
            Entity p = parts[i] == null ? null : parts[i].get();
            if (p == null || p.getId() != id) continue;
            Entity parent = parentOf(p);
            if (parent == p ? p.isAlive() : level.getEntity(parent.getId()) == parent) return p;
            parts[i] = null;
            return null;
        }
        return null;
    }

    private void remember(Level level, Entity part) {
        if (level != partsLevel.get()) {
            Arrays.fill(parts, null);
            partsLevel = new WeakReference<>(level);
        }
        for (WeakReference<Entity> p : parts) {
            if (p != null && p.get() == part) return;
        }
        parts[nextPart] = new WeakReference<>(part);
        nextPart = (nextPart + 1) % PARTS;
    }

    private static Entity parentOf(Entity e) {
        //? if >=1.14 {
        if (e instanceof EnderDragonPart) return ((EnderDragonPart) e).parentMob;
        //?}
        return e;
    }

    @Override
    public boolean pose(int id, float partialTick, double[] out) {
        Entity e = find(id);
        if (e == null) return false;
        poseOf(e, partialTick, out);
        return true;
    }

    /** Every answer at once, from one look-up: what a snapshot's capture reads for each of thousands. */
    @Override
    public int row(int id, float partialTick, int[] facts, CgBoxSink box, double[] pose) {
        Entity e = find(id);
        if (e == null) {
            facts[ROW_FLAGS] = 0;
            facts[ROW_KIND] = KIND_OTHER;
            facts[ROW_PARENT] = facts[ROW_ROOT] = -1;
            return 0;
        }
        facts[ROW_FLAGS] = flagsOf(e);
        facts[ROW_KIND] = kindOf(e);
        facts[ROW_PARENT] = parentOf(e).getId();
        facts[ROW_ROOT] = e.getRootVehicle().getId();
        boxOf(e, partialTick, box);
        poseOf(e, partialTick, pose);
        return ROW_BOXED | ROW_POSED;
    }

    private static void poseOf(Entity e, float partialTick, double[] out) {
        //? if >=1.15 {
        double x = e.getX(), y = e.getY(), z = e.getZ();
        //?} else {
        /*double x = e.x, y = e.y, z = e.z;
        *///?}
        out[X] = e.xo + (x - e.xo) * partialTick;
        out[Y] = e.yo + (y - e.yo) * partialTick;
        out[Z] = e.zo + (z - e.zo) * partialTick;
        //? if >=1.14 {
        Vec3 v = e.getDeltaMovement();
        out[VELOCITY_X] = v.x;
        out[VELOCITY_Y] = v.y;
        out[VELOCITY_Z] = v.z;
        //?} else {
        /*out[VELOCITY_X] = out[VELOCITY_Y] = out[VELOCITY_Z] = 0.0;
        *///?}
        AABB box = e.getBoundingBox();
        out[WIDTH] = box.maxX - box.minX;
        out[HEIGHT] = box.maxY - box.minY;
        out[EYE_HEIGHT] = e.getEyeHeight();
        //? if >=1.17 {
        float yaw = e.getYRot(), pitch = e.getXRot();
        //?} else {
        /*float yaw = e.yRot, pitch = e.xRot;
        *///?}
        out[YAW] = angle(e.yRotO, yaw, partialTick);
        out[PITCH] = e.xRotO + (pitch - e.xRotO) * partialTick;
        if (e instanceof LivingEntity) {
            LivingEntity living = (LivingEntity) e;
            out[BODY_YAW] = angle(living.yBodyRotO, living.yBodyRot, partialTick);
            out[HEAD_YAW] = angle(living.yHeadRotO, living.yHeadRot, partialTick);
            //? if >=1.19.4 {
            out[LIMB_SWING] = living.walkAnimation.position(partialTick);
            out[LIMB_SWING_AMOUNT] = living.walkAnimation.speed(partialTick);
            //?} else {
            /*out[LIMB_SWING] = living.animationPosition - living.animationSpeed * (1f - partialTick);
            out[LIMB_SWING_AMOUNT] = living.animationSpeedOld + (living.animationSpeed - living.animationSpeedOld) * partialTick;
            *///?}
        } else {
            out[BODY_YAW] = out[HEAD_YAW] = out[YAW];
            out[LIMB_SWING] = out[LIMB_SWING_AMOUNT] = 0.0;
        }
    }

    /** From {@code from} to {@code to} degrees by {@code t}, the short way round. */
    private static double angle(float from, float to, float t) {
        float d = to - from;
        d -= (float) Math.floor((d + 180f) / 360f) * 360f;
        return from + d * t;
    }

    @Override
    public boolean box(int id, float partialTick, CgBoxSink out) {
        Entity e = find(id);
        if (e == null) return false;
        boxOf(e, partialTick, out);
        return true;
    }

    private static void boxOf(Entity e, float partialTick, CgBoxSink out) {
        //? if >=1.15 {
        double x = e.getX(), y = e.getY(), z = e.getZ();
        //?} else {
        /*double x = e.x, y = e.y, z = e.z;
        *///?}
        // the box where it is drawn: moved back by what the tick has yet to cover
        double dx = (e.xo - x) * (1 - partialTick), dy = (e.yo - y) * (1 - partialTick);
        double dz = (e.zo - z) * (1 - partialTick);
        AABB b = e.getBoundingBox();
        out.set(b.minX + dx, b.minY + dy, b.minZ + dz, b.maxX + dx, b.maxY + dy, b.maxZ + dz);
    }

    @Override
    public int parent(int id) {
        Entity e = find(id);
        return e == null ? -1 : parentOf(e).getId();
    }

    @Override
    public int rootVehicle(int id) {
        Entity e = find(id);
        return e == null ? -1 : e.getRootVehicle().getId();
    }

    @Override
    public int flags(int id) {
        Entity e = find(id);
        return e == null ? 0 : flagsOf(e);
    }

    private int flagsOf(Entity e) {
        int f = 0;
        if (e.isAlive()) f |= ALIVE;
        if (e instanceof LivingEntity) {
            f |= LIVING;
            if (((LivingEntity) e).isFallFlying()) f |= GLIDING;
        }
        if (e instanceof Player) {
            Player p = (Player) e;
            f |= PLAYER;
            if (p.isSpectator()) f |= SPECTATOR;
            //? if >=1.17 {
            if (p.getAbilities().invulnerable) f |= INVULNERABLE;
            //?} else {
            /*if (p.abilities.invulnerable) f |= INVULNERABLE;
            *///?}
        }
        if (isLocal(e)) f |= LOCAL_PLAYER;
        //? if >=1.20 {
        if (e.onGround()) f |= ON_GROUND;
        //?} elif >=1.16.5 {
        /*if (e.isOnGround()) f |= ON_GROUND;
        *///?} else {
        /*if (e.onGround) f |= ON_GROUND;
        *///?}
        //? if >=1.15 {
        if (e.isShiftKeyDown()) f |= SNEAKING;
        //?} else {
        /*if (e.isSneaking()) f |= SNEAKING;
        *///?}
        if (e.isSprinting()) f |= SPRINTING;
        if (e.isSwimming()) f |= SWIMMING;
        if (e.isInWater()) f |= IN_WATER;
        if (e.isOnFire()) f |= ON_FIRE;
        if (e.isInvisible()) f |= INVISIBLE;
        if (e.isPickable()) f |= PICKABLE;
        if (e.isAttackable()) f |= ATTACKABLE;
        if (e.isInvulnerable()) f |= INVULNERABLE;
        // null: what Minecraft's own entity-less collision asks
        //? if >=1.21.6 {
        /*if (e.canBeCollidedWith(null)) f |= SOLID;
        *///?} elif >=1.16.5 {
        if (e.canBeCollidedWith()) f |= SOLID;
        //?} else {
        /*if (e.getCollideBox() != null) f |= SOLID;
        *///?}
        return f;
    }

    @Override
    public int kind(int id) {
        return kindOf(find(id));
    }

    private static int kindOf(Entity e) {
        if (e instanceof Player) return KIND_PLAYER;
        if (e instanceof Enemy) return KIND_MONSTER;
        if (e instanceof Animal) return KIND_ANIMAL;
        if (e instanceof ItemEntity) return KIND_ITEM;
        if (e instanceof Projectile) return KIND_PROJECTILE;
        //? if >=1.20.3 {
        /*if (e instanceof VehicleEntity) return KIND_VEHICLE;
        *///?} else {
        if (e instanceof AbstractMinecart || e instanceof Boat) return KIND_VEHICLE;
        //?}
        return KIND_OTHER;
    }

    @Override
    public void within(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, IntConsumer visitor) {
        Level level = level();
        if (level == null) return;
        AABB box = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
        //? if >=1.19.3 {
        // a visitor asking again, from inside this walk, gets a list of its own
        List<Entity> list = walking ? new ArrayList<>() : found;
        boolean outer = !walking;
        walking = true;
        try {
            level.getEntities(ALL, box, ANY, list);
            visit(level, list, visitor);
        } finally {
            list.clear();
            if (outer) walking = false;
        }
        //?} else {
        /*visit(level, level.getEntities((Entity) null, box, ANY), visitor);
        *///?}
    }

    private void visit(Level level, List<Entity> list, IntConsumer visitor) {
        for (int i = 0; i < list.size(); i++) {
            Entity e = list.get(i);
            int id = e.getId();
            if (level.getEntity(id) != e) remember(level, e);
            visitor.accept(id);
        }
    }
}
