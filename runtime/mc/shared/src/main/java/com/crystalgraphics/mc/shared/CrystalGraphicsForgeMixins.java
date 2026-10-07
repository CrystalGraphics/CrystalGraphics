package com.crystalgraphics.mc.shared;

/**
 * CrystalGraphics' world-render hook on MinecraftForge 1.21.3+, which has no render-stage event: Forge
 * 53 removed {@code RenderLevelStageEvent} with 1.21.2's frame graph and never replaced it. With it, the world events,
 * and on 26.1.1 to 26.2 the camera offset, whose event there comes too late to be seen. On 26.2, our Vulkan device
 * features ({@code DeviceFeaturesHook}).
 *
 * <p>Pinned as {@code variant.mixinPlugin} by each Forge node that needs it; the mixins live in the
 * forge branch's {@code mixin} package.</p>
 */
public final class CrystalGraphicsForgeMixins extends VariantMixins {

    public CrystalGraphicsForgeMixins() {
        super("crystalgraphics", "OpaquePassHook", "TransparentPassHook", "ExplosionHook", "LevelEventHook", "CameraHook",
                "DeviceFeaturesHook");
    }
}
