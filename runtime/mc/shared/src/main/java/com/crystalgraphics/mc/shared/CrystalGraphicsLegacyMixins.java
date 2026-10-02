package com.crystalgraphics.mc.shared;

/**
 * CrystalGraphics' hooks on Forge 1.8–1.12.2: the world render passes, the frame tick, resize and
 * shutdown, the lightmap, and the explosions and broken blocks {@code CgWorldEvents} reports. Pinned as {@code variant.mixinPlugin} by every legacy node; the mixins live in the legacy
 * branch's {@code mixin} package.
 */
public final class CrystalGraphicsLegacyMixins extends VariantMixins {

    public CrystalGraphicsLegacyMixins() {
        super("crystalgraphics", "CgRenderHook", "MixinMinecraft", "ActiveRenderInfoAccessor", "EntityRendererAccessor",
                "ExplosionHook", "LevelEventHook");
    }
}
