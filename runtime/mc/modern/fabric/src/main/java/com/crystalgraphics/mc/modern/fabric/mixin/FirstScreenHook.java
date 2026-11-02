package com.crystalgraphics.mc.modern.fabric.mixin;

import com.crystalgraphics.mc.modern.platform.HeldFirstScreen;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
//? if <1.20.1 {
/*import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
*///?} elif <1.20.2 {
import com.mojang.realmsclient.client.RealmsClient;
import net.minecraft.client.main.GameConfig;
import net.minecraft.server.packs.resources.ReloadInstance;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} else {
/*import org.spongepowered.asm.mixin.injection.Redirect;
*///?}

// The game's first screen, or its Quick Play, waits behind our loading screen (A13), then opens as asked once ours
// hides. Below 1.20.1 it is set in Minecraft's constructor, so the first title screen is swapped as it opens; 1.20.1
// holds setInitialScreen, 1.20.2+ the Runnable onGameLoadFinished builds, both once the initial reload finished.
// No refmap, so each name in both namespaces.
@Mixin(value = Minecraft.class, remap = false)
public abstract class FirstScreenHook {

    //? if <1.20.1 {
    /*@ModifyVariable(method = {"setScreen", "method_1507"}, at = @At("HEAD"), argsOnly = true, require = 1)
    private Screen crystalgraphics$firstScreen(Screen screen) {
        Screen instead = HeldFirstScreen.instead(screen);
        return instead != null ? instead : screen;
    }
    *///?} elif <1.20.2 {
    @Shadow(aliases = "method_51251")
    private void setInitialScreen(RealmsClient realms, ReloadInstance reload, GameConfig.QuickPlayData quickPlay) {
    }

    @Inject(method = {"setInitialScreen", "method_51251"}, at = @At("HEAD"), cancellable = true, require = 1)
    private void crystalgraphics$firstScreen(RealmsClient realms, ReloadInstance reload, GameConfig.QuickPlayData quickPlay,
                                             CallbackInfo ci) {
        if (HeldFirstScreen.hold(() -> setInitialScreen(realms, reload, quickPlay))) ci.cancel();
    }
    //?} else {
    /*@Redirect(method = {"onGameLoadFinished", "method_51736"},
            at = @At(value = "INVOKE", target = "Ljava/lang/Runnable;run()V"), require = 1)
    private void crystalgraphics$firstScreen(Runnable open) {
        if (!HeldFirstScreen.hold(open)) open.run();
    }
    *///?}
}
