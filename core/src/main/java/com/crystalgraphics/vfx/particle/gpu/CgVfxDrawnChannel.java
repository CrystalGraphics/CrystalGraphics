package com.crystalgraphics.vfx.particle.gpu;

import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.vfx.particle.form.CgVfxFormInput;

import javax.annotation.Nullable;

/**
 * One channel of a value a particle carries for its draws alone, as Niagara's DynamicMaterialParameter, SubImageIndex
 * and CameraOffset attributes are: a value over the particle's life, or a mix of two over life by a roll. The Step's
 * Look stage writes it after the particle's drawn part; a material reads it through {@code cg_dynamic_parameter}, and
 * the sprite factory reads the sub-image index and camera offset.
 *
 * <pre>{@code
 * CgVfxEmitter.builder("glow")
 *         .drawn(CgVfxDrawnChannel.DYNAMIC_0, 0, CgVfxDrawnChannel.over(fade))           // 0 to 1 over life
 *         .drawn(CgVfxDrawnChannel.DYNAMIC_0, 1, CgVfxDrawnChannel.spawn(0.1f, 0.2f))    // its own, fixed at birth
 *         .build();
 * }</pre>
 *
 * <pre>{@code
 * .drawn(CgVfxDrawnChannel.SPRITE, CgVfxDrawnChannel.SUB_IMAGE, CgVfxDrawnChannel.over(frames))   // a frame by age
 * .drawn(CgVfxDrawnChannel.SPRITE, CgVfxDrawnChannel.CAMERA_OFFSET, CgVfxDrawnChannel.update(-0.5f, 0f))
 * }</pre>
 *
 * <ul>
 *   <li>A slot is drawn only where its emitter sets a channel of it; a channel left unset there reads 0.</li>
 *   <li>{@link Roll#UPDATE} rolls again each step, as Cascade's distributions do on every update: a flicker, not a
 *       value per particle.</li>
 *   <li>A sub-image index is a frame of the renderer's {@code sub_image}, truncated unless it blends.</li>
 * </ul>
 *
 * @param from     the value over life, or a roll's low end
 * @param to       a roll's high end; {@code from} where it does not roll
 * @param roll     how the two mix
 * @param bind     its instance's user parameter, remapped, where {@code roll} is {@link Roll#BIND}; else null
 * @param realTime whether its life runs on the viewer's clock, as Cascade's SubUV {@code bUseRealTime} divides its
 *                 relative time by the time dilation: slow motion does not slow it
 */
public record CgVfxDrawnChannel(CgKeyframes from, CgKeyframes to, Roll roll, @Nullable CgVfxFormInput.Binding bind,
                                boolean realTime) {

    public CgVfxDrawnChannel(CgKeyframes from, CgKeyframes to, Roll roll) {
        this(from, to, roll, null, false);
    }

    /** Where a particle's draws read a slot: Niagara's {@code DynamicMaterialParameter} to {@code _3}, then the sprite's. */
    public static final int DYNAMIC_0 = 0, DYNAMIC_1 = 1, DYNAMIC_2 = 2, DYNAMIC_3 = 3, SPRITE = 4;

    /** Slots a particle may carry. */
    public static final int SLOTS = 5;

    /**
     * A drawn mask's bit for a mesh orientation ({@code CgVfxMeshRotation}), past the slots: Look writes the quaternion
     * into the slots' element's {@code scale}, which Range's {@code Objects} turns the mesh by.
     */
    public static final int ORIENTATION = 5;

    /** The {@link #SPRITE} slot's channels: its frame of the renderer's sub-images, and blocks drawn toward the eye. */
    public static final int SUB_IMAGE = 0, CAMERA_OFFSET = 1;

    /** How a channel's two ends mix. */
    public enum Roll {
        /** {@code from} alone. */
        NONE,
        /** By a roll from the particle's seed: fixed for its life. */
        SPAWN,
        /** By a roll made again each step. */
        UPDATE,
        /** Its instance's user parameter ({@link #bind}), the same for every particle and read again each step. */
        BIND
    }

    /** {@code value} over life, the same for every particle. */
    public static CgVfxDrawnChannel over(CgKeyframes value) {
        return new CgVfxDrawnChannel(value, value, Roll.NONE);
    }

    /** Between {@code min} and {@code max}, each particle its own, fixed at birth. */
    public static CgVfxDrawnChannel spawn(float min, float max) {
        return new CgVfxDrawnChannel(CgKeyframes.constant(min), CgKeyframes.constant(max), Roll.SPAWN);
    }

    /** Its instance's user parameter, remapped: Cascade's particle parameter distribution. */
    public static CgVfxDrawnChannel bound(CgVfxFormInput.Binding bind) {
        if (!bind.path().startsWith("user.")) throw new IllegalArgumentException("a drawn channel binds a user parameter, not " + bind.path());
        return new CgVfxDrawnChannel(CgKeyframes.constant(0f), CgKeyframes.constant(0f), Roll.BIND, bind, false);
    }

    /** This channel on the viewer's clock: its life read as Cascade's {@code bUseRealTime} reads it. */
    public CgVfxDrawnChannel inRealTime() {
        return new CgVfxDrawnChannel(from, to, roll, bind, true);
    }

    /** Between {@code min} and {@code max}, rolled again each step. */
    public static CgVfxDrawnChannel update(float min, float max) {
        return new CgVfxDrawnChannel(CgKeyframes.constant(min), CgKeyframes.constant(max), Roll.UPDATE);
    }
}
