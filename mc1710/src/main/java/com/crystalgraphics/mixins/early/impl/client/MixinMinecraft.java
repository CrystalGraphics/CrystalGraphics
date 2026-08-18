package com.crystalgraphics.mixins.early.impl.client;

import com.crystalgraphics.platform.CgPlatform;
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
        CgPlatform.lifecycle().onResize(width, height);
    }

    @Inject(method = "toggleFullscreen", at = @At("TAIL"))
    private void updateDisplayMode(CallbackInfo ci) {
        CgPlatform.lifecycle().onResize(displayWidth, displayHeight);
    }

    @Inject(method = "refreshResources", at = @At("TAIL"))
    private void onRefresh(CallbackInfo ci) {
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
     * That is the whole claim of CrystalGUI's live name environment, and until this existed the claim had
     * no callable witness. See {@code plan_m12.md} §26.4 and exit criterion 3.</p>
     *
     * <p>It reports live state rather than a constant, so a script that prints it demonstrates the method
     * really ran inside the game rather than resolving against something stubbed.</p>
     */
    public String cgMixinProbe() {
        return "cg-mixin-live " + displayWidth + "x" + displayHeight;
    }
}
