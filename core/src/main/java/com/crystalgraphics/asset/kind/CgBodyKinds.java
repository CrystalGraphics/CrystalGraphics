package com.crystalgraphics.asset.kind;

import com.crystalgraphics.object.CgPropertyFlag;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.CgTypes;
import com.crystalgraphics.object.CgValueType;
import com.crystalgraphics.object.value.CgQuantity;

import static com.crystalgraphics.asset.kind.CgCommonStructs.hard;
import static com.crystalgraphics.asset.kind.CgCommonStructs.mapOfValues;
import static com.crystalgraphics.object.CgValueType.BOOL;
import static com.crystalgraphics.object.CgValueType.FLOAT;
import static com.crystalgraphics.object.CgValueType.INT;
import static com.crystalgraphics.object.CgValueType.STRING;
import static com.crystalgraphics.object.CgValueType.VALUE;
import static com.crystalgraphics.object.CgValueType.VEC3;

/**
 * The body kinds: {@code prefab}, {@code skeleton}, {@code springs}, {@code rig} and
 * {@code retargeter}. A Blueprint actor's component tree, {@code USkeleton}'s sockets, spring chains,
 * {@code UIKRigDefinition} and {@code UIKRetargeter}.
 *
 * <p>Registered by {@link CgAssetKinds#register()}.</p>
 */
public final class CgBodyKinds {

    private CgBodyKinds() {
    }

    // ── Prefab ───────────────────────────────────────────────────────────────────────────

    static final CgType NODE = CgTypes.element("crystalgraphics:prefab.node")
            .property("name", STRING).required().flags(CgPropertyFlag.LOCKED)
            .property("parent", STRING).flags(CgPropertyFlag.STRUCTURAL)
            .property("location", VEC3).quantity(CgQuantity.POSITION).defaultValue(0).flags(CgPropertyFlag.KEYABLE)
            .property("rotation", CgValueType.ROTATION).quantity(CgQuantity.ROTATION).defaultValue(0).flags(CgPropertyFlag.KEYABLE)
            .property("scale", VEC3).quantity(CgQuantity.SCALE).defaultValue(1).flags(CgPropertyFlag.KEYABLE)
            .property("opacity", FLOAT).defaultValue(1).flags(CgPropertyFlag.KEYABLE)
            .property("values", mapOfValues())
            .property("meshes", CgValueType.listOf(CgValueType.struct(CgCommonStructs.MESH_SLOT))).flags(CgPropertyFlag.STRUCTURAL)
            .register();

    /** {@code prefabs/}: nodes, each with a rest transform, opacity, meshes and materials. */
    public static final CgType PREFAB = CgTypes.kind("crystalgraphics:prefab").folder("prefabs").version(1)
            .property("nodes", CgValueType.idList(NODE)).required().flags(CgPropertyFlag.STRUCTURAL)
            .register();

    // ── Skeleton ─────────────────────────────────────────────────────────────────────────

    /** A socket from a bone, from the entity, or derived from others. */
    static final CgType SOCKET = CgTypes.element("crystalgraphics:skeleton.socket")
            .property("name", STRING).required().flags(CgPropertyFlag.LOCKED, CgPropertyFlag.HEADLESS)
            .property("from", CgValueType.enumOf("bone", "entity", "derived")).required().flags(CgPropertyFlag.HEADLESS)
            .property("bone", STRING).flags(CgPropertyFlag.HEADLESS)
            .property("location", VEC3).quantity(CgQuantity.OFFSET).flags(CgPropertyFlag.HEADLESS)
            .property("rotation", CgValueType.ROTATION).quantity(CgQuantity.ROTATION).flags(CgPropertyFlag.HEADLESS)
            .property("scale", VEC3).quantity(CgQuantity.SCALE).flags(CgPropertyFlag.HEADLESS)
            .property("at", VALUE).flags(CgPropertyFlag.HEADLESS)
            .property("fn", CgValueType.enumOf("midpoint", "trace_down", "offset")).flags(CgPropertyFlag.HEADLESS)
            .property("of", VALUE).flags(CgPropertyFlag.HEADLESS)
            .property("facing", STRING).flags(CgPropertyFlag.HEADLESS)
            .property("reach", FLOAT).quantity(CgQuantity.LENGTH).flags(CgPropertyFlag.HEADLESS)
            .register();

    static final CgType FALLBACK = CgTypes.struct("crystalgraphics:skeleton.fallback")
            .property("height", FLOAT)
            .property("side", FLOAT)
            .register();

