package com.crystalgraphics.mc.shared;

/**
 * CrystalGraphics' world-render hook on Fabric 1.14-1.15, where Fabric API has no
 * {@code WorldRenderEvents}.
 *
 * <p>Pinned as {@code variant.mixinPlugin} by each Fabric node that needs it; the mixins live in the
 * fabric branch's {@code mixin} package.</p>
 */
public final class CrystalGraphicsFabricMixins extends VariantMixins {

    public CrystalGraphicsFabricMixins() {
        super("crystalgraphics", "WorldPassHook");
    }
}
