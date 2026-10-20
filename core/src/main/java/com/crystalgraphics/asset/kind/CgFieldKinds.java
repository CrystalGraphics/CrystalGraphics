package com.crystalgraphics.asset.kind;

import java.util.Arrays;

import com.crystalgraphics.object.CgPropertyFlag;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.CgTypes;
import com.crystalgraphics.object.CgValueType;
import com.crystalgraphics.object.value.CgQuantity;

import static com.crystalgraphics.object.CgValueType.BOOL;
import static com.crystalgraphics.object.CgValueType.FLOAT;
import static com.crystalgraphics.object.CgValueType.INT;
import static com.crystalgraphics.object.CgValueType.STRING;
import static com.crystalgraphics.object.CgValueType.TREE;
import static com.crystalgraphics.object.CgValueType.VEC3;

/**
 * The {@code field} kind: a grid of vectors ({@code UVectorFieldStatic}) or a graph of
 * force nodes (Chaos Physics Fields, flattened as Unreal's {@code UFieldNodeBase} graph is).
 *
 * <p>Registered by {@link CgAssetKinds#register()}.</p>
 */
public final class CgFieldKinds {

    private CgFieldKinds() {
    }

    /** Graph nodes by {@code kind}; each node's fields are its Chaos class's. */
    public static final CgType NODES = CgTypes.dispatch("crystalgraphics:field_node", "kind").register();

    private static final CgValueType FALLOFF = CgValueType.enumOf("none", "linear", "inverse", "squared", "logarithmic");

    /** A node of {@code kind}; its fields its Chaos class's, in declaration order. Register with {@link #done}. */
    private static CgType.Builder node(String kind) {
        return CgTypes.element("crystalgraphics:field_node." + kind).onRegister(t -> NODES.member(kind, t));
    }

    private static CgType done(CgType.Builder node) {
        return node.property("editor", TREE).flags(CgPropertyFlag.EDITOR_ONLY).register();
    }

    /** The falloff nodes' shared four: what a node yields inside its range, mapped onto {@code [min, max]}. */
    private static CgType.Builder ranged(CgType.Builder b) {
        return b.property("magnitude", FLOAT).defaultValue(1.0)
                .property("min_range", FLOAT).defaultValue(0.0)
                .property("max_range", FLOAT).defaultValue(1.0)
                .property("default", FLOAT).defaultValue(0.0);
    }

    static final CgType RADIAL_FALLOFF = done(ranged(node("radial_falloff"))
            .property("radius", FLOAT).quantity(CgQuantity.LENGTH).defaultValue(0.0)
            .property("position", VEC3).quantity(CgQuantity.POSITION).defaultValue(0)
            .property("falloff", FALLOFF).defaultValue("linear"));

    static final CgType PLANE_FALLOFF = done(ranged(node("plane_falloff"))
            .property("distance", FLOAT).quantity(CgQuantity.LENGTH).defaultValue(0.0)
            .property("position", VEC3).quantity(CgQuantity.POSITION).defaultValue(0)
            .property("normal", VEC3).quantity(CgQuantity.DIRECTION).defaultValue(Arrays.asList(0, 0, 1))
            .property("falloff", FALLOFF).defaultValue("linear"));

    static final CgType BOX_FALLOFF = done(ranged(node("box_falloff"))
            .property("transform", CgValueType.struct(CgCommonStructs.TRANSFORM))
            .property("falloff", FALLOFF).defaultValue("linear"));

    static final CgType NOISE = done(node("noise")
            .property("min_range", FLOAT).defaultValue(0.0)
            .property("max_range", FLOAT).defaultValue(1.0)
            .property("transform", CgValueType.struct(CgCommonStructs.TRANSFORM)));

    static final CgType WAVE_SCALAR = done(node("wave_scalar")
            .property("magnitude", FLOAT).defaultValue(1.0)
            .property("position", VEC3).quantity(CgQuantity.POSITION).defaultValue(0)
            .property("wavelength", FLOAT).quantity(CgQuantity.LENGTH).defaultValue(10000.0)
            .property("period", FLOAT).quantity(CgQuantity.TIME).defaultValue(1.0)
            .property("function", CgValueType.enumOf("cosine", "gaussian", "falloff", "decay")).defaultValue("cosine")
            .property("falloff", FALLOFF).defaultValue("linear"));

