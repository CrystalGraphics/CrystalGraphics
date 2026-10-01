package com.crystalgraphics.mc.shared;

/**
 * {@link CrystalGraphicsForgeMixins} on Forge 1.21.6–1.21.11, plus the level view: there Minecraft hands the level's
 * projection over on the CPU only as {@code renderLevel}'s argument, so a mixin takes it.
 *
 * <p>Pinned as {@code variant.mixinPlugin} by those Forge nodes.</p>
 */
public final class CrystalGraphicsForgeLevelMixins extends VariantMixins {

    public CrystalGraphicsForgeLevelMixins() {
        super("crystalgraphics", "OpaquePassHook", "TransparentPassHook", "LevelViewHook");
    }
}
