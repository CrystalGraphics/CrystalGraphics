package com.crystalgraphics.mc.modern.platform;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Nullable;

import com.crystalgraphics.mc.entity.CgEntityRef;
import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.mc.render.entity.CgEntityPictures;
import com.crystalgraphics.mc.render.entity.CgEntityPose;
import com.crystalgraphics.serialization.nbt.CgNbt;
import com.mojang.authlib.GameProfile;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Quaternionf;
import org.joml.Vector3f;
//? if >=1.15 <1.21.11 {
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
//?}
//? if >=1.14 {
import net.minecraft.client.player.RemotePlayer;
//?}
//? if >=1.19.4 <1.20 {
/*import com.mojang.blaze3d.vertex.PoseStack;
*///?}
//? if >=1.20 <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?}
//? if >=1.21.11 {
/*import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.Pose;
*///?}
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?}
//? if >=1.21.3 {
/*import net.minecraft.world.entity.EntitySpawnReason;
*///?}
//? if >=1.21.11 {
/*import net.minecraft.world.entity.EntityProcessor;
*///?}
//? if >=26.2 {
/*import net.minecraft.world.entity.EntitySpawnRequest;
*///?}

/**
 * {@link CgEntityPictures} on the modern tree, client: the inventory's own entity drawing, its angle code ported so any
 * pose draws as the inventory's player does (NR4 decision 2). Before 1.15 there is no inventory entity call to use.
 */
public final class EntityPicturesModern implements CgEntityPictures.Host {

    private static final float DEG = (float) (Math.PI / 180.0);
    /** The furthest a pointer turns a model, in vanilla's radians-times-twenty: about 60 degrees of body. */
    private static final double MAX_TURN = 1.4;
    /**
     * Ids for entities a picture makes, counting down from -1 where the game's count up: from 26.2 one made outside a
     * level has none, and a renderer reading it (a sheep's item models) throws.
     */
    private static final AtomicInteger IDS = new AtomicInteger(-1);

    @Override
    public String absent() {
        //? if <1.15 {
        /*return "no inventory entity drawing before 1.15";
        *///?} else {
        return null;
        //?}
    }

