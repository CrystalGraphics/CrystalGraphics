package com.crystalgraphics.mc.modern.forge.mixin;

import com.crystalgraphics.mc.modern.platform.HostSlotPlace;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

// SlotPlaceHook by an installed Forge's SRG names (1.17-1.20.4; read from forge-1.20.1-47.1.44-client.jar).
@Mixin(value = Slot.class, remap = false)
public abstract class SlotPlaceSrgHook implements HostSlotPlace {

    @Shadow
    @Final
    @Mutable
    public int f_40220_;

    @Shadow
    @Final
    @Mutable
    public int f_40221_;

    @Override
    public void crystalgraphics$place(int x, int y) {
        f_40220_ = x;
        f_40221_ = y;
    }
}
