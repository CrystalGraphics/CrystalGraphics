package com.crystalgraphics.mc.legacy.platform;

import java.nio.FloatBuffer;
import java.util.EnumMap;
import java.util.Map;

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
import com.crystalgraphics.mc.legacy.platform.world.ClientLegacy;
import com.crystalgraphics.mc.render.entity.CgEntityPose;
import com.crystalgraphics.mc.render.world.CgHostWorld;
import com.crystalgraphics.mc.render.world.CgItemDisplay;
import com.crystalgraphics.serialization.nbt.CgNbt;

import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.block.model.ItemCameraTransforms;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
//? if <1.9 {
/*import net.minecraft.util.BlockPos;
*///?} else {
import net.minecraft.util.math.BlockPos;
//?}

/**
 * {@link CgHostWorld} on legacy Forge, client: Minecraft's item, entity, block and tile entity renderers drawing the
 * client scene's host-drawn components at the opaque hook, under GL's model-view loaded with {@code view · model}, lit
 * as {@code renderEntities} lights an entity: standard item lighting under the view, the lightmap at its block.
 */
public final class HostWorldLegacy implements CgHostWorld.Host {

    private final EntityPicturesLegacy entities = new EntityPicturesLegacy();
    private final Matrix4f view = new Matrix4f();
    private final FloatBuffer matrix = BufferUtils.createFloatBuffer(16);
    private final Map<CgItemDisplay, ItemCameraTransforms.TransformType> displays = new EnumMap<>(CgItemDisplay.class);
    private double camX, camY, camZ;
    private float partialTick;

    /** A block and its tile entity, made once a component. */
    private static final class Block {
        final IBlockState state;
        @Nullable final TileEntity tile;

        Block(IBlockState state, @Nullable TileEntity tile) {
            this.state = state;
            this.tile = tile;
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
        //? if <1.11 {
        /*return s;
        *///?} else {
        return s == null || s.isEmpty() ? null : s;
        //?}
    }

    @Override
    public Object entity(CgEntityRef ref, @Nullable Object previous) {
        return entities.entity(ref, previous);
    }

    @Override
    public Object block(CgBlockState state, @Nullable Object previous) {
        if (previous != null) return previous;
        IBlockState s = CgBlocks.toHost(state);
        TileEntity tile = null;
        WorldClient world = ClientLegacy.world();
        if (s.getBlock() instanceof ITileEntityProvider && world != null) {
            // Made from the state and its data, never in the world
            tile = ((ITileEntityProvider) s.getBlock()).createNewTileEntity(world, s.getBlock().getMetaFromState(s));
            if (tile != null) {
                if (state.hasBlockEntity()) tile.readFromNBT(CgNbt.toHost(state.blockEntity()));
                //? if <1.10 {
                /*tile.setWorldObj(world);
                *///?} else {
                tile.setWorld(world);
                //?}
                tile.setPos(BlockPos.ORIGIN);
            }
        }
        return new Block(s, tile);
    }

    @Override
    public void drawItem(Object item, CgItemDisplay display, Matrix4fc model) {
        push(model);
        Minecraft.getMinecraft().getRenderItem().renderItem((ItemStack) item, transform(display));
        pop();
    }

