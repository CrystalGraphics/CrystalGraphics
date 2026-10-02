package com.crystalgraphics.mc.modern.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
//? if >=1.15 {
import com.crystalgraphics.mc.modern.platform.world.WorldEventsModern;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else {
/*import net.minecraft.client.multiplayer.MultiPlayerLevel;
*///?}
//? if >=1.21.5 {
/*import net.minecraft.world.entity.Entity;
*///?} elif >=1.15 {
import net.minecraft.world.entity.player.Player;
//?}

// A block broken, for CgWorldEvents: the client level's level event 2001, whether the server sent it or the local player
// broke the block. Named with descriptors in both namespaces, since the name has an overload; the source is a Player to
// 1.21.4 and an Entity after. 1.14's level does not override it, so the mixin is empty there.
//? if >=1.15 {
@Mixin(value = ClientLevel.class, remap = false)
//?} else {
/*@Mixin(value = MultiPlayerLevel.class, remap = false)
*///?}
public abstract class LevelEventHook {

    //? if >=1.21.5 {
    /*@Inject(method = {"levelEvent(Lnet/minecraft/world/entity/Entity;ILnet/minecraft/core/BlockPos;I)V",
            "method_8444(Lnet/minecraft/class_1297;ILnet/minecraft/class_2338;I)V"}, at = @At("HEAD"), require = 1)
    private void crystalgraphics$levelEvent(Entity source, int type, BlockPos pos, int data, CallbackInfo ci) {
        WorldEventsModern.levelEvent(type, pos, data);
    }
    *///?} elif >=1.15 {
    @Inject(method = {"levelEvent(Lnet/minecraft/world/entity/player/Player;ILnet/minecraft/core/BlockPos;I)V",
            "method_8444(Lnet/minecraft/class_1657;ILnet/minecraft/class_2338;I)V"}, at = @At("HEAD"), require = 1)
    private void crystalgraphics$levelEvent(Player source, int type, BlockPos pos, int data, CallbackInfo ci) {
        WorldEventsModern.levelEvent(type, pos, data);
    }
    //?}
}