    @Override
    public Object entity(CgEntityRef ref, @Nullable Object previous) {
        //? if <1.15 {
        /*return null;
        *///?} else {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        switch (ref.kind()) {
            case PLAYER:
                return mc.player;
            case LIVE:
                if (previous instanceof Entity && ((Entity) previous).isAlive() && ((Entity) previous).getUUID().equals(ref.uuid())) {
                    return previous;
                }
                for (Entity e : mc.level.entitiesForRendering()) {
                    if (e.getUUID().equals(ref.uuid())) return e instanceof LivingEntity ? e : null;
                }
                return null;
            case PROFILE:
                if (previous != null) return previous;
                return identified(player(mc, ref.profile()));
            default:
                if (previous != null) return previous;
                return identified(fromData(mc, CgNbt.toHost(ref.data().toNbt())));
        }
        //?}
    }

    @Nullable
    private static Object identified(@Nullable Object made) {
        if (made instanceof Entity) ((Entity) made).setId(IDS.getAndDecrement());
        return made;
    }

    // A mannequin of a profile, made client-side: the default skin until the skin is fetched (NR4 open item)
    private static Object player(Minecraft mc, String nameOrUuid) {
        UUID id;
        String name;
        try {
            id = UUID.fromString(nameOrUuid);
            name = nameOrUuid.substring(0, 8);
        } catch (IllegalArgumentException notUuid) {
            id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + nameOrUuid).getBytes(StandardCharsets.UTF_8));
            name = nameOrUuid;
        }
        //? if <1.14 {
        /*return null;
        *///?} elif >=1.19 <1.19.3 {
        /*return new RemotePlayer(mc.level, new GameProfile(id, name), null);
        *///?} else {
        return new RemotePlayer(mc.level, new GameProfile(id, name));
        //?}
    }

    // Made, never added to the level: a picture's entity never joins it
    @Nullable
    private static Object fromData(Minecraft mc, CompoundTag tag) {
        //? if <1.15 {
        /*Entity e = null;
        *///?} elif <1.21.3 {
        Entity e = EntityType.loadEntityRecursive(tag, mc.level, x -> x);
        //?} elif <1.21.11 {
        /*Entity e = EntityType.loadEntityRecursive(tag, mc.level, EntitySpawnReason.LOAD, x -> x);
        *///?} elif <26.2 {
        /*Entity e = EntityType.loadEntityRecursive(tag, mc.level, EntitySpawnReason.LOAD, EntityProcessor.NOP);
        *///?} else {
        /*Entity e = EntityType.loadEntityRecursive(tag, mc.level, new EntitySpawnRequest(EntitySpawnReason.LOAD, false),
                EntityProcessor.NOP);
        *///?}
        return e instanceof LivingEntity ? e : null;
    }

    @Override
    public float height(Object entity) {
        //? if <1.15 {
        /*return 1;
        *///?} else {
        return ((Entity) entity).getBbHeight();
        //?}
    }

    @Override
    public void draw(CgHostDrawContext ctx, Object entity, CgEntityPose pose, float scale, float x, float y, float w, float h) {
        LivingEntity e = (LivingEntity) entity;
        float cx = x + w / 2, cy = y + h / 2;
        int x0 = Math.round(x), y0 = Math.round(y), x1 = Math.round(x + w), y1 = Math.round(y + h);
        Quaternionf rotation = new Quaternionf().rotateZ((float) Math.PI);
        Quaternionf tilt = new Quaternionf().rotateX(pose.tilt() * DEG);
        rotation.mul(tilt);
        //? if <1.15 {
        /*return;
        *///?} elif <1.19.4 {
        /*// Only the pointer form: the pointer whose angles give the pose, as far as a pointer turns a model
        double turn = Math.max(-MAX_TURN, Math.min(MAX_TURN, pose.bodyYaw() / 20.0));
        double lift = Math.max(-MAX_TURN, Math.min(MAX_TURN, pose.tilt() / 20.0));
        InventoryScreen.renderEntityInInventory(Math.round(cx), Math.round(cy + e.getBbHeight() * scale / 2), Math.round(scale),
                (float) (40 * Math.tan(turn)), (float) (40 * Math.tan(lift)), e);
        *///?} elif <1.21.11 {
        // Vanilla's renderEntityInInventoryFollowsAngle, a pose's angles in place of a pointer's
        float body = e.yBodyRot, yaw = e.getYRot(), pitch = e.getXRot(), headO = e.yHeadRotO, head = e.yHeadRot;
        e.yBodyRot = 180 + pose.bodyYaw();
        e.setYRot(180 + pose.headYaw());
        e.setXRot(pose.pitch());
        e.yHeadRot = e.getYRot();
        e.yHeadRotO = e.getYRot();
        try {
            rotated(ctx, e, scale, cx, cy, x0, y0, x1, y1, rotation, tilt);
        } finally {
            e.yBodyRot = body;
            e.setYRot(yaw);
            e.setXRot(pitch);
            e.yHeadRotO = headO;
            e.yHeadRot = head;
        }
        //?} else {
        /*// The same, on the entity's render state, as vanilla does from 1.21.11
        EntityRenderState state = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(e).createRenderState(e, 1.0F);
        state.shadowPieces.clear();
        state.outlineColor = 0;
        if (state instanceof LivingEntityRenderState) {
            LivingEntityRenderState living = (LivingEntityRenderState) state;
            living.bodyRot = 180 + pose.bodyYaw();
            living.yRot = pose.headYaw() - pose.bodyYaw();
            living.xRot = living.pose != Pose.FALL_FLYING ? pose.pitch() : 0;
            living.boundingBoxWidth = living.boundingBoxWidth / living.scale;
            living.boundingBoxHeight = living.boundingBoxHeight / living.scale;
            living.scale = 1.0F;
        }
        Vector3f translation = new Vector3f(0, state.boundingBoxHeight / 2, 0);
        *///?}
        //? if >=1.21.11 <26.1 {
        /*((GuiGraphics) ctx.gui()).submitEntityRenderState(state, scale, translation, rotation, tilt, x0, y0, x1, y1);
        *///?} elif >=26.1 {
        /*((GuiGraphicsExtractor) ctx.gui()).entity(state, scale, translation, rotation, tilt, x0, y0, x1, y1);
        *///?}
    }

    // The rotation form of each era: feet at a point to 1.20.1; the middle at a point, then in a box, after
    private static void rotated(CgHostDrawContext ctx, LivingEntity e, float scale, float cx, float cy, int x0, int y0, int x1,
                                int y1, Quaternionf rotation, Quaternionf tilt) {
        //? if <1.19.4 {
        /*return;
        *///?} elif <1.20 {
        /*InventoryScreen.renderEntityInInventory((PoseStack) ctx.poseStack(), Math.round(cx),
                Math.round(cy + e.getBbHeight() * scale / 2), Math.round(scale), rotation, tilt, e);
        *///?} elif <1.20.2 {
        InventoryScreen.renderEntityInInventory((GuiGraphics) ctx.gui(), Math.round(cx),
                Math.round(cy + e.getBbHeight() * scale / 2), Math.round(scale), rotation, tilt, e);
        //?} elif <1.20.5 {
        /*InventoryScreen.renderEntityInInventory((GuiGraphics) ctx.gui(), cx, cy, Math.round(scale / e.getScale()),
                new Vector3f(0, e.getBbHeight() / 2, 0), rotation, tilt, e);
        *///?} elif <1.21.6 {
        /*InventoryScreen.renderEntityInInventory((GuiGraphics) ctx.gui(), cx, cy, scale / e.getScale(),
                new Vector3f(0, e.getBbHeight() / 2, 0), rotation, tilt, e);
        *///?} elif <1.21.11 {
        /*InventoryScreen.renderEntityInInventory((GuiGraphics) ctx.gui(), x0, y0, x1, y1,
                scale / e.getScale(), new Vector3f(0, e.getBbHeight() / 2, 0), rotation, tilt, e);
        *///?}
    }
}
