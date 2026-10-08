package com.crystalgraphics.mc.modern.platform.world;

import com.crystalgraphics.render.stage.CgHostEnvironment;
import com.crystalgraphics.render.stage.CgHostView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffects;
//? if >=26.3 {
/*import org.joml.Vector3fc;
*///?}
//? if >=1.21.11 {
/*import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttributes;
*///?}
//? if >=1.15 {
import net.minecraft.client.multiplayer.ClientLevel;
//?} else {
/*import net.minecraft.client.multiplayer.MultiPlayerLevel;
*///?}
//? if >=1.15 <26.2 {
import net.minecraft.client.renderer.GameRenderer;
//?}
//? if >=1.17 <1.21.3 {
import com.mojang.blaze3d.systems.RenderSystem;
//?}
//? if >=1.17 {
import net.minecraft.world.level.material.FogType;
//?} elif >=1.14 <1.17 {
/*import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
*///?}
import net.minecraft.world.phys.Vec3;

/**
 * Minecraft's world facts at the world stages, read where each version keeps them, into the stage's
 * {@link CgHostEnvironment}. Render thread, beside {@code HostViewModern}'s camera; it allocates nothing of its own (a
 * sky colour is a {@code Vec3} Minecraft makes, before 1.21.3).
 *
 * <ul>
 *   <li>From 1.21.11 the sun, moon, stars, sky, clouds, fog and the water-evaporating rule are environment attributes,
 *       sampled at the camera; day time is the default clock on 26.x. Fog is readable 1.17.1 to 1.21.1 and from
 *       1.21.11 (the attributes', without the player's effects), absent between.</li>
 *   <li>The lightning flash has no reader from 1.21.11: it stays absent there. The moon's
 *       phase starts at 1.16.5; dimension facts too.</li>
 * </ul>
 */
public final class EnvironmentModern {

    private EnvironmentModern() {
    }

