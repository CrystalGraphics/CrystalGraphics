package com.crystalgraphics.vfx.particle.renderer;

import com.crystalgraphics.object.value.CgUnits;
import org.joml.Matrix3f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * How an emitter's mesh particles turn to face something, from its mesh renderers: Niagara's mesh facing
 * ({@code NiagaraMeshParticleUtils.ush}, {@code NiagaraGetMeshFacingMatrix}) with Cascade's options it lacks
 * ({@code ParticleSystemRender.cpp}, {@code FDynamicMeshEmitterData::CalculateParticleTransform}), its mesh's rotation
 * and scale, and a rotation rolled per particle. A mesh is turned, innermost first, by its pre-rotation, sized, turned
 * by its mesh rotation and the particle's own, faced, then rolled by the particle's sprite rotation.
 *
 * <pre>{@code
 * CgVfxMeshFacing f = CgVfxMeshFacing.of("rocks", emitter.renderers(), emitter.local());
 * f.words();     // what the draw's object pass reads per slot
 * }</pre>
 *
 * <pre>{@code
 * renderers:
 *   - { id: r1, kind: mesh, meshes: [{ mesh: ..., rotation: [0, 1.5708, 0] }], facing: camera_position,
 *       facing_up: none, roll_about: facing, pre_rotation: { range: [[0, 0, 0], [0, 0, 6.2832]], per_channel: true } }
 * }</pre>
 *
 * <ul>
 *   <li>One emitter's mesh particles are placed once for all its mesh renderers: renderers that face differently fail
 *       at load, naming the emitter.</li>
 *   <li>A locked axis turns only a facing mesh, as Niagara's does; under {@code default} it is ignored.</li>
 * </ul>
 */
public record CgVfxMeshFacing(float[] words) {

    /** The vec4s a slot's facing takes in the object pass's table. */
    public static final int VECTORS = 6;
    /** Facing nothing: the mesh as the particle's own rotation turns it. */
    public static final CgVfxMeshFacing NONE = new CgVfxMeshFacing(defaults());

    private static float[] defaults() {
        float[] w = new float[VECTORS * 4];
        w[11] = 1f;   // the mesh rotation: the identity
        w[20] = w[21] = w[22] = 1f;   // the mesh scale
        return w;
    }

    /**
     * {@code renderers}' mesh facing: their first mesh renderer's, every other mesh renderer's the same. {@code local}
     * is the emitter's: a locked axis in simulation space then turns with its effect.
     */
    public static CgVfxMeshFacing of(String who, List<CgVfxRendererProperties> renderers, boolean local) {
        float[] words = null;
        for (CgVfxRendererProperties r : renderers) {
            if (!(r instanceof CgVfxMeshRendererProperties m)) continue;
            float[] w = words(who, m, local);
            if (words != null && !Arrays.equals(words, w)) {
                throw new IllegalArgumentException(who + "'s mesh renderers face, turn or size their meshes differently: its"
                        + " particles are placed once for every mesh renderer");
            }
            words = w;
        }
        return words == null ? NONE : new CgVfxMeshFacing(words);
    }

