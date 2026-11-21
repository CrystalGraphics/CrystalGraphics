package com.crystalgraphics.mc.modern.neoforge.mixin;

import com.crystalgraphics.world.CgEntitySaves;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
//? if >=1.21.6 {
/*import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?} else {
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
//?}

// What the engine saves with an entity (CgEntitySaves) is written as the game saves the entity, before the loader writes
// its persistent data: a change costs a flag until then. No refmap, so each name in every runtime namespace:
// Mojang from 1.20.5, SRG (m_) on 1.20.2-1.20.4.
@Mixin(value = Entity.class, remap = false)
public abstract class EntitySaveHook {

    //? if >=1.21.6 {
    /*@Inject(method = {"saveWithoutId", "m_20240_"}, at = @At("HEAD"), require = 1)
    private void crystalgraphics$saving(ValueOutput output, CallbackInfo ci) {
        CgEntitySaves.saving(this);
    }
    *///?} else {
    @Inject(method = {"saveWithoutId", "m_20240_"}, at = @At("HEAD"), require = 1)
    private void crystalgraphics$saving(CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
        CgEntitySaves.saving(this);
    }
    //?}
}
