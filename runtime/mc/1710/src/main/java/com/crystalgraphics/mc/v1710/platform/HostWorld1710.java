package com.crystalgraphics.mc.v1710.platform;

import java.nio.FloatBuffer;

import javax.annotation.Nullable;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import com.crystalgraphics.mc.block.CgBlockState;
import com.crystalgraphics.mc.item.CgItemStack;
import com.crystalgraphics.mc.item.CgItems;
import com.crystalgraphics.mc.render.entity.CgEntityPose;
import com.crystalgraphics.mc.render.world.CgHostWorld;
import com.crystalgraphics.mc.render.world.CgItemDisplay;
import com.crystalgraphics.shape.bounds.CgAABB;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraftforge.client.IItemRenderer.ItemRenderType;

/**
 * {@link CgHostWorld} on 1.7.10, client: Minecraft's item and entity renderers drawing the client scene's host-drawn
 * components at the opaque hook, under GL's model-view loaded with {@code view · model}, lit as {@code renderEntities}
 * lights an entity. 1.7.10 has no block states: a block component draws nothing. An item draws as Forge's
 * {@code IItemRenderer} types name its uses, held by the player.
 */
public final class HostWorld1710 implements CgHostWorld.Host {

    private final EntityPoses1710 poses = new EntityPoses1710();
    private final Matrix4f view = new Matrix4f();
    private final FloatBuffer matrix = BufferUtils.createFloatBuffer(16);
    private double camX, camY, camZ;
    private float partialTick;
    private boolean fullLight;

    @Override
    public String absent() {
        return null;
    }

    @Override
    public void begin(Matrix4fc view, double camX, double camY, double camZ, float partialTick, boolean fullLight) {
        this.fullLight = fullLight;
        this.view.set(view);
        this.camX = camX;
        this.camY = camY;
        this.camZ = camZ;
        this.partialTick = partialTick;
    }

    @Override
    public Object item(CgItemStack stack, @Nullable Object previous) {
        return previous != null ? previous : CgItems.toHost(stack);
    }

    @Override
    public Object block(CgBlockState state, @Nullable Object previous) {
        return null;
    }

    /** An entity's box inflated by half a block, around its feet, as 1.7.10's renderer culls it. */
    @Override
    public boolean bounds(Object drawn, CgAABB out) {
        if (!(drawn instanceof Entity)) return false;
        Entity e = (Entity) drawn;
        if (e.ignoreFrustumCheck) return false;
        out.set(e.boundingBox);
        out.set(out.minX() - e.posX - 0.5, out.minY() - e.posY - 0.5, out.minZ() - e.posZ - 0.5,
                out.maxX() - e.posX + 0.5, out.maxY() - e.posY + 0.5, out.maxZ() - e.posZ + 0.5);
        return true;
    }

    /** An entity's own render distance. */
    @Override
    public boolean inRange(Object drawn, double distanceSq) {
        return !(drawn instanceof Entity) || ((Entity) drawn).isInRangeToRenderDist(distanceSq);
    }

    @Override
    public void drawItem(Object item, CgItemDisplay display, Matrix4fc model) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;
        ItemStack stack = (ItemStack) item;
        push(model);
        ItemRenderType type = type(display);
        int passes = stack.getItem().requiresMultipleRenderPasses() ? stack.getItem().getRenderPasses(stack.getItemDamage()) : 1;
        for (int pass = 0; pass < passes; pass++) mc.entityRenderer.itemRenderer.renderItem(mc.thePlayer, stack, pass, type);
        pop();
    }

    @Override
    public void drawEntity(Object entity, CgEntityPose posed, float ageTicks, Matrix4fc model) {
        Entity e = (Entity) entity;
        push(model);
        poses.apply(e, posed.bodyYaw(), posed.headYaw(), posed.pitch(), posed, ageTicks, true);
        try {
            RenderManager.instance.renderEntityWithPosYaw(e, 0, 0, 0, posed.headYaw(), partialTick);
        } finally {
            poses.restore(e);
            pop();
        }
    }

    @Override
    public void drawBlock(Object block, Matrix4fc model) {
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
        WorldClient world = Minecraft.getMinecraft().theWorld;
        if (world != null && !fullLight) {
            int light = world.getLightBrightnessForSkyBlocks((int) Math.floor(model.m30() + camX),
                    (int) Math.floor(model.m31() + camY), (int) Math.floor(model.m32() + camZ), 0);
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, light % 65536, light / 65536);
        } else if (fullLight) {
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240f, 240f);
        }
        GL11.glColor4f(1, 1, 1, 1);
    }

    private static void pop() {
        GL11.glPopMatrix();
    }

    private static ItemRenderType type(CgItemDisplay display) {
        switch (display) {
            case GROUND:
            case FIXED:
                return ItemRenderType.ENTITY;
            case GUI:
                return ItemRenderType.INVENTORY;
            case FIRST_PERSON_LEFT_HAND:
            case FIRST_PERSON_RIGHT_HAND:
                return ItemRenderType.EQUIPPED_FIRST_PERSON;
            default:
                return ItemRenderType.EQUIPPED;
        }
    }
}
