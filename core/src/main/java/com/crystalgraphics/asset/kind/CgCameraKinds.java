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
import static com.crystalgraphics.object.CgValueType.STRING;
import static com.crystalgraphics.object.CgValueType.TREE;
import static com.crystalgraphics.object.CgValueType.VALUE;
import static com.crystalgraphics.object.CgValueType.VEC3;
import static com.crystalgraphics.object.CgValueType.VEC4;

/**
 * The camera kinds and the post block: {@code camera_rig} (Gameplay Cameras'
 * {@code UCameraRigAsset}), {@code shake} ({@code UCameraShakeBase}), and {@link #POST}, Unreal's
 * {@code FPostProcessSettings} wherever post goes.
 *
 * <ul>
 *   <li>Every rig node field is a parameter, a constant or {@code { bind: rig.<name> }}, so each is a value.</li>
 *   <li>A post key present overrides and a key absent does not (Unreal's {@code bOverride_} flags): the block is
 *       open, every key an {@code FPostProcessSettings} field in snake case.</li>
 * </ul>
 *
 * <p>Registered by {@link CgAssetKinds#register()}.</p>
 */
public final class CgCameraKinds {

    private CgCameraKinds() {
    }

    // ── Post ─────────────────────────────────────────────────────────────────────────────

    static final CgType EXPOSURE = CgTypes.struct("crystalgraphics:post.exposure")
            .property("compensation", FLOAT).flags(CgPropertyFlag.KEYABLE)
            .property("min_brightness", FLOAT)
            .property("max_brightness", FLOAT)
            .property("flash", FLOAT).flags(CgPropertyFlag.KEYABLE)
            .register();

    static final CgType WHITE_BALANCE = CgTypes.struct("crystalgraphics:post.white_balance")
            .property("temperature", FLOAT)
            .property("tint", FLOAT)
            .register();

    /** A colour grade's five, plus tint: the whole image's, or its shadows', midtones' or highlights'. */
    static final CgType GRADE = CgTypes.struct("crystalgraphics:post.grade")
            .property("saturation", VEC4)
            .property("contrast", VEC4)
            .property("gamma", VEC4)
            .property("gain", VEC4)
            .property("offset", VEC4)
            .property("tint", VEC4)
            .register();

    static final CgType MOTION_BLUR = CgTypes.struct("crystalgraphics:post.motion_blur")
            .property("amount", FLOAT)
            .property("max", FLOAT)
            .register();

    static final CgType DEPTH_OF_FIELD = CgTypes.struct("crystalgraphics:post.depth_of_field")
            .property("focal_distance", FLOAT).quantity(CgQuantity.LENGTH)
            .property("fstop", FLOAT)
            .register();

    /** An impact frame (ours). */
    static final CgType IMPACT = CgTypes.struct("crystalgraphics:post.impact")
            .property("frame", STRING).required()
            .property("amount", FLOAT).defaultValue(1.0).flags(CgPropertyFlag.KEYABLE)
            .property("seed", CgValueType.INT)
            .property("subject", STRING)
            .property("focus", CgValueType.VEC2)
            .register();

    /** Two parameters driven by a bound thing's position on screen, 0–1 ({@code UKoratPostProcessWorldToScreenComponent}). */
    static final CgType SCREEN_POINT = CgTypes.struct("crystalgraphics:post.screen_point")
            .property("target", CgCommonStructs.BINDING).required()
            .property("x", STRING)
            .property("y", STRING)
            .property("enabled", BOOL).defaultValue(true)
            .register();

    static final CgType POST_MATERIAL = CgTypes.struct("crystalgraphics:post.material")
            .property("name", STRING).flags(CgPropertyFlag.LOCKED)
            .property("material", hard("material")).required().flags(CgPropertyFlag.STRUCTURAL)
            .property("weight", FLOAT).defaultValue(1.0).flags(CgPropertyFlag.KEYABLE)
            .property("enabled", BOOL).defaultValue(true).flags(CgPropertyFlag.KEYABLE)
            .property("values", mapOfValues())
            .property("textures", CgValueType.mapOf(CgValueType.binaryRef("textures")))
            .property("screen_point", CgValueType.struct(SCREEN_POINT))
            .register();