    /** {@code skeletons/}: a model's sockets ({@code USkeleton}'s). */
    public static final CgType SKELETON = CgTypes.kind("crystalgraphics:skeleton").folder("skeletons").version(1)
            .property("model", CgValueType.binaryRef("models")).flags(CgPropertyFlag.HEADLESS)
            .property("sockets", CgValueType.idList(SOCKET)).required().flags(CgPropertyFlag.HEADLESS)
            .property("fallbacks", CgValueType.mapOf(CgValueType.struct(FALLBACK))).flags(CgPropertyFlag.HEADLESS)
            .register();

    // ── Springs ──────────────────────────────────────────────────────────────────────────

    static final CgType CHAIN = CgTypes.element("crystalgraphics:springs.chain")
            .property("root", STRING).required()
            .property("bones", INT)
            .property("inertia", FLOAT)
            .property("length_strength", FLOAT)
            .property("direction_strength", FLOAT)
            .property("movement_strength", FLOAT)
            .property("offset", VEC3).quantity(CgQuantity.OFFSET)
            .register();

    static final CgType COLLIDER = CgTypes.element("crystalgraphics:springs.collider")
            .property("bone", STRING)
            .property("offset", VEC3).quantity(CgQuantity.OFFSET)
            .property("radii", VEC3).quantity(CgQuantity.LENGTH)
            .property("chains", CgValueType.oneOf(CgValueType.enumOf("all"), CgValueType.listOf(STRING)))
            .property("ignore", CgValueType.listOf(STRING))
            .register();

    /** {@code springs/}: spring chains and their colliders on a model's bones (CC2's {@code UNuccDynamicsAsset}). */
    public static final CgType SPRINGS = CgTypes.kind("crystalgraphics:springs").folder("springs").version(1)
            .property("model", CgValueType.binaryRef("models"))
            .property("prefab", hard("prefab"))
            .property("chains", CgValueType.idList(CHAIN))
            .property("colliders", CgValueType.idList(COLLIDER))
            .register();

    // ── Rig, retargeter ──────────────────────────────────────────────────────────────────

    static final CgType RIG_CHAIN = CgTypes.element("crystalgraphics:rig.chain")
            .property("name", STRING).required().flags(CgPropertyFlag.LOCKED)
            .property("start", STRING).required()
            .property("end", STRING).required()
            .property("goal", STRING)
            .register();

    static final CgType OUTLINE = CgTypes.struct("crystalgraphics:rig.outline")
            .property("render_custom_depth", BOOL)
            .property("custom_depth_stencil_value", INT).range(0, 255)
            .property("overlay", hard("material"))
            .property("post", hard("material"))
            .property("colour", CgValueType.COLOUR)
            .property("strength", FLOAT)
            .register();

    /** {@code rigs/}: a skeleton read for retargeting ({@code UIKRigDefinition}). */
    public static final CgType RIG = CgTypes.kind("crystalgraphics:rig").folder("rigs").version(1)
            .property("model", CgValueType.binaryRef("models")).required()
            .property("adapter", STRING).required()
            .property("root", STRING)
            .property("pelvis", STRING)
            .property("chains", CgValueType.idList(RIG_CHAIN)).required()
            .property("carried", CgValueType.listOf(STRING))
            .property("on_missing", CgValueType.enumOf("keep_host", "refuse"))
            .property("outline", CgValueType.struct(OUTLINE))
            .register();

    static final CgType POSES = CgTypes.struct("crystalgraphics:retargeter.poses")
            .property("source", CgValueType.struct(CgCommonStructs.RETARGET_POSE))
            .property("target", CgValueType.struct(CgCommonStructs.RETARGET_POSE))
            .register();

    static final CgType FK = CgTypes.struct("crystalgraphics:retargeter.fk")
            .property("rotation", CgValueType.enumOf("interpolated", "one_to_one", "one_to_one_reversed", "match_chain",
                    "match_scaled_chain", "copy_local", "none"))
            .property("rotation_alpha", FLOAT)
            .property("translation", CgValueType.enumOf("none", "globally_scaled", "absolute", "stretch_uniform",
                    "stretch_non_uniform", "orient_and_scale"))
            .property("translation_alpha", FLOAT)
            .register();

    static final CgType IK = CgTypes.struct("crystalgraphics:retargeter.ik")
            .property("enabled", BOOL)
            .property("blend_to_source", FLOAT)
            .property("extension", FLOAT)
            .property("static_offset", VEC3).quantity(CgQuantity.OFFSET)
            .property("static_rotation_offset", VEC3).quantity(CgQuantity.ROTATION)
            .property("scale_vertical", FLOAT)
            .register();

