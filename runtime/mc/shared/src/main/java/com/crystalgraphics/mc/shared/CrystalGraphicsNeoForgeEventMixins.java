package com.crystalgraphics.mc.shared;

/**
 * The world events on NeoForge 26.1 and later: an explosion and a block broken reach the client only as a packet and
 * a level event, which no NeoForge event carries. The level view comes from the frame's camera state there, so
 * {@code LevelViewHook} is not among them.
 *
 * <p>Pinned as {@code variant.mixinPlugin} by those NeoForge nodes; the mixins live in the neoforge branch's
 * {@code mixin} package.</p>
 */
public final class CrystalGraphicsNeoForgeEventMixins extends VariantMixins {

    public CrystalGraphicsNeoForgeEventMixins() {
        super("crystalgraphics", "ExplosionHook", "LevelEventHook");
    }
}
