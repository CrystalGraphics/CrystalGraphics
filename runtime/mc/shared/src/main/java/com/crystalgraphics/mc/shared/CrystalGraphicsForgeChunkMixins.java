package com.crystalgraphics.mc.shared;

/**
 * The level mirror's chunk write hook ({@code ChunkWriteHook}) on MinecraftForge 1.14.4 to 1.21.1, which need no other mixin.
 *
 * <p>Pinned as {@code variant.mixinPlugin} by those nodes; the mixin lives in the forge branch's {@code mixin} package.</p>
 */
public final class CrystalGraphicsForgeChunkMixins extends VariantMixins {

    public CrystalGraphicsForgeChunkMixins() {
        super("crystalgraphics", new String[]{"ChunkWriteHook"});
    }
}