    static final CgType RETARGET_CHAIN = CgTypes.element("crystalgraphics:retargeter.chain")
            .property("target", STRING).required()
            .property("source", STRING)
            .property("fk", CgValueType.struct(FK))
            .property("ik", CgValueType.struct(IK))
            .register();

    /** The op stack, by {@code op} ({@code UIKRetargeter}'s ops); each op's own fields beside the key. */
    public static final CgType OPS = CgTypes.dispatch("crystalgraphics:retargeter.op", "op").register();

    static final CgType SCALE_SOURCE = op("scale_source").property("factor", FLOAT).register();

    static final CgType PELVIS_MOTION = op("pelvis_motion")
            .property("scale_horizontal", FLOAT)
            .property("scale_vertical", FLOAT)
            .property("translation_alpha", FLOAT)
            .property("rotation_alpha", FLOAT)
            .register();

    static final CgType FK_CHAINS = op("fk_chains").register();
    static final CgType IK_CHAINS = op("ik_chains").register();
    static final CgType FLOOR_CONSTRAINT = op("floor_constraint").property("weight", FLOAT).register();

    /** {@code FPinBoneData}: a bone copied from, and the bone it is copied to. */
    static final CgType PINNED_BONE = CgTypes.struct("crystalgraphics:retargeter.op.pinned_bone")
            .property("bone_to_copy_from", STRING).required()
            .property("bone_to_copy_to", STRING).required()
            .register();

    /** {@code FIKRetargetPinBoneOpSettings}. */
    static final CgType PIN_BONE = op("pin_bone")
            .property("bones_to_pin", CgValueType.listOf(CgValueType.struct(PINNED_BONE)))
            .property("skeleton_to_copy_from", CgValueType.enumOf("source", "target")).defaultValue("target")
            .property("copy_translation", BOOL).defaultValue(true)
            .property("translation_mode", CgValueType.enumOf("copy_global_position", "copy_local_position",
                    "copy_local_position_relative_offset", "copy_local_position_relative_scaled",
                    "copy_global_position_and_maintain_offset")).defaultValue("copy_global_position")
            .property("copy_rotation", BOOL).defaultValue(true)
            .property("rotation_mode", CgValueType.enumOf("copy_global_rotation", "maintain_offset_from_bone_to_copy_from"))
                    .defaultValue("copy_global_rotation")
            .property("copy_scale", BOOL).defaultValue(true)
            .property("propagate_to_children", BOOL).defaultValue(false)
            .property("global_offset", CgValueType.struct(CgCommonStructs.TRANSFORM))
            .property("local_offset", CgValueType.struct(CgCommonStructs.TRANSFORM))
            .register();

    /** {@code FCurveRemapPair}: an animation curve's name in the source and the target. */
    static final CgType CURVE_PAIR = CgTypes.struct("crystalgraphics:retargeter.op.curve_pair")
            .property("source_curve", STRING).required()
            .property("target_curve", STRING).required()
            .register();

    /** {@code FIKRetargetCurveRemapOpSettings}. */
    static final CgType CURVE_REMAP = op("curve_remap")
            .property("copy_all_source_curves", BOOL).defaultValue(true)
            .property("remap_curves", BOOL).defaultValue(true)
            .property("curves_to_remap", CgValueType.listOf(CgValueType.struct(CURVE_PAIR)))
            .register();

    private static CgType.Builder op(String name) {
        return CgTypes.element("crystalgraphics:retargeter.op." + name).open()
                .onRegister(t -> OPS.member(name, t));
    }

    static final CgType RETARGET_SOCKET = CgTypes.element("crystalgraphics:retargeter.socket")
            .property("name", STRING).required()
            .property("bone", STRING).required()
            .property("location", VEC3).quantity(CgQuantity.OFFSET)
            .property("rotation", CgValueType.ROTATION).quantity(CgQuantity.ROTATION)
            .property("scale", VEC3).quantity(CgQuantity.SCALE)
            .register();

    /** {@code retargeters/}: one rig's animation on another ({@code UIKRetargeter}). */
    public static final CgType RETARGETER = CgTypes.kind("crystalgraphics:retargeter").folder("retargeters").version(1)
            .property("source", hard("rig")).required()
            .property("target", hard("rig")).required()
            .property("poses", CgValueType.struct(POSES))
            .property("chains", CgValueType.idList(RETARGET_CHAIN))
            .property("ops", CgValueType.idList(OPS))
            .property("sockets", CgValueType.idList(RETARGET_SOCKET))
            .property("scale_effects", BOOL)
            .register();

    static void register() {
    }
}
