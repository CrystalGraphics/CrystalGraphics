package com.crystalgraphics.mc.shared;

/**
 * The mixins of MinecraftForge 1.14.4 to 1.21.1: the level mirror's chunk write hook ({@code ChunkWriteHook}), and on a
 * client the held camera's position ({@code CameraHook}).
 *
 * <p>Pinned as {@code variant.mixinPlugin} by those nodes; the mixins live in the forge branch's {@code mixin} package.</p>
 */
public final class CrystalGraphicsForgeChunkMixins extends VariantMixins {

    public CrystalGraphicsForgeChunkMixins() {
        super("crystalgraphics", new String[]{"ChunkWriteHook"}, "CameraHook");
    }
}
