package com.crystalgraphics.mc.v1710.mixins.early.impl.client;

import com.crystalgraphics.mc.v1710.platform.actor.HostRender1710;
import net.minecraft.client.renderer.entity.RendererLivingEntity;
import net.minecraft.entity.EntityLivingBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** A living entity's name tag, for {@code ActorRender1710}: its renderer's own, which a player's overrides. */
@Mixin(RendererLivingEntity.class)
public abstract class RendererLivingEntityHook implements HostRender1710.Living {

    @Shadow
    protected abstract void passSpecialRender(EntityLivingBase entity, double x, double y, double z);

    @Override
    public void crystalgraphics$passSpecialRender(EntityLivingBase entity, double x, double y, double z) {
        passSpecialRender(entity, x, y, z);
    }
}
