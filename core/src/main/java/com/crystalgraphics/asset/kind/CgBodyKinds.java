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
    /**
     * {@code FVirtualBone}: a bone the skeleton adds under {@code source}, following {@code target} in every clip, as
     * Unreal's virtual bones do (an IK foot that tracks its foot). Baked into each clip at import.
     */
    static final CgType VIRTUAL_BONE = CgTypes.struct("crystalgraphics:skeleton.virtual_bone")
            .property("name", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("source", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("target", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .register();

    public static final CgType SKELETON = CgTypes.kind("crystalgraphics:skeleton").folder("skeletons").version(1)
            .property("model", CgValueType.binaryRef("models")).flags(CgPropertyFlag.HEADLESS)
            .property("sockets", CgValueType.idList(SOCKET)).required().flags(CgPropertyFlag.HEADLESS)
            .property("fallbacks", CgValueType.mapOf(CgValueType.struct(FALLBACK))).flags(CgPropertyFlag.HEADLESS)
            .property("virtual_bones", CgValueType.listOf(CgValueType.struct(VIRTUAL_BONE))).flags(CgPropertyFlag.HEADLESS)
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

    /** A span of a clip whose root motion is bent so the character ends it at a named target. */
    static final CgType WARP_WINDOW = CgTypes.struct("crystalgraphics:clip.warp_window")
            .property("target", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("start", FLOAT).forms(CgValueForm.CONSTANT).required().quantity(CgQuantity.TIME).flags(CgPropertyFlag.HEADLESS)
            .property("end", FLOAT).forms(CgValueForm.CONSTANT).required().quantity(CgQuantity.TIME).flags(CgPropertyFlag.HEADLESS)
            .property("translation", BOOL).defaultValue(true).flags(CgPropertyFlag.HEADLESS)
            .property("ignore_height", BOOL).defaultValue(true).flags(CgPropertyFlag.HEADLESS)
            .property("rotation", BOOL).defaultValue(true).flags(CgPropertyFlag.HEADLESS)
            .property("facing", BOOL).defaultValue(false).flags(CgPropertyFlag.HEADLESS)
            .property("method", CgValueType.enumOf("slerp", "slerp_with_clamped_rate", "constant_rate", "scale")).defaultValue("slerp")
            .flags(CgPropertyFlag.HEADLESS)
            .property("rotation_time", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(1.0).flags(CgPropertyFlag.HEADLESS)
            .property("max_rotation_rate", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(0.0).flags(CgPropertyFlag.HEADLESS)
            .property("max_speed_ratio", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(0.0).flags(CgPropertyFlag.HEADLESS)
            .property("yaw_offset", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(0.0).flags(CgPropertyFlag.HEADLESS)
            .register();

    /** {@code clips/}: a clip's settings ({@code UAnimSequence}'s): sync markers, root motion, additive, notifies, warp windows. */
    public static final CgType CLIP = CgTypes.kind("crystalgraphics:clip").folder("clips").version(1)
            .property("sync_markers", CgValueType.listOf(CgValueType.struct(SYNC_MARKER))).flags(CgPropertyFlag.HEADLESS)
            .property("root_motion", CgValueType.struct(ROOT_MOTION)).flags(CgPropertyFlag.HEADLESS)
            .property("additive", CgValueType.struct(ADDITIVE)).flags(CgPropertyFlag.HEADLESS)
            .property("notifies", hard("sequence")).flags(CgPropertyFlag.HEADLESS)
            .property("warp_windows", CgValueType.listOf(CgValueType.struct(WARP_WINDOW))).flags(CgPropertyFlag.HEADLESS)
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

    /** {@code EAnimGroupRole}. */
    private static final CgValueType GROUP_ROLE = CgValueType.enumOf("can_be_leader", "always_follower", "always_leader",
            "transition_leader", "transition_follower", "exclusive_always_leader");

    static final CgType ANIM_SEQUENCE_PLAYER = animNode("sequence_player")
            .property("clip", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("loop", BOOL).defaultValue(false).flags(CgPropertyFlag.HEADLESS)
            .property("play_rate", ALPHA).defaultValue(1.0).flags(CgPropertyFlag.HEADLESS)
            .property("start_position", FLOAT).forms(CgValueForm.CONSTANT).quantity(CgQuantity.TIME).defaultValue(0.0)
            .flags(CgPropertyFlag.HEADLESS)
            .property("sync_group", STRING).flags(CgPropertyFlag.HEADLESS)
            .property("group_role", GROUP_ROLE).defaultValue("can_be_leader").flags(CgPropertyFlag.HEADLESS)
            .register();
    /** {@code FAnimNode_BlendSpacePlayer}: a blend space by its inputs. */
    static final CgType ANIM_BLEND_SPACE_PLAYER = animNode("blend_space_player")
            .property("blend_space", hard("blend_space")).required().flags(CgPropertyFlag.HEADLESS)
            .property("x", ALPHA).defaultValue(0.0).flags(CgPropertyFlag.HEADLESS)
            .property("y", ALPHA).defaultValue(0.0).flags(CgPropertyFlag.HEADLESS)
            .property("play_rate", ALPHA).defaultValue(1.0).flags(CgPropertyFlag.HEADLESS)
            .property("loop", BOOL).defaultValue(true).flags(CgPropertyFlag.HEADLESS)
            .property("start_position", FLOAT).forms(CgValueForm.CONSTANT).range(0, 1).defaultValue(0.0).flags(CgPropertyFlag.HEADLESS)
            .property("reset_on_change", BOOL).defaultValue(true).flags(CgPropertyFlag.HEADLESS)
            .property("sync_group", STRING).flags(CgPropertyFlag.HEADLESS)
            .property("group_role", GROUP_ROLE).defaultValue("can_be_leader").flags(CgPropertyFlag.HEADLESS)
            .register();
    /** {@code FAnimNode_RotationOffsetBlendSpace}: a mesh-space additive blend space over a base pose. */
    static final CgType ANIM_AIM_OFFSET = animNode("aim_offset")
            .property("base", NODE_NAME).required().flags(CgPropertyFlag.HEADLESS)
            .property("blend_space", hard("blend_space")).required().flags(CgPropertyFlag.HEADLESS)
            .property("x", ALPHA).defaultValue(0.0).flags(CgPropertyFlag.HEADLESS)
            .property("y", ALPHA).defaultValue(0.0).flags(CgPropertyFlag.HEADLESS)
            .property("alpha", ALPHA).defaultValue(1.0).flags(CgPropertyFlag.HEADLESS)
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

    // ── bone controls (FAnimNode_SkeletalControlBase): a source pose and an alpha each ──

    /** A control's alpha as a map: a bool blended in and out, or a value scaled, mapped, clamped and eased. */
    static final CgType CONTROL_ALPHA = CgTypes.struct("crystalgraphics:anim_graph.control_alpha")
            .property("bool", STRING).flags(CgPropertyFlag.HEADLESS)
            .property("blend_in", FLOAT).forms(CgValueForm.CONSTANT).quantity(CgQuantity.TIME).defaultValue(0.0).flags(CgPropertyFlag.HEADLESS)
            .property("blend_out", FLOAT).forms(CgValueForm.CONSTANT).quantity(CgQuantity.TIME).defaultValue(0.0).flags(CgPropertyFlag.HEADLESS)
            .property("value", ALPHA).flags(CgPropertyFlag.HEADLESS)
            .property("scale", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(1.0).flags(CgPropertyFlag.HEADLESS)
            .property("bias", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(0.0).flags(CgPropertyFlag.HEADLESS)
            .property("map_range", CgValueType.listOf(FLOAT)).flags(CgPropertyFlag.HEADLESS)
            .property("clamp", CgValueType.listOf(FLOAT)).flags(CgPropertyFlag.HEADLESS)
            .property("interp", CgValueType.listOf(FLOAT)).flags(CgPropertyFlag.HEADLESS)
            .register();
    private static final CgValueType CONTROL_ALPHA_VALUE = CgValueType.oneOf(FLOAT, STRING, CgValueType.struct(CONTROL_ALPHA));
    /** {@code EBoneControlSpace}. */
    private static final CgValueType BONE_SPACE = CgValueType.enumOf("world", "component", "parent_bone", "bone");
    /** A point a control reaches for: a location in a space, relative to a bone where the space is a bone's. */
    static final CgType IK_TARGET = CgTypes.struct("crystalgraphics:anim_graph.ik_target")
            .property("space", BONE_SPACE).required().flags(CgPropertyFlag.HEADLESS)
            .property("bone", STRING).flags(CgPropertyFlag.HEADLESS)
            .property("location", CgValueType.listOf(ALPHA)).required().flags(CgPropertyFlag.HEADLESS)
            .register();

    private static CgType.Builder controlNode(String kind) {
        return animNode(kind)
                .property("source", NODE_NAME).required().flags(CgPropertyFlag.HEADLESS)
                .property("alpha", CONTROL_ALPHA_VALUE).defaultValue(1.0).flags(CgPropertyFlag.HEADLESS);
    }

    static final CgType BONE_MODIFICATION = CgTypes.struct("crystalgraphics:anim_graph.bone_modification")
            .property("mode", CgValueType.enumOf("ignore", "replace", "add")).defaultValue("add").flags(CgPropertyFlag.HEADLESS)
            .property("space", BONE_SPACE).required().flags(CgPropertyFlag.HEADLESS)
            .property("value", CgValueType.listOf(ALPHA)).required().flags(CgPropertyFlag.HEADLESS)
            .register();
    /** {@code FAnimNode_ModifyBone}. */
    static final CgType ANIM_MODIFY_BONE = controlNode("modify_bone")
            .property("bone", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("translation", CgValueType.struct(BONE_MODIFICATION)).flags(CgPropertyFlag.HEADLESS)
            .property("rotation", CgValueType.struct(BONE_MODIFICATION)).flags(CgPropertyFlag.HEADLESS)
            .property("scale", CgValueType.struct(BONE_MODIFICATION)).flags(CgPropertyFlag.HEADLESS)
            .register();
    /** {@code FAnimNode_TwoBoneIK}. */
    static final CgType ANIM_TWO_BONE_IK = controlNode("two_bone_ik")
            .property("bone", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("effector", CgValueType.struct(IK_TARGET)).required().flags(CgPropertyFlag.HEADLESS)
            .property("joint_target", CgValueType.struct(IK_TARGET)).required().flags(CgPropertyFlag.HEADLESS)
            .property("stretch", CgValueType.listOf(FLOAT)).flags(CgPropertyFlag.HEADLESS)
            .property("rotation_from_effector", BOOL).defaultValue(false).flags(CgPropertyFlag.HEADLESS)
            .property("keep_relative_rotation", BOOL).defaultValue(false).flags(CgPropertyFlag.HEADLESS)
            .property("no_twist", CgValueType.listOf(FLOAT)).flags(CgPropertyFlag.HEADLESS)
            .register();
    /** {@code FAnimNode_LookAt}. */
    static final CgType ANIM_LOOK_AT = controlNode("look_at")
            .property("bone", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("target", CgValueType.struct(IK_TARGET)).required().flags(CgPropertyFlag.HEADLESS)
            .property("look_at_axis", CgValueType.listOf(FLOAT)).flags(CgPropertyFlag.HEADLESS)
            .property("look_up_axis", CgValueType.listOf(FLOAT)).flags(CgPropertyFlag.HEADLESS)
            .property("clamp", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(0.0).flags(CgPropertyFlag.HEADLESS)
            .property("interpolation_time", FLOAT).forms(CgValueForm.CONSTANT).quantity(CgQuantity.TIME).defaultValue(0.0).flags(CgPropertyFlag.HEADLESS)
            .property("interpolation", CgValueType.enumOf("linear", "cubic", "sinusoidal", "ease_in_out_exponent_2",
                    "ease_in_out_exponent_3", "ease_in_out_exponent_4", "ease_in_out_exponent_5")).defaultValue("linear").flags(CgPropertyFlag.HEADLESS)
            .property("interpolation_trigger", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(0.0).flags(CgPropertyFlag.HEADLESS)
            .register();
    /** {@code FAnimNode_CopyBone}. */
    static final CgType ANIM_COPY_BONE = controlNode("copy_bone")
            .property("source_bone", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("target_bone", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("translation", BOOL).defaultValue(false).flags(CgPropertyFlag.HEADLESS)
            .property("rotation", BOOL).defaultValue(false).flags(CgPropertyFlag.HEADLESS)
            .property("scale", BOOL).defaultValue(false).flags(CgPropertyFlag.HEADLESS)
            .property("space", BONE_SPACE).defaultValue("component").flags(CgPropertyFlag.HEADLESS)
            .register();
    /** {@code FAnimNode_Fabrik}. */
    static final CgType ANIM_FABRIK = controlNode("fabrik")
            .property("root_bone", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("tip_bone", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("effector", CgValueType.struct(IK_TARGET)).required().flags(CgPropertyFlag.HEADLESS)
            .property("tip_rotation", CgValueType.enumOf("keep_local", "copy_from_target", "keep_component")).defaultValue("keep_local").flags(CgPropertyFlag.HEADLESS)
            .property("precision", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(0.01).flags(CgPropertyFlag.HEADLESS)
            .property("max_iterations", INT).forms(CgValueForm.CONSTANT).defaultValue(10).flags(CgPropertyFlag.HEADLESS)
            .register();
    /** {@code FAnimNode_CCDIK}. */
    static final CgType ANIM_CCD_IK = controlNode("ccd_ik")
            .property("root_bone", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("tip_bone", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("effector", CgValueType.struct(IK_TARGET)).required().flags(CgPropertyFlag.HEADLESS)
            .property("precision", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(0.01).flags(CgPropertyFlag.HEADLESS)
            .property("max_iterations", INT).forms(CgValueForm.CONSTANT).defaultValue(10).flags(CgPropertyFlag.HEADLESS)
            .property("start_from_tail", BOOL).defaultValue(true).flags(CgPropertyFlag.HEADLESS)
            .property("enable_rotation_limit", BOOL).defaultValue(false).flags(CgPropertyFlag.HEADLESS)
            .property("rotation_limits", CgValueType.listOf(FLOAT)).flags(CgPropertyFlag.HEADLESS)
            .register();
    static final CgType CONSTRAINT_TARGET = CgTypes.struct("crystalgraphics:anim_graph.constraint_target")
            .property("target_bone", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("type", CgValueType.enumOf("translation", "rotation", "scale", "parent")).defaultValue("parent").flags(CgPropertyFlag.HEADLESS)
            .property("maintain_offset", BOOL).defaultValue(true).flags(CgPropertyFlag.HEADLESS)
            .property("axes", CgValueType.listOf(BOOL)).flags(CgPropertyFlag.HEADLESS)
            .property("weight", ALPHA).defaultValue(1.0).flags(CgPropertyFlag.HEADLESS)
            .register();
    /** {@code FAnimNode_Constraint}. */
    static final CgType ANIM_CONSTRAINT = controlNode("constraint")
            .property("bone", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("constraints", CgValueType.listOf(CgValueType.struct(CONSTRAINT_TARGET))).required().flags(CgPropertyFlag.HEADLESS)
            .register();
    static final CgType ANGULAR_LIMIT = CgTypes.struct("crystalgraphics:anim_graph.angular_limit")
            .property("bone", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("min", CgValueType.listOf(FLOAT)).required().flags(CgPropertyFlag.HEADLESS)
            .property("max", CgValueType.listOf(FLOAT)).required().flags(CgPropertyFlag.HEADLESS)
            .register();
    /** {@code FAnimNode_ApplyLimits}. */
    static final CgType ANIM_APPLY_LIMITS = controlNode("apply_limits")
            .property("limits", CgValueType.listOf(CgValueType.struct(ANGULAR_LIMIT))).required().flags(CgPropertyFlag.HEADLESS)
            .register();
    /** {@code FAnimNode_BoneDrivenController}, driving a bone. */
    static final CgType ANIM_BONE_DRIVEN_CONTROLLER = controlNode("bone_driven_controller")
            .property("source_bone", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("source_component", CgValueType.enumOf("translation_x", "translation_y", "translation_z", "rotation_x",
                    "rotation_y", "rotation_z", "scale", "scale_x", "scale_y", "scale_z")).required().flags(CgPropertyFlag.HEADLESS)
            .property("target_bone", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("affects", CgValueType.listOf(CgValueType.enumOf("translation_x", "translation_y", "translation_z",
                    "rotation_x", "rotation_y", "rotation_z", "scale_x", "scale_y", "scale_z"))).required().flags(CgPropertyFlag.HEADLESS)
            .property("mode", CgValueType.enumOf("add_to_input", "replace_component", "add_to_ref_pose")).defaultValue("add_to_input").flags(CgPropertyFlag.HEADLESS)
            .property("multiplier", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(1.0).flags(CgPropertyFlag.HEADLESS)
            .property("range", CgValueType.listOf(FLOAT)).flags(CgPropertyFlag.HEADLESS)
            .register();

    private static final CgValueType BONE_AXIS = CgValueType.enumOf("none", "x", "y", "z");
    static final CgType LEG_IK_LEG = CgTypes.struct("crystalgraphics:anim_graph.leg_ik_leg")
            .property("ik_foot_bone", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("fk_foot_bone", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("bones_in_limb", INT).forms(CgValueForm.CONSTANT).defaultValue(2).flags(CgPropertyFlag.HEADLESS)
            .property("min_rotation_angle", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(15.0).flags(CgPropertyFlag.HEADLESS)
            .property("foot_forward_axis", BONE_AXIS).defaultValue("y").flags(CgPropertyFlag.HEADLESS)
            .property("hinge_axis", BONE_AXIS).defaultValue("none").flags(CgPropertyFlag.HEADLESS)
            .property("rotation_limit", BOOL).defaultValue(false).flags(CgPropertyFlag.HEADLESS)
            .property("knee_twist_correction", BOOL).defaultValue(true).flags(CgPropertyFlag.HEADLESS)
            .register();
    /** {@code FAnimNode_LegIK}. */
    static final CgType ANIM_LEG_IK = controlNode("leg_ik")
            .property("legs", CgValueType.listOf(CgValueType.struct(LEG_IK_LEG))).required().flags(CgPropertyFlag.HEADLESS)
            .property("reach_precision", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(0.0001).flags(CgPropertyFlag.HEADLESS)
            .property("max_iterations", INT).forms(CgValueForm.CONSTANT).defaultValue(12).flags(CgPropertyFlag.HEADLESS)
            .property("soft_percent_length", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(1.0).flags(CgPropertyFlag.HEADLESS)
            .property("soft_alpha", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(1.0).flags(CgPropertyFlag.HEADLESS)
            .register();

    static final CgType FOOT_PLACEMENT_LEG = CgTypes.struct("crystalgraphics:anim_graph.foot_placement_leg")
            .property("fk_foot_bone", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("ik_foot_bone", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("ball_bone", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("bones_in_limb", INT).forms(CgValueForm.CONSTANT).defaultValue(2).flags(CgPropertyFlag.HEADLESS)
            .register();
    /** {@code FFootPlacementPlantSettings}, in metres. */
    static final CgType FOOT_PLACEMENT_PLANT = CgTypes.struct("crystalgraphics:anim_graph.foot_placement_plant")
            .property("speed_threshold", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(0.6).flags(CgPropertyFlag.HEADLESS)
            .property("distance_to_ground", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(0.1).flags(CgPropertyFlag.HEADLESS)
            .property("lock_type", CgValueType.enumOf("unlocked", "pivot_around_ball", "pivot_around_ankle", "lock_rotation"))
            .defaultValue("pivot_around_ball").flags(CgPropertyFlag.HEADLESS)
            .property("unplant_radius", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(0.35).flags(CgPropertyFlag.HEADLESS)
            .property("replant_radius_ratio", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(0.35).flags(CgPropertyFlag.HEADLESS)
            .property("unplant_angle", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(45.0).flags(CgPropertyFlag.HEADLESS)
            .property("replant_angle_ratio", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(0.5).flags(CgPropertyFlag.HEADLESS)
            .property("max_extension_ratio", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(0.5).flags(CgPropertyFlag.HEADLESS)
            .property("min_extension_ratio", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(0.2).flags(CgPropertyFlag.HEADLESS)
            .property("separating_distance", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(0.0).flags(CgPropertyFlag.HEADLESS)
            .property("unalignment_speed_threshold", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(2.0).flags(CgPropertyFlag.HEADLESS)
            .property("ankle_twist_reduction", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(0.75).flags(CgPropertyFlag.HEADLESS)
            .property("reconstruct_world_plant_from_velocity", BOOL).defaultValue(false).flags(CgPropertyFlag.HEADLESS)
            .property("adjust_heel_before_planting", BOOL).defaultValue(false).flags(CgPropertyFlag.HEADLESS)
            .register();
    /** {@code FFootPlacementPelvisSettings}, in metres. */
    static final CgType FOOT_PLACEMENT_PELVIS = CgTypes.struct("crystalgraphics:anim_graph.foot_placement_pelvis")
            .property("max_offset", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(0.5).flags(CgPropertyFlag.HEADLESS)
            .property("linear_stiffness", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(350.0).flags(CgPropertyFlag.HEADLESS)
            .property("linear_damping", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(1.0).flags(CgPropertyFlag.HEADLESS)
            .property("horizontal_rebalancing_weight", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(0.3).flags(CgPropertyFlag.HEADLESS)
            .property("max_offset_horizontal", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(0.1).flags(CgPropertyFlag.HEADLESS)
            .property("heel_lift_ratio", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(0.5).flags(CgPropertyFlag.HEADLESS)
            .property("movement_compensation", CgValueType.enumOf("component_space", "world_space", "sudden_motion_only"))
            .defaultValue("sudden_motion_only").flags(CgPropertyFlag.HEADLESS)
            .property("interpolation", BOOL).defaultValue(true).flags(CgPropertyFlag.HEADLESS)
            .property("disable_offset_in_air", BOOL).defaultValue(true).flags(CgPropertyFlag.HEADLESS)
            .register();
    /** {@code FFootPlacementInterpolationSettings}. */
    static final CgType FOOT_PLACEMENT_INTERPOLATION = CgTypes.struct("crystalgraphics:anim_graph.foot_placement_interpolation")
            .property("unplant_linear_stiffness", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(250.0).flags(CgPropertyFlag.HEADLESS)
            .property("unplant_linear_damping", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(1.0).flags(CgPropertyFlag.HEADLESS)
            .property("unplant_angular_stiffness", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(450.0).flags(CgPropertyFlag.HEADLESS)
            .property("unplant_angular_damping", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(1.0).flags(CgPropertyFlag.HEADLESS)
            .property("floor_linear_stiffness", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(1000.0).flags(CgPropertyFlag.HEADLESS)
            .property("floor_linear_damping", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(1.0).flags(CgPropertyFlag.HEADLESS)
            .property("floor_angular_stiffness", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(450.0).flags(CgPropertyFlag.HEADLESS)
            .property("floor_angular_damping", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(1.0).flags(CgPropertyFlag.HEADLESS)
            .property("floor_interpolation", BOOL).defaultValue(true).flags(CgPropertyFlag.HEADLESS)
            .property("separation_interpolation", BOOL).defaultValue(true).flags(CgPropertyFlag.HEADLESS)
            .register();
    /** {@code FFootPlacementTraceSettings}, in metres. */
    static final CgType FOOT_PLACEMENT_TRACE = CgTypes.struct("crystalgraphics:anim_graph.foot_placement_trace")
            .property("start_offset", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(-0.75).flags(CgPropertyFlag.HEADLESS)
            .property("end_offset", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(1.0).flags(CgPropertyFlag.HEADLESS)
            .property("sweep_radius", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(0.05).flags(CgPropertyFlag.HEADLESS)
            .property("max_ground_penetration", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(0.1).flags(CgPropertyFlag.HEADLESS)
            .property("enabled", BOOL).defaultValue(true).flags(CgPropertyFlag.HEADLESS)
            .register();
    /** {@code FAnimNode_FootPlacement}. */
    static final CgType ANIM_FOOT_PLACEMENT = controlNode("foot_placement")
            .property("pelvis_bone", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("ik_foot_root_bone", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("legs", CgValueType.listOf(CgValueType.struct(FOOT_PLACEMENT_LEG))).required().flags(CgPropertyFlag.HEADLESS)
            .property("plant", CgValueType.struct(FOOT_PLACEMENT_PLANT)).flags(CgPropertyFlag.HEADLESS)
            .property("pelvis", CgValueType.struct(FOOT_PLACEMENT_PELVIS)).flags(CgPropertyFlag.HEADLESS)
            .property("interpolation", CgValueType.struct(FOOT_PLACEMENT_INTERPOLATION)).flags(CgPropertyFlag.HEADLESS)
            .property("trace", CgValueType.struct(FOOT_PLACEMENT_TRACE)).flags(CgPropertyFlag.HEADLESS)
            .register();

    /** {@code FAnimNode_SpringBone}, in metres. */
    static final CgType ANIM_SPRING_BONE = controlNode("spring_bone")
            .property("bone", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("stiffness", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(50.0).flags(CgPropertyFlag.HEADLESS)
            .property("damping", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(4.0).flags(CgPropertyFlag.HEADLESS)
            .property("error_reset", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(2.56).flags(CgPropertyFlag.HEADLESS)
            .property("max_displacement", FLOAT).forms(CgValueForm.CONSTANT).flags(CgPropertyFlag.HEADLESS)
            .property("translate", CgValueType.listOf(BOOL)).flags(CgPropertyFlag.HEADLESS)
            .property("rotate", CgValueType.listOf(BOOL)).flags(CgPropertyFlag.HEADLESS)
            .property("owner_velocity", CgValueType.listOf(FLOAT)).flags(CgPropertyFlag.HEADLESS)
            .register();

    // ── Locomotion warping ──

    private static final CgValueType WARPING_MODE = CgValueType.enumOf("manual", "graph");
    static final CgType STRIDE_WARPING_FOOT = CgTypes.struct("crystalgraphics:anim_graph.stride_warping_foot")
            .property("ik_foot_bone", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("fk_foot_bone", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("thigh_bone", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .register();
    /** Feet fitted to the real speed: strides scaled along the way travelled, the pelvis pulled down, in metres. */
    static final CgType ANIM_STRIDE_WARPING = controlNode("stride_warping")
            .property("pelvis", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("ik_foot_root", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("feet", CgValueType.listOf(CgValueType.struct(STRIDE_WARPING_FOOT))).required().flags(CgPropertyFlag.HEADLESS)
            .property("mode", WARPING_MODE).defaultValue("graph").flags(CgPropertyFlag.HEADLESS)
            .property("locomotion_speed", ALPHA).defaultValue(0.0).flags(CgPropertyFlag.HEADLESS)
            .property("stride_scale", ALPHA).defaultValue(1.0).flags(CgPropertyFlag.HEADLESS)
            .property("stride_direction", CgValueType.listOf(FLOAT)).flags(CgPropertyFlag.HEADLESS)
            .property("min_root_motion_speed", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(0.1).flags(CgPropertyFlag.HEADLESS)
            .property("scale_clamp", CgValueType.listOf(FLOAT)).flags(CgPropertyFlag.HEADLESS)
            .property("scale_interp", CgValueType.listOf(FLOAT)).flags(CgPropertyFlag.HEADLESS)
            .property("pelvis_alpha", FLOAT).forms(CgValueForm.CONSTANT).range(0, 1).defaultValue(0.5).flags(CgPropertyFlag.HEADLESS)
            .property("pelvis_max_distance", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(0.1).flags(CgPropertyFlag.HEADLESS)
            .property("pelvis_stiffness", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(1.0).flags(CgPropertyFlag.HEADLESS)
            .property("pelvis_damping", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(1.0).flags(CgPropertyFlag.HEADLESS)
            .property("compensate_thighs", BOOL).defaultValue(true).flags(CgPropertyFlag.HEADLESS)
            .property("clamp_to_fk", BOOL).defaultValue(true).flags(CgPropertyFlag.HEADLESS)
            .property("orient_to_floor", BOOL).defaultValue(true).flags(CgPropertyFlag.HEADLESS)
            .register();
    /** Legs and root turned toward the way travelled, the spine turned back; angles in degrees about +y. */
    static final CgType ANIM_ORIENTATION_WARPING = controlNode("orientation_warping")
            .property("ik_foot_root", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("spine_bones", CgValueType.listOf(STRING)).required().flags(CgPropertyFlag.HEADLESS)
            .property("ik_foot_bones", CgValueType.listOf(STRING)).required().flags(CgPropertyFlag.HEADLESS)
            .property("mode", WARPING_MODE).defaultValue("graph").flags(CgPropertyFlag.HEADLESS)
            .property("locomotion_angle", ALPHA).defaultValue(0.0).flags(CgPropertyFlag.HEADLESS)
            .property("orientation_angle", ALPHA).defaultValue(0.0).flags(CgPropertyFlag.HEADLESS)
            .property("distribution", FLOAT).forms(CgValueForm.CONSTANT).range(0, 1).defaultValue(0.5).flags(CgPropertyFlag.HEADLESS)
            .property("interp_speed", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(10.0).flags(CgPropertyFlag.HEADLESS)
            .property("max_correction", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(180.0).flags(CgPropertyFlag.HEADLESS)
            .property("min_root_motion_speed", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(0.1).flags(CgPropertyFlag.HEADLESS)
            .property("flip_beyond", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(90.0).flags(CgPropertyFlag.HEADLESS)
            .register();
    private static final CgValueType OFFSET_ROOT_MODE = CgValueType.enumOf("accumulate", "interpolate", "hold_and_consume",
            "hold_shrink_and_consume", "hold", "release");
    /** The body let lag behind the entity and eased back: turn in place. Half-lives in seconds, errors in metres and degrees. */
    static final CgType ANIM_OFFSET_ROOT_BONE = animNode("offset_root_bone")
            .property("source", NODE_NAME).required().flags(CgPropertyFlag.HEADLESS)
            .property("translation_mode", OFFSET_ROOT_MODE).defaultValue("interpolate").flags(CgPropertyFlag.HEADLESS)
            .property("rotation_mode", OFFSET_ROOT_MODE).defaultValue("interpolate").flags(CgPropertyFlag.HEADLESS)
            .property("translation_half_life", FLOAT).forms(CgValueForm.CONSTANT).quantity(CgQuantity.TIME).defaultValue(0.1).flags(CgPropertyFlag.HEADLESS)
            .property("rotation_half_life", FLOAT).forms(CgValueForm.CONSTANT).quantity(CgQuantity.TIME).defaultValue(0.2).flags(CgPropertyFlag.HEADLESS)
            .property("max_translation_error", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(-1.0).flags(CgPropertyFlag.HEADLESS)
            .property("max_rotation_error", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(-1.0).flags(CgPropertyFlag.HEADLESS)
            .property("translation_speed_ratio", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(0.0).flags(CgPropertyFlag.HEADLESS)
            .property("rotation_speed_ratio", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(0.0).flags(CgPropertyFlag.HEADLESS)
            .property("on_ground", BOOL).defaultValue(true).flags(CgPropertyFlag.HEADLESS)
            .property("teleport_distance", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(3.0).flags(CgPropertyFlag.HEADLESS)
            .register();
    /** A clip held at a time set each frame: a variable's seconds, or matched by distance. */
    static final CgType ANIM_SEQUENCE_EVALUATOR = animNode("sequence_evaluator")
            .property("clip", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("loop", BOOL).defaultValue(false).flags(CgPropertyFlag.HEADLESS)
            .property("mode", CgValueType.enumOf("explicit", "advance_by_distance", "match_to_target")).defaultValue("explicit")
            .flags(CgPropertyFlag.HEADLESS)
            .property("input", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("curve", STRING).defaultValue("Distance").flags(CgPropertyFlag.HEADLESS)
            .property("play_rate_clamp", CgValueType.listOf(FLOAT)).flags(CgPropertyFlag.HEADLESS)
            .register();

    private static final CgValueType AXIS = CgValueType.enumOf("x", "y", "z");
    private static final CgValueType LINEAR_LIMIT = CgValueType.enumOf("free", "limited");
    /** {@code FAnimPhysConstraintSetup}, angles in degrees. */
    static final CgType ANIM_DYNAMICS_CONSTRAINT = CgTypes.struct("crystalgraphics:anim_graph.anim_dynamics_constraint")
            .property("linear_x", LINEAR_LIMIT).defaultValue("limited").flags(CgPropertyFlag.HEADLESS)
            .property("linear_y", LINEAR_LIMIT).defaultValue("limited").flags(CgPropertyFlag.HEADLESS)
            .property("linear_z", LINEAR_LIMIT).defaultValue("limited").flags(CgPropertyFlag.HEADLESS)
            .property("linear_min", CgValueType.listOf(FLOAT)).flags(CgPropertyFlag.HEADLESS)
            .property("linear_max", CgValueType.listOf(FLOAT)).flags(CgPropertyFlag.HEADLESS)
            .property("angular_type", CgValueType.enumOf("angular", "cone")).defaultValue("angular").flags(CgPropertyFlag.HEADLESS)
            .property("twist_axis", AXIS).defaultValue("x").flags(CgPropertyFlag.HEADLESS)
            .property("angular_target_axis", AXIS).defaultValue("x").flags(CgPropertyFlag.HEADLESS)
            .property("cone_angle", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(0.0).flags(CgPropertyFlag.HEADLESS)
            .property("angular_min", CgValueType.listOf(FLOAT)).flags(CgPropertyFlag.HEADLESS)
            .property("angular_max", CgValueType.listOf(FLOAT)).flags(CgPropertyFlag.HEADLESS)
            .property("angular_target", CgValueType.listOf(FLOAT)).flags(CgPropertyFlag.HEADLESS)
            .register();
    /** {@code FAnimPhysBodyDefinition}, in metres. */
    static final CgType ANIM_DYNAMICS_BODY = CgTypes.struct("crystalgraphics:anim_graph.anim_dynamics_body")
            .property("bone", STRING).flags(CgPropertyFlag.HEADLESS)
            .property("box_extents", CgValueType.listOf(FLOAT)).flags(CgPropertyFlag.HEADLESS)
            .property("local_joint_offset", CgValueType.listOf(FLOAT)).flags(CgPropertyFlag.HEADLESS)
            .property("constraint", CgValueType.struct(ANIM_DYNAMICS_CONSTRAINT)).flags(CgPropertyFlag.HEADLESS)
            .property("collision", CgValueType.enumOf("com", "custom_sphere", "inner_sphere", "outer_sphere")).defaultValue("com")
            .flags(CgPropertyFlag.HEADLESS)
            .property("sphere_radius", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(0.1).flags(CgPropertyFlag.HEADLESS)
            .register();
    /** {@code FAnimPhysSphericalLimit}. */
    static final CgType ANIM_DYNAMICS_SPHERE = CgTypes.struct("crystalgraphics:anim_graph.anim_dynamics_sphere")
            .property("driving_bone", STRING).flags(CgPropertyFlag.HEADLESS)
            .property("offset", CgValueType.listOf(FLOAT)).flags(CgPropertyFlag.HEADLESS)
            .property("radius", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(0.0).flags(CgPropertyFlag.HEADLESS)
            .property("type", CgValueType.enumOf("inner", "outer")).defaultValue("outer").flags(CgPropertyFlag.HEADLESS)
            .register();
    /** {@code FAnimPhysPlanarLimit}: the plane's point and its up axis. */
    static final CgType ANIM_DYNAMICS_PLANE = CgTypes.struct("crystalgraphics:anim_graph.anim_dynamics_plane")
            .property("driving_bone", STRING).flags(CgPropertyFlag.HEADLESS)
            .property("point", CgValueType.listOf(FLOAT)).flags(CgPropertyFlag.HEADLESS)
            .property("normal", CgValueType.listOf(FLOAT)).flags(CgPropertyFlag.HEADLESS)
            .register();
    /** How much of the component's motion the bodies feel, in metres a second squared. */
    static final CgType ANIM_DYNAMICS_COMPONENT_MOTION = CgTypes.struct("crystalgraphics:anim_graph.anim_dynamics_component_motion")
            .property("linear_acc_scale", CgValueType.listOf(FLOAT)).flags(CgPropertyFlag.HEADLESS)
            .property("linear_vel_scale", CgValueType.listOf(FLOAT)).flags(CgPropertyFlag.HEADLESS)
            .property("applied_linear_acc_clamp", CgValueType.listOf(FLOAT)).flags(CgPropertyFlag.HEADLESS)
            .register();
    /** {@code FAnimPhysSimSpaceSettings}. */
    static final CgType ANIM_DYNAMICS_SIM_SPACE = CgTypes.struct("crystalgraphics:anim_graph.anim_dynamics_sim_space")
            .property("angular_alpha", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(0.0).flags(CgPropertyFlag.HEADLESS)
            .property("max_angular_velocity", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(10000.0).flags(CgPropertyFlag.HEADLESS)
            .property("max_angular_acceleration", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(10000.0).flags(CgPropertyFlag.HEADLESS)
            .property("external_angular_velocity", CgValueType.listOf(FLOAT)).flags(CgPropertyFlag.HEADLESS)
            .register();
    /** {@code FAnimNode_AnimDynamics}, in metres; no wind and no retargeting. */
    static final CgType ANIM_DYNAMICS = controlNode("anim_dynamics")
            .property("bone", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("chain_end", STRING).flags(CgPropertyFlag.HEADLESS)
            .property("simulation_space", CgValueType.enumOf("component", "world", "root_relative", "bone_relative"))
            .defaultValue("component").flags(CgPropertyFlag.HEADLESS)
            .property("relative_space_bone", STRING).flags(CgPropertyFlag.HEADLESS)
            .property("bodies", CgValueType.listOf(CgValueType.struct(ANIM_DYNAMICS_BODY))).flags(CgPropertyFlag.HEADLESS)
            .property("gravity_scale", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(1.0).flags(CgPropertyFlag.HEADLESS)
            .property("gravity_override", CgValueType.listOf(FLOAT)).flags(CgPropertyFlag.HEADLESS)
            .property("gravity_override_in_sim_space", BOOL).defaultValue(false).flags(CgPropertyFlag.HEADLESS)
            .property("linear_damping", FLOAT).forms(CgValueForm.CONSTANT).flags(CgPropertyFlag.HEADLESS)
            .property("angular_damping", FLOAT).forms(CgValueForm.CONSTANT).flags(CgPropertyFlag.HEADLESS)
            .property("angular_bias", FLOAT).forms(CgValueForm.CONSTANT).flags(CgPropertyFlag.HEADLESS)
            .property("linear_spring", FLOAT).forms(CgValueForm.CONSTANT).flags(CgPropertyFlag.HEADLESS)
            .property("angular_spring", FLOAT).forms(CgValueForm.CONSTANT).flags(CgPropertyFlag.HEADLESS)
            .property("pre_iterations", INT).forms(CgValueForm.CONSTANT).defaultValue(4).flags(CgPropertyFlag.HEADLESS)
            .property("post_iterations", INT).forms(CgValueForm.CONSTANT).defaultValue(1).flags(CgPropertyFlag.HEADLESS)
            .property("external_force", CgValueType.listOf(FLOAT)).flags(CgPropertyFlag.HEADLESS)
            .property("component_motion", CgValueType.struct(ANIM_DYNAMICS_COMPONENT_MOTION)).flags(CgPropertyFlag.HEADLESS)
            .property("sim_space_settings", CgValueType.struct(ANIM_DYNAMICS_SIM_SPACE)).flags(CgPropertyFlag.HEADLESS)
            .property("spherical_limits", CgValueType.listOf(CgValueType.struct(ANIM_DYNAMICS_SPHERE))).flags(CgPropertyFlag.HEADLESS)
            .property("planar_limits", CgValueType.listOf(CgValueType.struct(ANIM_DYNAMICS_PLANE))).flags(CgPropertyFlag.HEADLESS)
            .register();

    /** {@code FAnimNode_LinkedInputPose}: a pose its layer's or graph's caller passes. */
    static final CgType ANIM_LINKED_INPUT_POSE = animNode("linked_input_pose")
            .property("name", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .register();
    /** {@code FAnimNode_LinkedAnimLayer}: a layer, the graph's own or a linked graph's, its input poses by name. */
    static final CgType ANIM_LINKED_ANIM_LAYER = animNode("linked_anim_layer")
            .property("layer", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("inputs", CgValueType.mapOf(NODE_NAME)).flags(CgPropertyFlag.HEADLESS)
            .register();
    /** {@code FAnimNode_LinkedAnimGraph}: another graph run as a node. */
    static final CgType ANIM_LINKED_ANIM_GRAPH = animNode("linked_anim_graph")
            .property("graph", hard("anim_graph")).required().flags(CgPropertyFlag.HEADLESS)
            .property("tag", STRING).flags(CgPropertyFlag.HEADLESS)
            .property("inputs", CgValueType.mapOf(NODE_NAME)).flags(CgPropertyFlag.HEADLESS)
            .register();

    /** An anim layer function: its input poses, the node that is its pose, its group and its swap's blends. */
    static final CgType ANIM_LAYER_FUNCTION = CgTypes.struct("crystalgraphics:anim_graph.layer_function")
            .property("inputs", CgValueType.listOf(STRING)).flags(CgPropertyFlag.HEADLESS)
            .property("output", NODE_NAME).required().flags(CgPropertyFlag.HEADLESS)
            .property("group", STRING).flags(CgPropertyFlag.HEADLESS)
            .property("blend_in", FLOAT).forms(CgValueForm.CONSTANT).quantity(CgQuantity.TIME).range(0, null).defaultValue(0.0)
            .flags(CgPropertyFlag.HEADLESS)
            .property("blend_out", FLOAT).forms(CgValueForm.CONSTANT).quantity(CgQuantity.TIME).range(0, null).defaultValue(0.0)
            .flags(CgPropertyFlag.HEADLESS)
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

    /** A variable fed by a gameplay tag the entity owns: Unreal's {@code FGameplayTagBlueprintPropertyMapping}. */
    static final CgType ANIM_TAG = CgTypes.struct("crystalgraphics:anim_graph.tag")
            .property("tag", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("as", CgValueType.enumOf("bool", "count", "float")).flags(CgPropertyFlag.HEADLESS)
            .register();

    /**
     * {@code anim_graphs/}: an anim blueprint's graph ({@code UAnimBlueprint}): its variables, its nodes by name, the one
     * whose pose is the output, and its state machines by name. {@code entity} binds a variable to a value of the entity
     * by name, a derived field or {@code <type>#<field>} of its networked data ({@code world.CgEntityFields#binding}),
     * set each frame by the entity's actor; {@code tags} a variable
     * to a gameplay tag the entity owns ({@code world.CgEntityTags}).
     */
    public static final CgType ANIM_GRAPH = CgTypes.kind("crystalgraphics:anim_graph").folder("anim_graphs").version(1)
            .property("variables", CgValueType.mapOf(CgValueType.struct(ANIM_VARIABLE))).flags(CgPropertyFlag.HEADLESS)
            .property("entity", CgValueType.mapOf(STRING)).flags(CgPropertyFlag.HEADLESS)
            .property("tags", CgValueType.mapOf(CgValueType.struct(ANIM_TAG))).flags(CgPropertyFlag.HEADLESS)
            .property("inputs", CgValueType.listOf(STRING)).flags(CgPropertyFlag.HEADLESS)
            .property("output", NODE_NAME).required().flags(CgPropertyFlag.HEADLESS)
            .property("nodes", CgValueType.mapOf(CgValueType.dispatch(ANIM_NODES))).required().flags(CgPropertyFlag.HEADLESS)
            .property("machines", CgValueType.mapOf(CgValueType.struct(ANIM_MACHINE))).flags(CgPropertyFlag.HEADLESS)
            .property("layers", CgValueType.mapOf(CgValueType.struct(ANIM_LAYER_FUNCTION))).flags(CgPropertyFlag.HEADLESS)
            .register();

    // ── Blend space ──────────────────────────────────────────────────────────────────────

    /** {@code FInterpolationParameter}: an axis's input smoothing. */
    static final CgType BLEND_SMOOTHING = CgTypes.struct("crystalgraphics:blend_space.smoothing")
            .property("time", FLOAT).forms(CgValueForm.CONSTANT).quantity(CgQuantity.TIME).range(0, null).defaultValue(0.0)
            .flags(CgPropertyFlag.HEADLESS)
            .property("type", CgValueType.enumOf("average", "linear", "cubic", "ease_in_out", "exponential_decay", "spring_damper"))
            .defaultValue("spring_damper").flags(CgPropertyFlag.HEADLESS)
            .property("damping", FLOAT).forms(CgValueForm.CONSTANT).range(0, null).defaultValue(1.0).flags(CgPropertyFlag.HEADLESS)
            .property("max_speed", FLOAT).forms(CgValueForm.CONSTANT).range(0, null).defaultValue(0.0).flags(CgPropertyFlag.HEADLESS)
            .register();

    /** {@code FBlendParameter}. */
    static final CgType BLEND_AXIS = CgTypes.struct("crystalgraphics:blend_space.axis")
            .property("name", STRING).flags(CgPropertyFlag.HEADLESS)
            .property("min", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(0.0).flags(CgPropertyFlag.HEADLESS)
            .property("max", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(100.0).flags(CgPropertyFlag.HEADLESS)
            .property("grid", INT).forms(CgValueForm.CONSTANT).range(1, null).defaultValue(4).flags(CgPropertyFlag.HEADLESS)
            .property("smoothing", CgValueType.struct(BLEND_SMOOTHING)).flags(CgPropertyFlag.HEADLESS)
            .property("wrap", BOOL).defaultValue(false).flags(CgPropertyFlag.HEADLESS)
            .register();

    /** {@code FBlendSample}. */
    static final CgType BLEND_SAMPLE = CgTypes.struct("crystalgraphics:blend_space.sample")
            .property("clip", STRING).required().flags(CgPropertyFlag.HEADLESS)
            .property("at", CgValueType.listOf(FLOAT)).required().flags(CgPropertyFlag.HEADLESS)
            .property("rate_scale", FLOAT).forms(CgValueForm.CONSTANT).defaultValue(1.0).flags(CgPropertyFlag.HEADLESS)
            .register();

    /**
     * {@code blend_spaces/}: clips placed on one or two axes and blended by where an input falls among them
     * ({@code UBlendSpace}, {@code UBlendSpace1D}, {@code UAimOffsetBlendSpace}).
     */
    public static final CgType BLEND_SPACE = CgTypes.kind("crystalgraphics:blend_space").folder("blend_spaces").version(1)
            .property("axes", CgValueType.listOf(CgValueType.struct(BLEND_AXIS))).required().flags(CgPropertyFlag.HEADLESS)
            .property("samples", CgValueType.listOf(CgValueType.struct(BLEND_SAMPLE))).required().flags(CgPropertyFlag.HEADLESS)
            .property("weight_speed", FLOAT).forms(CgValueForm.CONSTANT).range(0, null).defaultValue(0.0).flags(CgPropertyFlag.HEADLESS)
            .property("weight_ease", BOOL).defaultValue(true).flags(CgPropertyFlag.HEADLESS)
            .property("grid_interpolation", BOOL).defaultValue(false).flags(CgPropertyFlag.HEADLESS)
            .property("legacy_length", BOOL).defaultValue(false).flags(CgPropertyFlag.HEADLESS)
            .property("scale_axis", CgValueType.enumOf("none", "x", "y")).defaultValue("none").flags(CgPropertyFlag.HEADLESS)
            .property("sync", BOOL).defaultValue(false).flags(CgPropertyFlag.HEADLESS)
            .property("grid", CgValueType.listOf(CgValueType.listOf(CgValueType.listOf(FLOAT)))).flags(CgPropertyFlag.HEADLESS)
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
            .property("bend", VEC3)
            .property("rides", STRING)
            .property("pivot", VEC3)
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
