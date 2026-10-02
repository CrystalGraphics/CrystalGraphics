package com.crystalgraphics.mc.legacy.platform.world;

import com.crystalgraphics.platform.service.CgWorldQuery;
import com.crystalgraphics.render.stage.CgHostEnvironment;
import com.crystalgraphics.render.stage.CgHostView;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.Entity;
import net.minecraft.world.WorldProvider;
//? if >=1.9 {
import net.minecraft.init.MobEffects;
import net.minecraft.util.math.Vec3d;
//?} else {
/*import net.minecraft.potion.Potion;
import net.minecraft.util.Vec3;
*///?}

/**
 * Minecraft 1.8.9 to 1.12.2's world facts at the world stages, into the stage's {@link CgHostEnvironment}. Render
 * thread, beside {@code HostViewLegacy}'s camera; allocates nothing of its own (the sky colour is a vector Minecraft
 * makes).
 *
 * <ul>
 *   <li>Fog is GL state here, never held on the CPU: absent. So are the screen and FOV effect scales, which these
 *       versions lack.</li>
 *   <li>Night vision is 1 while the effect lasts, without its fade; darkness is 0, an effect these versions lack.</li>
 * </ul>
 */
public final class EnvironmentLegacy {

    private EnvironmentLegacy() {
    }

    public static void capture(Minecraft mc, float partialTick, CgHostView view, CgHostEnvironment out) {
        out.clear();
        WorldClient world = ClientLegacy.world();
        Entity viewer = mc.getRenderViewEntity();
        if (world == null || viewer == null) return;
        GameSettings options = mc.gameSettings;

        out.sun(world.getCelestialAngle(partialTick), world.getMoonPhase(), world.getStarBrightness(partialTick),
                world.getSunBrightness(partialTick));
        out.weather(world.getRainStrength(partialTick), world.getThunderStrength(partialTick),
                world.getLastLightningBolt() > 0 ? 1f : 0f);

        WorldProvider provider = world.provider;
        //? if >=1.12 {
        boolean hasSky = provider.hasSkyLight();
        //?} elif >=1.9 {
        /*boolean hasSky = !provider.hasNoSky();
        *///?} else {
        /*boolean hasSky = !provider.getHasNoSky();
        *///?}
        //? if >=1.9 {
        boolean hasCeiling = provider.getDimension() == -1;
        //?} else {
        /*boolean hasCeiling = provider.getDimensionId() == -1;
        *///?}
        //? if >=1.12 {
        Vec3d sky = world.getSkyColor(viewer, partialTick);
        float red = (float) sky.x, green = (float) sky.y, blue = (float) sky.z;
        //?} elif >=1.9 {
        /*Vec3d sky = world.getSkyColor(viewer, partialTick);
        float red = (float) sky.xCoord, green = (float) sky.yCoord, blue = (float) sky.zCoord;
        *///?} else {
        /*Vec3 sky = world.getSkyColor(viewer, partialTick);
        float red = (float) sky.xCoord, green = (float) sky.yCoord, blue = (float) sky.zCoord;
        *///?}
        out.sky(hasSky, hasCeiling, provider.doesWaterVaporize(), red, green, blue, provider.getCloudHeight());

        out.camera(cameraFluid(world, viewer, partialTick), options.thirdPersonView,
                (float) Math.toDegrees(2.0 * Math.atan(1.0 / view.projection().m11())), options.renderDistanceChunks * 16f,
                options.hideGUI);

        EntityPlayerSP player = ClientLegacy.player();
        if (player != null) {
            //? if >=1.9 {
            out.sight(player.isPotionActive(MobEffects.NIGHT_VISION) ? 1f : 0f,
                    player.isPotionActive(MobEffects.BLINDNESS) ? 1f : 0f, 0f);
            //?} else {
            /*out.sight(player.isPotionActive(Potion.nightVision) ? 1f : 0f, player.isPotionActive(Potion.blindness) ? 1f : 0f,
                    0f);
            *///?}
        }

        out.settings(options.particleSetting,
                options.fancyGraphics ? CgHostEnvironment.GRAPHICS_FANCY : CgHostEnvironment.GRAPHICS_FAST, Float.NaN, Float.NaN);
        out.time(world.getTotalWorldTime(), world.getWorldTime(), mc.isGamePaused(), 20f, false);
    }

    private static int cameraFluid(WorldClient world, Entity viewer, float partialTick) {
        //? if >=1.9 {
        Material m = ActiveRenderInfo.getBlockStateAtEntityViewpoint(world, viewer, partialTick).getMaterial();
        //?} else {
        /*Material m = ActiveRenderInfo.getBlockAtEntityViewpoint(world, viewer, partialTick).getMaterial();
        *///?}
        int fluid = WorldQueryLegacy.fluidOf(m);
        return fluid == CgWorldQuery.FLUID_WATER ? CgHostEnvironment.FLUID_WATER
                : fluid == CgWorldQuery.FLUID_LAVA ? CgHostEnvironment.FLUID_LAVA : CgHostEnvironment.FLUID_NONE;
    }
}
