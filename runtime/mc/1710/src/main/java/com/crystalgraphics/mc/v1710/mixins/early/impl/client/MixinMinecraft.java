package com.crystalgraphics.mc.v1710.mixins.early.impl.client;

import com.crystalgraphics.lifecycle.CgEngineLifecycle;
import com.crystalgraphics.lifecycle.CgGraphicsLifecycle;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Minecraft.class)
public class MixinMinecraft {

    @Shadow
    public int displayWidth;

    @Shadow
    public int displayHeight;

    @Inject(method = "resize", at = @At("TAIL"))
    private void onResize(int width, int height, CallbackInfo ci) {
        CgGraphicsLifecycle.onResize(width, height);
    }

    @Inject(method = "toggleFullscreen", at = @At("TAIL"))
    private void updateDisplayMode(CallbackInfo ci) {
        CgGraphicsLifecycle.onResize(displayWidth, displayHeight);
    }

    @Inject(method = "refreshResources", at = @At("TAIL"))
    private void onRefresh(CallbackInfo ci) {
    }

    /**
     * Frees the GL context's resources at game exit.
     *
     * <p><b>HEAD, not TAIL</b>: a listener frees its GL objects only while the context is whole, and by the tail
     * Minecraft has torn the display down.</p>
     *
     * <p>{@code shutdownMinecraftApplet} rather than {@code shutdown}, because it is the one every exit
     * path reaches: {@code Minecraft.run}'s {@code finally} calls it, which covers the ordinary quit,
     * the crash path, and the silent {@code MinecraftError} exit that leaves no report at all.</p>
     */
    @Inject(method = "shutdownMinecraftApplet", at = @At("HEAD"))
    private void onShutdown(CallbackInfo ci) {
        CgEngineLifecycle.shutdown();
    }

    /**
     * <b>A PUBLIC member of {@code Minecraft} that no class file on disk declares.</b>
     *
     * <p>Every other member this mixin contributes is an {@code @Inject} handler, and Mixin merges those
     * as <em>private synthetics</em> — real, present in the live bytes, and uncallable by anything
     * outside. That makes them proof that a transformer added something, and no proof at all that a
     * consumer can reach it.</p>
     *
     * <p>This one is reachable. It exists so a CrystalGUI script can write
     * {@code Minecraft.getMinecraft().cgMixinProbe()} and have that <b>compile</b> — which a compiler
     * given a file-based classpath cannot do, because the method is in no jar, in no source tree, and in
     * nothing a resource lookup can return. Only bytecode read back out of the running loader carries it.
     * That is the whole claim of CrystalGUI's live name environment -- a compiler that resolves against
     * bytecode read back out of the running class loader rather than against files -- and until this
     * existed the claim had no callable witness.</p>
     *
     * <p>It reports live state rather than a constant, so a script that prints it demonstrates the method
     * really ran inside the game rather than resolving against something stubbed.</p>
     */
    public String cgMixinProbe() {
        return "cg-mixin-live " + displayWidth + "x" + displayHeight;
    }
}
