package com.crystalgraphics.asset.kind;

import com.crystalgraphics.object.CgPropertyFlag;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.CgTypes;
import com.crystalgraphics.object.CgValueType;
import com.crystalgraphics.object.value.CgQuantity;

import static com.crystalgraphics.asset.kind.CgCommonStructs.BINDING;
import static com.crystalgraphics.asset.kind.CgCommonStructs.hard;
import static com.crystalgraphics.asset.kind.CgCommonStructs.mapOfValues;
import static com.crystalgraphics.object.CgValueType.BOOL;
import static com.crystalgraphics.object.CgValueType.CURVE;
import static com.crystalgraphics.object.CgValueType.FLOAT;
import static com.crystalgraphics.object.CgValueType.INT;
import static com.crystalgraphics.object.CgValueType.STRING;
import static com.crystalgraphics.object.CgValueType.TREE;
import static com.crystalgraphics.object.CgValueType.VALUE;
import static com.crystalgraphics.object.CgValueType.VEC3;

/**
 * The {@code sequence} kind ({@code spec/sequences.md}): Unreal's {@code ULevelSequence} with an anim montage's
 * sections and notifies: roles, locators, spawnables, tracks of 23 kinds, their sections, splines.
 *
 * <pre>{@code
 * CgSequenceKinds.TRACKS.member("parameter");                 // a parameter track: envelope, then its sections
 * CgSequenceKinds.SEQUENCE.property("tick_resolution");       // required: every key is on these ticks
 * }</pre>
 *
 * <ul>
 *   <li>A channel is a curve on the sequence's ticks ({@code sequences.md} § Channels); held channels (bool, event,
 *       particle, object, actor) are typed when the sequence compiles, not here.</li>
 *   <li>A track's envelope keys are the schema's for every kind; each kind's section holds the common section keys,
 *       then its own.</li>
 * </ul>
 */
public final class CgSequenceKinds {

    private CgSequenceKinds() {
    }

    // ── Roles, locators, spawnables ──────────────────────────────────────────────────────

    static final CgType ROLE = CgTypes.element("crystalgraphics:sequence.role")
            .property("name", STRING).required().flags(CgPropertyFlag.LOCKED, CgPropertyFlag.HEADLESS)
            .property("kind", CgValueType.enumOf("caster", "target", "character")).required()
            .property("character", STRING)
            .property("seed", BOOL)
            .property("absent", CgValueType.enumOf("skip", "fail"))
            .property("editor", TREE).flags(CgPropertyFlag.EDITOR_ONLY)
            .register();

    static final CgType LOCATOR = CgTypes.element("crystalgraphics:sequence.locator")
            .property("name", STRING)
            .property("parent", BINDING)
            .property("location", VEC3).quantity(CgQuantity.POSITION)
            .property("rotation", CgValueType.ROTATION).quantity(CgQuantity.ROTATION)
            .property("scale", VEC3).quantity(CgQuantity.SCALE)
            .property("editor", TREE).flags(CgPropertyFlag.EDITOR_ONLY)
            .register();

    static final CgType SPLINE_LAYER = CgTypes.element("crystalgraphics:sequence.spline_mesh.layer")
            .property("top", VALUE)
            .property("loop", VALUE)
            .property("bottom", VALUE)
            .property("loop_only", VALUE)
            .property("material", hard("material"))
            .property("scale", VALUE)
            .property("editor", TREE).flags(CgPropertyFlag.EDITOR_ONLY)
            .register();

    static final CgType SPLINE_PATH = CgTypes.struct("crystalgraphics:sequence.spline_mesh.path")
            .property("follow", VALUE)
            .property("interval", FLOAT).quantity(CgQuantity.TIME)
            .property("max_points", INT)
            .register();

    /** A system placed along a spline mesh's path, every so often. */
    static final CgType SPLINE_ALONG = CgTypes.element("crystalgraphics:sequence.spline_mesh.along")
            .property("system", hard("system")).required()
            .property("every", VALUE)
            .property("condition", CgValueType.struct(CgCommonStructs.CONDITION))
            .register();

    /** A system placed at a spline mesh's end. */
    static final CgType SPLINE_AT_END = CgTypes.element("crystalgraphics:sequence.spline_mesh.at_end")
            .property("system", hard("system")).required()
            .register();

