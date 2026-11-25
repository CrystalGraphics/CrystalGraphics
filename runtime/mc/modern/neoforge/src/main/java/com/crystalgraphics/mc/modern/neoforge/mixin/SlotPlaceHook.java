package com.crystalgraphics.mc.modern.neoforge.mixin;

import com.crystalgraphics.mc.modern.platform.HostSlotPlace;
import net.minecraft.world.inventory.Slot;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

// A slot moved to where an engine screen draws it (OwnedContainer), by Mojang's names: x and y are final from 1.15.2.
@Mixin(value = Slot.class, remap = false)
public abstract class SlotPlaceHook implements HostSlotPlace {

    @Shadow
    @Final
    @Mutable
    public int x;

    @Shadow
    @Final
    @Mutable
    public int y;

    @Override
    public void crystalgraphics$place(int x, int y) {
        this.x = x;
        this.y = y;
    }
}