    private static float[] words(String who, CgVfxMeshRendererProperties m, boolean local) {
        float[] w = defaults();
        w[0] = m.facing().ordinal();
        w[1] = m.facingUp() == null ? 0f : m.facingUp().ordinal() + 1;
        w[2] = m.rollAbout() == null ? 0f : m.rollAbout().ordinal() + 1;
        if (m.lockedAxis() != null) {
            float[] axis = vector(who, "locked_axis's axis", m.lockedAxis().get("axis"), new float[]{0f, 1f, 0f});
            float length = (float) Math.sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2]);
            if (!(length > 0f)) throw new IllegalArgumentException(who + "'s locked_axis has no direction");
            Object space = m.lockedAxis().get("space");
            w[3] = 1f;
            for (int a = 0; a < 3; a++) w[4 + a] = axis[a] / length;
            // Niagara's: a simulation-space axis is the effect's under a local emitter (bLocalSpace).
            w[7] = "local".equals(space) || (local && (space == null || "simulation".equals(space))) ? 1f : 0f;
        }
        if (!m.meshes().isEmpty()) {
            Map<String, Object> mesh = m.meshes().get(0);
            if (mesh.get("rotation") != null) {
                float[] r = vector(who, "mesh's rotation", mesh.get("rotation"), null);
                System.arraycopy(CgUnits.rotatorToHost(r[0], r[1], r[2]), 0, w, 8, 4);
            }
            if (mesh.get("scale") != null) System.arraycopy(vector(who, "mesh's scale", mesh.get("scale"), null), 0, w, 20, 3);
        }
        Object pre = m.preRotation();
        if (pre instanceof List<?>) {
            float[] r = vector(who, "pre_rotation", pre, null);
            System.arraycopy(r, 0, w, 12, 3);
            System.arraycopy(r, 0, w, 16, 3);
            w[15] = 1f;
        } else if (pre instanceof Map<?, ?> range && range.get("range") instanceof List<?> ends && ends.size() == 2
                && range.keySet().stream().allMatch(k -> k.equals("range") || k.equals("per_channel"))) {
            System.arraycopy(vector(who, "pre_rotation's low end", ends.get(0), null), 0, w, 12, 3);
            System.arraycopy(vector(who, "pre_rotation's high end", ends.get(1), null), 0, w, 16, 3);
            w[15] = Boolean.TRUE.equals(range.get("per_channel")) ? 2f : 1f;
        } else if (pre instanceof Map<?, ?> pick && pick.size() == 1 && pick.get("pick") instanceof List<?> ends && ends.size() == 2) {
            // Cascade's extremes: one roll for the whole rotation, the second end above a half (Distributions.cpp:731-741).
            System.arraycopy(vector(who, "pre_rotation's first end", ends.get(0), null), 0, w, 12, 3);
            System.arraycopy(vector(who, "pre_rotation's second end", ends.get(1), null), 0, w, 16, 3);
            w[15] = 3f;
        } else if (pre != null) {
            throw new IllegalArgumentException(who + "'s pre_rotation " + pre + " is not read yet: a rotation, a range of them or a"
                    + " pick of two");
        }
        return w;
    }

    private static float[] vector(String who, String what, @Nullable Object v, @Nullable float[] fallback) {
        if (v == null && fallback != null) return fallback;
        if (!(v instanceof List<?> l) || l.size() != 3 || !l.stream().allMatch(Number.class::isInstance)) {
            throw new IllegalArgumentException(who + "'s " + what + " " + v + " is not three numbers");
        }
        return new float[]{((Number) l.get(0)).floatValue(), ((Number) l.get(1)).floatValue(), ((Number) l.get(2)).floatValue()};
    }

    /** Whether it turns its meshes toward anything. */
    public boolean faces() {
        return words[0] != 0f;
    }

    /**
     * The Java twin of the object pass's turn ({@code range.compute}, {@code range_facing}), for checking it: the 3x3
     * placing a mesh particle, its columns its axes times its size. {@code toCamera} is from the particle to the eye,
     * {@code forward} and {@code up} the camera's, {@code slotTurn} its slot's rotation; {@code particle} its own
     * rotation, {@code rolls} its three pre-rotation rolls.
     */
    public Matrix3f model(Vector3f toCamera, Vector3f forward, Vector3f up, Vector3f velocity, float spin, Quaternionf slotTurn,
                          Quaternionf particle, Vector3f size, float[] rolls, Matrix3f out) {
        Vector3f worldUp = new Vector3f(0f, 1f, 0f), worldX = new Vector3f(1f, 0f, 0f);
        Vector3f eye = new Vector3f(toCamera).normalize();
        int mode = (int) words[0], upMode = (int) words[1], roll = (int) words[2];
        Matrix3f facing = new Matrix3f();
        Vector3f faced = null, locked = null;
        if (mode != 0) {
            faced = mode == 1 ? (velocity.lengthSquared() > 0f ? new Vector3f(velocity).normalize() : new Vector3f(worldUp))
                    : mode == 2 ? new Vector3f(eye) : new Vector3f(forward).normalize().negate();
            if (words[3] != 0f) {
                locked = new Vector3f(words[4], words[5], words[6]);
                if (words[7] != 0f) slotTurn.transform(locked);
                if (Math.abs(faced.dot(locked)) > 0.99f) faced = Math.abs(locked.y) > 0.99f ? new Vector3f(worldX) : new Vector3f(worldUp);
                Vector3f y = new Vector3f(faced).cross(locked).normalize(), x = new Vector3f(locked).cross(y);
                facing.setColumn(0, x).setColumn(1, locked).setColumn(2, y);
            } else if (upMode == 3) {
                facing.set(between(worldX, faced));
            } else {
                Vector3f ref;
                if (upMode == 0 && mode == 3) {
                    ref = new Vector3f(up);
                } else {
                    // Niagara's world up, else world x when facing along it; Cascade's turn toward the camera first.
                    ref = upMode == 2 ? new Vector3f(eye) : new Vector3f(worldUp);
                    if (Math.abs(faced.dot(ref)) > 0.99f) ref = new Vector3f(worldUp);
                    float d = faced.dot(ref);
                    if (Math.abs(d) > 0.99f) ref = new Vector3f(worldX).mul(-Math.signum(d));
                }
                Vector3f y = new Vector3f(faced).cross(ref).normalize(), z = new Vector3f(y).cross(faced);
                facing.setColumn(0, faced).setColumn(1, z).setColumn(2, y);
            }
        }
        out.set(facing).mul(new Matrix3f().set(particle)).mul(new Matrix3f().set(new Quaternionf(words[8], words[9], words[10], words[11])));
        if (mode != 0 && roll != 0) {
            Vector3f axis = roll == 2 ? eye : locked != null ? locked : faced;
            out.set(new Matrix3f().set(new Quaternionf().fromAxisAngleRad(axis, -spin)).mul(out));
        }
        out.scale(size.x * words[20], size.y * words[21], size.z * words[22]);
        if (words[15] != 0f) {
            float[] r = new float[3];
            for (int c = 0; c < 3; c++) {
                float u = words[15] == 3f ? (rolls[0] > 0.5f ? 1f : 0f) : words[15] == 2f ? rolls[c] : rolls[0];
                r[c] = words[12 + c] + (words[16 + c] - words[12 + c]) * u;
            }
            float[] q = CgUnits.rotatorToHost(r[0], r[1], r[2]);
            out.mul(new Matrix3f().set(new Quaternionf(q[0], q[1], q[2], q[3])));
        }
        return out;
    }

    /** Unreal's FindBetweenNormals: the shortest turn from {@code a} to {@code b}, half a turn about an axis across a. */
    private static Quaternionf between(Vector3f a, Vector3f b) {
        float w = 1f + a.dot(b);
        if (w < 1e-6f) {
            Vector3f axis = Math.abs(a.x) > Math.abs(a.z) ? new Vector3f(-a.y, a.x, 0f) : new Vector3f(0f, -a.z, a.y);
            axis.normalize();
            return new Quaternionf(axis.x, axis.y, axis.z, 0f);
        }
        Vector3f c = new Vector3f(a).cross(b);
        return new Quaternionf(c.x, c.y, c.z, w).normalize();
    }
}
