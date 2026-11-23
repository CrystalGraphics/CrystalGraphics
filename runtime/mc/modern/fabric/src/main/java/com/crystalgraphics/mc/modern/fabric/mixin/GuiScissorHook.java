package com.crystalgraphics.mc.modern.fabric.mixin;

//? if >=1.16.5 <1.21.5 {
import com.crystalgraphics.mc.modern.platform.PicturesModern;
import com.mojang.blaze3d.platform.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

// While a picture's cell draws, a scissor Minecraft's GUI sets (in the window's pixels, the cell's) is moved to the cell
// on its page: a scissor is in the framebuffer's pixels, which the cell's viewport does not move. com.mojang.blaze3d is
// never obfuscated, so the name holds on every loader.
@Mixin(value = GlStateManager.class, remap = false)
public abstract class GuiScissorHook {

    @ModifyVariable(method = "_scissorBox", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 1)
    private static int crystalgraphics$x(int x) {
        return x + PicturesModern.scissorX();
    }

    @ModifyVariable(method = "_scissorBox", at = @At("HEAD"), argsOnly = true, ordinal = 1, require = 1)
    private static int crystalgraphics$y(int y) {
        return y + PicturesModern.scissorY();
    }
}
//?} else {
/*import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;

// Empty outside 1.16.5 - 1.21.4: below, the GUI sets no scissor through the state manager; from 1.21.5 a cell is drawn
// into a scratch target at its origin and copied.
@Mixin(value = Camera.class, remap = false)
public abstract class GuiScissorHook {
}
*///?}
