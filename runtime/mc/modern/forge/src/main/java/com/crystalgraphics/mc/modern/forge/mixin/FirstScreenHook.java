package com.crystalgraphics.mc.modern.forge.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
//? if >=1.20.1 {
import com.crystalgraphics.mc.modern.platform.HeldFirstScreen;
import org.spongepowered.asm.mixin.injection.At;
//?}
//? if >=1.20.1 <1.20.2 {
import com.mojang.realmsclient.client.RealmsClient;
import net.minecraft.client.main.GameConfig;
import net.minecraft.server.packs.resources.ReloadInstance;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?} elif >=1.20.2 {
/*import org.spongepowered.asm.mixin.injection.Redirect;
*///?}

// The game's first screen, or its Quick Play, waits behind our loading screen (A13): opened once the initial reload
// finished, it is held, then run as asked once ours hides. 1.20.1: setInitialScreen; 1.20.2+: the Runnable
// onGameLoadFinished builds and runs. Below 1.20.1 the first screen opens in Minecraft's constructor, so Forge's
// screen-open event swaps it (CrystalGraphicsForge.Events). No refmap: Mojang's name and SRG's.
@Mixin(value = Minecraft.class, remap = false)
public abstract class FirstScreenHook {

    //? if >=1.20.1 <1.20.2 {
    @Shadow(aliases = "m_278684_")
    private void setInitialScreen(RealmsClient realms, ReloadInstance reload, GameConfig.QuickPlayData quickPlay) {
    }

    @Inject(method = {"setInitialScreen", "m_278684_"}, at = @At("HEAD"), cancellable = true, require = 1)
    private void crystalgraphics$firstScreen(RealmsClient realms, ReloadInstance reload, GameConfig.QuickPlayData quickPlay,
                                             CallbackInfo ci) {
        if (HeldFirstScreen.hold(() -> setInitialScreen(realms, reload, quickPlay))) ci.cancel();
    }
    //?} elif >=1.20.2 {
    /*@Redirect(method = {"onGameLoadFinished", "m_286052_"}, at = @At(value = "INVOKE", target = "Ljava/lang/Runnable;run()V"),
            require = 1)
    private void crystalgraphics$firstScreen(Runnable open) {
        if (!HeldFirstScreen.hold(open)) open.run();
    }
    *///?}
}
