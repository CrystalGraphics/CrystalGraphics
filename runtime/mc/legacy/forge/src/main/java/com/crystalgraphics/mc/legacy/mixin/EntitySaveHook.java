package com.crystalgraphics.mc.legacy.mixin;

import com.crystalgraphics.world.CgEntitySaves;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
//? if >=1.9 {
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//?} else {
/*import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

/**
 * What the engine saves with an entity ({@link CgEntitySaves}) is written as the game saves the entity, at the head of
 * {@code writeToNBT}, before Forge writes its entity data: a change costs a flag until then. At SRG names:
 * {@code func_189511_e} from 1.10, answering the compound; {@code func_70109_d} on 1.8.9.
 */
@Mixin(value = Entity.class, remap = false)
public abstract class EntitySaveHook {

    //? if >=1.9 {
    @Inject(method = "func_189511_e", at = @At("HEAD"), require = 1)
    private void crystalgraphics$saving(NBTTagCompound tag, CallbackInfoReturnable<NBTTagCompound> cir) {
        CgEntitySaves.saving(this);
    }
    //?} else {
    /*@Inject(method = "func_70109_d", at = @At("HEAD"), require = 1)
    private void crystalgraphics$saving(NBTTagCompound tag, CallbackInfo ci) {
        CgEntitySaves.saving(this);
    }
    *///?}
}
