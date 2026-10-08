package com.crystalgraphics.mc.modern.neoforge.mixin;

//? if >=26.2 <26.3 {
/*import com.crystalgraphics.mc.modern.platform.HostProfiler;
import com.crystalgraphics.trace.CgTrace;
import net.minecraft.client.Minecraft;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.profiling.SingleTickProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Minecraft's profiler sections into the trace, beside the profiler it builds for each frame: no event reaches it.
@Mixin(value = Minecraft.class, remap = false)
public abstract class ProfilerHook {

    @Inject(method = "constructProfiler", at = @At("RETURN"), cancellable = true, require = 1)
    private void crystalgraphics$trace(boolean frameProfile, SingleTickProfiler tickProfiler,
                                       CallbackInfoReturnable<ProfilerFiller> cir) {
        if (CgTrace.isEnabled(HostProfiler.CHANNEL)) {
            cir.setReturnValue(ProfilerFiller.combine(cir.getReturnValue(), HostProfiler.INSTANCE));
        }
    }
}
*///?} else {
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;

// Empty on the nodes that pin this plugin and have no Minecraft 26.2 profiler hook.
@Mixin(value = Camera.class, remap = false)
public abstract class ProfilerHook {
}
//?}
