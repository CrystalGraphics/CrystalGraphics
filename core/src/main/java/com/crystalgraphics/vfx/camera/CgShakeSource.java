package com.crystalgraphics.vfx.camera;

/**
 * Where a {@link CgShakePlay} comes from: its play space and radii, and the pool it returns to. A shake asset's compiled
 * definition is one; a mod with a pattern of its own implements it.
 *
 * <pre>{@code
 * CgShakeSource source = new CgShakeSource() {
 *     public boolean world() { return false; }                // the camera's own axes
 *     public float inner() { return 0; }
 *     public float outer() { return 0; }                      // felt everywhere
 *     public void give(CgShakePlay play) { pool.push(play); }
 * };
 * CgShakePlay play = pool.isEmpty() ? new CgShakePlay(source, myPattern, new CgCameraShakeState(0.1f, 0.2f, 1f)) : pool.pop();
 * play.start(x, y, z, 1f);
 * }</pre>
 */
public interface CgShakeSource {

    /** True for the world's axes ({@code ECameraShakePlaySpace::World}); false for the camera's. */
    boolean world();

    /**
     * For a world play, the pattern's axes in the host's: host axis {@code i} is the pattern's {@code |axes[i]| - 1},
     * signed. Unreal's {@code {1, 3, 2}} by default.
     */
    default int[] axes() {
        return UNREAL_AXES;
    }

    /** Felt in full within this many blocks of the play's point. */
    float inner();

    /** Not felt beyond this many blocks; 0 for everywhere. */
    float outer();

    /** The exponent of the fade from {@link #inner} to {@link #outer}. */
    default float falloff() {
        return 1f;
    }

    /** Takes {@code play} back once it has ended, for its next start. */
    void give(CgShakePlay play);

    int[] UNREAL_AXES = {1, 3, 2};
}