    /** A post block ({@code FPostProcessSettings}); open, since every settings field may appear. */
    public static final CgType POST = CgTypes.struct("crystalgraphics:post").open()
            .property("bloom", FLOAT).flags(CgPropertyFlag.KEYABLE)
            .property("bloom_threshold", FLOAT).flags(CgPropertyFlag.KEYABLE)
            .property("exposure", CgValueType.struct(EXPOSURE))
            .property("vignette", FLOAT).flags(CgPropertyFlag.KEYABLE)
            .property("chromatic", FLOAT).flags(CgPropertyFlag.KEYABLE)
            .property("white_balance", CgValueType.struct(WHITE_BALANCE))
            .property("colour", CgValueType.struct(GRADE))
            .property("shadows", CgValueType.struct(GRADE))
            .property("midtones", CgValueType.struct(GRADE))
            .property("highlights", CgValueType.struct(GRADE))
            .property("lut", CgValueType.binaryRef("textures"))
            .property("grading_intensity", FLOAT).flags(CgPropertyFlag.KEYABLE)
            .property("tone_curve", FLOAT).flags(CgPropertyFlag.KEYABLE)
            .property("expand_gamut", FLOAT).flags(CgPropertyFlag.KEYABLE)
            .property("film_grain", FLOAT).flags(CgPropertyFlag.KEYABLE)
            .property("motion_blur", CgValueType.struct(MOTION_BLUR))
            .property("depth_of_field", CgValueType.struct(DEPTH_OF_FIELD))
            .property("materials", CgValueType.listOf(CgValueType.struct(POST_MATERIAL)))
            .property("impact", CgValueType.struct(IMPACT))
            .register();

    // ── Camera rig ───────────────────────────────────────────────────────────────────────

    /** Rig nodes by {@code node}; a mod registers its own. */
    public static final CgType NODES = CgTypes.dispatch("crystalgraphics:camera_node", "node").register();

    private static final CgValueType SPACE = CgValueType.enumOf("camera_pose", "active_context", "owning_context",
            "pivot", "subject", "world");

    /**
     * A node of {@code name}: its fields in its Unreal class's declaration order, snake-cased, then
     * {@code editor}. Register with {@link #done}.
     */
    private static CgType.Builder node(String name) {
        return CgTypes.element("crystalgraphics:camera_node." + name).onRegister(t -> NODES.member(name, t));
    }

    private static CgType done(CgType.Builder node) {
        return node.property("editor", TREE).flags(CgPropertyFlag.EDITOR_ONLY).register();
    }

    /**
     * A camera parameter ({@code F<T>CameraParameter}): a constant, or bound to a rig parameter
     * ({@code { bind: rig.<name> }}).
     */
    private static CgType.Builder param(CgType.Builder b, String name, CgValueType type) {
        return b.property(name, type).forms(CgValueForm.CONSTANT, CgValueForm.BINDING);
    }

    /** {@code FCameraActorAttachmentInfo}: what an attach node follows. */
    static final CgType ATTACHMENT = CgTypes.struct("crystalgraphics:camera_node.attachment")
            .property("role", STRING)
            .property("socket", STRING)
            .property("weight", FLOAT).defaultValue(1.0)
            .register();

    /** {@code FCameraActorTargetInfo}: what a framing node keeps on screen. */
    static final CgType FRAMING_TARGET = CgTypes.struct("crystalgraphics:camera_node.framing_target")
            .property("role", STRING)
            .property("socket", STRING)
            .property("target_shape", CgValueType.enumOf("point", "automatic_bounds", "manual_bounds")).defaultValue("point")
            .property("target_size", FLOAT).quantity(CgQuantity.LENGTH).defaultValue(10.0)
            .property("weight", FLOAT).defaultValue(1.0)
            .register();

    /** {@code FCameraFramingZone}: margins as fractions of the screen. */
    static final CgType FRAMING_ZONE = CgTypes.struct("crystalgraphics:camera_node.framing_zone")
            .property("left", FLOAT).defaultValue(0.0)
            .property("top", FLOAT).defaultValue(0.0)
            .property("right", FLOAT).defaultValue(0.0)
            .property("bottom", FLOAT).defaultValue(0.0)
            .register();

    /** A value interpolator ({@code UCameraValueInterpolator}); {@code damped} is the only kind until 10 CA1. */
    static final CgType INTERPOLATOR = CgTypes.struct("crystalgraphics:camera_node.interpolator")
            .property("kind", CgValueType.enumOf("damped")).required()
            .property("factor", FLOAT)
            .register();

