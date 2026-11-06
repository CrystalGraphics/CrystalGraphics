package com.crystalgraphics.mc.v1710.mixins.early.impl.client;

import com.crystalgraphics.mc.v1710.platform.ScreenInput1710;
import net.minecraft.client.gui.GuiScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 1.7.10's Forge has no screen input event, so this is how {@code CgInputStage.SCREEN} reaches a screen: while a
 * listener wants input, the screen's tick-time drain becomes {@link ScreenInput1710#drainInto}, which gives each event
 * to the stage first. Cancelling without draining would leave the queue full for {@code runTick}'s own in-world loop
 * (the inventory's {@code allowUserInput}), which would eat the screen's clicks. With no listener it costs one read.
 */
@Mixin(GuiScreen.class)
public abstract class MixinGuiScreen {

    @Inject(method = "handleInput", at = @At("HEAD"), cancellable = true, require = 1)
    private void cg$handleInput(CallbackInfo ci) {
        if (!ScreenInput1710.wanted()) return;
        ScreenInput1710.drainInto((GuiScreen) (Object) this);
        ci.cancel();
    }
}
