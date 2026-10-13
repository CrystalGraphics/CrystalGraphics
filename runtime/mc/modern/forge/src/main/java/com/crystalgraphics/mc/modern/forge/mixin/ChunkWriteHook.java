package com.crystalgraphics.mc.modern.forge.mixin;

import com.crystalgraphics.collision.mirror.CgMirrorColumn;
import com.crystalgraphics.mc.modern.platform.world.MirroredChunk;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;


// The level mirror's write hook: every block write of a watched chunk is logged before it lands, and its unload
// detaches it (docs/COLLISION.md, CgMirrorColumn). No refmap, so each method is named in every runtime namespace:
// Mojang (dev runs, 1.21.3+ Forge, NeoForge 1.20.5+, 26.x), SRG 1.17+ (m_), MCP 1.14.4-1.16.5 (func_), intermediary.
// @see com.crystalgraphics.mc.shared.VariantMixins
@Mixin(value = LevelChunk.class, remap = false)
public abstract class ChunkWriteHook implements MirroredChunk {

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

    // 1.21.5 turned the moving flag into the update flags.
    //? if >=1.21.5 {
    /*@Inject(method = {"setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;",
            "method_12010"}, at = @At("HEAD"), require = 1, remap = false)
    private void crystalgraphics$write(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
    *///?} else {
    @Inject(method = {"setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;",
            "m_6978_", "func_177436_a", "method_12010"}, at = @At("HEAD"), require = 1, remap = false)
    private void crystalgraphics$write(BlockPos pos, BlockState state, boolean moving, CallbackInfoReturnable<BlockState> cir) {
    //?}
        CgMirrorColumn c = crystalgraphics$column;
        if (c != null) c.write(pos.getX(), pos.getY(), pos.getZ(), state, state.getBlock().hasDynamicShape());
    }

    @Inject(method = {"setLoaded(Z)V", "m_62913_", "func_177417_c", "method_12226"}, at = @At("HEAD"), require = 1,
            remap = false)
    private void crystalgraphics$loaded(boolean loaded, CallbackInfo ci) {
        CgMirrorColumn c = crystalgraphics$column;
        if (!loaded && c != null) c.unloaded();
    }
}
