package com.crystalgraphics.mc.modern.neoforge.mixin;

import com.crystalgraphics.mc.modern.platform.HostWalk;
import net.minecraft.world.entity.WalkAnimationState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

// A pose's walk cycle set for one draw and put back (HostWalk), by Mojang's names.
@Mixin(value = WalkAnimationState.class, remap = false)
public abstract class WalkAnimationHook implements HostWalk {

    @Shadow
    private float speedOld;

    @Shadow
    private float speed;

    @Shadow
    private float position;

    @Override
    public void crystalgraphics$walk(float position, float speed, float speedOld) {
        this.position = position;
        this.speed = speed;
        this.speedOld = speedOld;
    }
}
