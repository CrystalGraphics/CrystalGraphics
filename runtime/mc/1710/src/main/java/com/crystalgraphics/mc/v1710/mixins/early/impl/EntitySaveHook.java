package com.crystalgraphics.mc.v1710.mixins.early.impl;

import com.crystalgraphics.world.CgEntitySaves;
import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * What the engine saves with an entity ({@link CgEntitySaves}) is written as the game saves the entity, at the head of
 * {@code writeToNBT}, before Forge writes its entity data: a change costs a flag until then.
 */
@Mixin(Entity.class)
public abstract class EntitySaveHook {

    @Inject(method = "writeToNBT", at = @At("HEAD"))
    private void crystalgraphics$saving(NBTTagCompound tag, CallbackInfo ci) {
        CgEntitySaves.saving(this);
    }
}
