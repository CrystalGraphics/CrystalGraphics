package com.crystalgraphics.vfx.camera;

import javax.annotation.Nullable;

import org.joml.Vector3fc;

/**
 * One shake pattern playing at a point: Unreal's {@code UCameraShakeBase} instance. Each frame in {@code SHAKE} its
 * pattern's result is weighted by its blend, its distance and the player's screen effects, turned into the play's
 * space and summed into the camera beside every {@link CgCameraShake}.
 *
 * <pre>{@code
 * CgShakePlay play = definition.play(x, y, z, 1f, seed);   // a shake asset's, pooled
 * play.stop(false);                                         // blends out; stop(true) ends it this frame
 * }</pre>
 *
 * <ul>
 *   <li>Once it has ended it goes back to its {@link CgShakeSource} and may be started again: drop the handle when
 *       {@link #playing()} is false.</li>
 *   <li>Rotation is summed into the host's yaw, pitch and roll: exact for one play, and for several while they stay
 *       small, as a shake's do.</li>
 * </ul>
 */
public final class CgShakePlay {

    private final CgShakeSource source;
    private final CgShakePattern pattern;
    private final CgCameraShakeState state;
    double x, y, z;
    float scale, inner, outer, falloff;
    boolean world, live;

    public CgShakePlay(CgShakeSource source, CgShakePattern pattern, CgCameraShakeState state) {
        this.source = source;
        this.pattern = pattern;
        this.state = state;
    }

    /** Starts it at the point, scaled, felt as its source says: from this frame. A play already running restarts its blend there. */
    public CgShakePlay start(double x, double y, double z, float scale) {
        return start(x, y, z, scale, source.inner(), source.outer(), source.falloff(), source.world());
    }

    /**
     * Starts it at the point, scaled, its radii (blocks; an {@code outer} of 0 for everywhere), falloff and space
     * the play site's rather than its source's.
     */
    public CgShakePlay start(double x, double y, double z, float scale, float inner, float outer, float falloff, boolean world) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.scale = scale;
        this.inner = inner;
        this.outer = outer;
        this.falloff = falloff;
        this.world = world;
        state.start();
        if (!live) {
            live = true;
            CgShakeRuntime.add(this);
        }
        return this;
    }

    /** Blends it out by its pattern's blend-out, or ends it this frame. */
    public void stop(boolean immediately) {
        state.stop(immediately);
    }

    public boolean playing() {
        return live;
    }

    /** {@code source}'s play running, or null: a single-instance shake restarts it rather than starting another. */
    @Nullable
    public static CgShakePlay playing(CgShakeSource source) {
        return CgShakeRuntime.playing(source);
    }

    public CgShakeSource source() {
        return source;
    }

    public CgShakePattern pattern() {
        return pattern;
    }

    /** Steps the pattern and its blend; false once it has ended, and it is handed back to its source. */
    boolean step(float dt, float[] out) {
        float weight = state.update(dt);
        if (!state.playing()) {
            live = false;
            source.give(this);
            return false;
        }
        pattern.update(dt, out);
        float w = weight * scale;
        for (int c = 0; c < CgShakePattern.CHANNELS; c++) out[c] *= w;
        return true;
    }

    /**
     * {@code result} turned into the host's offset, as {@code UCameraShakeBase::ApplyPlaySpace}: {@code out} gets
     * blocks along the world's axes, then yaw (turning right), pitch (looking down) and roll in degrees.
     * {@code forward}, {@code right} and {@code up} are the camera's axes in the world.
     */
    static void toHost(float[] result, boolean world, int[] axes, Vector3fc forward, Vector3fc right, Vector3fc up, float[] out) {
        float lx = result[CgShakePattern.X], ly = result[CgShakePattern.Y], lz = result[CgShakePattern.Z];
        if (world) {
            for (int i = 0; i < 3; i++) {
                int a = axes[i];
                out[i] = Math.signum(a) * result[Math.abs(a) - 1];
            }
        } else {
            out[0] = forward.x() * lx + right.x() * ly + up.x() * lz;
            out[1] = forward.y() * lx + right.y() * ly + up.y() * lz;
            out[2] = forward.z() * lx + right.z() * ly + up.z() * lz;
        }
        double p = Math.toRadians(result[CgShakePattern.PITCH]), yw = Math.toRadians(result[CgShakePattern.YAW]);
        double r = Math.toRadians(result[CgShakePattern.ROLL]);
        if (!world) {
            // CameraAnimationHelper::ApplyOffset: OffsetRot * CameraRot, the offset in the camera's own frame.
            out[3] = result[CgShakePattern.YAW];
            out[4] = -result[CgShakePattern.PITCH];
            out[5] = result[CgShakePattern.ROLL];
            return;
        }
        // CameraRot * OffsetRot: the offset turns the world. Its effect seen from the camera, D = Cam * Offset * Cam^-1,
        // read back as a rotator; the matrix is FRotationTranslationMatrix's, rows forward, right, up.
        double sp = Math.sin(p), cp = Math.cos(p), sy = Math.sin(yw), cy = Math.cos(yw), sr = Math.sin(r), cr = Math.cos(r);
        M[0] = cp * cy;                  M[1] = cp * sy;                  M[2] = sp;
        M[3] = sr * sp * cy - cr * sy;   M[4] = sr * sp * sy + cr * cy;   M[5] = -sr * cp;
        M[6] = -(cr * sp * cy + sr * sy); M[7] = cy * sr - cr * sp * sy;  M[8] = cr * cp;
        unreal(forward, axes, B, 0);
        unreal(right, axes, B, 3);
        unreal(up, axes, B, 6);
        double d00 = row(0, 0), d01 = row(0, 1), d02 = row(0, 2), d12 = row(1, 2), d22 = row(2, 2);
        out[3] = (float) Math.toDegrees(Math.atan2(d01, d00));
        out[4] = (float) -Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, d02))));
        out[5] = (float) Math.toDegrees(Math.atan2(-d12, d22));
    }

    private static final double[] M = new double[9], B = new double[9];

    /** {@code (B_k * M) . B_l}: the camera's axis {@code k} turned by the offset, along its axis {@code l}. */
    private static double row(int k, int l) {
        double s = 0;
        for (int j = 0; j < 3; j++) {
            double turned = B[k * 3] * M[j] + B[k * 3 + 1] * M[3 + j] + B[k * 3 + 2] * M[6 + j];
            s += turned * B[l * 3 + j];
        }
        return s;
    }

    /** A host vector in the pattern's world axes. */
    private static void unreal(Vector3fc v, int[] axes, double[] out, int at) {
        for (int i = 0; i < 3; i++) out[at + Math.abs(axes[i]) - 1] = Math.signum(axes[i]) * v.get(i);
    }
}