    @Override
    public void drawEntity(Object entity, CgEntityPose posed, float ageTicks, Matrix4fc model) {
        Entity e = (Entity) entity;
        push(model);
        // Its own angles set for the draw and put back, as NR4's pictures pose one
        float yaw = e.rotationYaw, yawO = e.prevRotationYaw, pitch = e.rotationPitch, pitchO = e.prevRotationPitch;
        int age = e.ticksExisted;
        EntityLivingBase living = e instanceof EntityLivingBase ? (EntityLivingBase) e : null;
        float body = 0, bodyO = 0, head = 0, headO = 0;
        if (living != null) {
            body = living.renderYawOffset;
            bodyO = living.prevRenderYawOffset;
            head = living.rotationYawHead;
            headO = living.prevRotationYawHead;
            living.renderYawOffset = living.prevRenderYawOffset = posed.bodyYaw();
            living.rotationYawHead = living.prevRotationYawHead = posed.headYaw();
        }
        e.rotationYaw = e.prevRotationYaw = posed.headYaw();
        e.rotationPitch = e.prevRotationPitch = posed.pitch();
        e.ticksExisted = (int) ageTicks;
        try {
            //? if <1.10 {
            /*Minecraft.getMinecraft().getRenderManager().doRenderEntity(e, 0, 0, 0, posed.headYaw(), partialTick, false);
            *///?} elif <1.12 {
            /*Minecraft.getMinecraft().getRenderManager().doRenderEntity(e, 0, 0, 0, posed.headYaw(), partialTick, false);
            *///?} else {
            Minecraft.getMinecraft().getRenderManager().renderEntity(e, 0, 0, 0, posed.headYaw(), partialTick, false);
            //?}
        } finally {
            if (living != null) {
                living.renderYawOffset = body;
                living.prevRenderYawOffset = bodyO;
                living.rotationYawHead = head;
                living.prevRotationYawHead = headO;
            }
            e.rotationYaw = yaw;
            e.prevRotationYaw = yawO;
            e.rotationPitch = pitch;
            e.prevRotationPitch = pitchO;
            e.ticksExisted = age;
            pop();
        }
    }

    @Override
    public void drawBlock(Object block, Matrix4fc model) {
        Block b = (Block) block;
        push(model);
        TileEntityRendererDispatcher tiles = TileEntityRendererDispatcher.instance;
        // A block its tile entity draws (a chest) has its model drawn by that renderer, with the state's data
        //? if <1.12 {
        /*boolean byTile = b.tile != null && tiles.getSpecialRenderer(b.tile) != null;
        *///?} else {
        boolean byTile = b.tile != null && tiles.getRenderer(b.tile) != null;
        //?}
        if (!byTile) Minecraft.getMinecraft().getBlockRendererDispatcher().renderBlockBrightness(b.state, 1f);
        if (byTile) {
            //? if <1.12 {
            /*tiles.renderTileEntityAt(b.tile, 0, 0, 0, partialTick);
            *///?} else {
            tiles.render(b.tile, 0, 0, 0, partialTick);
            //?}
        }
        pop();
    }

    @Override
    public void end() {
        RenderHelper.disableStandardItemLighting();
    }

    // The view, lit as renderEntities lights under it, then the model; the lightmap at the model's block
    private void push(Matrix4fc model) {
        GL11.glPushMatrix();
        matrix.clear();
        view.get(matrix);
        GL11.glLoadMatrix(matrix);
        RenderHelper.enableStandardItemLighting();
        matrix.clear();
        model.get(matrix);
        GL11.glMultMatrix(matrix);
        WorldClient world = ClientLegacy.world();
        if (world != null) {
            int light = world.getCombinedLight(new BlockPos(Math.floor(model.m30() + camX), Math.floor(model.m31() + camY),
                    Math.floor(model.m32() + camZ)), 0);
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, light % 65536, light / 65536);
        }
        GL11.glColor4f(1, 1, 1, 1);
    }

    private static void pop() {
        GL11.glPopMatrix();
    }

    // 1.8.9 has one third and one first person transform: a hand's display is its two-handed name there
    private ItemCameraTransforms.TransformType transform(CgItemDisplay display) {
        ItemCameraTransforms.TransformType t = displays.get(display);
        if (t != null) return t;
        String name = display.name();
        for (ItemCameraTransforms.TransformType each : ItemCameraTransforms.TransformType.values()) {
            if (each.name().equals(name)) t = each;
        }
        if (t == null) {
            String shared = name.replace("_LEFT_HAND", "").replace("_RIGHT_HAND", "");
            for (ItemCameraTransforms.TransformType each : ItemCameraTransforms.TransformType.values()) {
                if (each.name().equals(shared)) t = each;
            }
        }
        if (t == null) t = ItemCameraTransforms.TransformType.NONE;
        displays.put(display, t);
        return t;
    }
}
