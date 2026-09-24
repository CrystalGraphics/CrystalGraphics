package com.crystalgraphics.mc.modern.fabric.mixin;

//? if <1.16 {
/*import com.crystalgraphics.mc.modern.platform.LifecycleModern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Both passes at the end of the level on Fabric 1.15, which has no WorldRenderEvents: where Forge 1.15's
// RenderWorldLastEvent fires. The node config carries no refmap, so the method is named in both
// namespaces -- Mojang's for a dev run, intermediary's in production -- and the class through its
// literal, which the remap rewrites. @see com.crystalgraphics.mc.shared.CrystalGraphicsFabricMixins
@Mixin(value = LevelRenderer.class, remap = false)
public abstract class WorldPassHook {

    @Inject(method = {"renderLevel", "method_22710"}, at = @At("TAIL"), require = 1)
    private void crystalgraphics$worldPasses(CallbackInfo ci) {
        LifecycleModern.opaquePass(Minecraft.getInstance().getFrameTime());
        LifecycleModern.transparentPass();
    }
}
*///?}
