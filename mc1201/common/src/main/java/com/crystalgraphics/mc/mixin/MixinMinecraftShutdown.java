package com.crystalgraphics.mc.mixin;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.service.CgLifecycleService;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects into {@link Minecraft#destroy()} to call
 * {@link CgLifecycleService#onContextDestroy()} at the very start of shutdown,
 * before MC tears down the GL context.
 *
 * <p>Method verified from extracted MC 1.20.1 source at
 * {@code mc1201/fabric/build/mc-src/java/net/minecraft/client/Minecraft.java:1026}.</p>
 */
@Mixin(Minecraft.class)
public abstract class MixinMinecraftShutdown {

    @Inject(method = "destroy", at = @At("HEAD"))
    private void cg_onDestroy(CallbackInfo ci) {
        CgPlatform.lifecycle().onContextDestroy();
    }
}
