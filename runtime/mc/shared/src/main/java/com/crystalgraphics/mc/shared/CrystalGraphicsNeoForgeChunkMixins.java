package com.crystalgraphics.mc.shared;

/**
 * The level mirror's chunk write hook ({@code ChunkWriteHook}) on NeoForge 1.20.2 to 1.21.5, which need no other mixin.
 *
 * <p>Pinned as {@code variant.mixinPlugin} by those nodes; the mixin lives in the neoforge branch's {@code mixin} package.</p>
 */
public final class CrystalGraphicsNeoForgeChunkMixins extends VariantMixins {

    public CrystalGraphicsNeoForgeChunkMixins() {
        super("crystalgraphics", new String[]{"ChunkWriteHook"});
    }
}