    static final CgType ARRAY = done(node("array").property("children", CgValueType.idList(NODES)));

    /** Another rig, its parameters overridden by name ({@code FCameraRigAssetReference}). */
    static final CgType RIG_NODE = done(node("rig")
            .property("rig", hard("camera_rig")).required()
            .property("parameters", mapOfValues()));

    static final CgType ATTACH_TO = done(param(param(param(node("attach_to"),
            "target", CgValueType.struct(ATTACHMENT)),
            "location", BOOL).defaultValue(true),
            "rotation", BOOL).defaultValue(false));

    static final CgType ATTACH_TO_GROUP = done(node("attach_to_group")
            .property("targets", CgValueType.listOf(CgValueType.struct(ATTACHMENT))));

    static final CgType BOOM_ARM = done(param(param(param(node("boom_arm"),
            "offset", VEC3).quantity(CgQuantity.OFFSET)
            .property("interpolator", CgValueType.struct(INTERPOLATOR)),
            "max_forward", FLOAT).defaultValue(-1.0),
            "max_backward", FLOAT).defaultValue(-1.0)
            .property("input", STRING)
            .property("additive_rotation", BOOL).defaultValue(false));

    static final CgType OFFSET = done(param(param(node("offset"),
            "translation", VEC3).quantity(CgQuantity.OFFSET),
            "rotation", CgValueType.ROTATION).quantity(CgQuantity.ROTATION)
            .property("space", SPACE).defaultValue("camera_pose"));

    static final CgType SET_LOCATION = done(param(node("set_location"), "location", VEC3).quantity(CgQuantity.POSITION)
            .property("space", SPACE).defaultValue("owning_context"));

    static final CgType SET_ROTATION = done(param(node("set_rotation"), "rotation", CgValueType.ROTATION).quantity(CgQuantity.ROTATION)
            .property("space", SPACE).defaultValue("camera_pose"));

    static final CgType FIELD_OF_VIEW = done(param(node("field_of_view"), "fov", FLOAT).quantity(CgQuantity.ANGLE)
            .range(0.001, 360).uiRange(5, 170));

    /** {@code ULensParametersCameraNode}; {@code focal_length} is in millimetres whatever the asset's units. */
    static final CgType LENS = done(param(param(param(param(node("lens")
            .property("enable_focal_length", BOOL).defaultValue(true).toggleFor("focal_length"),
            "focal_length", FLOAT).defaultValue(35.0)
            .property("enable_aperture", BOOL).defaultValue(true).toggleFor("aperture"),
            "aperture", FLOAT).defaultValue(16.0)
            .property("enable_focus_distance", BOOL).defaultValue(false).toggleFor("focus_distance"),
            "focus_distance", FLOAT).quantity(CgQuantity.LENGTH).defaultValue(1000.0),
            "physical", BOOL).defaultValue(false));

    static final CgType DAMPEN_POSITION = done(param(param(param(node("dampen_position"),
            "forward", FLOAT).defaultValue(0.0),
            "lateral", FLOAT).defaultValue(0.0),
            "vertical", FLOAT).defaultValue(0.0)
            .property("space", SPACE).defaultValue("camera_pose"));

    static final CgType DAMPEN_ROTATION = done(param(param(param(node("dampen_rotation"),
            "yaw", FLOAT).defaultValue(0.0),
            "pitch", FLOAT).defaultValue(0.0),
            "roll", FLOAT).defaultValue(0.0));

    /** Offsets along curves of an input ({@code USplineOffsetCameraNode}): three curves each, x y z or roll pitch yaw. */
    static final CgType SPLINE_OFFSET = done(param(node("spline_offset"), "input", FLOAT)
            .property("translation_offset_spline", CgValueType.listOf(CgValueType.CURVE)).quantity(CgQuantity.OFFSET)
            .property("rotation_offset_spline", CgValueType.listOf(CgValueType.CURVE)).quantity(CgQuantity.ROTATION)
            .property("space", SPACE).defaultValue("camera_pose"));

