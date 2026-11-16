package com.crystalgraphics.mc.legacy.platform.world;

import com.crystalgraphics.platform.world.CgBoxSink;
import com.crystalgraphics.platform.world.CgEntityFacts;
import com.crystalgraphics.platform.world.CgEntityQuery;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.IEntityMultiPart;
import net.minecraft.entity.IProjectile;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.EnumAction;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;
//? if >=1.10 {
import net.minecraft.util.EnumHand;
import net.minecraft.util.EnumHandSide;
//?}
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
//? if >=1.12 {
import net.minecraft.entity.MultiPartEntityPart;
//?} else {
/*import net.minecraft.entity.boss.EntityDragonPart;
*///?}
//? if >=1.9 {
import net.minecraft.util.math.AxisAlignedBB;
//?} else {
/*import net.minecraft.util.AxisAlignedBB;
*///?}

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.IntConsumer;

/**
 * {@link CgEntityQuery} over one level on Forge 1.8.9 to 1.12.2, a server's included. Server-safe;
 * {@link LevelsLegacy} hands one out per level, and the client's {@link EntityQueryLegacy} extends it with its
 * local and camera entity. The level's thread.
 *
 * <ul>
 *   <li>No entity swims before 1.13, and none glides before 1.9.</li>
 *   <li>{@link #within} walks the level's own box query, parts included: a list per call.</li>
 *   <li>A part's id resolves on a server, which lists parts by id; on a client, through the last parts {@link #within}
 *       handed out.</li>
 * </ul>
 */
public class LevelEntityQueryLegacy implements CgEntityQuery {

    /** Parts {@link #within} handed out, overwritten in turn: what a part's id resolves through on a client. */
    private static final int PARTS = 16;

    /** Weak: the host's cache maps the level to this, and would otherwise keep it forever. */
    private final WeakReference<World> bound;
    @SuppressWarnings("unchecked")
    private final WeakReference<Entity>[] parts = new WeakReference[PARTS];
    private int nextPart;
    private WeakReference<World> partsWorld = new WeakReference<>(null);

    LevelEntityQueryLegacy(World bound) {
        this.bound = new WeakReference<>(bound);
    }

