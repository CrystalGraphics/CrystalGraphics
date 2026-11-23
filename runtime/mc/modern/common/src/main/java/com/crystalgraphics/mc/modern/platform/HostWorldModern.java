package com.crystalgraphics.mc.modern.platform;

import javax.annotation.Nullable;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.crystalgraphics.mc.block.CgBlockState;
import com.crystalgraphics.mc.block.CgBlocks;
import com.crystalgraphics.mc.entity.CgEntityRef;
import com.crystalgraphics.mc.item.CgItemStack;
import com.crystalgraphics.mc.item.CgItems;
import com.crystalgraphics.mc.render.entity.CgEntityPose;
import com.crystalgraphics.mc.render.world.CgHostWorld;
import com.crystalgraphics.mc.render.world.CgItemDisplay;
import com.crystalgraphics.shape.bounds.CgAABB;

import net.minecraft.client.Minecraft;
//? if >=1.15 {
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.world.phys.AABB;
//?}
//? if >=1.15 <1.17 {
/*import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
*///?}
//? if >=1.15 <26.2 {
import net.minecraft.client.renderer.LevelRenderer;
//?} elif >=26.2 {
/*import net.minecraft.util.LightCoordsUtil;
*///?}
//? if >=1.15 <1.21.10 {
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.block.RenderShape;
//?}
//? if >=1.15 <1.19.3 {
/*import com.mojang.math.Quaternion;
*///?}
//? if >=1.15 <1.19.4 {
/*import net.minecraft.client.renderer.block.model.ItemTransforms;
*///?}
//? if >=1.19.3 {
import com.mojang.blaze3d.systems.RenderSystem;
//?}
//? if >=1.19.4 {
import net.minecraft.world.item.ItemDisplayContext;
//?}
//? if >=1.21.10 {
/*import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.phys.Vec3;
*///?}
//? if >=1.21.10 <26.1 {
/*import net.minecraft.client.renderer.state.CameraRenderState;
*///?} elif >=26.1 {
/*import net.minecraft.client.renderer.state.level.CameraRenderState;
*///?}

/**
 * {@link CgHostWorld} on the modern tree, client: Minecraft's item, entity and block renderers drawing the client
 * scene's host-drawn components into its world. To 1.21.8 they draw at once through its entity buffers at the opaque
 * hook, the pose {@code modelView⁻¹ · view · model} from 1.19.3 (right whichever of the pose stack and
 * {@code RenderSystem}'s model-view holds the camera's rotation), the level's rotation replayed before it. From 1.21.10
 * they are submitted into the level's own collector as it submits its entities ({@code WorldSubmitHook}), under its
 * camera, and rendered in its feature frame with them.
 */
public final class HostWorldModern implements CgHostWorld.Host {

    private final EntityPicturesModern entities = new EntityPicturesModern();
    private final Matrix4f base = new Matrix4f();
    private final Quaternionf q = new Quaternionf();
    private final Vector3f v = new Vector3f();
    private double camX, camY, camZ;
    private float partialTick;
    private boolean fullLight;
    //? if >=1.15 {
    private final PoseStack pose = new PoseStack();
    //?}
    //? if >=1.21.10 {
    /*// The level's own collector and camera, as it submits its entities: set by submitting, read by the draw
    @Nullable private SubmitNodeCollector collector;
    @Nullable private CameraRenderState camera;
    *///?}

    /** A stack, and from 1.21.10 its render state, updated each frame for the model's animation. */
    private static final class Item {
        final Object stack;
        @Nullable final Object state;

        Item(Object stack, @Nullable Object state) {
            this.stack = stack;
            this.state = state;
        }
    }

    /** A block and its block entity, made once a component. */
    private static final class Block {
        final Object state;
        @Nullable final Object blockEntity;

        Block(Object state, @Nullable Object blockEntity) {
            this.state = state;
            this.blockEntity = blockEntity;
        }
    }

    @Override
    public String absent() {
        //? if <1.15 {
        /*return "no entity buffers before 1.15";
        *///?} else {
        return null;
        //?}
    }

    @Override
    public void begin(Matrix4fc view, double camX, double camY, double camZ, float partialTick, boolean fullLight) {
        this.fullLight = fullLight;
        this.camX = camX;
        this.camY = camY;
        this.camZ = camZ;
        this.partialTick = partialTick;
        //? if >=1.19.3 <1.20.6 {
        base.set(RenderSystem.getModelViewMatrix()).invert().mul(view);
        //?} elif >=1.20.6 <1.21.10 {
        /*base.set(RenderSystem.getModelViewStack()).invert().mul(view);
        *///?} elif >=1.21.10 {
        /*// Submitted as the level submits its entities: camera-relative under an identity pose, turned at render
        base.identity();
        *///?} else {
        /*base.set(view);
        *///?}
    }

