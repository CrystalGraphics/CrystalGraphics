package com.crystalgraphics.mc.modern.forge.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
//? if >=1.20.1 <1.20.2 {
import com.crystalgraphics.mc.modern.platform.HeldFirstScreen;
import com.mojang.realmsclient.client.RealmsClient;
import net.minecraft.client.main.GameConfig;
import net.minecraft.server.packs.resources.ReloadInstance;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}

// The game's first screen, or its Quick Play, waits behind our loading screen (A13): opened by the initial reload's
// completion, it is held, then run as asked once ours hides. No refmap: Mojang's name and SRG's. Empty where the
// version's opening is not hooked yet.
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
    //?}
}
