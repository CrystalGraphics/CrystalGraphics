package com.crystalgraphics.mc.shared;

/**
 * {@link CrystalGraphicsFabricFrameMixins} on Fabric 1.21.6–1.21.11, plus the level view: there Minecraft hands the
 * level's projection over on the CPU only as {@code renderLevel}'s argument.
 *
 * <p>Pinned as {@code variant.mixinPlugin} by those Fabric nodes.</p>
 */
public final class CrystalGraphicsFabricLevelMixins extends VariantMixins {

    public CrystalGraphicsFabricLevelMixins() {
        super("crystalgraphics", "FrameEndHook", "LevelViewHook");
    }
}
