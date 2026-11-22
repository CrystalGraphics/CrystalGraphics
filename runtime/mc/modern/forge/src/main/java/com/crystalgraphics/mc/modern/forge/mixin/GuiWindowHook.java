package com.crystalgraphics.mc.modern.forge.mixin;

//? if <1.21.6 {
import com.crystalgraphics.mc.modern.platform.PicturesModern;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// While a picture draws, Minecraft's window is the picture's: its pixels, its GUI scale, its size in units. A scissor,
// a tooltip's clamp and a GuiRenderer's projection then land in the picture (26.1 on sets the window state instead).
@Mixin(value = Window.class, remap = false)
public abstract class GuiWindowHook {

    @Inject(method = {"getWidth", "m_85441_", "func_198109_k"}, at = @At("HEAD"), cancellable = true, require = 1)
    private void crystalgraphics$width(CallbackInfoReturnable<Integer> cir) {
        if (PicturesModern.answersWindow()) cir.setReturnValue(PicturesModern.windowWidth(0));
    }

    @Inject(method = {"getHeight", "m_85442_", "func_198091_l"}, at = @At("HEAD"), cancellable = true, require = 1)
    private void crystalgraphics$height(CallbackInfoReturnable<Integer> cir) {
        if (PicturesModern.answersWindow()) cir.setReturnValue(PicturesModern.windowHeight(0));
    }

    @Inject(method = {"getGuiScale", "m_85449_", "func_198100_s"}, at = @At("HEAD"), cancellable = true, require = 1)
    private void crystalgraphics$scale(CallbackInfoReturnable<Double> cir) {
        if (PicturesModern.answersWindow()) cir.setReturnValue(PicturesModern.windowGuiScale(0.0));
    }

    @Inject(method = {"getGuiScaledWidth", "m_85445_", "func_198107_o"}, at = @At("HEAD"), cancellable = true, require = 1)
    private void crystalgraphics$unitsWide(CallbackInfoReturnable<Integer> cir) {
        if (PicturesModern.answersWindow()) cir.setReturnValue(PicturesModern.windowUnitsWidth(0));
    }

    @Inject(method = {"getGuiScaledHeight", "m_85446_", "func_198087_p"}, at = @At("HEAD"), cancellable = true, require = 1)
    private void crystalgraphics$unitsHigh(CallbackInfoReturnable<Integer> cir) {
        if (PicturesModern.answersWindow()) cir.setReturnValue(PicturesModern.windowUnitsHeight(0));
    }
}
//?} elif <26.1 {
/*import com.crystalgraphics.mc.modern.platform.PicturesModern;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// While a picture draws, Minecraft's window is the picture's: its pixels, its GUI scale, its size in units. A scissor,
// a tooltip's clamp and a GuiRenderer's projection then land in the picture (26.1 on sets the window state instead).
@Mixin(value = Window.class, remap = false)
public abstract class GuiWindowHook {

    @Inject(method = {"getWidth", "m_85441_", "func_198109_k"}, at = @At("HEAD"), cancellable = true, require = 1)
    private void crystalgraphics$width(CallbackInfoReturnable<Integer> cir) {
        if (PicturesModern.answersWindow()) cir.setReturnValue(PicturesModern.windowWidth(0));
    }

    @Inject(method = {"getHeight", "m_85442_", "func_198091_l"}, at = @At("HEAD"), cancellable = true, require = 1)
    private void crystalgraphics$height(CallbackInfoReturnable<Integer> cir) {
        if (PicturesModern.answersWindow()) cir.setReturnValue(PicturesModern.windowHeight(0));
    }

    @Inject(method = {"getGuiScale", "m_85449_", "func_198100_s"}, at = @At("HEAD"), cancellable = true, require = 1)
    private void crystalgraphics$scale(CallbackInfoReturnable<Integer> cir) {
        if (PicturesModern.answersWindow()) cir.setReturnValue(PicturesModern.windowScale(0));
    }

    @Inject(method = {"getGuiScaledWidth", "m_85445_", "func_198107_o"}, at = @At("HEAD"), cancellable = true, require = 1)
    private void crystalgraphics$unitsWide(CallbackInfoReturnable<Integer> cir) {
        if (PicturesModern.answersWindow()) cir.setReturnValue(PicturesModern.windowUnitsWidth(0));
    }

    @Inject(method = {"getGuiScaledHeight", "m_85446_", "func_198087_p"}, at = @At("HEAD"), cancellable = true, require = 1)
    private void crystalgraphics$unitsHigh(CallbackInfoReturnable<Integer> cir) {
        if (PicturesModern.answersWindow()) cir.setReturnValue(PicturesModern.windowUnitsHeight(0));
    }
}
*///?} else {
/*import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;

// Empty from 26.1: the window state's fields are set while a picture draws.
@Mixin(value = Camera.class, remap = false)
public abstract class GuiWindowHook {
}
*///?}