    public static void capture(Minecraft mc, float partialTick, CgHostView view, CgHostEnvironment out) {
        out.clear().screen(screenOpen(mc));
        //? if >=1.15 {
        ClientLevel level = mc.level;
        //?} else {
        /*MultiPlayerLevel level = mc.level;
        *///?}
        if (level == null) return;
        Options options = mc.options;

        //? if >=1.21.11 {
        /*EnvironmentAttributeSystem attributes = level.environmentAttributes();
        Vec3 eye = new Vec3(view.x(), view.y(), view.z());
        // Minecraft turns the sky by the sun's angle in degrees, 0 at noon, as it once turned it by the time of day.
        out.sun(attributes.getValue(EnvironmentAttributes.SUN_ANGLE, eye) / 360f,
                attributes.getValue(EnvironmentAttributes.MOON_PHASE, eye).index(),
                attributes.getValue(EnvironmentAttributes.STAR_BRIGHTNESS, eye),
                attributes.getValue(EnvironmentAttributes.SKY_LIGHT_FACTOR, eye));
        *///?} elif >=1.16.5 {
        out.sun(level.getSunAngle(partialTick) / (float) (Math.PI * 2.0), level.dimensionType().moonPhase(level.getDayTime()),
                level.getStarBrightness(partialTick), level.getSkyDarken(partialTick));
        //?} else {
        /*out.sun(level.getSunAngle(partialTick) / (float) (Math.PI * 2.0), -1, level.getStarBrightness(partialTick),
                level.getSkyDarken(partialTick));
        *///?}
        //? if >=1.15 <1.21.11 {
        float flash = level.getSkyFlashTime() > 0 ? 1f : 0f;
        //?} else {
        /*float flash = Float.NaN;
        *///?}
        out.weather(level.getRainLevel(partialTick), level.getThunderLevel(partialTick), flash);

        //? if >=1.16.5 {
        boolean hasSky = level.dimensionType().hasSkyLight(), hasCeiling = level.dimensionType().hasCeiling();
        //?} else {
        /*boolean hasSky = false, hasCeiling = false;
        *///?}
        //? if >=1.21.11 {
        /*// Positional, though it reads as a dimension's: getDimensionValue throws for it.
        boolean ultraWarm = attributes.getValue(EnvironmentAttributes.WATER_EVAPORATES, eye);
        *///?} elif >=1.16.5 {
        boolean ultraWarm = level.dimensionType().ultraWarm();
        //?} else {
        /*boolean ultraWarm = false;
        *///?}
        float red = Float.NaN, green = Float.NaN, blue = Float.NaN, clouds = Float.NaN;
        //? if >=26.3 {
        /*Vector3fc sky = attributes.getValue(EnvironmentAttributes.SKY_COLOR, eye);
        red = sky.x();
        green = sky.y();
        blue = sky.z();
        clouds = attributes.getValue(EnvironmentAttributes.CLOUD_HEIGHT, eye);
        *///?} elif >=1.21.11 {
        /*int sky = attributes.getValue(EnvironmentAttributes.SKY_COLOR, eye);
        red = (sky >> 16 & 0xFF) / 255f;
        green = (sky >> 8 & 0xFF) / 255f;
        blue = (sky & 0xFF) / 255f;
        clouds = attributes.getValue(EnvironmentAttributes.CLOUD_HEIGHT, eye);
        *///?} elif >=1.21.3 {
        /*int sky = level.getSkyColor(mc.gameRenderer.getMainCamera().getPosition(), partialTick);
        red = (sky >> 16 & 0xFF) / 255f;
        green = (sky >> 8 & 0xFF) / 255f;
        blue = (sky & 0xFF) / 255f;
        *///?} elif >=1.17 <1.21.3 {
        Vec3 sky = level.getSkyColor(mc.gameRenderer.getMainCamera().getPosition(), partialTick);
        red = (float) sky.x;
        green = (float) sky.y;
        blue = (float) sky.z;
        //?} elif >=1.16.5 <1.17 {
        /*Vec3 sky = level.getSkyColor(mc.gameRenderer.getMainCamera().getBlockPosition(), partialTick);
        red = (float) sky.x;
        green = (float) sky.y;
        blue = (float) sky.z;
        *///?}
        //? if >=1.16.5 <1.21.6 {
        clouds = level.effects().getCloudHeight();
        //?} elif >=1.21.6 <1.21.11 {
        /*if (level.dimensionType().cloudHeight().isPresent()) clouds = level.dimensionType().cloudHeight().get();
        *///?}
        out.sky(hasSky, hasCeiling, ultraWarm, red, green, blue, clouds);

        //? if >=1.17 <1.21.3 {
        float[] fog = RenderSystem.getShaderFogColor();
        out.fog(fog[0], fog[1], fog[2], RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd());
        //?} elif >=26.3 {
        /*Vector3fc fog = attributes.getValue(EnvironmentAttributes.FOG_COLOR, eye);
        out.fog(fog.x(), fog.y(), fog.z(), attributes.getValue(EnvironmentAttributes.FOG_START_DISTANCE, eye),
                attributes.getValue(EnvironmentAttributes.FOG_END_DISTANCE, eye));
        *///?} elif >=1.21.11 {
        /*int fog = attributes.getValue(EnvironmentAttributes.FOG_COLOR, eye);
        out.fog((fog >> 16 & 0xFF) / 255f, (fog >> 8 & 0xFF) / 255f, (fog & 0xFF) / 255f,
                attributes.getValue(EnvironmentAttributes.FOG_START_DISTANCE, eye),
                attributes.getValue(EnvironmentAttributes.FOG_END_DISTANCE, eye));
        *///?}

        out.camera(cameraFluid(mc), perspective(options), (float) Math.toDegrees(2.0 * Math.atan(1.0 / view.projection().m11())),
                renderDistance(options), guiHidden(mc));

        LocalPlayer player = mc.player;
        if (player != null) {
            float night = 0f;
            if (player.hasEffect(MobEffects.NIGHT_VISION)) {
                //? if >=1.15 <26.2 {
                night = GameRenderer.getNightVisionScale(player, partialTick);
                //?} elif >=26.2 {
                /*night = 1f;
                *///?} else {
                /*night = mc.gameRenderer.getNightVisionScale(player, partialTick);
                *///?}
            }
            //? if >=1.19 {
            float darkness = player.hasEffect(MobEffects.DARKNESS) ? 1f : 0f;
            //?} else {
            /*float darkness = 0f;
            *///?}
            out.sight(night, player.hasEffect(MobEffects.BLINDNESS) ? 1f : 0f, darkness);
        }

        settings(options, out);

        long gameTime = level.getLevelData().getGameTime();
        //? if >=26.1 {
        /*long dayTime = level.getDefaultClockTime();
        *///?} else {
        long dayTime = level.getLevelData().getDayTime();
        //?}
        //? if >=1.20.3 {
        /*float tickRate = level.tickRateManager().tickrate();
        boolean frozen = level.tickRateManager().isFrozen();
        *///?} else {
        float tickRate = 20f;
        boolean frozen = false;
        //?}
        out.time(gameTime, dayTime, mc.isPaused(), tickRate, frozen);
    }

