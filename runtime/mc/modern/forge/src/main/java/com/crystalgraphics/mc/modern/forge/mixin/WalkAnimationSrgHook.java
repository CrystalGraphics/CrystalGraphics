package com.crystalgraphics.mc.modern.forge.mixin;

//? if >=1.19.4 {
import com.crystalgraphics.mc.modern.platform.HostWalk;
import net.minecraft.world.entity.WalkAnimationState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

// WalkAnimationHook by an installed Forge's SRG names (1.19.4-1.20.4; read from Forge 47.2.0's SRG jar, setSpeed and
// position() telling them apart).
@Mixin(value = WalkAnimationState.class, remap = false)
public abstract class WalkAnimationSrgHook implements HostWalk {

    @Shadow
    private float f_267406_;

    @Shadow
    private float f_267371_;

    @Shadow
    private float f_267358_;

    @Override
    public void crystalgraphics$walk(float position, float speed, float speedOld) {
        f_267358_ = position;
        f_267371_ = speed;
        f_267406_ = speedOld;
    }
}
//?} else {
/*import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;

// Empty before 1.19.4: the walk cycle is public fields there.
@Mixin(value = Camera.class, remap = false)
public abstract class WalkAnimationSrgHook {
}
*///?}
