package com.crystalgraphics.mc.v1710.mixins.early.impl.client;

import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The entity renderer's private lightmap texture. */
@Mixin(EntityRenderer.class)
public interface EntityRendererAccessor {

    @Accessor("lightmapTexture")
    DynamicTexture crystalgraphics$lightmap();
}