    private static int cameraFluid(Minecraft mc) {
        //? if >=26.2 {
        /*FogType type = mc.gameRenderer.mainCamera().getFluidInCamera();
        *///?} elif >=1.17 {
        FogType type = mc.gameRenderer.getMainCamera().getFluidInCamera();
        //?}
        //? if >=1.17 {
        if (type == FogType.WATER) return CgHostEnvironment.FLUID_WATER;
        if (type == FogType.LAVA) return CgHostEnvironment.FLUID_LAVA;
        if (type == FogType.POWDER_SNOW) return CgHostEnvironment.FLUID_POWDER_SNOW;
        return CgHostEnvironment.FLUID_NONE;
        //?} elif >=1.14 <1.17 {
        /*FluidState fluid = mc.gameRenderer.getMainCamera().getFluidInCamera();
        if (fluid.isEmpty()) return CgHostEnvironment.FLUID_NONE;
        if (fluid.getType().isSame(Fluids.WATER)) return CgHostEnvironment.FLUID_WATER;
        return fluid.getType().isSame(Fluids.LAVA) ? CgHostEnvironment.FLUID_LAVA : CgHostEnvironment.FLUID_NONE;
        *///?} else {
        /*return -1;
        *///?}
    }

    private static int perspective(Options options) {
        //? if >=1.16.5 {
        return options.getCameraType().ordinal();
        //?} else {
        /*return options.thirdPersonView;
        *///?}
    }

    private static float renderDistance(Options options) {
        //? if >=1.18 {
        return options.getEffectiveRenderDistance() * 16f;
        //?} else {
        /*return options.renderDistance * 16f;
        *///?}
    }

    /** 26.2 moved the HUD's hidden flag and the screen onto {@code Minecraft.gui}. */
    private static boolean guiHidden(Minecraft mc) {
        //? if <26.2 {
        return mc.options.hideGui;
        //?} else {
        /*return mc.gui.hud.isHidden();
        *///?}
    }

    private static boolean screenOpen(Minecraft mc) {
        //? if <26.2 {
        return mc.screen != null;
        //?} else {
        /*return mc.gui.screen() != null;
        *///?}
    }

    /** The particles, graphics and accessibility settings: enums whose order is the environment's constants. */
    private static void settings(Options options, CgHostEnvironment out) {
        //? if >=1.19 {
        int particles = ((Enum<?>) options.particles().get()).ordinal();
        float screen = options.screenEffectScale().get().floatValue(), fov = options.fovEffectScale().get().floatValue();
        //?} elif >=1.16.5 {
        /*int particles = options.particles.ordinal();
        float screen = options.screenEffectScale, fov = options.fovEffectScale;
        *///?} elif >=1.14 {
        /*int particles = options.particles.ordinal();
        float screen = Float.NaN, fov = Float.NaN;
        *///?} else {
        /*int particles = options.particles;
        float screen = Float.NaN, fov = Float.NaN;
        *///?}
        //? if >=1.21.11 {
        /*// A preset only sets these, so a custom one maps too: Fabulous is improved transparency, Fancy cutout leaves.
        int graphics = options.improvedTransparency().get() ? CgHostEnvironment.GRAPHICS_FABULOUS
                : options.cutoutLeaves().get() ? CgHostEnvironment.GRAPHICS_FANCY : CgHostEnvironment.GRAPHICS_FAST;
        *///?} elif >=1.19 {
        int graphics = ((Enum<?>) options.graphicsMode().get()).ordinal();
        //?} elif >=1.16.5 <1.19 {
        /*int graphics = options.graphicsMode.ordinal();
        *///?} else {
        /*int graphics = options.fancyGraphics ? CgHostEnvironment.GRAPHICS_FANCY : CgHostEnvironment.GRAPHICS_FAST;
        *///?}
        out.settings(particles, graphics, screen, fov);
    }
}
