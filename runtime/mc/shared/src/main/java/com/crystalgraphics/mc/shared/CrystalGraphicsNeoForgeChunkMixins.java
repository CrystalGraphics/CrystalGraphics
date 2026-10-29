package com.crystalgraphics.mc.shared;

/**
 * The mixins of NeoForge 1.20.2 to 1.21.5: the level mirror's chunk write hook ({@code ChunkWriteHook}), and on a
 * client the held camera's position ({@code CameraHook}).
 *
 * <p>Pinned as {@code variant.mixinPlugin} by those nodes; the mixins live in the neoforge branch's {@code mixin} package.</p>
 */
public final class CrystalGraphicsNeoForgeChunkMixins extends VariantMixins {

    public CrystalGraphicsNeoForgeChunkMixins() {
        super("crystalgraphics", new String[]{"ChunkWriteHook"}, "CameraHook");
    }
}