    static final CgType SPLINE_MESH = CgTypes.struct("crystalgraphics:sequence.spline_mesh")
            .property("path", CgValueType.struct(SPLINE_PATH)).required()
            .property("loop_length", FLOAT).quantity(CgQuantity.LENGTH)
            .property("top_offset", FLOAT).quantity(CgQuantity.LENGTH)
            .property("bottom_offset", FLOAT).quantity(CgQuantity.LENGTH)
            .property("layers", CgValueType.idList(SPLINE_LAYER)).required()
            .property("along", CgValueType.idList(SPLINE_ALONG))
            .property("at_end", CgValueType.idList(SPLINE_AT_END))
            .register();

    static final CgType CAPTURE = CgTypes.struct("crystalgraphics:sequence.capture")
            .property("target", CgValueType.binaryRef("textures")).required()
            .property("fov", FLOAT).quantity(CgQuantity.ANGLE)
            .property("every", INT)
            .property("show", CgValueType.listOf(BINDING))
            .property("hide", CgValueType.listOf(BINDING))
            .register();

    /** A prefab written inline in its spawnable ({@code bodies.md}). */
    static final CgType INLINE_PREFAB = CgTypes.struct("crystalgraphics:sequence.inline_prefab")
            .property("nodes", CgValueType.idList(CgBodyKinds.NODE)).required()
            .register();

    /** A node's rest for this sequence ({@code sequences.md} § Rests): a constant is a rest, never a track. */
    static final CgType REST = CgTypes.struct("crystalgraphics:sequence.rest")
            .property("opacity", FLOAT)
            .property("values", mapOfValues())
            .register();

    static final CgType PUPPET = CgTypes.struct("crystalgraphics:sequence.puppet")
            .property("model", CgValueType.binaryRef("models")).flags(CgPropertyFlag.STRUCTURAL)
            .property("animation", VALUE)
            .property("meshes", CgValueType.listOf(CgValueType.struct(CgCommonStructs.MESH_SLOT)))
            .register();

    /** A role's model frozen in its pose, once or every so many ticks (afterimages). */
    static final CgType POSE_COPY = CgTypes.struct("crystalgraphics:sequence.pose_copy")
            .property("role", STRING).required()
            .property("material", hard("material"))
            .property("every", INT)
            .property("lifetime", INT)
            .property("fade", INT)
            .register();

    /** {@code ACineCameraActor}'s settings. */
    static final CgType CINE = CgTypes.struct("crystalgraphics:sequence.cine")
            .property("fov", FLOAT).quantity(CgQuantity.ANGLE)
            .property("focal_length", FLOAT)
            .property("sensor", CgValueType.VEC2)
            .property("focus", FLOAT).quantity(CgQuantity.LENGTH)
            .property("clip", CgValueType.VEC2).quantity(CgQuantity.LENGTH)
            .register();

    static final CgType CAMERA = CgTypes.struct("crystalgraphics:sequence.camera")
            .property("cine", CgValueType.struct(CINE))
            .property("rig", hard("camera_rig"))
            .register();

    static final CgType LIGHT = CgTypes.struct("crystalgraphics:sequence.light")
            .property("type", CgValueType.enumOf("point", "spot", "directional")).required()
            .property("colour", CgValueType.COLOUR)
            .property("intensity", FLOAT)
            .property("radius", FLOAT).quantity(CgQuantity.LENGTH)
            .property("cone", FLOAT).quantity(CgQuantity.ANGLE)
            .property("cast_shadows", BOOL)
            .property("shadow_colour", CgValueType.COLOUR)
            .register();

    static final CgType DECAL = CgTypes.struct("crystalgraphics:sequence.decal")
            .property("material", hard("material")).required()
            .property("size", VEC3).quantity(CgQuantity.LENGTH)
            .property("fade", VALUE)
            .register();

    static final CgType TEMPERATURE = CgTypes.struct("crystalgraphics:sequence.colour_region.temperature")
            .property("type", CgValueType.enumOf("legacy", "white_balance", "colour_temperature"))
            .property("value", FLOAT)
            .property("tint", FLOAT)
            .register();

