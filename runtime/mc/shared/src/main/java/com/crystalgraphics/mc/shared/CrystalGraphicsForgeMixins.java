package com.crystalgraphics.mc.shared;

/**
 * CrystalGraphics' world-render hook on MinecraftForge 1.21.3+, which has no render-stage event: Forge
 * 53 removed {@code RenderLevelStageEvent} with 1.21.2's frame graph and never replaced it.
 *
 * <p>Pinned as {@code variant.mixinPlugin} by each Forge node that needs it; the mixins live in the
 * forge branch's {@code mixin} package.</p>
 */
public final class CrystalGraphicsForgeMixins extends VariantMixins {

    public CrystalGraphicsForgeMixins() {
        super("crystalgraphics", "LevelRendererHook", "ParticleEngineHook");
    }
}
