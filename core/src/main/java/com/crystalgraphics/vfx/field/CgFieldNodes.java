package com.crystalgraphics.vfx.field;

import java.util.List;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.crystalgraphics.vfx.field.CgFieldNode.Kind;
import com.crystalgraphics.vfx.field.CgFieldNode.Output;

/**
 * Chaos's field node kinds ({@code FieldSystemNodes.cpp}), registered as the format names them: each with its
 * {@code ComputeFieldBounds} and the numbers {@code ScaleFunctionResult} is linear in.
 *
 * <pre>{@code
 * CgFieldNode.Kind falloff = CgFieldNodes.RADIAL_FALLOFF;        // or CgFieldNode.Kind.of("radial_falloff")
 * }</pre>
 *
 * <ul>
 *   <li>A falloff bounds only where its default is 0, as Chaos's radial and box falloffs do.</li>
 * </ul>
 */
public final class CgFieldNodes {

    private CgFieldNodes() {
    }

    public static final Kind RADIAL_FALLOFF = Kind.register(new Kind("radial_falloff", Output.SCALAR,
            (n, in) -> n.param("default") != 0f ? CgFieldBounds.INFINITE : sphere(n), List.of("magnitude", "default"), null));
    public static final Kind PLANE_FALLOFF = Kind.register(new Kind("plane_falloff", Output.SCALAR,
            (n, in) -> CgFieldBounds.INFINITE, List.of("magnitude", "default"), null));
    public static final Kind BOX_FALLOFF = Kind.register(new Kind("box_falloff", Output.SCALAR,
            (n, in) -> n.param("default") != 0f ? CgFieldBounds.INFINITE : box(n), List.of("magnitude", "default"), null));
    public static final Kind NOISE = Kind.register(new Kind("noise", Output.SCALAR,
            (n, in) -> CgFieldBounds.INFINITE, List.of("min_range", "max_range"), null));
    public static final Kind WAVE_SCALAR = Kind.register(new Kind("wave_scalar", Output.SCALAR,
            (n, in) -> CgFieldBounds.INFINITE, List.of("magnitude"), null));
    public static final Kind UNIFORM_SCALAR = Kind.register(new Kind("uniform_scalar", Output.SCALAR,
            (n, in) -> CgFieldBounds.INFINITE, List.of("magnitude"), null));
    public static final Kind UNIFORM_VECTOR = Kind.register(new Kind("uniform_vector", Output.VECTOR,
            (n, in) -> CgFieldBounds.INFINITE, List.of("magnitude"), null));
    public static final Kind RADIAL_VECTOR = Kind.register(new Kind("radial_vector", Output.VECTOR,
            (n, in) -> CgFieldBounds.INFINITE, List.of("magnitude"), null));
    public static final Kind ROTATED_RADIAL_VECTOR = Kind.register(new Kind("rotated_radial_vector", Output.VECTOR,
            (n, in) -> CgFieldBounds.INFINITE, List.of("magnitude"), null));
    public static final Kind RANDOM_VECTOR = Kind.register(new Kind("random_vector", Output.VECTOR,
            (n, in) -> CgFieldBounds.INFINITE, List.of("magnitude"), null));
    /** A scalar and a vector broadcast; multiply and divide bound as their intersection, add and subtract their union. */
    public static final Kind OPERATOR = Kind.register(new Kind("operator",
            (n, in) -> in[0] == Output.VECTOR || in[1] == Output.VECTOR ? Output.VECTOR : Output.SCALAR,
            (n, in) -> n.param("op") < 2f ? in[0].intersect(in[1]) : in[0].union(in[1]), List.of("magnitude"), null));
    /** Its field where its culling allows: inside bounds as their union, outside their intersection. */
    public static final Kind CULLING = Kind.register(new Kind("culling", (n, in) -> in[1],
            (n, in) -> n.param("op") == 0f ? in[0].union(in[1]) : in[0].intersect(in[1]), List.of(), "field"));

    /** Loads the built-in kinds: {@link Kind}'s lookups call it first. */
    static void load() {
    }

    private static CgFieldBounds sphere(CgFieldNode n) {
        Vector3f p = n.vector("position", new Vector3f());
        return CgFieldBounds.sphere(p.x, p.y, p.z, n.param("radius"));
    }

    /** Chaos's unit box, half a block each way (its 50 cm), under the node's transform. */
    private static CgFieldBounds box(CgFieldNode n) {
        int at = n.kind().offset("transform");
        float[] p = n.params();
        Quaternionf q = new Quaternionf(p[at + 3], p[at + 4], p[at + 5], p[at + 6]);
        Vector3f lo = new Vector3f(Float.POSITIVE_INFINITY), hi = new Vector3f(Float.NEGATIVE_INFINITY), c = new Vector3f();
        for (int corner = 0; corner < 8; corner++) {
            c.set((corner & 1) == 0 ? -0.5f : 0.5f, (corner & 2) == 0 ? -0.5f : 0.5f, (corner & 4) == 0 ? -0.5f : 0.5f)
                    .mul(p[at + 7], p[at + 8], p[at + 9]).rotate(q).add(p[at], p[at + 1], p[at + 2]);
            lo.min(c);
            hi.max(c);
        }
        return new CgFieldBounds(lo.x, lo.y, lo.z, hi.x, hi.y, hi.z);
    }
}
