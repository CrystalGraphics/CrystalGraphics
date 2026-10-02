package com.crystalgraphics.mc.shared;

/**
 * CrystalGraphics' hooks on Fabric 1.14-1.15: the frame end, and the world passes, where Fabric API has
 * no {@code WorldRenderEvents}.
 *
 * <p>Pinned as {@code variant.mixinPlugin} by each Fabric node that needs it; the mixins live in the
 * fabric branch's {@code mixin} package. Every later node pins {@link CrystalGraphicsFabricFrameMixins}.</p>
 */
public final class CrystalGraphicsFabricMixins extends VariantMixins {

    public CrystalGraphicsFabricMixins() {
        super("crystalgraphics", "WorldPassHook", "FrameEndHook", "CameraHook", "FovHook", "ExplosionHook", "LevelEventHook");
    }
}
