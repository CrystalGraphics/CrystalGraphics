package com.crystalgraphics.mc.legacy.platform.world;

import com.crystalgraphics.platform.service.CgHostCamera;
import net.minecraftforge.client.event.EntityViewRenderEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * {@link CgHostCamera} on Forge 1.8.9 to 1.12.2: holds the last offset core gave and adds it in Forge's camera-setup
 * and field-of-view events. Client only: provided by {@code PlatformServiceLegacy.register}, which also registers this
 * instance on Forge's event bus.
 *
 * <ul>
 *   <li>Rotation, roll and field of view only: no event offers the camera's position, so the translation is dropped.</li>
 *   <li>Render thread only, like the events that read it.</li>
 * </ul>
 */
public final class HostCameraLegacy implements CgHostCamera {

    private float yaw, pitch, roll, fovScale = 1f;

    @Override
    public void offset(float x, float y, float z, float yaw, float pitch, float roll, float fovScale) {
        this.yaw = yaw;
        this.pitch = pitch;
        this.roll = roll;
        this.fovScale = fovScale;
    }

    @Override
    public int capabilities() {
        return ROTATION | ROLL | FOV;
    }

    @SubscribeEvent
    public void onCameraSetup(EntityViewRenderEvent.CameraSetup event) {
        //? if >=1.9 {
        event.setYaw(event.getYaw() + yaw);
        event.setPitch(event.getPitch() + pitch);
        event.setRoll(event.getRoll() + roll);
        //?} else {
        /*event.yaw += yaw;
        event.pitch += pitch;
        event.roll += roll;
        *///?}
    }

    @SubscribeEvent
    public void onFov(EntityViewRenderEvent.FOVModifier event) {
        event.setFOV(event.getFOV() * fovScale);
    }
}
