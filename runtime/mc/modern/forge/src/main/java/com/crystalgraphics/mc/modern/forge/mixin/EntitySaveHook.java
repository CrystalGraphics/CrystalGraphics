package com.crystalgraphics.mc.modern.forge.mixin;

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
// Mojang 1.20.6+, SRG (m_) 1.17-1.20.4, MCP (func_) 1.14.4-1.16.5. Forge 1.13.2 and 1.14.3 load no mixins: there a change is written as it happens.
@Mixin(value = Entity.class, remap = false)
public abstract class EntitySaveHook {

    //? if >=1.21.6 {
    /*@Inject(method = {"saveWithoutId", "m_20240_", "func_189511_e"}, at = @At("HEAD"), require = 1)
    private void crystalgraphics$saving(ValueOutput output, CallbackInfo ci) {
        CgEntitySaves.saving(this);
    }
    *///?} else {
    @Inject(method = {"saveWithoutId", "m_20240_", "func_189511_e"}, at = @At("HEAD"), require = 1)
    private void crystalgraphics$saving(CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
        CgEntitySaves.saving(this);
    }
    //?}
}
