package com.crystalgraphics.asset.kind;

import com.crystalgraphics.object.CgPropertyFlag;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.CgTypes;
import com.crystalgraphics.object.CgValueType;
import com.crystalgraphics.object.CgValueType.Strength;
import com.crystalgraphics.object.value.CgQuantity;

import static com.crystalgraphics.object.CgValueType.BOOL;
import static com.crystalgraphics.object.CgValueType.FLOAT;
import static com.crystalgraphics.object.CgValueType.INT;
import static com.crystalgraphics.object.CgValueType.STRING;
import static com.crystalgraphics.object.CgValueType.VALUE;
import static com.crystalgraphics.object.CgValueType.VEC3;

/**
 * The structs several kinds share ({@code spec/schema/common.schema.json}'s {@code $defs}): a placement, a condition,
 * a mesh slot, a quality tier, an input's declaration, and the reference shapes.
 *
 * <pre>{@code
 * .property("at", CgValueType.struct(CgCommonStructs.PLACE))           // { role: caster, socket: EFF_root }
 * .property("material", CgCommonStructs.hard("material"))              // loaded with its referrer
 * .property("quality", CgCommonStructs.TIER)                           // one tier: low | medium | high | ultra
 * }</pre>
 */
public final class CgCommonStructs {

    private CgCommonStructs() {
    }

    /** A hard reference to an asset of {@code kind}: loaded with its referrer. */
    public static CgValueType hard(String kind) {
        return CgValueType.ref(kind, Strength.HARD);
    }

    /** A soft reference to an asset of {@code kind}: loaded on first use. */
    public static CgValueType soft(String kind) {
        return CgValueType.ref(kind, Strength.SOFT);
    }

    /** A map of names to values in any form, typed by what the names name (a module's inputs, a material's parameters). */
    public static CgValueType mapOfValues() {
        return CgValueType.mapOf(VALUE);
    }

    /** A quality tier (Cascade's detail modes, Niagara's quality levels). */
    public static final CgValueType TIER = CgValueType.enumOf("low", "medium", "high", "ultra");

    /** A set of tiers; absent means all. */
    public static final CgValueType TIERS = CgValueType.listOf(TIER);

    /** A quantity's spec name ({@code spec/core.md} § Units). */
    public static final CgValueType QUANTITY = CgValueType.enumOf("length", "position", "offset", "speed", "acceleration",
            "direction", "scale", "angle", "angular_speed", "rotation", "time", "frequency", "rate", "per_length",
            "colour", "none");

    /**
     * An input's declaration: a built-in module's in {@code modules.yaml}, a data module's in its asset
     * ({@code spec/modules.md} § Declaration). Keyed by {@code name}, unique within its module, as Niagara keys a
     * script's inputs; its key order is {@code aaa-design} §2.49's.
     */
    public static final CgType INPUT_DECLARATION = CgTypes.struct("crystalgraphics:input")
            .property("name", STRING).required()
            .property("type", CgValueType.enumOf("float", "int", "bool", "enum", "vec2", "vec3", "vec4", "colour", "rotation",
                    "position", "curve", "mesh", "texture", "material", "socket", "string", "field", "array", "struct")).required()
            .property("quantity", QUANTITY).defaultValue("none")
            .property("default", VALUE)
            .property("values", CgValueType.listOf(STRING))
            .property("by", CgValueType.listOf(STRING))
            .property("rolls", CgValueType.enumOf("particle", "loop", "instance", "draw"))
            .property("per_instance", BOOL).defaultValue(false)
            .property("forms", CgValueType.listOf(CgValueType.enumOf("constant", "range", "pick", "curve", "gradient",
                    "array", "binding", "function", "data", "struct")))
            .property("timeline", CgValueType.enumOf("key_time", "key_value", "loop_count"))
            .property("toggle_for", STRING)
            .property("visible_when", CgValueType.mapOf(VALUE))
            .property("description", STRING)
            .register();

    /** A role's socket, the value of a {@code socket} input or parameter ({@code spec/core.md} § Values). */
    static final CgType SOCKET = CgTypes.struct("crystalgraphics:socket")
            .property("role", STRING).required()
            .property("socket", STRING).required()
            .register();