    static final CgType SPLINE_ORBIT = done(param(node("spline_orbit")
            .property("location_offset_spline", CgValueType.listOf(CgValueType.CURVE)).quantity(CgQuantity.OFFSET)
            .property("target_offset_spline", CgValueType.listOf(CgValueType.CURVE)).quantity(CgQuantity.OFFSET)
            .property("rotation_offset_spline", CgValueType.listOf(CgValueType.CURVE)).quantity(CgQuantity.ROTATION),
            "multiplier", FLOAT).defaultValue(1.0)
            .property("target_offset_space", SPACE).defaultValue("active_context")
            .property("input", STRING));

    /** {@code UBaseFramingCameraNode}'s fields, which dolly and panning framing share. */
    private static CgType.Builder framing(String name) {
        CgType.Builder b = node(name)
                .property("target_location", VALUE)
                .property("targets", CgValueType.listOf(CgValueType.struct(FRAMING_TARGET)));
        b = param(b, "set_target_distance", BOOL).defaultValue(false);
        b = param(b, "initialize_with_ideal_framing", BOOL).defaultValue(true);
        b = param(b, "ideal", CgValueType.VEC2);
        b = param(b, "reframe_damping", FLOAT);
        b = param(b, "low_reframe_damping", FLOAT);
        b = param(b, "reengage", FLOAT).quantity(CgQuantity.TIME);
        b = param(b, "disengage", FLOAT).quantity(CgQuantity.TIME);
        b = param(b, "anticipation", FLOAT).quantity(CgQuantity.TIME);
        b = param(b, "dead_zone", CgValueType.struct(FRAMING_ZONE));
        return param(b, "soft_zone", CgValueType.struct(FRAMING_ZONE));
    }

    static final CgType DOLLY_FRAMING = done(param(param(framing("dolly_framing"),
            "can_move_laterally", BOOL).defaultValue(false),
            "can_move_vertically", BOOL).defaultValue(false));

    static final CgType PANNING_FRAMING = done(param(param(framing("panning_framing"),
            "can_pan_laterally", BOOL).defaultValue(false),
            "can_pan_vertically", BOOL).defaultValue(false));

    static final CgType COLLISION_PUSH = done(param(param(node("collision_push")
            .property("safe_position", CgValueType.enumOf("active_context", "owning_context", "pivot", "subject")).defaultValue("pivot")
            .property("custom_safe_position", VALUE),
            "safe_offset", VEC3).quantity(CgQuantity.OFFSET)
            .property("safe_position_offset_space", CgValueType.enumOf("active_context", "owning_context", "pivot",
                    "camera_pose", "subject")).defaultValue("pivot")
            .property("enable_collision", VALUE),
            "radius", FLOAT).quantity(CgQuantity.LENGTH)
            .property("collision_channel", STRING).defaultValue("camera")
            .property("push", CgValueType.struct(INTERPOLATOR))
            .property("pull", CgValueType.struct(INTERPOLATOR))
            .property("run_async_collision", BOOL).defaultValue(false));

    static final CgType TARGET_RAY_CAST = done(param(node("target_ray_cast")
            .property("trace_channel", STRING).defaultValue("camera"),
            "auto_focus", BOOL).defaultValue(true));

    static final CgType POST_NODE = done(node("post").property("post", CgValueType.struct(POST)));

    static final CgType SHAKE_NODE = done(node("shake")
            .property("shake", hard("shake")).required()
            .property("evaluation_mode", CgValueType.enumOf("inline", "visual_layer")).defaultValue("visual_layer"));

    static final CgType RIG_PARAMETER = CgTypes.struct("crystalgraphics:camera_rig.parameter")
            .property("type", CgValueType.enumOf("float", "int", "bool", "vec2", "vec3", "vec4", "colour", "rotation",
                    "position", "socket")).required().flags(CgPropertyFlag.STRUCTURAL)
            .property("quantity", CgCommonStructs.QUANTITY).defaultValue("none").flags(CgPropertyFlag.STRUCTURAL)
            .property("default", VALUE)
            .register();

    // ── Blends ───────────────────────────────────────────────────────────────────────────

    /** Blends by {@code kind} (Gameplay Cameras' blend nodes, {@code Nodes/Blends/}). */
    public static final CgType BLENDS = CgTypes.dispatch("crystalgraphics:camera_blend", "kind").register();

    private static CgType.Builder blend(String kind) {
        return CgTypes.struct("crystalgraphics:camera_blend." + kind).onRegister(t -> BLENDS.member(kind, t));
    }

    /** A simple blend's time ({@code USimpleBlendCameraNode.BlendTime}). */
    private static CgType.Builder timed(CgType.Builder b) {
        return param(b, "time", FLOAT).quantity(CgQuantity.TIME).defaultValue(1.0);
    }

