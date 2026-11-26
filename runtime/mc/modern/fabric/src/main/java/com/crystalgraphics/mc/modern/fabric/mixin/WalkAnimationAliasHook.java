package com.crystalgraphics.mc.modern.fabric.mixin;

//? if >=1.19.4 {
import com.crystalgraphics.mc.modern.platform.HostWalk;
import net.minecraft.world.entity.WalkAnimationState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

// A pose's walk cycle set for one draw and put back (HostWalk): Mojang's names in a dev run, intermediary's in a game
// (read from Loom's 1.20.1 intermediary jar, setSpeed telling speed apart).
@Mixin(value = WalkAnimationState.class, remap = false)
public abstract class WalkAnimationAliasHook implements HostWalk {

    @Shadow(aliases = "field_42109")
    private float speedOld;

    @Shadow(aliases = "field_42110")
    private float speed;

    @Shadow(aliases = "field_42111")
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
public abstract class WalkAnimationAliasHook {
}
*///?}
