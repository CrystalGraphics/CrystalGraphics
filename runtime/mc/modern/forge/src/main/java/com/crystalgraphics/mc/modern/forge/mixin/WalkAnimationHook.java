package com.crystalgraphics.mc.modern.forge.mixin;

//? if >=1.19.4 {
import com.crystalgraphics.mc.modern.platform.HostWalk;
import net.minecraft.world.entity.WalkAnimationState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

// A pose's walk cycle set for one draw and put back (HostWalk), by Mojang's names. WalkAnimationSrgHook is an installed
// Forge's; VariantMixins applies the one the class has.
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
//?} else {
/*import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;

// Empty before 1.19.4: the walk cycle is public fields there.
@Mixin(value = Camera.class, remap = false)
public abstract class WalkAnimationHook {
}
*///?}
