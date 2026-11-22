package com.crystalgraphics.mc.modern.platform;

import javax.annotation.Nullable;

import com.crystalgraphics.mc.block.CgBlockState;
import com.crystalgraphics.mc.block.CgBlocks;
import com.crystalgraphics.mc.item.CgItemStack;
import com.crystalgraphics.mc.item.CgItems;
import com.crystalgraphics.mc.render.block.CgBlockPictures;
import com.crystalgraphics.mc.render.custom.CgHostDrawContext;
import com.crystalgraphics.serialization.nbt.CgNbt;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
//? if >=1.15 <1.21.9 {
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.level.block.RenderShape;
//?}
//? if >=1.15 {
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
//?}
//? if >=1.15 <1.17 {
/*import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
*///?}
//? if >=1.15 <1.19.3 {
/*import com.mojang.math.Vector3f;
*///?}
//? if >=1.19.3 <1.21.9 {
import com.mojang.math.Axis;
//?}
//? if >=1.21.5 <1.21.9 {
/*import net.minecraft.world.phys.Vec3;
*///?}
//? if >=1.21.6 {
/*import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
*///?}
//? if >=1.21.6 <26.1 {
/*import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.item.ItemStack;
*///?}
//? if >=26.1 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;
*///?}

/**
 * {@link CgBlockPictures} on the modern tree, client. To 1.21.5 the block model in a block item's slot view, its block
 * entity made from the state's data and drawn by its own renderer; from 1.21.6, whose GUI is recorded with no 3D pose,
 * the block's item through the GUI's item path (NR4 decision 3).
 */
public final class BlockPicturesModern implements CgBlockPictures.Host {

    /** Full light, as a GUI item draws. */
    private static final int LIGHT = 15728880;

    /** The game's objects for one picture: its state, a block entity where it draws one, its item from 1.21.6. */
    private static final class Prepared {
        @Nullable final Object state, blockEntity, item;

        Prepared(@Nullable Object state, @Nullable Object blockEntity, @Nullable Object item) {
            this.state = state;
            this.blockEntity = blockEntity;
            this.item = item;
        }
    }

    @Override
    public String absent() {
        //? if <1.15 {
        /*return "no block pictures before 1.15";
        *///?} else {
        return null;
        //?}
    }

    @Override
    public Object prepare(CgBlockState s, @Nullable Object previous) {
        if (previous != null) return previous;
        //? if <1.15 {
        /*return new Prepared(null, null, null);
        *///?} elif <1.21.6 {
        BlockState state = CgBlocks.toHost(s);
        return new Prepared(state, blockEntity(state, s), null);
        //?} else {
        /*CgBlocks.toHost(s);   // refused by name where the game has not got the block or a property
        ItemStack item = CgItems.toHost(CgItemStack.of(s.block(), 1));
        return new Prepared(null, null, item == null || item.isEmpty() ? null : item);
        *///?}
    }

    @Override
    public void draw(CgHostDrawContext ctx, Object prepared, float x, float y, float size) {
        Prepared p = (Prepared) prepared;
        //? if <1.15 {
        /*return;
        *///?} elif <1.21.6 {
        model(ctx, p, x, y, size);
        //?} elif <26.1 {
        /*if (p.item == null) return;
        GuiGraphics g = (GuiGraphics) ctx.gui();
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(size / 16f);
        g.renderItem((ItemStack) p.item, 0, 0);
        g.pose().popMatrix();
        *///?} else {
        /*if (p.item == null) return;
        GuiGraphicsExtractor g = (GuiGraphicsExtractor) ctx.gui();
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(size / 16f);
        g.item((ItemStack) p.item, 0, 0);
        g.pose().popMatrix();
        *///?}
    }

    // The block in a block item's slot view: turned 30 degrees down and 225 round, at five eighths, centred
    private static void model(CgHostDrawContext ctx, Prepared p, float x, float y, float size) {
        //? if >=1.15 <1.21.6 {
        Minecraft mc = Minecraft.getInstance();
        PoseStack pose = (PoseStack) ctx.poseStack();
        MultiBufferSource buffers = (MultiBufferSource) ctx.buffers();
        BlockState state = (BlockState) p.state;
        pose.pushPose();
        pose.translate(x + size / 2, y + size / 2, 100);
        pose.scale(size, -size, size);
        rotate(pose);
        pose.scale(0.625f, 0.625f, 0.625f);
        pose.translate(-0.5, -0.5, -0.5);
        // A block its block entity draws (a chest) has no model shape: its renderer draws it, with the state's data
        if (state.getRenderShape() == RenderShape.MODEL) {
            mc.getBlockRenderer().renderSingleBlock(state, pose, buffers, LIGHT, OverlayTexture.NO_OVERLAY);
        }
        if (p.blockEntity != null) renderEntity((BlockEntity) p.blockEntity, pose, buffers, LIGHT);
        ((MultiBufferSource.BufferSource) buffers).endBatch();
        pose.popPose();
        //?}
    }

    //? if >=1.15 <1.21.9 {
    private static void rotate(PoseStack pose) {
        //? if <1.19.3 {
        /*pose.mulPose(Vector3f.XP.rotationDegrees(30));
        pose.mulPose(Vector3f.YP.rotationDegrees(225));
        *///?} else {
        pose.mulPose(Axis.XP.rotationDegrees(30));
        pose.mulPose(Axis.YP.rotationDegrees(225));
        //?}
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static void renderEntity(BlockEntity be, PoseStack pose, MultiBufferSource buffers, int light) {
        //? if <1.17 {
        /*BlockEntityRenderer renderer = BlockEntityRenderDispatcher.instance.getRenderer(be);
        *///?} else {
        BlockEntityRenderer renderer = Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(be);
        //?}
        if (renderer == null) return;
        //? if <1.21.5 {
        renderer.render(be, 1.0F, pose, buffers, light, OverlayTexture.NO_OVERLAY);
        //?} else {
        /*renderer.render(be, 1.0F, pose, buffers, light, OverlayTexture.NO_OVERLAY, Vec3.ZERO);
        *///?}
    }
    //?}

    //? if >=1.15 {
    // Made from the state and its data, never in the level (a picture's, or a scene's through HostWorldModern); given the client's level from 1.17, where a chest's
    // renderer reads its facing from the block entity only once it has one
    @Nullable
    static BlockEntity blockEntity(BlockState state, CgBlockState s) {
        if (!(state.getBlock() instanceof EntityBlock)) return null;
        Minecraft mc = Minecraft.getInstance();
        //? if <1.17 {
        /*BlockEntity be = ((EntityBlock) state.getBlock()).newBlockEntity(mc.level);
        *///?} else {
        BlockEntity be = ((EntityBlock) state.getBlock()).newBlockEntity(BlockPos.ZERO, state);
        if (be != null && mc.level != null) be.setLevel(mc.level);
        //?}
        if (be != null && s.hasBlockEntity()) load(be, state, CgNbt.toHost(s.blockEntity()));
        return be;
    }

    private static void load(BlockEntity be, BlockState state, CompoundTag tag) {
        //? if <1.16 {
        /*be.load(tag);
        *///?} elif <1.17 {
        /*be.load(state, tag);
        *///?} elif <1.20.5 {
        be.load(tag);
        //?} elif <1.21.6 {
        /*be.loadWithComponents(tag, Minecraft.getInstance().level.registryAccess());
        *///?} else {
        /*be.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, Minecraft.getInstance().level.registryAccess(),
                tag));
        *///?}
    }
    //?}
}
