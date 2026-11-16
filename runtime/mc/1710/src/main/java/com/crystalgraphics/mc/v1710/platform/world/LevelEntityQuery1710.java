package com.crystalgraphics.mc.v1710.platform.world;

import com.crystalgraphics.platform.world.CgBoxSink;
import com.crystalgraphics.platform.world.CgEntityFacts;
import com.crystalgraphics.platform.world.CgEntityQuery;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.IEntityMultiPart;
import net.minecraft.entity.IProjectile;
import net.minecraft.entity.boss.EntityDragonPart;
import net.minecraft.entity.item.EntityBoat;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.entity.item.EntityMinecart;
import net.minecraft.entity.monster.IMob;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.EnumAction;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.IntConsumer;

/**
 * {@link CgEntityQuery} over one level on Minecraft 1.7.10, a server's included. Server-safe; {@link Levels1710}
 * hands one out per level, and the client's {@link EntityQuery1710} extends it with its local and camera entity. The
 * level's thread.
 *
 * <ul>
 *   <li>No entity swims or glides on this version, and no player spectates.</li>
 *   <li>{@link #within} walks the level's own box query, parts included: a list per call.</li>
 *   <li>A part's id resolves on a server, which lists parts by id; on a client, through the last parts {@link #within}
 *       handed out.</li>
 * </ul>
 */
public class LevelEntityQuery1710 implements CgEntityQuery {

    private static final double PLAYER_EYE = 1.62;
    /** Parts {@link #within} handed out, overwritten in turn: what a part's id resolves through on a client. */
    private static final int PARTS = 16;

    /** Weak: the host's cache maps the level to this, and would otherwise keep it forever. */
    private final WeakReference<World> bound;
    /** The box {@link #within} asks with: 1.7.10's boxes are mutable. */
    private final AxisAlignedBB query = AxisAlignedBB.getBoundingBox(0, 0, 0, 0, 0, 0);
    @SuppressWarnings("unchecked")
    private final WeakReference<Entity>[] parts = new WeakReference[PARTS];
    private int nextPart;
    private WeakReference<World> partsWorld = new WeakReference<>(null);

