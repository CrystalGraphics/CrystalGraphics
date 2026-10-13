package com.crystalgraphics.mc.legacy.mixin;

import com.crystalgraphics.collision.mirror.CgMirrorColumn;
import com.crystalgraphics.mc.legacy.platform.world.MirroredChunkLegacy;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//? if >=1.9 {
import net.minecraft.util.math.BlockPos;
//?} else {
/*import net.minecraft.util.BlockPos;
*///?}

/**
 * The level mirror's write hook on Forge 1.8.9 to 1.12.2: every block write of a watched chunk is logged before it lands
 * ({@code Chunk.setBlockState}, {@code func_177436_a}), and its unload ({@code onChunkUnload} / {@code onUnload},
 * {@code func_76623_d}) detaches it.
 */
@Mixin(value = Chunk.class, remap = false)
public abstract class ChunkWriteHook implements MirroredChunkLegacy {

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

    @Inject(method = "func_177436_a", at = @At("HEAD"), require = 1, remap = false)
    private void crystalgraphics$write(BlockPos pos, IBlockState state, CallbackInfoReturnable<IBlockState> cir) {
        CgMirrorColumn c = crystalgraphics$column;
        //? if >=1.10 {
        if (c != null) c.write(pos.getX(), pos.getY(), pos.getZ(), state, state.getBlock() == Blocks.PISTON_EXTENSION);
        //?} else {
        /*if (c != null) c.write(pos.getX(), pos.getY(), pos.getZ(), state, state.getBlock() == Blocks.piston_extension);
        *///?}
    }

    @Inject(method = "func_76623_d", at = @At("HEAD"), require = 1, remap = false)
    private void crystalgraphics$unloaded(CallbackInfo ci) {
        CgMirrorColumn c = crystalgraphics$column;
        if (c != null) c.unloaded();
    }
}
