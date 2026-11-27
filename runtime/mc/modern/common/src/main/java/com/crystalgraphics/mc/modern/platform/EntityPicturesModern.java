package com.crystalgraphics.mc.modern.platform;

import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.mc.render.entity.CgEntityPictures;
import com.crystalgraphics.mc.render.entity.CgEntityPose;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Quaternionf;
//? if >=1.19.3 {
import org.joml.Vector3f;
//?} elif >=1.15 {
/*import com.mojang.math.Quaternion;
import com.mojang.math.Vector3f;
*///?}
//? if >=1.15 <1.21.11 {
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
//?}
//? if >=1.15 <1.20 {
/*import com.mojang.blaze3d.vertex.PoseStack;
*///?}
//? if >=1.15 <1.19.4 {
/*import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
*///?}
//? if >=1.17 <1.19.4 {
/*import com.mojang.blaze3d.platform.Lighting;
*///?}
//? if >=1.20 <26.1 {
import net.minecraft.client.gui.GuiGraphics;
//?}
//? if >=1.21.11 <26.3 {
/*import net.minecraft.world.InteractionHand;
*///?}
//? if >=1.21.11 {
/*import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.Pose;
*///?}
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
*///?}

/**
 * {@link CgEntityPictures} on the modern tree, client: the inventory's own entity drawing with a pose's angles in place
 * of a pointer's. Vanilla's angle form from 1.19.4, its render state from 1.21.11; below 1.19.4 vanilla turns a model
 * only toward a pointer, so its {@code renderEntityInInventory} is ported with the pose's angles (NR22 decision 2).
 * Before 1.15 there is no inventory entity call to port.
 */
public final class EntityPicturesModern implements CgEntityPictures.Host {

    private static final float DEG = (float) (Math.PI / 180.0);

    private final EntityPosesModern poses = new EntityPosesModern();
    //? if >=1.15 <1.19.4 {
    /*// What the ported draw renders, read by its fancy-graphics Runnable: one, made once
    private LivingEntity drawn;
    private PoseStack drawStack;
    private MultiBufferSource.BufferSource drawBuffers;
    private final Runnable render = () -> Minecraft.getInstance().getEntityRenderDispatcher().render(drawn, 0, 0, 0, 0,
            1, drawStack, drawBuffers, 15728880);
    *///?}

    @Override
    public String absent() {
        //? if <1.15 {
        /*return "no inventory entity drawing before 1.15";
        *///?} else {
        return null;
        //?}
    }

    @Override
    public void draw(CgHostDrawContext ctx, Object entity, CgEntityPose pose, float ageTicks, float scale, float x, float y,
                     float w, float h, float centreY) {
        if (!(entity instanceof LivingEntity)) return;
        LivingEntity e = (LivingEntity) entity;
        float cx = x + w / 2, cy = y + centreY;
        int x0 = Math.round(x), y0 = Math.round(y), x1 = Math.round(x + w), y1 = Math.round(y + h);
        //? if >=1.15 <1.21.11 {
        // NO NAMETAG OF THE GAME'S, as its inventory draws none: a UI draws its own. A living renderer skips it while the
        // GUI is hidden (shouldShowName: Minecraft.renderNames)
        Options options = Minecraft.getInstance().options;
        boolean hidden = options.hideGui;
        options.hideGui = true;
        try {
        //?}
        //? if <1.15 {
        /*return;
        *///?} elif <1.19.4 {
        /*ported(e, pose, ageTicks, Math.round(cx), Math.round(cy + e.getBbHeight() * scale / 2), Math.round(scale));
        *///?} elif <1.21.11 {
        // Vanilla's renderEntityInInventoryFollowsAngle, a pose's angles in place of a pointer's
        Quaternionf rotation = new Quaternionf().rotateZ((float) Math.PI);
        Quaternionf tilt = new Quaternionf().rotateX(pose.tilt() * DEG);
        rotation.mul(tilt);
        poses.apply(e, 180 + pose.bodyYaw(), 180 + pose.headYaw(), pose.pitch(), pose, ageTicks, false);
        try {
            rotated(ctx, e, scale, cx, cy, x0, y0, x1, y1, rotation, tilt);
        } finally {
            poses.restore(e);
        }
        //?}
        //? if >=1.15 <1.21.11 {
        } finally {
            options.hideGui = hidden;
        }
        //?} elif >=1.21.11 {
        /*// The same, on the entity's render state, as vanilla does from 1.21.11
        Quaternionf rotation = new Quaternionf().rotateZ((float) Math.PI);
        Quaternionf tilt = new Quaternionf().rotateX(pose.tilt() * DEG);
        rotation.mul(tilt);
        //? if <26.3 {
        // THE MAIN ARM SWINGS: one never swung would read as its off hand's to 1.21.11; 26.3 picks the main one itself
        InteractionHand hand = e.swingingArm;
        if (!pose.ownSwing()) e.swingingArm = InteractionHand.MAIN_HAND;
        //?}
        EntityRenderState state = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(e).createRenderState(e, 1.0F);
        //? if <26.3 {
        e.swingingArm = hand;
        //?}
        state.shadowPieces.clear();
        state.outlineColor = 0;
        // No nametag of the game's, as its inventory draws none: a UI draws its own
        state.nameTag = null;
        if (state instanceof LivingEntityRenderState) {
            LivingEntityRenderState living = (LivingEntityRenderState) state;
            EntityPosesModern.state(state, 180 + pose.bodyYaw(), pose.headYaw() - pose.bodyYaw(),
                    living.pose != Pose.FALL_FLYING ? pose.pitch() : 0, pose, ageTicks);
            living.boundingBoxWidth = living.boundingBoxWidth / living.scale;
            living.boundingBoxHeight = living.boundingBoxHeight / living.scale;
            living.scale = 1.0F;
        }
        // The game centres the box's middle: a middle lower than it is vanilla's offsetY, in blocks
        Vector3f translation = new Vector3f(0, state.boundingBoxHeight / 2 + (centreY - h / 2) / scale, 0);
        *///?}
        //? if >=1.21.11 <26.1 {
        /*((GuiGraphics) ctx.gui()).submitEntityRenderState(state, scale, translation, rotation, tilt, x0, y0, x1, y1);
        *///?} elif >=26.1 {
        /*((GuiGraphicsExtractor) ctx.gui()).entity(state, scale, translation, rotation, tilt, x0, y0, x1, y1);
        *///?}
    }

