package com.crystalgraphics.mc.legacy.mixin;

import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The entity renderer's private lightmap texture, at its SRG name, the same on every plateau. */
@Mixin(value = EntityRenderer.class, remap = false)
public interface EntityRendererAccessor {

    @Accessor(value = "field_78513_d", remap = false)
    DynamicTexture crystalgraphics$lightmap();
}