    /** Which roles a colour region grades: all but these, or only these. */
    static final CgType AFFECTED = CgTypes.struct("crystalgraphics:sequence.colour_region.affected")
            .property("mode", CgValueType.enumOf("exclude", "include"))
            .property("roles", CgValueType.listOf(STRING))
            .register();

    static final CgType COLOUR_REGION = CgTypes.struct("crystalgraphics:sequence.colour_region")
            .property("shape", CgValueType.enumOf("sphere", "box", "cylinder", "cone"))
            .property("priority", INT)
            .property("intensity", FLOAT)
            .property("inner", FLOAT).quantity(CgQuantity.LENGTH)
            .property("outer", FLOAT).quantity(CgQuantity.LENGTH)
            .property("falloff", FLOAT)
            .property("invert", BOOL)
            .property("temperature", CgValueType.struct(TEMPERATURE))
            .property("colour", CgValueType.struct(CgCameraKinds.GRADE))
            .property("shadows", CgValueType.struct(CgCameraKinds.GRADE))
            .property("midtones", CgValueType.struct(CgCameraKinds.GRADE))
            .property("highlights", CgValueType.struct(CgCameraKinds.GRADE))
            .property("affected", CgValueType.struct(AFFECTED))
            .property("enabled", BOOL)
            .register();

    /**
     * A spawnable: an object the sequence makes and owns, its kind the one kind key present ({@code system},
     * {@code prefab}, {@code puppet}…; {@code sequences.md} § Spawnables). Exactly one kind key.
     */
    static final CgType SPAWNABLE = CgTypes.element("crystalgraphics:sequence.spawnable")
            .property("name", STRING).flags(CgPropertyFlag.LOCKED)
            .property("at", CgValueType.struct(CgCommonStructs.PLACE))
            .property("nodes", CgValueType.mapOf(CgValueType.struct(REST)))
            .property("bind", mapOfValues())
            .property("editor", TREE).flags(CgPropertyFlag.EDITOR_ONLY)
            .property("system", hard("system")).flags(CgPropertyFlag.STRUCTURAL)
            .property("prefab", CgValueType.oneOf(hard("prefab"), CgValueType.struct(INLINE_PREFAB))).flags(CgPropertyFlag.STRUCTURAL)
            .property("puppet", CgValueType.struct(PUPPET)).flags(CgPropertyFlag.STRUCTURAL)
            .property("pose_copy", CgValueType.struct(POSE_COPY)).flags(CgPropertyFlag.STRUCTURAL)
            .property("camera", CgValueType.struct(CAMERA)).flags(CgPropertyFlag.STRUCTURAL)
            .property("light", CgValueType.struct(LIGHT)).flags(CgPropertyFlag.STRUCTURAL)
            .property("post", CgValueType.struct(CgCameraKinds.POST)).flags(CgPropertyFlag.STRUCTURAL)
            .property("priority", FLOAT)
            .property("unbound", BOOL)
            .property("radius", FLOAT).quantity(CgQuantity.LENGTH)
            .property("blend_radius", FLOAT).quantity(CgQuantity.LENGTH)
            .property("field", CgValueType.oneOf(hard("field"), CgValueType.struct(CgFieldKinds.INLINE_GRAPH))).flags(CgPropertyFlag.STRUCTURAL)
            .property("decal", CgValueType.struct(DECAL)).flags(CgPropertyFlag.STRUCTURAL)
            .property("spline_mesh", CgValueType.struct(SPLINE_MESH)).flags(CgPropertyFlag.STRUCTURAL)
            .property("capture", CgValueType.struct(CAPTURE)).flags(CgPropertyFlag.STRUCTURAL)
            .property("colour_region", CgValueType.struct(COLOUR_REGION)).flags(CgPropertyFlag.STRUCTURAL)
            .register();

    static final CgType COMPOSITE_SECTION = CgTypes.element("crystalgraphics:sequence.composite_section")
            .property("name", STRING).flags(CgPropertyFlag.LOCKED, CgPropertyFlag.HEADLESS)
            .property("range", CgValueType.listOf(INT))
            .property("next", STRING)
            .register();

    static final CgType MARKED_FRAME = CgTypes.struct("crystalgraphics:sequence.marked_frame")
            .property("at", INT).required()
            .property("label", STRING)
            .register();

