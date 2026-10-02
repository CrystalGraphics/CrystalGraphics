package com.crystalgraphics.mc.modern.forge.mixin;

//? if >=1.21.5 {
/*import com.crystalgraphics.mc.modern.platform.world.WorldEventsModern;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// A block broken, for CgWorldEvents: the client level's level event 2001, from the server or the local player. Named
// with its descriptor, since the name has an overload. Forge 1.21.3+ only, which runs Mojang names (no refmap);
// earlier Forge has no hook for it.
@Mixin(value = ClientLevel.class, remap = false)
public abstract class LevelEventHook {

    @Inject(method = "levelEvent(Lnet/minecraft/world/entity/Entity;ILnet/minecraft/core/BlockPos;I)V",
            at = @At("HEAD"), require = 1)
    private void crystalgraphics$levelEvent(Entity source, int type, BlockPos pos, int data, CallbackInfo ci) {
        WorldEventsModern.levelEvent(type, pos, data);
    }
}
*///?} elif >=1.21.3 {
/*import com.crystalgraphics.mc.modern.platform.world.WorldEventsModern;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// A block broken, for CgWorldEvents: the client level's level event 2001, from the server or the local player. Named
// with its descriptor, since the name has an overload. Forge 1.21.3+ only, which runs Mojang names (no refmap);
// earlier Forge has no hook for it.
@Mixin(value = ClientLevel.class, remap = false)
public abstract class LevelEventHook {

    @Inject(method = "levelEvent(Lnet/minecraft/world/entity/player/Player;ILnet/minecraft/core/BlockPos;I)V",
            at = @At("HEAD"), require = 1)
    private void crystalgraphics$levelEvent(Player source, int type, BlockPos pos, int data, CallbackInfo ci) {
        WorldEventsModern.levelEvent(type, pos, data);
    }
}
*///?}
