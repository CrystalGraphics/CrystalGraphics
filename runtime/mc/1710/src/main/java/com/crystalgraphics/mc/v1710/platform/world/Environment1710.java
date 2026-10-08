package com.crystalgraphics.mc.v1710.platform.world;

import com.crystalgraphics.platform.service.CgWorldQuery;
import com.crystalgraphics.render.stage.CgHostEnvironment;
import com.crystalgraphics.render.stage.CgHostView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.potion.Potion;
import net.minecraft.util.Vec3;
import net.minecraft.world.WorldProvider;

/**
 * Minecraft 1.7.10's world facts at the world stages, into the stage's {@link CgHostEnvironment}. Render thread,
 * beside {@code HostView1710}'s camera; allocates nothing of its own (the sky colour is a vector Minecraft makes).
 *
 * <ul>
 *   <li>Fog is GL state here, never held on the CPU: absent. So are the screen and FOV effect scales.</li>
 *   <li>The Nether is the one world with a ceiling and the one where water boils, both read off its hell flag.</li>
 *   <li>Night vision is 1 while the effect lasts, without its fade; darkness is 0.</li>
 * </ul>
 */
public final class Environment1710 {

    private Environment1710() {
    }

    public static void capture(Minecraft mc, float partialTick, CgHostView view, CgHostEnvironment out) {
        out.clear().screen(mc.currentScreen != null);
        WorldClient world = mc.theWorld;
        EntityLivingBase viewer = mc.renderViewEntity;
        if (world == null || viewer == null) return;
        GameSettings options = mc.gameSettings;

        out.sun(world.getCelestialAngle(partialTick), world.getMoonPhase(), world.getStarBrightness(partialTick),
                world.getSunBrightness(partialTick));
        out.weather(world.getRainStrength(partialTick), world.getWeightedThunderStrength(partialTick),
                world.lastLightningBolt > 0 ? 1f : 0f);

        WorldProvider provider = world.provider;
        Vec3 sky = world.getSkyColor(viewer, partialTick);
        out.sky(!provider.hasNoSky, provider.isHellWorld, provider.isHellWorld, (float) sky.xCoord, (float) sky.yCoord,
                (float) sky.zCoord, provider.getCloudHeight());

        int fluid = WorldQuery1710.fluidOf(ActiveRenderInfo.getBlockAtEntityViewpoint(world, viewer, partialTick).getMaterial());
        out.camera(fluid == CgWorldQuery.FLUID_WATER ? CgHostEnvironment.FLUID_WATER
                        : fluid == CgWorldQuery.FLUID_LAVA ? CgHostEnvironment.FLUID_LAVA : CgHostEnvironment.FLUID_NONE,
                options.thirdPersonView, (float) Math.toDegrees(2.0 * Math.atan(1.0 / view.projection().m11())),
                options.renderDistanceChunks * 16f, options.hideGUI);

        EntityClientPlayerMP player = mc.thePlayer;
        if (player != null) {
            out.sight(player.isPotionActive(Potion.nightVision) ? 1f : 0f, player.isPotionActive(Potion.blindness) ? 1f : 0f,
                    0f);
        }

        out.settings(options.particleSetting,
                options.fancyGraphics ? CgHostEnvironment.GRAPHICS_FANCY : CgHostEnvironment.GRAPHICS_FAST, Float.NaN, Float.NaN);
        out.time(world.getTotalWorldTime(), world.getWorldTime(), mc.isGamePaused(), 20f, false);
    }
}