    /**
     * Where something is placed: at a locator, a role's socket or a spawnable's node, following it or not
     * ({@code common.schema.json#/$defs/place}). Exactly one of {@code locator}, {@code role} and {@code spawnable}.
     */
    public static final CgType PLACE = CgTypes.struct("crystalgraphics:place")
            .property("locator", STRING)
            .property("role", STRING)
            .property("socket", STRING)
            .property("spawnable", STRING)
            .property("node", STRING)
            .property("follow", BOOL)
            .property("offset", VEC3).quantity(CgQuantity.OFFSET)
            .property("rotation", CgValueType.ROTATION).quantity(CgQuantity.ROTATION)
            .property("scale", VEC3).quantity(CgQuantity.SCALE)
            .register();

    /**
     * A section's or a spawn's condition: a registered kind with its own keys, or {@code all}, {@code any},
     * {@code not} of others ({@code common.schema.json#/$defs/condition}, {@code sequences.md} § Conditions).
     * A condition kind's own keys sit beside {@code kind} and are typed by its registration.
     */
    public static final CgType CONDITION = CgTypes.struct("crystalgraphics:condition").open()
            .property("kind", STRING)
            .property("role", STRING)
            .property("is", CgValueType.listOf(VALUE))
            .property("all", CgValueType.listOf(CgValueType.SELF))
            .property("any", CgValueType.listOf(CgValueType.SELF))
            .property("not", CgValueType.SELF)
            .register();

    /**
     * What a track or a placement binds ({@code sequences.md} § Bindings): a role, a socket or material of it, a
     * locator, a spawnable or a node or material of it, a camera; or the host's {@code environment}, or an id.
     */
    static final CgType BINDING_STRUCT = CgTypes.struct("crystalgraphics:binding")
            .property("role", STRING)
            .property("socket", STRING)
            .property("material", STRING)
            .property("value", CgValueType.enumOf("velocity"))
            .property("locator", STRING)
            .property("spawnable", STRING)
            .property("node", STRING)
            .property("camera", STRING)
            .register();

    /** A binding as a file writes it: a struct, {@code environment}, or a bare id. */
    public static final CgValueType BINDING = CgValueType.oneOf(CgValueType.struct(BINDING_STRUCT), STRING);

    /** A mesh in a prefab node with its materials and outline fields ({@code common.schema.json#/$defs/mesh_slot}). */
    static final CgType MESH_SLOT = CgTypes.struct("crystalgraphics:mesh_slot")
            .property("mesh", CgValueType.binaryRef("models")).flags(CgPropertyFlag.STRUCTURAL)
            .property("name", STRING)
            .property("material", hard("material")).flags(CgPropertyFlag.STRUCTURAL)
            .property("materials", CgValueType.listOf(hard("material"))).flags(CgPropertyFlag.STRUCTURAL)
            .property("overlay", CgValueType.oneOf(hard("material"), CgValueType.listOf(hard("material"))))
            .property("cast_shadow", VALUE)
            .property("motion_vectors", VALUE)
            .property("clears_mask", CgValueType.oneOf(BOOL, CgValueType.listOf(BOOL)))
            .property("overlay_max_draw_distance", FLOAT).quantity(CgQuantity.LENGTH)
            .property("render_custom_depth", BOOL)
            .property("custom_depth_stencil_value", INT).range(0, 255)
            .property("custom_depth_stencil_write_mask", CgValueType.enumOf("all", "bit1", "bit2", "bit3", "bit4", "bit5",
                    "bit6", "bit7", "bit8", "low4", "high4"))
            .register();

    /** A retarget pose: the root's offset and bone rotations ({@code common.schema.json#/$defs/retarget_pose}). */
    static final CgType RETARGET_POSE = CgTypes.struct("crystalgraphics:retarget_pose")
            .property("root_offset", VEC3).quantity(CgQuantity.OFFSET)
            .property("rotations", CgValueType.mapOf(VEC3))
            .register();

    /** {@code FTransform}: a location, a rotation, a scale. */
    static final CgType TRANSFORM = CgTypes.struct("crystalgraphics:transform")
            .property("location", VEC3).quantity(CgQuantity.POSITION).defaultValue(0)
            .property("rotation", CgValueType.ROTATION).quantity(CgQuantity.ROTATION).defaultValue(0)
            .property("scale", VEC3).quantity(CgQuantity.SCALE).defaultValue(1)
            .register();

    // ── How a value is written ({@code common.schema.json#/$defs/value} and {@code curve}) ─────────────────────────────