    static final CgType POP = timed(blend("pop")).register();
    static final CgType LINEAR = timed(blend("linear")).register();
    static final CgType SMOOTH = timed(blend("smooth")
            .property("type", CgValueType.enumOf("smooth_step", "smoother_step")).defaultValue("smooth_step")).register();
    static final CgType EASING = timed(blend("easing")
            .property("type", CgValueType.enumOf("linear", "sin_in", "sin_out", "sin_in_out", "quad_in", "quad_out",
                    "quad_in_out", "cubic", "cubic_in", "cubic_out", "cubic_in_out", "hermite_cubic_in_out", "quart_in",
                    "quart_out", "quart_in_out", "quint_in", "quint_out", "quint_in_out", "expo_in", "expo_out",
                    "expo_in_out", "circ_in", "circ_out", "circ_in_out")).defaultValue("linear")).register();
    /** Orbits round the pivot, driven by another blend's progress. */
    static final CgType ORBIT = blend("orbit")
            .property("driving_blend", CgValueType.dispatch(BLENDS)).register();
    /** Location, rotation and the rest each by its own blend. */
    static final CgType LOCATION_ROTATION = blend("location_rotation")
            .property("location_blend", CgValueType.dispatch(BLENDS))
            .property("rotation_blend", CgValueType.dispatch(BLENDS))
            .property("other_blend", CgValueType.dispatch(BLENDS))
            .register();

    /** A transition's condition by {@code kind}: the rig tags before or after, which rig, the same rig. */
    static final CgType TRANSITION_CONDITION = CgTypes.struct("crystalgraphics:camera_rig.transition_condition").open()
            .property("kind", CgValueType.enumOf("previous_rig_tags", "next_rig_tags", "is_camera_rig", "is_same_rig")).required()
            .register();

    static final CgType TRANSITION = CgTypes.struct("crystalgraphics:camera_rig.transition")
            .property("conditions", CgValueType.listOf(CgValueType.struct(TRANSITION_CONDITION)))
            .property("blend", CgValueType.dispatch(BLENDS))
            .property("to", STRING)
            .register();

    /** {@code cameras/rigs/}: a tree of camera nodes and its parameters ({@code UCameraRigAsset}). */
    public static final CgType CAMERA_RIG = CgTypes.kind("crystalgraphics:camera_rig").folder("cameras/rigs").version(1)
            .property("tags", CgValueType.listOf(STRING))
            .property("initial_orientation", CgValueType.enumOf("none", "context_yaw_pitch", "previous_yaw_pitch",
                    "previous_absolute_target", "previous_relative_target"))
            .property("parameters", CgValueType.mapOf(CgValueType.struct(RIG_PARAMETER))).flags(CgPropertyFlag.STRUCTURAL)
            .property("root", CgValueType.idList(NODES)).required().flags(CgPropertyFlag.STRUCTURAL)
            .property("enter_transitions", CgValueType.listOf(CgValueType.struct(TRANSITION)))
            .property("exit_transitions", CgValueType.listOf(CgValueType.struct(TRANSITION)))
            .property("requires", CgValueType.listOf(STRING))
            .property("on_missing", CgValueType.enumOf("refuse", "drop_roll", "offset_only"))
            .register();

    // ── Shake ────────────────────────────────────────────────────────────────────────────

    /** Shake patterns by {@code kind}; a mod registers its own. */
    public static final CgType PATTERNS = CgTypes.dispatch("crystalgraphics:shake_pattern", "kind").register();

    private static CgType.Builder pattern(String kind) {
        return CgTypes.struct("crystalgraphics:shake_pattern." + kind).open().onRegister(t -> PATTERNS.member(kind, t));
    }

    private static CgType axis(String id, CgQuantity amplitude) {
        return CgTypes.struct("crystalgraphics:shake_pattern." + id)
                .property("amplitude", FLOAT).quantity(amplitude)
                .property("frequency", FLOAT).quantity(CgQuantity.FREQUENCY)
                .property("initial_offset", CgValueType.enumOf("random", "zero"))
                .register();
    }

    /** One axis of a location oscillation: its amplitude a length. */
    static final CgType LOCATION_AXIS = axis("location_axis", CgQuantity.LENGTH);
    /** One axis of a rotation or field-of-view oscillation: its amplitude an angle. */
    static final CgType ANGLE_AXIS = axis("angle_axis", CgQuantity.ANGLE);

