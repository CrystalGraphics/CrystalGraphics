package com.crystalgraphics.mc.shared;

/**
 * CrystalGraphics' frame end on Fabric 1.16 onward: {@code GameRenderer.render}'s tail, since Fabric API
 * has no frame event. The world passes are Fabric API's events there, but the transparent pass on 26.x, where none
 * fires after weather ({@code TransparentPassHook}). On 26.2, our Vulkan device features ({@code DeviceFeaturesHook}).
 * From 1.21.6, Fabric API's GUI registry stands aside while the pictures' own {@code GuiRenderer} is made
 * ({@code GuiRegistryHook}).
 *
 * <p>Pinned as {@code variant.mixinPlugin} by each Fabric node from 1.16.5; 1.14-1.15 pin
 * {@link CrystalGraphicsFabricMixins}, which adds the world passes.</p>
 */
public final class CrystalGraphicsFabricFrameMixins extends VariantMixins {

    public CrystalGraphicsFabricFrameMixins() {
        super("crystalgraphics", new String[]{"ChunkWriteHook", "EntitySaveHook"}, "FrameEndHook", "CameraHook", "HandHook", "FovHook", "ExplosionHook", "LevelEventHook", "SoundVolumeHook",
                "DeviceFeaturesHook", "TransparentPassHook", "FirstScreenHook", "ScreenOpenHook",
                "EntityRendererHook", "ActorRenderHook", "WorldSubmitHook", "VanillaPoseHook", "GuiTargetHook", "GuiWindowHook", "GuiScissorHook", "GuiBlendHook", "GuiRegistryHook");
    }
}
