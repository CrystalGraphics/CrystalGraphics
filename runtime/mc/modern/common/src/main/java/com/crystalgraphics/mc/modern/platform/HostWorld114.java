package com.crystalgraphics.mc.modern.platform;

//? if <1.15 {
/*import java.nio.FloatBuffer;

import javax.annotation.Nullable;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.crystalgraphics.mc.block.CgBlockState;
import com.crystalgraphics.mc.block.CgBlocks;
import com.crystalgraphics.mc.entity.CgEntityRef;
import com.crystalgraphics.mc.item.CgItemStack;
import com.crystalgraphics.mc.item.CgItems;
import com.crystalgraphics.mc.render.entity.CgEntityPose;
import com.crystalgraphics.mc.render.world.CgHostWorld;
import com.crystalgraphics.mc.render.world.CgItemDisplay;
import com.crystalgraphics.serialization.nbt.CgNbt;
import com.mojang.blaze3d.platform.GLX;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Lighting;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

// CgHostWorld on the modern tree's 1.13.2 to 1.14.4, client: Minecraft's renderers before the pose stack, drawing the
// client scene's host-drawn components at the opaque hook under GL's model-view loaded with view * model, lit as
// renderEntities lights an entity: lighting turned on under the view, the lightmap at its block. Entities from data
// from 1.14.3; none on 1.13.2.
public final class HostWorld114 implements CgHostWorld.Host {

    private final EntityPicturesModern entities = new EntityPicturesModern();
    private final Matrix4f view = new Matrix4f();
    private final FloatBuffer matrix = BufferUtils.createFloatBuffer(16);
    private double camX, camY, camZ;
    private float partialTick;

    // A block and its block entity, made once a component
    private static final class Block {
        final BlockState state;
        @Nullable final BlockEntity blockEntity;

        Block(BlockState state, @Nullable BlockEntity blockEntity) {
            this.state = state;
            this.blockEntity = blockEntity;
        }
    }

    @Override
    public String absent() {
        return null;
    }

    @Override
    public void begin(Matrix4fc view, double camX, double camY, double camZ, float partialTick) {
        this.view.set(view);
        this.camX = camX;
        this.camY = camY;
        this.camZ = camZ;
        this.partialTick = partialTick;
    }

    @Override
    public Object item(CgItemStack stack, @Nullable Object previous) {
        if (previous != null) return previous;
        ItemStack s = CgItems.toHost(stack);
        return s == null || s.isEmpty() ? null : s;
    }

    @Override
    public Object entity(CgEntityRef ref, @Nullable Object previous) {
        return entities.entity(ref, previous);
    }

    @Override
    public Object block(CgBlockState state, @Nullable Object previous) {
        if (previous != null) return previous;
        BlockState s = CgBlocks.toHost(state);
        BlockEntity be = null;
        Minecraft mc = Minecraft.getInstance();
        if (s.getBlock() instanceof EntityBlock && mc.level != null) {
            // Made from the state and its data, never in the level
            be = ((EntityBlock) s.getBlock()).newBlockEntity(mc.level);
            if (be != null) {
                be.setLevel(mc.level);
                if (state.hasBlockEntity()) be.load(CgNbt.toHost(state.blockEntity()));
            }
        }
        return new Block(s, be);
    }

    @Override
    public void drawItem(Object item, CgItemDisplay display, Matrix4fc model) {
        push(model);
        Minecraft.getInstance().getItemRenderer().renderStatic((ItemStack) item, ItemTransforms.TransformType.valueOf(display.name()));
        pop();
    }

    @Override
    public void drawEntity(Object entity, CgEntityPose posed, float ageTicks, Matrix4fc model) {
        Entity e = (Entity) entity;
        push(model);
        // Its own angles set for the draw and put back, as NR4's pictures pose one
        float yaw = e.yRot, yawO = e.yRotO, pitch = e.xRot, pitchO = e.xRotO;
        int age = e.tickCount;
        LivingEntity living = e instanceof LivingEntity ? (LivingEntity) e : null;
        float body = 0, bodyO = 0, head = 0, headO = 0;
        if (living != null) {
            body = living.yBodyRot;
            bodyO = living.yBodyRotO;
            head = living.yHeadRot;
            headO = living.yHeadRotO;
            living.yBodyRot = living.yBodyRotO = posed.bodyYaw();
            living.yHeadRot = living.yHeadRotO = posed.headYaw();
        }
        e.yRot = e.yRotO = posed.headYaw();
        e.xRot = e.xRotO = posed.pitch();
        e.tickCount = (int) ageTicks;
        try {
            Minecraft.getInstance().getEntityRenderDispatcher().render(e, 0, 0, 0, posed.headYaw(), partialTick, false);
        } finally {
            if (living != null) {
                living.yBodyRot = body;
                living.yBodyRotO = bodyO;
                living.yHeadRot = head;
                living.yHeadRotO = headO;
            }
            e.yRot = yaw;
            e.yRotO = yawO;
            e.xRot = pitch;
            e.xRotO = pitchO;
            e.tickCount = age;
            pop();
        }
    }

    @Override
    public void drawBlock(Object block, Matrix4fc model) {
        Block b = (Block) block;
        push(model);
        // A block its block entity draws (a chest) has no model shape: its renderer draws it, with the state's data
        if (b.state.getRenderShape() == RenderShape.MODEL) Minecraft.getInstance().getBlockRenderer().renderSingleBlock(b.state, 1f);
        if (b.blockEntity != null) BlockEntityRenderDispatcher.instance.render(b.blockEntity, 0, 0, 0, partialTick);
        pop();
    }

    @Override
    public void end() {
        Lighting.turnOff();
    }

    // The view, lit as renderEntities lights under it, then the model; the lightmap at the model's block
    private void push(Matrix4fc model) {
        GL11.glPushMatrix();
        matrix.clear();
        view.get(matrix);
        GL11.glLoadMatrixf(matrix);
        Lighting.turnOn();
        matrix.clear();
        model.get(matrix);
        GL11.glMultMatrixf(matrix);
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            // getLightColor's packing, by hand: 1.13.2 leaves that method unnamed
            BlockPos at = new BlockPos((int) Math.floor(model.m30() + camX), (int) Math.floor(model.m31() + camY),
                    (int) Math.floor(model.m32() + camZ));
            int sky = mc.level.getBrightness(LightLayer.SKY, at), blockLight = mc.level.getBrightness(LightLayer.BLOCK, at);
            GLX.glMultiTexCoord2f(GLX.GL_TEXTURE1, blockLight << 4, sky << 4);
        }
        GlStateManager.color4f(1, 1, 1, 1);
    }

    private static void pop() {
        GL11.glPopMatrix();
    }
}
*///?} else {
/** Only below 1.15: {@code HostWorldModern} draws from there. */
final class HostWorld114 {
}
//?}