    static final CgType SPLINE = CgTypes.element("crystalgraphics:sequence.spline")
            .property("name", VALUE)
            .property("parent", BINDING)
            .property("points", CgValueType.listOf(VEC3)).required().quantity(CgQuantity.POSITION)
            .property("closed", BOOL).defaultValue(false)
            .property("editor", TREE).flags(CgPropertyFlag.EDITOR_ONLY)
            .register();

    // ── Tracks and sections ──────────────────────────────────────────────────────────────

    /** Tracks by {@code kind} ({@code sequences.md} § Kinds); each member carries the envelope and its sections. */
    public static final CgType TRACKS = CgTypes.dispatch("crystalgraphics:track", "kind").register();

    private static final CgValueType BLEND = CgValueType.enumOf("absolute", "additive", "relative", "additive_from_base", "override");

    /** A triggered section's start: at an event rather than a tick. */
    static final CgType START = CgTypes.struct("crystalgraphics:track.section.start")
            .property("event", STRING).required()
            .register();

    /** A section's ease in and out, in ticks, or {@code { auto: true }}. */
    static final CgType EASE = CgTypes.struct("crystalgraphics:track.section.ease")
            .property("in", INT)
            .property("out", INT)
            .property("in_fn", STRING)
            .property("out_fn", STRING)
            .property("auto", BOOL)
            .register();

    private static final CgValueType ATTACH_RULE = CgValueType.enumOf("keep_relative", "keep_world", "snap_to_target");

    static final CgType ATTACH_RULES = CgTypes.struct("crystalgraphics:track.attach.rules")
            .property("location", ATTACH_RULE)
            .property("rotation", ATTACH_RULE)
            .property("scale", ATTACH_RULE)
            .register();

    static final CgType MATERIAL_SLOT = CgTypes.struct("crystalgraphics:track.material.slot")
            .property("name", STRING)
            .property("index", INT)
            .property("type", CgValueType.enumOf("indexed", "overlay"))
            .register();

    static final CgType PATH_SPLINE = CgTypes.struct("crystalgraphics:track.path.path")
            .property("spline", STRING).required()
            .register();

    /** A camera cut's camera in a subsequence. */
    static final CgType CUT_CAMERA = CgTypes.struct("crystalgraphics:track.camera_cut.camera")
            .property("sequence", STRING)
            .property("camera", STRING).required()
            .register();

    /** A split screen (ours). */
    static final CgType PANELS = CgTypes.struct("crystalgraphics:track.camera_cut.panels")
            .property("direction", VALUE)
            .property("split", VALUE)
            .property("cameras", CgValueType.listOf(STRING))
            .register();

    static final CgType NOTIFY_FILTER = CgTypes.struct("crystalgraphics:track.notify.filter")
            .property("significance", CgCommonStructs.TIER)
            .register();

    /**
     * The keys every section holds, before its kind's ({@code sequences.md} § Tracks). Open: channels named by data
     * ({@code x}, a material parameter, a morph target) follow the declared keys.
     */
    private static CgType.Builder section(String kind) {
        return CgTypes.element("crystalgraphics:track." + kind + ".section").open()
                .property("range", CgValueType.listOf(INT)).flags(CgPropertyFlag.HEADLESS)
                .property("start", CgValueType.struct(START)).flags(CgPropertyFlag.HEADLESS)
                .property("condition", CgValueType.struct(CgCommonStructs.CONDITION))
                .property("row", INT).defaultValue(0)
                .property("overlap_priority", INT).defaultValue(0)
                .property("blend", BLEND)
                .property("ease", CgValueType.struct(EASE))
                .property("completion", CgValueType.enumOf("keep_state", "restore_state"))
                .property("pre_roll", INT).defaultValue(0)
                .property("post_roll", INT).defaultValue(0)
                .property("active", BOOL).defaultValue(true)
                .property("weight", CURVE);
    }

    /** A track of {@code kind}: the envelope, then {@code sections} of {@code sectionType}. */
    private static CgType track(String kind, CgType sectionType) {
        return CgTypes.element("crystalgraphics:track." + kind)
                .property("binding", BINDING)
                .property("muted", BOOL).defaultValue(false)
                .property("handle", STRING)
                .property("side", CgValueType.enumOf("rpg", "ours"))
                .property("parameter", STRING)
                .property("property", STRING)
                .property("collection", hard("parameters"))
                .property("can_blend", BOOL)
                .property("fire_forwards", BOOL).defaultValue(true)
                .property("fire_backwards", BOOL).defaultValue(false)
                .property("unsupported", TREE)
                .property("editor", TREE).flags(CgPropertyFlag.EDITOR_ONLY)
                .property("sections", CgValueType.idList(sectionType)).required()
                .onRegister(t -> TRACKS.member(kind, t))
                .register();
    }

