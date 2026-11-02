package com.crystalgraphics.mc.modern.neoforge.mixin;

import com.crystalgraphics.mc.modern.platform.HeldFirstScreen;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// The game's first screen, or its Quick Play, waits behind our loading screen (A13): the Runnable onGameLoadFinished
// builds once the initial reload finished is held, then run once ours hides. No refmap: Mojang's name and SRG's
// (NeoForge 1.20.2-1.20.4).
@Mixin(value = Minecraft.class, remap = false)
public abstract class FirstScreenHook {

    @Redirect(method = {"onGameLoadFinished", "m_286052_"}, at = @At(value = "INVOKE", target = "Ljava/lang/Runnable;run()V"),
            require = 1)
    private void crystalgraphics$firstScreen(Runnable open) {
        if (!HeldFirstScreen.hold(open)) open.run();
    }
}