    @Override
    public Object item(CgItemStack stack, @Nullable Object previous) {
        if (previous != null) return previous;
        //? if >=1.15 {
        ItemStack s = CgItems.toHost(stack);
        if (s == null || s.isEmpty()) return null;
        //?}
        //? if >=1.15 <1.21.10 {
        return new Item(s, null);
        //?} elif >=1.21.10 {
        /*return new Item(s, new ItemStackRenderState());
        *///?} else {
        /*return null;
        *///?}
    }

    @Override
    public Object entity(CgEntityRef ref, @Nullable Object previous) {
        return entities.entity(ref, previous);
    }

    @Override
    public Object block(CgBlockState state, @Nullable Object previous) {
        if (previous != null) return previous;
        //? if >=1.15 {
        BlockState s = CgBlocks.toHost(state);
        return new Block(s, BlockPicturesModern.blockEntity(s, state));
        //?} else {
        /*return state;
        *///?}
    }

    @Override
    public void drawItem(Object item, CgItemDisplay display, Matrix4fc model) {
        //? if >=1.15 {
        Minecraft mc = Minecraft.getInstance();
        Item it = (Item) item;
        ItemStack stack = (ItemStack) it.stack;
        PoseStack p = push(model);
        int light = light(model);
        //?}
        //? if >=1.15 <1.17 {
        /*mc.getItemRenderer().renderStatic(stack, ItemTransforms.TransformType.valueOf(display.name()), light,
                OverlayTexture.NO_OVERLAY, p, mc.renderBuffers().bufferSource());
        *///?} elif >=1.17 <1.19.4 {
        /*mc.getItemRenderer().renderStatic(stack, ItemTransforms.TransformType.valueOf(display.name()), light,
                OverlayTexture.NO_OVERLAY, p, mc.renderBuffers().bufferSource(), 0);
        *///?} elif >=1.19.4 <1.21.10 {
        mc.getItemRenderer().renderStatic(stack, ItemDisplayContext.valueOf(display.name()), light,
                OverlayTexture.NO_OVERLAY, p, mc.renderBuffers().bufferSource(), mc.level, 0);
        //?} elif >=1.21.10 {
        /*ItemStackRenderState state = (ItemStackRenderState) it.state;
        mc.getItemModelResolver().updateForTopItem(state, stack, ItemDisplayContext.valueOf(display.name()), mc.level, null, 0);
        state.submit(p, collector(mc), light, OverlayTexture.NO_OVERLAY, 0);
        *///?}
        //? if >=1.15 {
        pose.popPose();
        //?}
    }

    @Override
    public void drawEntity(Object entity, CgEntityPose posed, float ageTicks, Matrix4fc model) {
        //? if >=1.15 {
        Minecraft mc = Minecraft.getInstance();
        Entity e = (Entity) entity;
        PoseStack p = push(model);
        int light = light(model);
        //?}
        //? if >=1.15 <1.21.10 {
        // Posed as NR4's pictures pose one: its own angles set for the draw, put back after (a live entity keeps its own)
        float yaw = yaw(e), pitch = pitch(e), yawO = e.yRotO, pitchO = e.xRotO;
        int age = e.tickCount;
        float body = 0, bodyO = 0, head = 0, headO = 0;
        LivingEntity living = e instanceof LivingEntity ? (LivingEntity) e : null;
        if (living != null) {
            body = living.yBodyRot;
            bodyO = living.yBodyRotO;
            head = living.yHeadRot;
            headO = living.yHeadRotO;
            living.yBodyRot = living.yBodyRotO = posed.bodyYaw();
            living.yHeadRot = living.yHeadRotO = posed.headYaw();
        }
        yaw(e, posed.headYaw());
        pitch(e, posed.pitch());
        e.yRotO = posed.headYaw();
        e.xRotO = posed.pitch();
        e.tickCount = (int) ageTicks;
        try {
            render(mc, e, p, light);
        } finally {
            if (living != null) {
                living.yBodyRot = body;
                living.yBodyRotO = bodyO;
                living.yHeadRot = head;
                living.yHeadRotO = headO;
            }
            yaw(e, yaw);
            pitch(e, pitch);
            e.yRotO = yawO;
            e.xRotO = pitchO;
            e.tickCount = age;
        }
        //?} elif >=1.21.10 {
        /*// Its render state, posed as the inventory's 1.21.11 code poses one, lit where the component stands
        EntityRenderState state = mc.getEntityRenderDispatcher().getRenderer(e).createRenderState(e, partialTick);
        if (state instanceof LivingEntityRenderState) {
            LivingEntityRenderState living = (LivingEntityRenderState) state;
            living.bodyRot = posed.bodyYaw();
            living.yRot = posed.headYaw() - posed.bodyYaw();
            living.xRot = posed.pitch();
        }
        state.ageInTicks = ageTicks;
        state.lightCoords = light;
        state.x = state.y = state.z = 0;
        mc.getEntityRenderDispatcher().submit(state, camera, 0, 0, 0, p, collector(mc));
        *///?}
        //? if >=1.15 {
        pose.popPose();
        //?}
    }

