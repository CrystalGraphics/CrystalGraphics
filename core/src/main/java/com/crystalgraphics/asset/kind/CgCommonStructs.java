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
    public static final CgValueType QUANTITY = CgValueType.enumOf("none", "length", "position", "offset", "speed",
            "acceleration", "direction", "scale", "angle", "angular_speed", "rotation", "time", "frequency", "rate",
            "per_length", "colour");

    /**
     * An input's declaration: a built-in module's in {@code modules.yaml}, a data module's in its asset
     * ({@code spec/modules.md} § Declaration). Keyed by {@code name}, unique within its module, as Niagara keys a
     * script's inputs; its key order is {@code aaa-design} §2.49's.
     */
    public static final CgType INPUT_DECLARATION = CgTypes.struct("crystalgraphics:input")
            .property("name", STRING).required()
            .property("type", CgValueType.enumOf("float", "int", "bool", "string", "enum", "vec2", "vec3", "vec4", "colour",
                    "rotation", "position", "curve", "mesh", "texture", "material", "socket", "field", "array", "struct")).required()
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

    static void register() {
    }
}