    static final CgType LOCATION = CgTypes.struct("crystalgraphics:shake_pattern.location")
            .property("amplitude_multiplier", FLOAT).defaultValue(1.0)
            .property("frequency_multiplier", FLOAT).defaultValue(1.0)
            .property("x", CgValueType.struct(LOCATION_AXIS))
            .property("y", CgValueType.struct(LOCATION_AXIS))
            .property("z", CgValueType.struct(LOCATION_AXIS))
            .register();

    static final CgType ROTATION = CgTypes.struct("crystalgraphics:shake_pattern.rotation")
            .property("amplitude_multiplier", FLOAT).defaultValue(1.0)
            .property("frequency_multiplier", FLOAT).defaultValue(1.0)
            .property("pitch", CgValueType.struct(ANGLE_AXIS))
            .property("yaw", CgValueType.struct(ANGLE_AXIS))
            .property("roll", CgValueType.struct(ANGLE_AXIS))
            .register();

    private static CgType.Builder oscillation(CgType.Builder b) {
        return b.property("duration", CgValueType.oneOf(FLOAT, CgValueType.enumOf("infinite"))).quantity(CgQuantity.TIME)
                .property("blend_in", FLOAT).quantity(CgQuantity.TIME)
                .property("blend_out", FLOAT).quantity(CgQuantity.TIME)
                .property("location", CgValueType.struct(LOCATION))
                .property("rotation", CgValueType.struct(ROTATION))
                .property("fov", CgValueType.struct(ANGLE_AXIS));
    }

    /** A trauma impulse: how much, over how long. */
    static final CgType IMPULSE = CgTypes.struct("crystalgraphics:shake_pattern.impulse")
            .property("amount", FLOAT)
            .property("seconds", FLOAT).quantity(CgQuantity.TIME)
            .register();

    /** How a trauma shake arrives from its source: within reach, after a delay, eased. */
    static final CgType ARRIVES = CgTypes.struct("crystalgraphics:shake_pattern.arrives")
            .property("reach", FLOAT).quantity(CgQuantity.LENGTH)
            .property("seconds", FLOAT).quantity(CgQuantity.TIME)
            .property("easing", STRING)
            .register();

    static final CgType PERLIN = oscillation(pattern("perlin")).register();
    static final CgType WAVE = oscillation(pattern("wave")).register();
    static final CgType COMPOSITE = pattern("composite").property("children", CgValueType.listOf(CgValueType.dispatch(PATTERNS))).register();
    static final CgType SEQUENCE_PATTERN = pattern("sequence")
            .property("sequence", hard("sequence"))
            .property("play_rate", FLOAT).defaultValue(1.0)
            .property("scale", FLOAT).defaultValue(1.0)
            .property("blend_in", FLOAT).quantity(CgQuantity.TIME)
            .property("blend_out", FLOAT).quantity(CgQuantity.TIME)
            .property("random_segment", BOOL)
            .register();
    static final CgType TRAUMA = pattern("trauma")
            .property("trauma", FLOAT)
            .property("punch", FLOAT)
            .property("tremor", CgValueType.struct(IMPULSE))
            .property("kick", CgValueType.struct(IMPULSE))
            .property("arrives", CgValueType.struct(ARRIVES))
            .register();

    static final CgType PLAY = CgTypes.struct("crystalgraphics:shake.play")
            .property("inner", FLOAT).quantity(CgQuantity.LENGTH)
            .property("outer", FLOAT).quantity(CgQuantity.LENGTH)
            .property("falloff", FLOAT)
            .property("space", CgValueType.enumOf("camera_local", "world", "user_defined"))
            .register();

    /** {@code cameras/shakes/}: a shake pattern ({@code UCameraShakeBase}). */
    public static final CgType SHAKE = CgTypes.kind("crystalgraphics:shake").folder("cameras/shakes").version(1)
            .property("single_instance", BOOL).defaultValue(false)
            .property("scale", FLOAT).defaultValue(1.0)
            .property("pattern", CgValueType.dispatch(PATTERNS)).required().flags(CgPropertyFlag.STRUCTURAL)
            .property("play", CgValueType.struct(PLAY))
            .register();

    static void register() {
    }
}