    @Override
    public void drawBlock(Object block, Matrix4fc model) {
        //? if >=1.15 {
        Minecraft mc = Minecraft.getInstance();
        Block b = (Block) block;
        BlockState state = (BlockState) b.state;
        PoseStack p = push(model);
        int light = light(model);
        //?}
        //? if >=1.15 <1.21.10 {
        MultiBufferSource buffers = mc.renderBuffers().bufferSource();
        // A block its block entity draws (a chest) has no model shape: its renderer draws it, with the state's data
        if (state.getRenderShape() == RenderShape.MODEL) {
            mc.getBlockRenderer().renderSingleBlock(state, p, buffers, light, OverlayTexture.NO_OVERLAY);
        }
        if (b.blockEntity != null) BlockPicturesModern.renderEntity((BlockEntity) b.blockEntity, p, buffers, light);
        //?} elif >=1.21.10 {
        /*// As a piston draws a block out of the level: lit and tinted where the component stands
        BlockPos at = blockAt(model);
        MovingBlockRenderState moving = new MovingBlockRenderState();
        moving.blockState = state;
        moving.blockPos = at;
        moving.randomSeedPos = at;
        moving.biome = mc.level.getBiome(at);
        *///?}
        //? if >=1.21.10 <26.1 {
        /*moving.level = mc.level;
        *///?} elif >=26.1 {
        /*moving.lightEngine = mc.level.getLightEngine();
        moving.cardinalLighting = mc.level.cardinalLighting();
        *///?}
        //? if >=1.21.10 {
        /*SubmitNodeCollector collector = collector(mc);
        *///?}
        //? if >=1.21.10 <26.2 {
        /*collector.submitMovingBlock(p, moving);
        *///?} elif >=26.2 {
        /*collector.submitMovingBlock(p, moving, 0);
        *///?}
        //? if >=1.21.10 {
        /*if (b.blockEntity != null) {
            BlockEntityRenderState be = blockEntityState(mc, (BlockEntity) b.blockEntity);
            if (be != null) {
                be.lightCoords = light;
                mc.getBlockEntityRenderDispatcher().submit(be, p, collector, camera);
            }
        }
        *///?}
        //? if >=1.15 {
        pose.popPose();
        //?}
    }

    /**
     * An entity's box as its renderer culls it, inflated by half a block, around its feet; a block's cube with a block
     * around it, as a banner draws above its block and Minecraft culls block entities by their section.
     */
    @Override
    public boolean bounds(Object drawn, CgAABB out) {
        //? if >=1.15 {
        if (drawn instanceof Entity) {
            Entity e = (Entity) drawn;
            //? if <1.21.3 {
            if (e.noCulling) return false;
            AABB box = e.getBoundingBoxForCulling();
            //?} else {
            /*// The renderer's culling box is its own (protected), Entity's box by default
            AABB box = e.getBoundingBox();
            *///?}
            if (box.hasNaN() || box.getSize() == 0.0) {
                out.set(-2, -2, -2, 2, 2, 2);
                return true;
            }
            out.set(box);
            out.set(out.minX() - e.getX() - 0.5, out.minY() - e.getY() - 0.5, out.minZ() - e.getZ() - 0.5,
                    out.maxX() - e.getX() + 0.5, out.maxY() - e.getY() + 0.5, out.maxZ() - e.getZ() + 0.5);
            return true;
        }
        if (drawn instanceof Block) {
            Object be = ((Block) drawn).blockEntity;
            if (be != null && offScreen((BlockEntity) be)) return false;
            out.set(-1, -1, -1, 2, 2, 2);
            return true;
        }
        //?}
        return false;
    }

    /** An entity's own render distance (its size and the player's entity distance); a block entity's renderer's. */
    @Override
    @SuppressWarnings("rawtypes")
    public boolean inRange(Object drawn, double distanceSq) {
        //? if >=1.15 {
        if (drawn instanceof Entity) return ((Entity) drawn).shouldRenderAtSqrDistance(distanceSq);
        if (drawn instanceof Block && ((Block) drawn).blockEntity != null) {
            BlockEntityRenderer renderer = renderer((BlockEntity) ((Block) drawn).blockEntity);
            if (renderer == null) return true;
            //? if >=1.17 {
            double far = renderer.getViewDistance();
            //?} else {
            /*double far = 64;
            *///?}
            return distanceSq < far * far;
        }
        //?}
        return true;
    }

