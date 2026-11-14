package com.crystalgraphics.asset.kind;

import com.crystalgraphics.object.CgPropertyFlag;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.CgTypes;
import com.crystalgraphics.object.CgValueType;
import com.crystalgraphics.object.value.CgQuantity;
import com.crystalgraphics.object.value.CgValueForm;

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

    // ── Clip ─────────────────────────────────────────────────────────────────────────────

    static final CgType SYNC_MARKER = CgTypes.struct("crystalgraphics:clip.sync_marker")
            .property("name", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("at", FLOAT).forms(CgValueForm.CONSTANT).required().quantity(CgQuantity.TIME).flags(CgPropertyFlag.HEADLESS)
            .register();

    static final CgType ROOT_MOTION = CgTypes.struct("crystalgraphics:clip.root_motion")
            .property("enabled", BOOL).defaultValue(false).flags(CgPropertyFlag.HEADLESS)
            .property("root_bone", STRING).flags(CgPropertyFlag.HEADLESS)
            .property("lock", CgValueType.enumOf("ref_pose", "anim_first_frame", "zero")).defaultValue("ref_pose").flags(CgPropertyFlag.HEADLESS)
            .property("force_root_lock", BOOL).defaultValue(false).flags(CgPropertyFlag.HEADLESS)
            .register();

    static final CgType ADDITIVE = CgTypes.struct("crystalgraphics:clip.additive")
            .property("type", CgValueType.enumOf("none", "local_space", "mesh_space")).defaultValue("none").flags(CgPropertyFlag.HEADLESS)
            .property("base", CgValueType.enumOf("ref_pose", "frame", "clip_frame")).defaultValue("ref_pose").flags(CgPropertyFlag.HEADLESS)
            .property("base_clip", STRING).flags(CgPropertyFlag.HEADLESS)
            .property("base_frame", INT).forms(CgValueForm.CONSTANT).defaultValue(0).flags(CgPropertyFlag.HEADLESS)
            .register();

    /** {@code clips/}: a clip's settings ({@code UAnimSequence}'s): sync markers, root motion, additive, notifies. */
    public static final CgType CLIP = CgTypes.kind("crystalgraphics:clip").folder("clips").version(1)
            .property("sync_markers", CgValueType.listOf(CgValueType.struct(SYNC_MARKER))).flags(CgPropertyFlag.HEADLESS)
            .property("root_motion", CgValueType.struct(ROOT_MOTION)).flags(CgPropertyFlag.HEADLESS)
            .property("additive", CgValueType.struct(ADDITIVE)).flags(CgPropertyFlag.HEADLESS)
            .property("notifies", hard("sequence")).flags(CgPropertyFlag.HEADLESS)
            .register();

    // ── Anim graph ───────────────────────────────────────────────────────────────────────

    /** A node name in the graph's {@code nodes}. */
    private static final CgValueType NODE_NAME = STRING;
    /** A constant, or the name of a variable the graph declares. */
    private static final CgValueType ALPHA = CgValueType.oneOf(FLOAT, STRING);

    static final CgType ANIM_VARIABLE = CgTypes.struct("crystalgraphics:anim_graph.variable")
            .property("type", CgValueType.enumOf("float", "bool", "int")).required().flags(CgPropertyFlag.HEADLESS)
            .property("default", CgValueType.oneOf(FLOAT, BOOL)).flags(CgPropertyFlag.HEADLESS)
            .register();

    /** Nodes by {@code kind}: Unreal's anim graph nodes the engine runs. */
    public static final CgType ANIM_NODES = CgTypes.dispatch("crystalgraphics:anim_graph.node", "kind").register();

    private static CgType.Builder animNode(String kind) {
        return CgTypes.struct("crystalgraphics:anim_graph." + kind).onRegister(t -> ANIM_NODES.member(kind, t));
    }

    static final CgType ANIM_SEQUENCE_PLAYER = animNode("sequence_player")
            .property("clip", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("loop", BOOL).defaultValue(false).flags(CgPropertyFlag.HEADLESS)
            .property("play_rate", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(1.0).flags(CgPropertyFlag.HEADLESS)
            .property("start_position", FLOAT).forms(CgValueForm.CONSTANT).quantity(CgQuantity.TIME).defaultValue(0.0)
            .flags(CgPropertyFlag.HEADLESS)
            .register();
    static final CgType ANIM_REF_POSE = animNode("ref_pose").register();
    static final CgType ANIM_SLOT = animNode("slot")
            .property("slot_name", STRING).defaultValue("DefaultSlot").flags(CgPropertyFlag.HEADLESS)
            .property("source", NODE_NAME).required().flags(CgPropertyFlag.HEADLESS)
            .register();
    static final CgType ANIM_TWO_WAY_BLEND = animNode("two_way_blend")
            .property("a", NODE_NAME).required().flags(CgPropertyFlag.HEADLESS)
            .property("b", NODE_NAME).required().flags(CgPropertyFlag.HEADLESS)
            .property("alpha", ALPHA).required().flags(CgPropertyFlag.HEADLESS)
            .register();
    static final CgType ANIM_BLEND_LIST_BY_BOOL = animNode("blend_list_by_bool")
            .property("selector", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("when_true", NODE_NAME).required().flags(CgPropertyFlag.HEADLESS)
            .property("when_false", NODE_NAME).required().flags(CgPropertyFlag.HEADLESS)
            .property("blend_times", CgValueType.listOf(FLOAT)).flags(CgPropertyFlag.HEADLESS)
            .property("reset_child_on_activate", BOOL).defaultValue(false).flags(CgPropertyFlag.HEADLESS)
            .register();
    static final CgType ANIM_BLEND_LIST_BY_INT = animNode("blend_list_by_int")
            .property("selector", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("poses", CgValueType.listOf(NODE_NAME)).required().flags(CgPropertyFlag.HEADLESS)
            .property("blend_times", CgValueType.listOf(FLOAT)).flags(CgPropertyFlag.HEADLESS)
            .property("reset_child_on_activate", BOOL).defaultValue(false).flags(CgPropertyFlag.HEADLESS)
            .register();

    static final CgType ANIM_BRANCH = CgTypes.struct("crystalgraphics:anim_graph.branch")
            .property("bone", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("depth", INT).forms(CgValueForm.CONSTANT).defaultValue(0).flags(CgPropertyFlag.HEADLESS)
            .register();
    static final CgType ANIM_BONE_WEIGHT = CgTypes.struct("crystalgraphics:anim_graph.bone_weight")
            .property("bone", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("weight", FLOAT).forms(CgValueForm.CONSTANT).range(0, 1).required().flags(CgPropertyFlag.HEADLESS)
            .register();
    /** {@code FBranchFilter}s and blend mask entries. */
    static final CgType ANIM_MASK = CgTypes.struct("crystalgraphics:anim_graph.mask")
            .property("branches", CgValueType.listOf(CgValueType.struct(ANIM_BRANCH))).flags(CgPropertyFlag.HEADLESS)
            .property("bones", CgValueType.listOf(CgValueType.struct(ANIM_BONE_WEIGHT))).flags(CgPropertyFlag.HEADLESS)
            .register();
    static final CgType ANIM_LAYER = CgTypes.struct("crystalgraphics:anim_graph.layer")
            .property("pose", NODE_NAME).required().flags(CgPropertyFlag.HEADLESS)
            .property("mask", CgValueType.struct(ANIM_MASK)).required().flags(CgPropertyFlag.HEADLESS)
            .property("weight", ALPHA).defaultValue(1.0).flags(CgPropertyFlag.HEADLESS)
            .property("curves", CgValueType.enumOf("max_weight", "use_base_only")).defaultValue("max_weight").flags(CgPropertyFlag.HEADLESS)
            .register();
    static final CgType ANIM_LAYERED_BLEND = animNode("layered_blend")
            .property("base", NODE_NAME).required().flags(CgPropertyFlag.HEADLESS)
            .property("layers", CgValueType.listOf(CgValueType.struct(ANIM_LAYER))).required().flags(CgPropertyFlag.HEADLESS)
            .register();
    static final CgType ANIM_APPLY_ADDITIVE = animNode("apply_additive")
            .property("base", NODE_NAME).required().flags(CgPropertyFlag.HEADLESS)
            .property("additive", NODE_NAME).required().flags(CgPropertyFlag.HEADLESS)
            .property("alpha", ALPHA).defaultValue(1.0).flags(CgPropertyFlag.HEADLESS)
            .register();
    /** Its own name is the cached pose's. */
    static final CgType ANIM_SAVE_CACHED_POSE = animNode("save_cached_pose")
            .property("pose", NODE_NAME).required().flags(CgPropertyFlag.HEADLESS)
            .register();
    static final CgType ANIM_USE_CACHED_POSE = animNode("use_cached_pose")
            .property("save", NODE_NAME).required().flags(CgPropertyFlag.HEADLESS)
            .register();

    static final CgType ANIM_STATE_MACHINE = animNode("state_machine")
            .property("machine", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .register();
    static final CgType ANIM_INERTIALIZATION = animNode("inertialization")
            .property("source", NODE_NAME).required().flags(CgPropertyFlag.HEADLESS)
            .register();

    private static final CgValueType COMPARISON = CgValueType.enumOf("<", "<=", ">", ">=", "==", "!=");

    /** A transition rule: {@code all}, {@code any}, {@code not}, a variable compared, a clock compared, an event. */
    static final CgType ANIM_RULE = CgTypes.struct("crystalgraphics:anim_graph.rule")
            .property("all", CgValueType.listOf(CgValueType.SELF)).flags(CgPropertyFlag.HEADLESS)
            .property("any", CgValueType.listOf(CgValueType.SELF)).flags(CgPropertyFlag.HEADLESS)
            .property("not", CgValueType.SELF).flags(CgPropertyFlag.HEADLESS)
            .property("variable", STRING).flags(CgPropertyFlag.HEADLESS)
            .property("op", COMPARISON).flags(CgPropertyFlag.HEADLESS)
            .property("time_remaining", COMPARISON).flags(CgPropertyFlag.HEADLESS)
            .property("time_remaining_fraction", COMPARISON).flags(CgPropertyFlag.HEADLESS)
            .property("state_time", COMPARISON).flags(CgPropertyFlag.HEADLESS)
            .property("value", CgValueType.oneOf(FLOAT, BOOL)).flags(CgPropertyFlag.HEADLESS)
            .property("event", STRING).flags(CgPropertyFlag.HEADLESS)
            .register();

    /** A state ({@code pose}) or a conduit ({@code entry_rule}). */
    static final CgType ANIM_STATE = CgTypes.struct("crystalgraphics:anim_graph.state")
            .property("pose", NODE_NAME).flags(CgPropertyFlag.HEADLESS)
            .property("always_reset_on_entry", BOOL).defaultValue(false).flags(CgPropertyFlag.HEADLESS)
            .property("entry_rule", CgValueType.struct(ANIM_RULE)).flags(CgPropertyFlag.HEADLESS)
            .register();

    static final CgType ANIM_TRANSITION = CgTypes.struct("crystalgraphics:anim_graph.transition")
            .property("from", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("to", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("rule", CgValueType.struct(ANIM_RULE)).flags(CgPropertyFlag.HEADLESS)
            .property("automatic", BOOL).defaultValue(false).flags(CgPropertyFlag.HEADLESS)
            .property("crossfade", FLOAT).forms(CgValueForm.CONSTANT).quantity(CgQuantity.TIME).range(0, null).defaultValue(0.2)
            .flags(CgPropertyFlag.HEADLESS)
            .property("blend", CgValueType.enumOf("linear", "cubic", "hermite_cubic", "sinusoidal", "quadratic_in_out",
                    "cubic_in_out", "quartic_in_out", "quintic_in_out", "circular_in", "circular_out", "circular_in_out",
                    "exp_in", "exp_out", "exp_in_out")).defaultValue("hermite_cubic").flags(CgPropertyFlag.HEADLESS)
            .property("logic", CgValueType.enumOf("standard", "inertialization")).defaultValue("standard").flags(CgPropertyFlag.HEADLESS)
            .property("priority", INT).forms(CgValueForm.CONSTANT).defaultValue(1).flags(CgPropertyFlag.HEADLESS)
            .property("trigger_time", FLOAT).forms(CgValueForm.CONSTANT).quantity(CgQuantity.TIME).defaultValue(-1.0)
            .flags(CgPropertyFlag.HEADLESS)
            .property("overshoot_correction", BOOL).defaultValue(true).flags(CgPropertyFlag.HEADLESS)
            .property("min_reentry", FLOAT).forms(CgValueForm.CONSTANT).quantity(CgQuantity.TIME).defaultValue(-1.0)
            .flags(CgPropertyFlag.HEADLESS)
            .property("inertialize_self", BOOL).defaultValue(false).flags(CgPropertyFlag.HEADLESS)
            .register();

    /** {@code FBakedAnimationStateMachine}: its states in order, the first the initial by default. */
    static final CgType ANIM_MACHINE = CgTypes.struct("crystalgraphics:anim_graph.machine")
            .property("initial", STRING).flags(CgPropertyFlag.HEADLESS)
            .property("max_transitions_per_frame", INT).forms(CgValueForm.CONSTANT).range(1, null).defaultValue(3)
            .flags(CgPropertyFlag.HEADLESS)
            .property("skip_first_update_transition", BOOL).defaultValue(true).flags(CgPropertyFlag.HEADLESS)
            .property("reinitialize_on_becoming_relevant", BOOL).defaultValue(true).flags(CgPropertyFlag.HEADLESS)
            .property("states", CgValueType.mapOf(CgValueType.struct(ANIM_STATE))).required().flags(CgPropertyFlag.HEADLESS)
            .property("transitions", CgValueType.listOf(CgValueType.struct(ANIM_TRANSITION))).flags(CgPropertyFlag.HEADLESS)
            .register();

    /**
     * {@code anim_graphs/}: an anim blueprint's graph ({@code UAnimBlueprint}): its variables, its nodes by name, the one
     * whose pose is the output, and its state machines by name.
     */
    public static final CgType ANIM_GRAPH = CgTypes.kind("crystalgraphics:anim_graph").folder("anim_graphs").version(1)
            .property("variables", CgValueType.mapOf(CgValueType.struct(ANIM_VARIABLE))).flags(CgPropertyFlag.HEADLESS)
            .property("output", NODE_NAME).required().flags(CgPropertyFlag.HEADLESS)
            .property("nodes", CgValueType.mapOf(CgValueType.dispatch(ANIM_NODES))).required().flags(CgPropertyFlag.HEADLESS)
            .property("machines", CgValueType.mapOf(CgValueType.struct(ANIM_MACHINE))).flags(CgPropertyFlag.HEADLESS)
            .register();

    // ── Springs ──────────────────────────────────────────────────────────────────────────

    static final CgType CHAIN = CgTypes.element("crystalgraphics:springs.chain")
            .property("root", STRING).required()
            .property("bones", INT).forms(CgValueForm.CONSTANT)
            .property("inertia", FLOAT).forms(CgValueForm.CONSTANT)
            .property("length_strength", FLOAT).forms(CgValueForm.CONSTANT)
            .property("direction_strength", FLOAT).forms(CgValueForm.CONSTANT)
            .property("movement_strength", FLOAT).forms(CgValueForm.CONSTANT)
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