    static final CgType TRANSFORM = track("transform", section("transform")
            .property("translation", CgValueType.listOf(CURVE)).quantity(CgQuantity.POSITION).flags(CgPropertyFlag.KEYABLE)
            .property("rotation", CgValueType.listOf(CURVE)).quantity(CgQuantity.ROTATION).flags(CgPropertyFlag.KEYABLE)
            .property("scale", CgValueType.listOf(CURVE)).quantity(CgQuantity.SCALE).flags(CgPropertyFlag.KEYABLE)
            .property("mask", VALUE)
            .property("quaternion", BOOL)
            .register());

    static final CgType ATTACH = track("attach", section("attach")
            .property("to", BINDING)
            .property("attach", CgValueType.struct(ATTACH_RULES))
            .property("detach", CgValueType.struct(ATTACH_RULES))
            .property("full_revert_on_detach", BOOL)
            .register());

    static final CgType PATH = track("path", section("path")
            .property("path", CgValueType.struct(PATH_SPLINE))
            .property("timing", CURVE)
            .property("follow_rotation", BOOL)
            .property("reverse", BOOL)
            .property("front_axis", CgValueType.enumOf("x", "y", "z", "-x", "-y", "-z"))
            .property("up_axis", CgValueType.enumOf("x", "y", "z", "-x", "-y", "-z"))
            .register());

    static final CgType SPAWNED = track("spawned", section("spawned")
            .property("spawned", CURVE).flags(CgPropertyFlag.HEADLESS)
            .register());

    static final CgType SYSTEM = track("system", section("system")
            .property("keys", CURVE)
            .property("evaluate", CgValueType.enumOf("activate_if_inactive", "none"))
            .property("end", CgValueType.enumOf("set_inactive", "deactivate", "none"))
            .property("age", CgValueType.enumOf("tick_delta", "desired_age", "desired_age_no_seek"))
            .property("allow_scalability", BOOL)
            .register());

    /** Channels by the parameter's type, named by component. */
    static final CgType PARAMETER = track("parameter", section("parameter").register());

    static final CgType MATERIAL = track("material", section("material")
            .property("slot", CgValueType.struct(MATERIAL_SLOT))
            .property("scalars", CgValueType.mapOf(CURVE))
            .property("vectors", CgValueType.mapOf(VALUE))
            .property("colours", CgValueType.mapOf(VALUE))
            .property("bools", CgValueType.mapOf(CURVE))
            .register());

    /** Channels by parameter name. */
    static final CgType GLOBAL = track("global", section("global").register());

    /** One channel of the property's type, named by component ({@code x}, {@code y}, {@code z}). */
    static final CgType PROPERTY = track("property", section("property").register());

    static final CgType VISIBILITY = track("visibility", section("visibility")
            .property("visible", CURVE)
            .register());

    static final CgType WORLD_VISIBILITY = track("world_visibility", section("world_visibility")
            .property("visible", CURVE)
            .register());

    static final CgType STENCIL = track("stencil", section("stencil")
            .property("value", CURVE)
            .register());

    static final CgType ANIMATION = track("animation", section("animation")
            .property("clip", VALUE).flags(CgPropertyFlag.STRUCTURAL)
            .property("start_offset", INT)
            .property("play_rate", FLOAT).defaultValue(1.0)
            .property("loop", BOOL)
            .property("slot", STRING)
            .property("interpolation", CgValueType.enumOf("smooth", "step")).defaultValue("smooth")
            .register());

    /** A {@code float} channel per morph target, by its name. */
    static final CgType MORPH = track("morph", section("morph").register());

    static final CgType CAMERA_CUT = track("camera_cut", section("camera_cut")
            .property("camera", CgValueType.oneOf(CgValueType.struct(CUT_CAMERA), STRING))
            .property("lock_previous", BOOL)
            .property("panels", CgValueType.struct(PANELS))
            .register());