    //? if >=1.15 {
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean offScreen(BlockEntity be) {
        BlockEntityRenderer renderer = renderer(be);
        if (renderer == null) return false;
        //? if <1.21.6 {
        return renderer.shouldRenderOffScreen(be);
        //?} else {
        /*return renderer.shouldRenderOffScreen();
        *///?}
    }

    // Raw: one type argument to 1.21.11, two from 26.1
    @Nullable
    @SuppressWarnings("rawtypes")
    private static BlockEntityRenderer renderer(BlockEntity be) {
        //? if <1.17 {
        /*return BlockEntityRenderDispatcher.instance.getRenderer(be);
        *///?} else {
        return Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(be);
        //?}
    }
    //?}

    @Override
    public void end() {
        //? if >=1.15 <1.21.10 {
        Minecraft.getInstance().renderBuffers().bufferSource().endBatch();
        //?} elif >=1.21.10 {
        /*// The level renders its submits, ours among them, in its own feature frame
        collector = null;
        camera = null;
        *///?}
    }

    //? if >=1.15 {
    // The pose for a camera-relative model: base · model, its normal matrix with it
    private PoseStack push(Matrix4fc model) {
        pose.pushPose();
        //? if >=1.19.3 {
        PoseStack.Pose last = pose.last();
        last.pose().set(base).mul(model);
        last.normal().set(last.pose()).invert().transpose();
        //?} else {
        /*// Mojang's matrices here: the view's rotation and the model as translation, rotation and scale
        base.getNormalizedRotation(q);
        pose.mulPose(new Quaternion(q.x, q.y, q.z, q.w));
        model.getTranslation(v);
        pose.translate(v.x, v.y, v.z);
        model.getNormalizedRotation(q);
        pose.mulPose(new Quaternion(q.x, q.y, q.z, q.w));
        model.getScale(v);
        pose.scale(v.x, v.y, v.z);
        *///?}
        return pose;
    }

    private BlockPos blockAt(Matrix4fc model) {
        return new BlockPos((int) Math.floor(model.m30() + camX), (int) Math.floor(model.m31() + camY),
                (int) Math.floor(model.m32() + camZ));
    }

    // Minecraft's light at the model's block, as an entity standing there is lit
    private int light(Matrix4fc model) {
        if (fullLight) return 0xF000F0;
        //? if <26.1 {
        return LevelRenderer.getLightColor(Minecraft.getInstance().level, blockAt(model));
        //?} elif <26.2 {
        /*return LevelRenderer.getLightCoords(Minecraft.getInstance().level, blockAt(model));
        *///?} else {
        /*return LightCoordsUtil.getLightCoords(Minecraft.getInstance().level, blockAt(model));
        *///?}
    }
    //?}

    //? if >=1.21.10 {
    /*// Host side: the level's collector and camera, as LevelRenderer.submitEntities returns
    public void submitting(SubmitNodeCollector collector, CameraRenderState camera) {
        this.collector = collector;
        this.camera = camera;
    }

    private SubmitNodeCollector collector(Minecraft mc) {
        return collector;
    }
    *///?}
    //? if >=1.21.10 {
    /*// Extracted by its renderer straight: the dispatcher's own extraction culls by distance from the block entity's
    // position, and a component's block entity stands at the origin, never in the level
    @Nullable
    @SuppressWarnings({"rawtypes", "unchecked"})
    private BlockEntityRenderState blockEntityState(Minecraft mc, BlockEntity be) {
        BlockEntityRenderer renderer = mc.getBlockEntityRenderDispatcher().getRenderer(be);
        if (renderer == null) return null;
        BlockEntityRenderState state = renderer.createRenderState();
        renderer.extractRenderState(be, state, partialTick, camera.pos, null);
        return state;
    }
    *///?}

    //? if >=1.15 <1.21.10 {
    private void render(Minecraft mc, Entity e, PoseStack p, int light) {
        //? if <1.21.3 {
        mc.getEntityRenderDispatcher().render(e, 0, 0, 0, yaw(e), partialTick, p, mc.renderBuffers().bufferSource(), light);
        //?} else {
        /*mc.getEntityRenderDispatcher().render(e, 0, 0, 0, partialTick, p, mc.renderBuffers().bufferSource(), light);
        *///?}
    }

    private static float yaw(Entity e) {
        //? if >=1.17 {
        return e.getYRot();
        //?} else {
        /*return e.yRot;
        *///?}
    }

    private static void yaw(Entity e, float yaw) {
        //? if >=1.17 {
        e.setYRot(yaw);
        //?} else {
        /*e.yRot = yaw;
        *///?}
    }

    private static float pitch(Entity e) {
        //? if >=1.17 {
        return e.getXRot();
        //?} else {
        /*return e.xRot;
        *///?}
    }

    private static void pitch(Entity e, float pitch) {
        //? if >=1.17 {
        e.setXRot(pitch);
        //?} else {
        /*e.xRot = pitch;
        *///?}
    }
    //?}
}