    LevelEntityQuery1710(World bound) {
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

    /** The entity with this UUID in its level: its loaded list walked, as 1.7.10 has no lookup. */
    @SuppressWarnings("unchecked")
    @Override
    public <V> V entity(UUID id) {
        World world = world();
        if (world == null || id == null) return null;
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
        IEntityMultiPart parent = e instanceof EntityDragonPart ? ((EntityDragonPart) e).entityDragonObj : null;
        return parent instanceof Entity ? (Entity) parent : e;
    }

    @Override
    public boolean pose(int id, float partialTick, double[] out) {
        Entity e = find(id);
        if (e == null) return false;
        out[X] = e.lastTickPosX + (e.posX - e.lastTickPosX) * partialTick;
        out[Y] = e.lastTickPosY + (e.posY - e.lastTickPosY) * partialTick - e.yOffset;
        out[Z] = e.lastTickPosZ + (e.posZ - e.lastTickPosZ) * partialTick;
        out[VELOCITY_X] = e.motionX;
        out[VELOCITY_Y] = e.motionY;
        out[VELOCITY_Z] = e.motionZ;
        out[WIDTH] = Math.abs(e.boundingBox.maxX - e.boundingBox.minX);
        out[HEIGHT] = Math.abs(e.boundingBox.maxY - e.boundingBox.minY);
        out[EYE_HEIGHT] = eyeHeight(e);
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

    // A player's eyeHeight is measured from its posY, which sits yOffset above the feet on the local player and at
    // them on a remote or server one; the camera is 1.62 above the feet either way (EntityRenderer.orientCamera).
    private static double eyeHeight(Entity e) {
        if (!(e instanceof EntityPlayer)) return e.getEyeHeight();
        EntityPlayer player = (EntityPlayer) e;
        return PLAYER_EYE + player.getEyeHeight() - player.getDefaultEyeHeight();
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
        HostShapes1710.set(e.boundingBox, dx, dy, dz, out);
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
        while (e.ridingEntity != null) e = e.ridingEntity;
        return e.getEntityId();
    }

    @Override
    public int flags(int id) {
        Entity e = find(id);
        if (e == null) return 0;
        int f = 0;
        if (e.isEntityAlive()) f |= ALIVE;
        if (e instanceof EntityLivingBase) f |= LIVING;
        if (e instanceof EntityPlayer) {
            f |= PLAYER;
            if (((EntityPlayer) e).capabilities.disableDamage) f |= INVULNERABLE;
        }
        if (isLocal(e)) f |= LOCAL_PLAYER;
        if (e.onGround) f |= ON_GROUND;
        if (e.isSneaking()) f |= SNEAKING;
        if (e.isSprinting()) f |= SPRINTING;
        if (e.isInWater()) f |= IN_WATER;
        if (e.isBurning()) f |= ON_FIRE;
        if (e.isInvisible()) f |= INVISIBLE;
        if (e.canBeCollidedWith()) f |= PICKABLE;
        if (e.canAttackWithItem()) f |= ATTACKABLE;
        if (e.isEntityInvulnerable()) f |= INVULNERABLE;
        if (e.getBoundingBox() != null) f |= SOLID;
        return f;
    }

    // No swimming, gliding or off hand on this version. The swing is read from its fields, as getSwingProgress does: the
    // getter is client-only. Item use is the player's, and its ticks in are client-only: a server says none, and the
    // use's length is its held item's, the one in use.
    @Override
    public boolean facts(int id, float partialTick, CgEntityFacts out) {
        Entity e = find(id);
        if (e == null) return false;
        out.moveX = e.posX - e.lastTickPosX;
        out.moveY = e.posY - e.lastTickPosY;
        out.moveZ = e.posZ - e.lastTickPosZ;
        out.y = e.lastTickPosY + (e.posY - e.lastTickPosY) * partialTick - e.yOffset;
        int f = 0;
        if (e.onGround) f |= CgEntityFacts.GROUNDED;
        if (e.isSneaking()) f |= CgEntityFacts.SNEAKING;
        if (e.isSprinting()) f |= CgEntityFacts.SPRINTING;
        if (e.isInWater()) f |= CgEntityFacts.IN_WATER;
        out.vehicle = e.ridingEntity == null ? -1 : e.ridingEntity.getEntityId();
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
        ItemStack held = e.getHeldItem();
        if (e instanceof EntityPlayer && ((EntityPlayer) e).isUsingItem()) {
            EntityPlayer p = (EntityPlayer) e;
            f |= CgEntityFacts.USING;
            if (p.worldObj.isRemote) {
                out.useTicks = p.getItemInUseDuration();
                out.useLength = out.useTicks + p.getItemInUseCount();
            } else if (held != null) {
                out.useLength = held.getMaxItemUseDuration();
            }
        }
        if (held != null) {
            out.item[CgEntityFacts.MAIN] = Item.getIdFromItem(held.getItem());
            out.use[CgEntityFacts.MAIN] = useOf(held.getItemUseAction());
        }
        return f;
    }

    private static int useOf(EnumAction action) {
        if (action == null) return CgEntityFacts.USE_NONE;
        switch (action) {
            case eat: return CgEntityFacts.USE_EAT;
            case drink: return CgEntityFacts.USE_DRINK;
            case block: return CgEntityFacts.USE_BLOCK;
            case bow: return CgEntityFacts.USE_BOW;
            case none: return CgEntityFacts.USE_NONE;
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
     * An entity's type id: Forge's string with the namespace split off ({@code minecraft:Zombie}, a mod's
     * {@code mymod.wyvern} as {@code mymod:wyvern}). A player, which no registry holds, is {@code minecraft:player};
     * null for any other entity class the registry does not hold.
     */
    public static String typeOf(Entity e) {
        if (e instanceof EntityPlayer) return "minecraft:player";
        String name = EntityList.getEntityString(e);
        if (name == null) return null;
        int dot = name.indexOf('.');
        return dot < 0 ? "minecraft:" + name : name.substring(0, dot) + ":" + name.substring(dot + 1);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void within(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, IntConsumer visitor) {
        World world = world();
        if (world == null) return;
        List<Entity> found = world.getEntitiesWithinAABBExcludingEntity(null,
                query.setBounds(minX, minY, minZ, maxX, maxY, maxZ));
        for (int i = 0; i < found.size(); i++) visit(world, found.get(i), visitor);
    }
}
