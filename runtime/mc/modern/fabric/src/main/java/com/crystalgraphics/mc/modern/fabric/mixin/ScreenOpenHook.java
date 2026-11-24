package com.crystalgraphics.mc.modern.fabric.mixin;

import com.crystalgraphics.mc.modern.platform.ScreensModern;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
//? if >=26.2 {
/*import net.minecraft.client.gui.Gui;
*///?} else {
import net.minecraft.client.Minecraft;
//?}

// Fabric has no screen-opening event: the screen about to open, swapped at setScreen's head (ScreensModern.instead),
// the point Forge and NeoForge post ScreenEvent.Opening from. 26.2 moved setScreen to Gui. No refmap, so each name in
// both namespaces.
//? if >=26.2 {
/*@Mixin(value = Gui.class, remap = false)
*///?} else {
@Mixin(value = Minecraft.class, remap = false)
//?}
public abstract class ScreenOpenHook {

    @ModifyVariable(method = {"setScreen", "method_1507"}, at = @At("HEAD"), argsOnly = true, require = 1)
    private Screen crystalgraphics$screenOpening(Screen screen) {
        Screen instead = ScreensModern.instead(screen);
        return instead != null ? instead : screen;
    }
}