    // Vanilla's renderEntityInInventory (javap of 1.15.2, 1.16.5, 1.17.1, 1.19.2 and 1.19.3), its angles a pose's: the
    // feet at (x, y), drawn at partial tick 1 with full light and no shadow; one copy an era, as its calls differ
    //? if >=1.15 <1.16 {
    /*private void ported(LivingEntity e, CgEntityPose pose, float ageTicks, int x, int y, int scale) {
        RenderSystem.pushMatrix();
        RenderSystem.translatef((float) x, (float) y, 1050.0F);
        RenderSystem.scalef(1.0F, 1.0F, -1.0F);
        PoseStack p = new PoseStack();
        p.translate(0.0D, 0.0D, 1000.0D);
        p.scale((float) scale, (float) scale, (float) scale);
        Quaternion rotation = Vector3f.ZP.rotationDegrees(180.0F);
        Quaternion tilt = Vector3f.XP.rotationDegrees(pose.tilt());
        rotation.mul(tilt);
        p.mulPose(rotation);
        poses.apply(e, 180 + pose.bodyYaw(), 180 + pose.headYaw(), pose.pitch(), pose, ageTicks, false);
        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        tilt.conj();
        dispatcher.overrideCameraOrientation(tilt);
        dispatcher.setRenderShadow(false);
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        drawn = e;
        drawStack = p;
        drawBuffers = buffers;
        try {
            render.run();
            buffers.endBatch();
        } finally {
            dispatcher.setRenderShadow(true);
            poses.restore(e);
            drawn = null;
            drawStack = null;
            drawBuffers = null;
            RenderSystem.popMatrix();
        }
    }
    *///?} elif >=1.16 <1.17 {
    /*private void ported(LivingEntity e, CgEntityPose pose, float ageTicks, int x, int y, int scale) {
        RenderSystem.pushMatrix();
        RenderSystem.translatef((float) x, (float) y, 1050.0F);
        RenderSystem.scalef(1.0F, 1.0F, -1.0F);
        PoseStack p = new PoseStack();
        p.translate(0.0D, 0.0D, 1000.0D);
        p.scale((float) scale, (float) scale, (float) scale);
        Quaternion rotation = Vector3f.ZP.rotationDegrees(180.0F);
        Quaternion tilt = Vector3f.XP.rotationDegrees(pose.tilt());
        rotation.mul(tilt);
        p.mulPose(rotation);
        poses.apply(e, 180 + pose.bodyYaw(), 180 + pose.headYaw(), pose.pitch(), pose, ageTicks, false);
        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        tilt.conj();
        dispatcher.overrideCameraOrientation(tilt);
        dispatcher.setRenderShadow(false);
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        drawn = e;
        drawStack = p;
        drawBuffers = buffers;
        try {
            RenderSystem.runAsFancy(render);
            buffers.endBatch();
        } finally {
            dispatcher.setRenderShadow(true);
            poses.restore(e);
            drawn = null;
            drawStack = null;
            drawBuffers = null;
            RenderSystem.popMatrix();
        }
    }
    *///?} elif >=1.17 <1.19.3 {
    /*private void ported(LivingEntity e, CgEntityPose pose, float ageTicks, int x, int y, int scale) {
        PoseStack view = RenderSystem.getModelViewStack();
        view.pushPose();
        view.translate(x, y, 1050.0D);
        view.scale(1.0F, 1.0F, -1.0F);
        RenderSystem.applyModelViewMatrix();
        PoseStack p = new PoseStack();
        p.translate(0.0D, 0.0D, 1000.0D);
        p.scale((float) scale, (float) scale, (float) scale);
        Quaternion rotation = Vector3f.ZP.rotationDegrees(180.0F);
        Quaternion tilt = Vector3f.XP.rotationDegrees(pose.tilt());
        rotation.mul(tilt);
        p.mulPose(rotation);
        poses.apply(e, 180 + pose.bodyYaw(), 180 + pose.headYaw(), pose.pitch(), pose, ageTicks, false);
        Lighting.setupForEntityInInventory();
        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        tilt.conj();
        dispatcher.overrideCameraOrientation(tilt);
        dispatcher.setRenderShadow(false);
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        drawn = e;
        drawStack = p;
        drawBuffers = buffers;
        try {
            RenderSystem.runAsFancy(render);
            buffers.endBatch();
        } finally {
            dispatcher.setRenderShadow(true);
            poses.restore(e);
            drawn = null;
            drawStack = null;
            drawBuffers = null;
            view.popPose();
            RenderSystem.applyModelViewMatrix();
            Lighting.setupFor3DItems();
        }
    }
    *///?} elif >=1.19.3 <1.19.4 {
    /*private void ported(LivingEntity e, CgEntityPose pose, float ageTicks, int x, int y, int scale) {
        PoseStack view = RenderSystem.getModelViewStack();
        view.pushPose();
        view.translate(x, y, 1050.0D);
        view.scale(1.0F, 1.0F, -1.0F);
        RenderSystem.applyModelViewMatrix();
        PoseStack p = new PoseStack();
        p.translate(0.0D, 0.0D, 1000.0D);
        p.scale((float) scale, (float) scale, (float) scale);
        Quaternionf rotation = new Quaternionf().rotateZ((float) Math.PI);
        Quaternionf tilt = new Quaternionf().rotateX(pose.tilt() * DEG);
        rotation.mul(tilt);
        p.mulPose(rotation);
        poses.apply(e, 180 + pose.bodyYaw(), 180 + pose.headYaw(), pose.pitch(), pose, ageTicks, false);
        Lighting.setupForEntityInInventory();
        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        tilt.conjugate();
        dispatcher.overrideCameraOrientation(tilt);
        dispatcher.setRenderShadow(false);
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        drawn = e;
        drawStack = p;
        drawBuffers = buffers;
        try {
            RenderSystem.runAsFancy(render);
            buffers.endBatch();
        } finally {
            dispatcher.setRenderShadow(true);
            poses.restore(e);
            drawn = null;
            drawStack = null;
            drawBuffers = null;
            view.popPose();
            RenderSystem.applyModelViewMatrix();
            Lighting.setupFor3DItems();
        }
    }
    *///?}

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
        /*// The game centres the box's middle: a middle lower than it is vanilla's offsetY, in blocks
        InventoryScreen.renderEntityInInventory((GuiGraphics) ctx.gui(), x0, y0, x1, y1, scale / e.getScale(),
                new Vector3f(0, e.getBbHeight() / 2 + (cy - (y0 + y1) / 2f) / scale, 0), rotation, tilt, e);
        *///?}
    }

    // InventoryScreen's: to 1.20.1 the player's feet at (51, 75) of the panel, 3 units over its 70-unit box's bottom,
    // the pointer measured from 50 units over them; from 1.20.2 the box's middle, the player lowered by offsetY 0.0625
    @Override
    public float inventoryCentreY(float h, float entityHeight, float scale) {
        //? if <1.20.2 {
        return h - 3 - entityHeight * scale / 2;
        //?} else {
        /*return h / 2 + scale * 0.0625F;
        *///?}
    }

    @Override
    public float inventoryLookY(float h) {
        //? if <1.20.2 {
        return h - 53;
        //?} else {
        /*return h / 2;
        *///?}
    }
}