    /** A curve's keys, or its baked samples ({@code core.md} § Curves). */
    static final CgType CURVE_KEYS = CgTypes.struct("crystalgraphics:curve.keys")
            .property("keys", CgValueType.ARRAY).required()
            .property("default", CgValueType.TREE)
            .property("pre", STRING)
            .property("post", STRING)
            .register();
    static final CgType CURVE_SAMPLES_RANGE = CgTypes.struct("crystalgraphics:curve.samples.range")
            .property("range", CgValueType.ARRAY).required()
            .property("values", CgValueType.ARRAY).required()
            .register();
    static final CgType CURVE_SAMPLES = CgTypes.struct("crystalgraphics:curve.samples")
            .property("samples", CgValueType.struct(CURVE_SAMPLES_RANGE)).required()
            .property("default", CgValueType.TREE)
            .property("pre", STRING)
            .property("post", STRING)
            .register();

    /** A curve as a file writes it: an asset's id, its keys or its samples. */
    public static final CgValueType CURVE = CgValueType.oneOf(STRING, CgValueType.struct(CURVE_KEYS),
            CgValueType.struct(CURVE_SAMPLES));

    private static CgType form(String key, CgValueType value) {
        return CgTypes.struct("crystalgraphics:value." + key).property(key, value).required().register();
    }

    static final CgType RANGE_FORM = CgTypes.struct("crystalgraphics:value.range")
            .property("range", CgValueType.ARRAY).required()
            .property("per_channel", BOOL)
            .property("locked", STRING)
            .register();
    static final CgType PICK_FORM = form("pick", CgValueType.ARRAY);
    static final CgType CURVE_FORM = CgTypes.struct("crystalgraphics:value.curve")
            .property("curve", CURVE).required()
            .property("by", STRING)
            .register();
    static final CgType CURVES_FORM = CgTypes.struct("crystalgraphics:value.curves")
            .property("curves", CgValueType.listOf(CURVE)).required()
            .property("by", STRING)
            .register();
    static final CgType GRADIENT_FORM = CgTypes.struct("crystalgraphics:value.gradient")
            .property("gradient", CURVE).required()
            .property("by", STRING)
            .register();
    static final CgType ARRAY_FORM = CgTypes.struct("crystalgraphics:value.array")
            .property("array", CgValueType.ARRAY).required()
            .property("by", STRING)
            .property("interpolate", BOOL)
            .property("address", STRING)
            .register();
    static final CgType REMAP = CgTypes.struct("crystalgraphics:value.bind.remap")
            .property("in", CgValueType.ARRAY).required()
            .property("out", CgValueType.ARRAY).required()
            .register();
    static final CgType BIND_FORM = CgTypes.struct("crystalgraphics:value.bind")
            .property("bind", STRING).required()
            .property("scale", CgValueType.TREE)
            .property("default", CgValueType.TREE)
            .property("component", STRING)
            .property("remap", CgValueType.struct(REMAP))
            .property("mode", CgValueType.oneOf(STRING, CgValueType.listOf(STRING)))
            .register();
    static final CgType FN_FORM = CgTypes.struct("crystalgraphics:value.fn")
            .property("fn", STRING).required()
            .property("inputs", mapOfValues())
            .register();
    static final CgType MESH_FORM = form("mesh", STRING);
    static final CgType TEXTURE_FORM = form("texture", STRING);
    static final CgType FIELD_FORM = form("field", CgValueType.TREE);
    static final CgType UNIT_FORM = CgTypes.struct("crystalgraphics:value.unit").open()
            .property("unit", STRING).required()
            .property("value", CgValueType.TREE)
            .property("range", CgValueType.TREE)
            .property("curve", CgValueType.TREE)
            .property("by", CgValueType.TREE)
            .register();

    /**
     * A value as a file writes it ({@code core.md} § Values): a constant, a list, or a map naming its form, else a
     * struct of values. Alternatives in the schema's order: a map takes the first whose required key it holds, so
     * {@code { unit: turns, range: [...] }} is a range with a unit beside it.
     */
    public static final CgValueType VALUE_FORMS = CgValueType.oneOf(STRING, CgValueType.ARRAY,
            CgValueType.struct(RANGE_FORM), CgValueType.struct(PICK_FORM), CgValueType.struct(CURVE_FORM),
            CgValueType.struct(CURVES_FORM), CgValueType.struct(GRADIENT_FORM), CgValueType.struct(ARRAY_FORM),
            CgValueType.struct(BIND_FORM), CgValueType.struct(FN_FORM), CgValueType.struct(MESH_FORM),
            CgValueType.struct(TEXTURE_FORM), CgValueType.struct(FIELD_FORM), CgValueType.struct(UNIT_FORM),
            mapOfValues());

    static void register() {
    }
}
