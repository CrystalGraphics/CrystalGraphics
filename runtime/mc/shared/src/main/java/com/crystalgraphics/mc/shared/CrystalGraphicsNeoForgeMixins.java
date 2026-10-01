package com.crystalgraphics.mc.shared;

/**
 * The level view on NeoForge 1.21.6–1.21.11: there Minecraft hands the level's projection over on the CPU only as
 * {@code renderLevel}'s argument, and NeoForge's stage events carry no projection.
 *
 * <p>Pinned as {@code variant.mixinPlugin} by those NeoForge nodes; the mixin lives in the neoforge branch's
 * {@code mixin} package.</p>
 */
public final class CrystalGraphicsNeoForgeMixins extends VariantMixins {

    public CrystalGraphicsNeoForgeMixins() {
        super("crystalgraphics", "LevelViewHook");
    }
}