    static final CgType UNIFORM_SCALAR = done(node("uniform_scalar").property("magnitude", FLOAT).defaultValue(1.0));

    static final CgType UNIFORM_VECTOR = done(node("uniform_vector")
            .property("magnitude", FLOAT).defaultValue(1.0)
            .property("direction", VEC3).quantity(CgQuantity.DIRECTION).defaultValue(0));

    static final CgType RADIAL_VECTOR = done(node("radial_vector")
            .property("magnitude", FLOAT).defaultValue(1.0)
            .property("position", VEC3).quantity(CgQuantity.POSITION).defaultValue(0));

    static final CgType ROTATED_RADIAL_VECTOR = done(node("rotated_radial_vector")
            .property("magnitude", FLOAT).defaultValue(1.0)
            .property("position", VEC3).quantity(CgQuantity.POSITION).defaultValue(0)
            .property("angle", FLOAT).quantity(CgQuantity.ANGLE).defaultValue(0.0)
            .property("axis", VEC3).quantity(CgQuantity.DIRECTION).defaultValue(Arrays.asList(0, 0, 1)));

    static final CgType RANDOM_VECTOR = done(node("random_vector").property("magnitude", FLOAT).defaultValue(1.0));

    /** Two earlier nodes combined; {@code left} and {@code right} are their ids. */
    static final CgType OPERATOR = done(node("operator")
            .property("magnitude", FLOAT).defaultValue(1.0)
            .property("op", CgValueType.enumOf("multiply", "divide", "add", "subtract")).defaultValue("multiply")
            .property("left", STRING)
            .property("right", STRING));

    /** An earlier node evaluated only inside (or outside) another; {@code culling} and {@code field} are their ids. */
    static final CgType CULLING = done(node("culling")
            .property("culling", STRING)
            .property("field", STRING)
            .property("op", CgValueType.enumOf("inside", "outside")).defaultValue("inside"));

    static final CgType DATA = CgTypes.struct("crystalgraphics:field.data")
            .property("path", CgValueType.binaryRef("fields")).required().flags(CgPropertyFlag.STRUCTURAL)
            .register();

    /** A force graph written inline where a field is placed (a spawnable, a system's field), as kits write it. */
    static final CgType INLINE_GRAPH = CgTypes.struct("crystalgraphics:field.inline_graph").ownsIds()
            .property("kind", CgValueType.enumOf("graph")).required()
            .property("target", CgValueType.enumOf("linear_force", "linear_velocity", "kill", "orbit", "speed_scale",
                    "angular_velocity", "scale", "strain")).required()
            .property("root", CgValueType.LOCAL_ID).required()
            .property("magnitude_var", FLOAT)
            .property("nodes", CgValueType.idList(NODES)).required()
            .register();

    /** {@code fields/}: a grid of vectors, or a force graph. */
    public static final CgType FIELD = CgTypes.kind("crystalgraphics:field").folder("fields").version(1)
            .property("kind", CgValueType.enumOf("grid", "graph")).required().flags(CgPropertyFlag.STRUCTURAL)
            .property("size", CgValueType.listOf(INT)).flags(CgPropertyFlag.STRUCTURAL)
            .property("bounds", CgValueType.listOf(VEC3)).quantity(CgQuantity.POSITION)
            .property("intensity", FLOAT)
            .property("data", CgValueType.struct(DATA))
            .property("tiles", BOOL)
            .property("target", CgValueType.enumOf("linear_force", "linear_velocity", "kill", "orbit", "speed_scale",
                    "angular_velocity", "scale", "strain")).flags(CgPropertyFlag.STRUCTURAL)
            .property("root", CgValueType.LOCAL_ID).flags(CgPropertyFlag.STRUCTURAL)
            .property("magnitude_var", FLOAT)
            .property("nodes", CgValueType.idList(NODES)).flags(CgPropertyFlag.STRUCTURAL)
            .register();

    static void register() {
    }
}