    /** The level it answers for; null for none. */
    World world() {
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

    /** The entity with this UUID in its level: the server's own lookup, a client's loaded list walked. */
    @SuppressWarnings("unchecked")
    @Override
    public <V> V entity(UUID id) {
        World world = world();
        if (world == null || id == null) return null;
        if (world instanceof WorldServer) return (V) ((WorldServer) world).getEntityFromUuid(id);
        for (Object e : world.loadedEntityList) {
            if (id.equals(((Entity) e).getUniqueID())) return (V) e;
        }
        return null;
    }

    /** The entity or part with network id {@code id} in its level; null for none. */
    Entity find(int id) {
        World world = world();
        if (world == null || id < 0) return null;
        Entity e = world.getEntityByID(id);
        return e != null ? e : rememberedPart(world, id);
    }

    private Entity rememberedPart(World world, int id) {
        if (world != partsWorld.get()) return null;
        for (int i = 0; i < PARTS; i++) {
            Entity p = parts[i] == null ? null : parts[i].get();
            if (p == null || p.getEntityId() != id) continue;
            Entity parent = parentOf(p);
            if (parent == p ? p.isEntityAlive() : world.getEntityByID(parent.getEntityId()) == parent) return p;
            parts[i] = null;
            return null;
        }
        return null;
    }

    /** Hands {@code e} to {@code visitor}, first remembering it if it is a part the level cannot find by id. */
    void visit(World world, Entity e, IntConsumer visitor) {
        int id = e.getEntityId();
        if (world.getEntityByID(id) != e) remember(world, e);
        visitor.accept(id);
    }

    private void remember(World world, Entity part) {
        if (world != partsWorld.get()) {
            Arrays.fill(parts, null);
            partsWorld = new WeakReference<>(world);
        }
        for (WeakReference<Entity> p : parts) {
            if (p != null && p.get() == part) return;
        }
        parts[nextPart] = new WeakReference<>(part);
        nextPart = (nextPart + 1) % PARTS;
    }

    private static Entity parentOf(Entity e) {
        //? if >=1.12 {
        IEntityMultiPart parent = e instanceof MultiPartEntityPart ? ((MultiPartEntityPart) e).parent : null;
        //?} else {
        /*IEntityMultiPart parent = e instanceof EntityDragonPart ? ((EntityDragonPart) e).entityDragonObj : null;
        *///?}
        return parent instanceof Entity ? (Entity) parent : e;
    }

    @Override
    public boolean pose(int id, float partialTick, double[] out) {
        Entity e = find(id);
        if (e == null) return false;
        out[X] = e.lastTickPosX + (e.posX - e.lastTickPosX) * partialTick;
        out[Y] = e.lastTickPosY + (e.posY - e.lastTickPosY) * partialTick;
        out[Z] = e.lastTickPosZ + (e.posZ - e.lastTickPosZ) * partialTick;
        out[VELOCITY_X] = e.motionX;
        out[VELOCITY_Y] = e.motionY;
        out[VELOCITY_Z] = e.motionZ;
        AxisAlignedBB box = e.getEntityBoundingBox();
        out[WIDTH] = box.maxX - box.minX;
        out[HEIGHT] = box.maxY - box.minY;
        out[EYE_HEIGHT] = e.getEyeHeight();
        out[YAW] = angle(e.prevRotationYaw, e.rotationYaw, partialTick);
        out[PITCH] = e.prevRotationPitch + (e.rotationPitch - e.prevRotationPitch) * partialTick;
        if (e instanceof EntityLivingBase) {
            EntityLivingBase living = (EntityLivingBase) e;
            out[BODY_YAW] = angle(living.prevRenderYawOffset, living.renderYawOffset, partialTick);
            out[HEAD_YAW] = angle(living.prevRotationYawHead, living.rotationYawHead, partialTick);
            out[LIMB_SWING] = living.limbSwing - living.limbSwingAmount * (1f - partialTick);
            out[LIMB_SWING_AMOUNT] = living.prevLimbSwingAmount + (living.limbSwingAmount - living.prevLimbSwingAmount) * partialTick;
        } else {
            out[BODY_YAW] = out[HEAD_YAW] = out[YAW];
            out[LIMB_SWING] = out[LIMB_SWING_AMOUNT] = 0.0;
        }
        return true;
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
        // the box where it is drawn: moved back by what the tick has yet to cover
        double dx = (e.lastTickPosX - e.posX) * (1 - partialTick), dy = (e.lastTickPosY - e.posY) * (1 - partialTick);
        double dz = (e.lastTickPosZ - e.posZ) * (1 - partialTick);
        AxisAlignedBB b = e.getEntityBoundingBox();
        out.set(b.minX + dx, b.minY + dy, b.minZ + dz, b.maxX + dx, b.maxY + dy, b.maxZ + dz);
        return true;
    }

    @Override
    public int parent(int id) {
        Entity e = find(id);
        return e == null ? -1 : parentOf(e).getEntityId();
    }

    @Override
    public int rootVehicle(int id) {
        Entity e = find(id);
        if (e == null) return -1;
        //? if >=1.10 {
        return e.getLowestRidingEntity().getEntityId();
        //?} else {
        /*while (e.ridingEntity != null) e = e.ridingEntity;
        return e.getEntityId();
        *///?}
    }

    @Override
    public int flags(int id) {
        Entity e = find(id);
        if (e == null) return 0;
        int f = 0;
        if (e.isEntityAlive()) f |= ALIVE;
        if (e instanceof EntityLivingBase) {
            f |= LIVING;
            //? if >=1.9 {
            if (((EntityLivingBase) e).isElytraFlying()) f |= GLIDING;
            //?}
        }
        if (e instanceof EntityPlayer) {
            EntityPlayer p = (EntityPlayer) e;
            f |= PLAYER;
            if (p.isSpectator()) f |= SPECTATOR;
            if (p.capabilities.disableDamage) f |= INVULNERABLE;
        }
        if (isLocal(e)) f |= LOCAL_PLAYER;
        if (e.onGround) f |= ON_GROUND;
        if (e.isSneaking()) f |= SNEAKING;
        if (e.isSprinting()) f |= SPRINTING;
        if (e.isInWater()) f |= IN_WATER;
        if (e.isBurning()) f |= ON_FIRE;
        if (e.isInvisible()) f |= INVISIBLE;
        if (e.canBeCollidedWith()) f |= PICKABLE;
        //? if >=1.10 {
        if (e.canBeAttackedWithItem()) f |= ATTACKABLE;
        //?} else {
        /*if (e.canAttackWithItem()) f |= ATTACKABLE;
        *///?}
        //? if >=1.12 {
        if (e.isEntityInvulnerable(DamageSource.GENERIC)) f |= INVULNERABLE;
        //?} else {
        /*if (e.isEntityInvulnerable(DamageSource.generic)) f |= INVULNERABLE;
        *///?}
        if (e.getCollisionBoundingBox() != null) f |= SOLID;
        return f;
    }

    // The swing is read from its fields, as getSwingProgress does: the getter is client-only. 1.8.9's item use is the
    // player's, and its ticks in are client-only: a server says none, and the use's length is its held item's.
    @Override
    public boolean facts(int id, float partialTick, CgEntityFacts out) {
        Entity e = find(id);
        if (e == null) return false;
        out.moveX = e.posX - e.lastTickPosX;
        out.moveY = e.posY - e.lastTickPosY;
        out.moveZ = e.posZ - e.lastTickPosZ;
        out.y = e.lastTickPosY + (e.posY - e.lastTickPosY) * partialTick;
        int f = 0;
        if (e.onGround) f |= CgEntityFacts.GROUNDED;
        if (e.isSneaking()) f |= CgEntityFacts.SNEAKING;
        if (e.isSprinting()) f |= CgEntityFacts.SPRINTING;
        if (e.isInWater()) f |= CgEntityFacts.IN_WATER;
        //? if >=1.10 {
        Entity vehicle = e.getRidingEntity();
        //?} else {
        /*Entity vehicle = e.ridingEntity;
        *///?}
        out.vehicle = vehicle == null ? -1 : vehicle.getEntityId();
        if (e instanceof EntityLivingBase) f |= living((EntityLivingBase) e, partialTick, out);
        out.bits |= f;
        input(e, out);
        return true;
    }

    /** A player's own input, where this side has it: the client's, for its own player. */
    void input(Entity e, CgEntityFacts out) {
    }

    private static int living(EntityLivingBase e, float partialTick, CgEntityFacts out) {
        int f = CgEntityFacts.LIVING;
        if (e.isPlayerSleeping()) f |= CgEntityFacts.SLEEPING;
        if (e.getHealth() <= 0f) f |= CgEntityFacts.DEAD;
        out.hurtTime = e.hurtTime;
        out.deathTime = e.deathTime;
        float d = e.swingProgress - e.prevSwingProgress;
        if (d < 0f) d += 1f;
        out.swing = e.prevSwingProgress + d * partialTick;
        if (e.isSwingInProgress) f |= CgEntityFacts.SWINGING;
        //? if >=1.10 {
        if (e.isElytraFlying()) f |= CgEntityFacts.GLIDING;
        if (e.getPrimaryHand() == EnumHandSide.LEFT) f |= CgEntityFacts.LEFT_HANDED;
        if (e.isSwingInProgress) out.swingHand = e.swingingHand == EnumHand.OFF_HAND ? CgEntityFacts.OFF : CgEntityFacts.MAIN;
        if (e.isHandActive()) {
            f |= CgEntityFacts.USING;
            out.useHand = e.getActiveHand() == EnumHand.OFF_HAND ? CgEntityFacts.OFF : CgEntityFacts.MAIN;
            out.useTicks = e.getItemInUseMaxCount();
            out.useLength = out.useTicks + e.getItemInUseCount();
        }
        held(e.getHeldItemMainhand(), CgEntityFacts.MAIN, out);
        held(e.getHeldItemOffhand(), CgEntityFacts.OFF, out);
        //?} else {
        /*if (e instanceof EntityPlayer && ((EntityPlayer) e).isUsingItem()) {
            EntityPlayer p = (EntityPlayer) e;
            f |= CgEntityFacts.USING;
            if (p.worldObj.isRemote) {
                out.useTicks = p.getItemInUseDuration();
                out.useLength = out.useTicks + p.getItemInUseCount();
            } else if (p.getHeldItem() != null) {
                out.useLength = p.getHeldItem().getMaxItemUseDuration();
            }
        }
        held(e.getHeldItem(), CgEntityFacts.MAIN, out);
        *///?}
        return f;
    }

    private static void held(ItemStack stack, int hand, CgEntityFacts out) {
        //? if >=1.11 {
        if (stack.isEmpty()) return;
        //?} else {
        /*if (stack == null) return;
        *///?}
        out.item[hand] = Item.getIdFromItem(stack.getItem());
        out.use[hand] = useOf(stack.getItemUseAction());
    }

    private static int useOf(EnumAction action) {
        if (action == null) return CgEntityFacts.USE_NONE;
        switch (action) {
            case EAT: return CgEntityFacts.USE_EAT;
            case DRINK: return CgEntityFacts.USE_DRINK;
            case BLOCK: return CgEntityFacts.USE_BLOCK;
            case BOW: return CgEntityFacts.USE_BOW;
            case NONE: return CgEntityFacts.USE_NONE;
            default: return CgEntityFacts.USE_OTHER;
        }
    }

    @Override
    public int kind(int id) {
        Entity e = find(id);
        if (e instanceof EntityPlayer) return KIND_PLAYER;
        if (e instanceof IMob) return KIND_MONSTER;
        if (e instanceof EntityAnimal) return KIND_ANIMAL;
        if (e instanceof EntityItem) return KIND_ITEM;
        if (e instanceof IProjectile) return KIND_PROJECTILE;
        if (e instanceof EntityMinecart || e instanceof EntityBoat) return KIND_VEHICLE;
        return KIND_OTHER;
    }

    @Override
    public String type(int id) {
        Entity e = find(id);
        return e == null ? null : typeOf(e);
    }

    /**
     * An entity's type id: its registry key from 1.11 ({@code minecraft:zombie}), Forge's string before it with the
     * namespace split off ({@code minecraft:Zombie}, a mod's {@code mymod.wyvern} as {@code mymod:wyvern}). A player,
     * which no registry holds, is {@code minecraft:player}; null for any other entity class the registry does not hold.
     */
    public static String typeOf(Entity e) {
        if (e instanceof EntityPlayer) return "minecraft:player";
        //? if >=1.11 {
        Object key = EntityList.getKey(e);
        return key == null ? null : key.toString();
        //?} else {
        /*String name = EntityList.getEntityString(e);
        if (name == null) return null;
        int dot = name.indexOf('.');
        return dot < 0 ? "minecraft:" + name : name.substring(0, dot) + ":" + name.substring(dot + 1);
        *///?}
    }

    @Override
    public void within(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, IntConsumer visitor) {
        World world = world();
        if (world == null) return;
        List<Entity> found = world.getEntitiesWithinAABBExcludingEntity(null,
                new AxisAlignedBB(minX, minY, minZ, maxX, maxY, maxZ));
        for (int i = 0; i < found.size(); i++) visit(world, found.get(i), visitor);
    }
}
