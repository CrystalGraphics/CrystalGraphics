package com.crystalgraphics.mc.shared;

/**
 * {@link CrystalGraphicsForgeLevelMixins} on Forge 1.21.6–1.21.7, plus the HUD: Forge 56-57 have no HUD event, so
 * {@code HudHook} fires it. The class exists on that node alone; a plugin listing it elsewhere fails Mixin's prepare.
 *
 * <p>Pinned as {@code variant.mixinPlugin} by the 1.21.6 Forge node.</p>
 */
public final class CrystalGraphicsForgeHudMixins extends VariantMixins {

    public CrystalGraphicsForgeHudMixins() {
        super("crystalgraphics", new String[]{"ChunkWriteHook"}, "OpaquePassHook", "TransparentPassHook", "LevelViewHook", "ExplosionHook", "LevelEventHook", "CameraHook", "SoundVolumeHook", "HudHook",
                "FirstScreenHook");
    }
}
