package com.crystalgraphics.mc.v1710.mixins.early.impl;

import com.crystalgraphics.collision.mirror.CgMirrorColumn;
import com.crystalgraphics.mc.v1710.platform.world.MirroredChunk1710;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The level mirror's write hook on 1.7.10: every block or metadata write of a watched chunk is logged before it lands
 * ({@code func_150807_a}, {@code setBlockMetadata}), and its unload ({@code onChunkUnload}) detaches it. Both sides.
 */
@Mixin(Chunk.class)
public abstract class ChunkWriteHook implements MirroredChunk1710 {

    @Shadow
    @Final
    public int xPosition;

    @Shadow
    @Final
    public int zPosition;

    @Unique
    private volatile CgMirrorColumn crystalgraphics$column;

    @Override
    public CgMirrorColumn crystalgraphics$column() {
        return crystalgraphics$column;
    }

    @Override
    public void crystalgraphics$column(CgMirrorColumn column) {
        crystalgraphics$column = column;
    }

    @Inject(method = "func_150807_a", at = @At("HEAD"), require = 1)
    private void crystalgraphics$write(int x, int y, int z, Block block, int meta, CallbackInfoReturnable<Boolean> cir) {
        CgMirrorColumn c = crystalgraphics$column;
        if (c != null) c.write(xPosition << 4 | x, y, zPosition << 4 | z, block, block == Blocks.piston_extension);
    }

    @Inject(method = "setBlockMetadata", at = @At("HEAD"), require = 1)
    private void crystalgraphics$meta(int x, int y, int z, int meta, CallbackInfoReturnable<Boolean> cir) {
        CgMirrorColumn c = crystalgraphics$column;
        if (c != null) c.write(xPosition << 4 | x, y, zPosition << 4 | z, null, false);
    }

    @Inject(method = "onChunkUnload", at = @At("HEAD"), require = 1)
    private void crystalgraphics$unloaded(CallbackInfo ci) {
        CgMirrorColumn c = crystalgraphics$column;
        if (c != null) c.unloaded();
    }
}
