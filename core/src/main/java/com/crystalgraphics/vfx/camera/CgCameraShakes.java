package com.crystalgraphics.vfx.camera;

/**
 * Ready-made camera shakes, each sized to be played at the scale its javadoc names. Vary one with
 * {@link CgCameraShake#toBuilder()}.
 *
 * <pre>{@code
 * CgCameraShakes.EXPLOSION.play(x, y, z, blastRadius);
 * CgCameraShakes.RECOIL.play(x, y, z, -aimX, -aimY, -aimZ, 1f);
 * CgCameraShake bigger = CgCameraShakes.IMPACT.toBuilder().punch(1.2f).build();
 * }</pre>
 */
public final class CgCameraShakes {

    /**
     * A blast, played at its radius: full trauma and a shove away from it within the radius, none past five, then the
     * ground trembling on for four seconds. Felt at once; give it {@code arrives} to match a visible shock front.
     */
    public static final CgCameraShake EXPLOSION = CgCameraShake.builder()
            .trauma(1f).punch(1.5f).tremor(0.85f, 4f).kick(0.14f, 0.5f).radii(1f, 5f).build();

    /** A heavy hit, played at the size of what hit: a jolt and a shove away from it, within one size, none past six. */
    public static final CgCameraShake IMPACT = CgCameraShake.builder()
            .trauma(0.6f).punch(0.8f).kick(0.06f, 0.25f).radii(1f, 6f).build();

    /**
     * Firing something heavy, played at scale 1 with the direction it pushes back: a jolt, a shove back and a kick of
     * the field of view, full within 3 blocks and gone by 30.
     */
    public static final CgCameraShake RECOIL = CgCameraShake.builder()
            .trauma(0.7f).punch(0.7f).kick(0.1f, 0.35f).radii(3f, 30f).build();

    /** A held tremor ({@link CgCameraShake#hold()}), at scale 1: up to 0.3 trauma, full within 4 blocks, none past 24. */
    public static final CgCameraShake RUMBLE = CgCameraShake.builder().trauma(0.3f).radii(4f, 24f).build();

    private CgCameraShakes() {
    }
}
