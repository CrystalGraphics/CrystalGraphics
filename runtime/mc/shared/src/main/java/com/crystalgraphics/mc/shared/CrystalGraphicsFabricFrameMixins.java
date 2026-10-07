package com.crystalgraphics.mc.shared;

/**
 * CrystalGraphics' frame end on Fabric 1.16 onward: {@code GameRenderer.render}'s tail, since Fabric API
 * has no frame event. The world passes are Fabric API's events there. On 26.2, our Vulkan device features
 * ({@code DeviceFeaturesHook}).
 *
 * <p>Pinned as {@code variant.mixinPlugin} by each Fabric node from 1.16.5; 1.14-1.15 pin
 * {@link CrystalGraphicsFabricMixins}, which adds the world passes.</p>
 */
public final class CrystalGraphicsFabricFrameMixins extends VariantMixins {

    public CrystalGraphicsFabricFrameMixins() {
        super("crystalgraphics", "FrameEndHook", "CameraHook", "FovHook", "ExplosionHook", "LevelEventHook",
                "DeviceFeaturesHook");
    }
}