    static final CgType CAMERA_ANIMATION = track("camera_animation", section("camera_animation")
            .property("animation", hard("sequence"))
            .property("play_rate", FLOAT).defaultValue(1.0)
            .property("play_scale", FLOAT).defaultValue(1.0)
            .property("blend_in", FLOAT).quantity(CgQuantity.TIME)
            .property("blend_out", FLOAT).quantity(CgQuantity.TIME)
            .property("loop", BOOL)
            .register());

    static final CgType SHAKE = track("shake", section("shake")
            .property("shake", hard("shake"))
            .property("scale", FLOAT).defaultValue(1.0)
            .property("space", CgValueType.enumOf("camera_local", "world", "user_defined")).defaultValue("camera_local")
            .property("space_rotation", CgValueType.ROTATION).quantity(CgQuantity.ROTATION)
            .register());

    static final CgType FADE = track("fade", section("fade")
            .property("amount", CURVE)
            .property("colour", CgValueType.COLOUR)
            .property("fade_audio", BOOL)
            .register());

    static final CgType SOUND = track("sound", section("sound")
            .property("sound", VALUE)
            .property("start_offset", INT)
            .property("volume", CURVE)
            .property("pitch", CURVE)
            .property("looping", BOOL)
            .property("play_until_finished", BOOL)
            .property("attenuation", VALUE)
            .register());

    static final CgType EVENT = track("event", section("event")
            .property("keys", CURVE).flags(CgPropertyFlag.HEADLESS)
            .property("event", STRING).flags(CgPropertyFlag.HEADLESS)
            .property("args", TREE).flags(CgPropertyFlag.HEADLESS)
            .register());

    static final CgType SUBSEQUENCE = track("subsequence", section("subsequence")
            .property("sequence", hard("sequence")).flags(CgPropertyFlag.HEADLESS, CgPropertyFlag.STRUCTURAL)
            .property("start_offset", INT)
            .property("end_offset", INT)
            .property("first_loop_start_offset", INT)
            .property("loop", BOOL)
            .property("time_scale", VALUE)
            .property("bias", INT)
            .property("state", CgValueType.enumOf("keep", "restore", "inherit"))
            .property("origin", TREE)
            .property("roles", CgValueType.mapOf(STRING))
            .register());

    static final CgType TIME_DILATION = track("time_dilation", section("time_dilation")
            .property("rate", CURVE).flags(CgPropertyFlag.HEADLESS)
            .register());

    static final CgType NOTIFY = track("notify", section("notify")
            .property("at", INT)
            .property("kind", STRING).required()
            .property("properties", TREE)
            .property("chance", FLOAT).defaultValue(1.0).range(0, 1)
            .property("weight_threshold", FLOAT)
            .property("filter", CgValueType.struct(NOTIFY_FILTER))
            .property("branching_point", BOOL)
            .register());

    // ── The kind ─────────────────────────────────────────────────────────────────────────

    /** {@code sequences/} ({@code ULevelSequence}). */
    public static final CgType SEQUENCE = CgTypes.kind("crystalgraphics:sequence").folder("sequences").version(1)
            .property("tick_resolution", FLOAT).required().flags(CgPropertyFlag.HEADLESS, CgPropertyFlag.STRUCTURAL)
            .property("display_rate", FLOAT).required()
            .property("range", CgValueType.listOf(INT)).flags(CgPropertyFlag.HEADLESS)
            .property("evaluation", CgValueType.enumOf("sub_frames", "frame_locked"))
            .property("marked_frames", CgValueType.listOf(CgValueType.struct(MARKED_FRAME)))
            .property("composite_sections", CgValueType.idList(COMPOSITE_SECTION)).flags(CgPropertyFlag.HEADLESS)
            .property("roles", CgValueType.idList(ROLE)).flags(CgPropertyFlag.HEADLESS, CgPropertyFlag.STRUCTURAL)
            .property("locators", CgValueType.idList(LOCATOR))
            .property("spawnables", CgValueType.idList(SPAWNABLE)).flags(CgPropertyFlag.STRUCTURAL)
            .property("tracks", CgValueType.idList(TRACKS)).flags(CgPropertyFlag.STRUCTURAL)
            .property("splines", CgValueType.idList(SPLINE))
            .register();

    static void register() {
    }
}
