package com.crystalgraphics.vfx.field;

import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;

/**
 * A field graph evaluated on the CPU, node for node as {@code fx_field.glsl} evaluates it on the GPU: Chaos's field nodes
 * ({@code FieldSystemNodes.cpp}'s {@code Evaluate}). The mirror the GPU is held to, and a field's value where a tool
 * asks.
 *
 * <pre>{@code
 * float[] v = new float[4];
 * CgFieldEval.graph(pull, 0f, 1f, 2f, 0f, seedBits, 0, v);   // at (0, 1, 2) in its own space: v[0..2], a scalar in all
 * }</pre>
 *
 * <ul>
 *   <li>The point is in the field's own space, from its placement and before its turn; the value is in that space too.</li>
 *   <li>{@code seed} and {@code key} feed {@code random_vector} as the GPU's particle seed and step do.</li>
 * </ul>
 */
public final class CgFieldEval {

    private CgFieldEval() {
    }

    /** {@code graph}'s root at {@code (x, y, z)} in its own space, {@code age} seconds into its life, into {@code out[0..3]}. */
    public static void graph(CgFieldDefinition.Graph graph, float x, float y, float z, float age, int seed, int key, float[] out) {
        int nodes = graph.nodes().size();
        float[][] r = new float[nodes][4];
        for (int n = 0; n < nodes; n++) {
            CgFieldNode node = graph.nodes().get(n);
            float[] p = node.params();
            int[] in = node.inputs();
            float[] v = r[n];
            switch (node.kind().name()) {
                case "radial_falloff" -> {
                    float radius = p[4], d = len(x - p[5], y - p[6], z - p[7]);
                    if (radius > 0f) fill(v, d < radius ? ranged(p, p[8], 1f - d / radius) : p[3]);
                }
                case "plane_falloff" -> {
                    float dist = p[4];
                    if (dist > 0f) {
                        float s = p[8] * x + p[9] * y + p[10] * z - (p[8] * p[5] + p[9] * p[6] + p[10] * p[7]);
                        fill(v, s < -1e-8f && s > -dist ? ranged(p, p[11], 1f + s / dist) : p[3]);
                    }
                }
                case "box_falloff" -> {
                    float[] q = turn(-p[7], -p[8], -p[9], p[10], x - p[4], y - p[5], z - p[6]);
                    float ex = Math.abs(q[0] / p[11]) - 0.5f, ey = Math.abs(q[1] / p[12]) - 0.5f, ez = Math.abs(q[2] / p[13]) - 0.5f;
                    float e = Math.max(ex, Math.max(ey, ez));
                    fill(v, e < 0f ? ranged(p, p[14], -Math.min(e, 0f) / 0.5f) : p[3]);
                }
                case "wave_scalar" -> {
                    float t = Math.max(age, 0f), radius = p[4] * t / p[5];
                    int fn = Math.round(p[6]);
                    if (fn == 3) {
                        float decay = t / p[5];
                        fill(v, p[0] * (float) Math.exp(-decay * decay));
                    } else {
                        float d = len(x - p[1], y - p[2], z - p[3]), frac = 1f - d / radius;
                        float phase = -(p[4] != 0f ? (float) (2 * Math.PI) / p[4] : 0f) * radius * frac;
                        if (fn == 0) fill(v, p[0] * (float) Math.cos(phase));
                        else if (fn == 1) fill(v, p[0] * (float) Math.exp(-phase * phase));
                        else if (d < radius && radius > 0f) fill(v, p[0] * falloff(p[7], frac));
                    }
                }
                case "uniform_scalar" -> fill(v, p[0]);
                case "uniform_vector" -> set(v, p[0] * p[1], p[0] * p[2], p[0] * p[3]);
                case "radial_vector", "rotated_radial_vector" -> {
                    float dx = x - p[1], dy = y - p[2], dz = z - p[3], l = len(dx, dy, dz);
                    float[] d = l > 1e-8f ? new float[]{dx / l, dy / l, dz / l} : new float[3];
                    if (node.kind() == CgFieldNodes.ROTATED_RADIAL_VECTOR) {
                        float al = len(p[5], p[6], p[7]), s = (float) Math.sin(p[4] * 0.5), c = (float) Math.cos(p[4] * 0.5);
                        float ax = al > 1e-8f ? p[5] / al : 0f, ay = al > 1e-8f ? p[6] / al : 0f, az = al > 1e-8f ? p[7] / al : 0f;
                        d = turn(ax * s, ay * s, az * s, c, d[0], d[1], d[2]);
                    }
                    set(v, p[0] * d[0], p[0] * d[1], p[0] * d[2]);
                }
                case "random_vector" -> {
                    float rx = CgVfxEmitterInstance.rand(seed, n, key * 3) * 2f - 1f;
                    float ry = CgVfxEmitterInstance.rand(seed, n, key * 3 + 1) * 2f - 1f;
                    float rz = CgVfxEmitterInstance.rand(seed, n, key * 3 + 2) * 2f - 1f;
                    float l = len(rx, ry, rz);
                    if (l > 1e-8f) set(v, p[0] * rx / l, p[0] * ry / l, p[0] * rz / l);
                }
                case "operator" -> {
                    boolean hl = in[0] >= 0, hr = in[1] >= 0;
                    float[] l = hl ? r[in[0]] : new float[4], rr = hr ? r[in[1]] : new float[4];
                    int o = Math.round(p[1]);
                    for (int c = 0; c < 4; c++) {
                        float value = hl && hr ? (o == 0 ? rr[c] * l[c] : o == 1 ? l[c] / rr[c] : o == 2 ? rr[c] + l[c] : l[c] - rr[c])
                                : hl ? l[c] : rr[c];
                        v[c] = value * p[0];
                    }
                }
                case "culling" -> {
                    float cull = in[0] >= 0 ? r[in[0]][0] : 0f;
                    boolean keep = Math.round(p[0]) == 0 ? cull == 0f : cull != 0f;
                    if (keep && in[1] >= 0) System.arraycopy(r[in[1]], 0, v, 0, 4);
                }
                default -> throw new IllegalArgumentException(node.id() + ": a " + node.kind().name() + " is not evaluated yet");
            }
        }
        System.arraycopy(r[nodes - 1], 0, out, 0, 4);
    }

    /** Chaos's {@code EvalFalloffFunction}: none, linear, inverse, squared, logarithmic, as declared. */
    static float falloff(float kind, float f) {
        return switch (Math.round(kind)) {
            case 0 -> 1f;
            case 1 -> f;
            case 2 -> 2f * (1f - 1f / (f + 1f));
            case 3 -> f * f;
            default -> (float) (Math.log(f + 1.0) / Math.log(2.0));
        };
    }

    private static float ranged(float[] p, float kind, float f) {
        return p[0] * (p[1] + (p[2] - p[1]) * falloff(kind, f));
    }

    private static float len(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static void fill(float[] v, float s) {
        v[0] = v[1] = v[2] = v[3] = s;
    }

    private static void set(float[] v, float x, float y, float z) {
        v[0] = x;
        v[1] = y;
        v[2] = z;
        v[3] = 0f;
    }

    /** {@code (x, y, z)} turned by the quaternion {@code (qx, qy, qz, qw)}, as {@code fx_field_turn}. */
    static float[] turn(float qx, float qy, float qz, float qw, float x, float y, float z) {
        float cx = qy * z - qz * y + qw * x, cy = qz * x - qx * z + qw * y, cz = qx * y - qy * x + qw * z;
        return new float[]{x + 2f * (qy * cz - qz * cy), y + 2f * (qz * cx - qx * cz), z + 2f * (qx * cy - qy * cx)};
    }
}
