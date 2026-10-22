package com.crystalgraphics.asset.kind;

import java.util.Arrays;
import java.util.Map;

import com.crystalgraphics.object.CgProperty;
import com.crystalgraphics.object.CgPropertyFlag;
import com.crystalgraphics.object.CgPropertyPath;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.CgTypes;
import com.crystalgraphics.object.CgValueType;
import com.crystalgraphics.object.validate.CgIssue;
import com.crystalgraphics.object.validate.CgValidation;
import com.crystalgraphics.object.value.CgQuantity;
import com.crystalgraphics.object.value.CgValueForm;

import static com.crystalgraphics.asset.kind.CgCommonStructs.TIER;
import static com.crystalgraphics.asset.kind.CgCommonStructs.TIERS;
import static com.crystalgraphics.asset.kind.CgCommonStructs.hard;
import static com.crystalgraphics.asset.kind.CgCommonStructs.mapOfValues;
import static com.crystalgraphics.object.CgValueType.BOOL;
import static com.crystalgraphics.object.CgValueType.FLOAT;
import static com.crystalgraphics.object.CgValueType.INT;
import static com.crystalgraphics.object.CgValueType.STRING;
import static com.crystalgraphics.object.CgValueType.TREE;
import static com.crystalgraphics.object.CgValueType.VALUE;
import static com.crystalgraphics.object.CgValueType.VEC2;
import static com.crystalgraphics.object.CgValueType.VEC3;
import static com.crystalgraphics.object.CgValueType.VEC4;

/**
 * The particle kinds: {@code system}, {@code emitter},
 * {@code module}, {@code effect_type} and {@code ending}, with their elements and renderers. Niagara's
 * {@code UNiagaraSystem}, {@code UNiagaraEmitter}, module scripts and {@code UNiagaraEffectType}.
 *
 * <pre>{@code
 * CgParticleKinds.SYSTEM.property("emitters");          // id-list<crystalgraphics:system.emitter>
 * CgParticleKinds.RENDERERS.member("sprite");           // the sprite renderer's fields
 * }</pre>
 *
 * <p>Registered by {@link CgAssetKinds#register()}; read them through it.</p>
 */
public final class CgParticleKinds {

    private CgParticleKinds() {
    }

    /** A module entry, Niagara's {@code MergeId} its id: {@code { id, module, enabled, inputs }}. */
    static final CgType MODULE_ENTRY = CgTypes.element("crystalgraphics:emitter.module")
            .property("module", hard("module")).flags(CgPropertyFlag.STRUCTURAL)
            .property("enabled", BOOL).defaultValue(true).flags(CgPropertyFlag.STRUCTURAL)
            .property("inputs", mapOfValues())
            .property("after", CgValueType.LOCAL_ID)
            .property("before", CgValueType.LOCAL_ID)
            .validator(CgParticleKinds::builtInModule)
            .register();

    static final CgType DETERMINISM = CgTypes.struct("crystalgraphics:determinism")
            .property("seed", INT)
            .register();

    static final CgType FIXED_BOUNDS = CgTypes.struct("crystalgraphics:fixed_bounds")
            .property("fixed", CgValueType.listOf(VEC3)).quantity(CgQuantity.POSITION)
            .register();

    /** {@code FNiagaraEmitterScalabilitySettings}. */
    static final CgType EMITTER_SCALABILITY = CgTypes.struct("crystalgraphics:effect_type.emitter")
            .property("tiers", TIERS)
            .property("scale_spawn_count", BOOL).defaultValue(false).toggleFor("spawn_count_scale")
            .property("spawn_count_scale", FLOAT).defaultValue(1.0)
            .register();

    static final CgType EMITTER_PROPERTIES = CgTypes.struct("crystalgraphics:emitter.properties")
            .property("capacity", INT).flags(CgPropertyFlag.STRUCTURAL).range(0, null)
            .property("local_space", BOOL).defaultValue(false).flags(CgPropertyFlag.STRUCTURAL)
            .property("simulation", CgValueType.enumOf("gpu", "analytic")).defaultValue("gpu").flags(CgPropertyFlag.STRUCTURAL)
            .property("bounds", CgValueType.oneOf(CgValueType.enumOf("dynamic"), CgValueType.struct(FIXED_BOUNDS))).defaultValue("dynamic")
            .property("interpolated_spawn", BOOL)
            .property("determinism", CgValueType.struct(DETERMINISM))
            .property("quality", CgValueType.listOf(TIER)).flags(CgPropertyFlag.STRUCTURAL)
            // UNiagaraEmitter::ScalabilityOverrides: an entry whose tiers hold replaces the effect type's; the last wins
            .property("scalability", CgValueType.listOf(CgValueType.struct(EMITTER_SCALABILITY)))
            .register();

    static final CgType SPAWN = CgTypes.struct("crystalgraphics:emitter.event.spawn")
            .property("emitter", STRING).description("The handle spawned at each event's place.")
            .property("count", FLOAT)   // Cascade's EventReceiverSpawn: a float distribution
            .property("inherit_velocity", VEC3)
            .property("use_emitter_location", BOOL)
            .property("use_particle_time", BOOL)
            .register();

    /** An event handler ({@code FNiagaraEventScriptProperties}). */
    static final CgType EVENT = CgTypes.element("crystalgraphics:emitter.event")
            .property("trigger", CgValueType.enumOf("spawn", "death", "collision", "location", "burst")).required()
            .property("source", CgValueType.LOCAL_ID)
            .property("name", STRING)
            .property("spawn", CgValueType.struct(SPAWN))
            .property("kill", BOOL)
            .property("max_per_frame", INT).forms(CgValueForm.CONSTANT)
            .register();

    // ── Renderers ────────────────────────────────────────────────────────────────────────

    /** Renderers by {@code kind}; a mod registers its own. */
    public static final CgType RENDERERS = CgTypes.dispatch("crystalgraphics:renderer", "kind").register();

    static final CgType SUB_IMAGE = CgTypes.struct("crystalgraphics:renderer.sub_image")
            .property("size", VEC2)
            .property("rects", CgValueType.listOf(VEC4))
            .property("blend", BOOL).defaultValue(false)
            .register();

    static final CgType MESH_ENTRY = CgTypes.struct("crystalgraphics:renderer.mesh_entry")
            .property("mesh", CgValueType.binaryRef("models")).required()
            .property("scale", VEC3).quantity(CgQuantity.SCALE)
            .property("rotation", CgValueType.ROTATION).quantity(CgQuantity.ROTATION)
            .property("pivot_offset", VEC3).quantity(CgQuantity.OFFSET)
            .property("pivot_space", VALUE)
            .register();

    static final CgType MESH_PREFAB = CgTypes.struct("crystalgraphics:renderer.mesh_prefab")
            .property("prefab", hard("prefab")).required()
            .register();

    static final CgType LOCKED_AXIS = CgTypes.struct("crystalgraphics:renderer.locked_axis")
            .property("axis", VEC3).quantity(CgQuantity.DIRECTION)
            .property("space", VALUE)
            .register();

    static final CgType RIBBON_UV = CgTypes.struct("crystalgraphics:renderer.ribbon_uv")
            .property("distribution", VALUE)
            .property("leading_edge", VALUE)
            .property("trailing_edge", VALUE)
            .property("tiling_length", FLOAT).quantity(CgQuantity.LENGTH)
            .property("offset", VEC2)
            .property("scale", VEC2)
            .register();

    static final CgType RIBBON_TESSELLATION = CgTypes.struct("crystalgraphics:renderer.ribbon_tessellation")
            .property("mode", VALUE)
            .property("factor", FLOAT)
            .property("tension", FLOAT)
            .property("angle", FLOAT).quantity(CgQuantity.ANGLE)
            .register();

    static final CgType TRAIL_TESSELLATION = CgTypes.struct("crystalgraphics:renderer.trail_tessellation")
            .property("distance", FLOAT).quantity(CgQuantity.LENGTH)
            .property("angle", FLOAT).quantity(CgQuantity.ANGLE)
            .property("width", FLOAT).quantity(CgQuantity.LENGTH)
            .property("max", INT)
            .register();

    static final CgType TRAIL_FADE = CgTypes.struct("crystalgraphics:renderer.trail_fade")
            .property("alpha", FLOAT)
            .property("width", FLOAT)
            .register();

    static final CgType TRAIL_RETRACT = CgTypes.struct("crystalgraphics:renderer.trail_retract")
            .property("retract", FLOAT).quantity(CgQuantity.TIME)
            .property("fade", CgValueType.struct(TRAIL_FADE))
            .register();

    private static final CgValueType SORT = CgValueType.enumOf("none", "view_depth", "view_distance", "spawn_order",
            "oldest_first", "newest_first");

    static final CgType SPRITE = renderer("sprite")
            .property("alignment", CgValueType.enumOf("unaligned", "velocity", "custom")).defaultValue("unaligned")
            .property("facing", CgValueType.enumOf("camera", "camera_plane", "camera_position", "custom", "camera_distance_blend")).defaultValue("camera")
            .property("facing_blend", VEC2).quantity(CgQuantity.LENGTH)
            .property("sort", SORT).defaultValue("none")
            .property("pivot", VEC2)
            .property("sub_image", CgValueType.struct(SUB_IMAGE))
            .property("camera_distance", VEC2).quantity(CgQuantity.LENGTH)
            .property("cast_shadows", BOOL).defaultValue(false)
            .property("soft_depth", FLOAT).quantity(CgQuantity.LENGTH)
            .register();

    static final CgType MESH = renderer("mesh")
            .property("meshes", CgValueType.oneOf(CgValueType.listOf(CgValueType.struct(MESH_ENTRY)),
                    CgValueType.struct(MESH_PREFAB), VALUE)).flags(CgPropertyFlag.STRUCTURAL)
            .property("material_overrides", CgValueType.listOf(hard("material")))
            .property("facing", CgValueType.enumOf("default", "velocity", "camera_position", "camera_plane")).defaultValue("default")
            .property("locked_axis", CgValueType.struct(LOCKED_AXIS))
            .property("sort", SORT).defaultValue("none")
            .property("sub_image", CgValueType.struct(SUB_IMAGE))
            .property("camera_distance", VEC2).quantity(CgQuantity.LENGTH)
            .property("cast_shadows", BOOL).defaultValue(false)
            .register();

    static final CgType RIBBON = renderer("ribbon")
            .property("facing", CgValueType.enumOf("screen", "custom", "custom_side_vector")).defaultValue("screen")
            .property("shape", CgValueType.enumOf("plane", "multi_plane", "tube", "custom")).defaultValue("plane")
            .property("width_segments", INT)
            .property("max_ribbons", INT)
            .property("uv0", CgValueType.struct(RIBBON_UV))
            .property("uv1", CgValueType.struct(RIBBON_UV))
            .property("tessellation", CgValueType.struct(RIBBON_TESSELLATION))
            .property("link_order_by_id", BOOL)
            .property("max_link_gap", INT)
            .register();

    static final CgType BEAM = renderer("beam")
            // Cascade's default (ParticleBeamModules.cpp:50).
            .property("beam_method", CgValueType.enumOf("distance", "target", "branch")).defaultValue("target")
            .property("sheets", INT)
            .property("max_beam_count", INT)
            .property("speed", FLOAT).quantity(CgQuantity.SPEED)
            .property("always_on", BOOL)
            .property("distance", FLOAT).quantity(CgQuantity.LENGTH)
            .property("interpolation_points", INT)
            .property("taper_method", CgValueType.enumOf("none", "full", "partial")).defaultValue("none")
            .property("taper_factor", FLOAT)
            .property("taper_scale", FLOAT)
            .property("up_vector_step_size", INT)
            .property("render_geometry", BOOL)
            .property("render_direct_line", BOOL)
            .property("render_lines", BOOL)
            .property("render_tessellation", BOOL)
            .property("texture_tile", INT)
            .property("texture_tile_distance", FLOAT).quantity(CgQuantity.LENGTH)
            .register();

    static final CgType LIGHT = renderer("light")
            .property("inverse_squared_falloff", BOOL)
            .property("exponent", FLOAT)
            .property("radius_scale", FLOAT).quantity(CgQuantity.LENGTH)
            .property("colour_add", CgValueType.COLOUR)
            .property("alpha_scales_brightness", BOOL)
            .property("diffuse_scale", FLOAT)
            .property("specular_scale", FLOAT)
            .property("volumetric_scattering", FLOAT)
            .property("affects_translucency", BOOL)
            .register();

    static final CgType SYSTEM_RENDERER = renderer("system")
            .property("system", hard("system")).required().flags(CgPropertyFlag.STRUCTURAL)
            .property("count_limit", INT)
            .property("assign_on_particle_id", BOOL)
            .property("only_activate_new", BOOL)
            .register();

    static final CgType SEQUENCE_RENDERER = renderer("sequence")
            .property("sequence", hard("sequence")).required().flags(CgPropertyFlag.STRUCTURAL)
            .property("play_rate", FLOAT).defaultValue(1.0)
            .register();

    static final CgType ANIM_TRAIL = renderer("anim_trail")
            .property("edge", CgValueType.listOf(STRING))
            .property("width_mode", CgValueType.enumOf("centre", "first", "second")).defaultValue("centre")
            .property("life", INT)
            .property("tessellation", CgValueType.struct(TRAIL_TESSELLATION))
            .property("width", FLOAT).quantity(CgQuantity.LENGTH)
            .property("colour", CgValueType.COLOUR)
            .property("uv0", CgValueType.struct(RIBBON_UV))
            .property("tiling_distance", FLOAT).quantity(CgQuantity.LENGTH)
            .property("stop", CgValueType.oneOf(CgValueType.enumOf("dead_on_deactivate"), CgValueType.struct(TRAIL_RETRACT)))
            .register();

    static final CgType ARC = renderer("arc")
            .property("subdivisions", INT)
            .register();

    static final CgType TRAILS = renderer("trails")
            .property("points", INT).flags(CgPropertyFlag.STRUCTURAL)
            .property("subdivisions", INT)
            .property("taper", FLOAT)
            .register();

    /**
     * A renderer member: the fields every renderer holds, then its kind's. The
     * family's {@code kind} key is written after the id.
     */
    private static CgType.Builder renderer(String kind) {
        CgType.Builder b = CgTypes.element("crystalgraphics:renderer." + kind).open()
                .property("enabled", BOOL).defaultValue(true)
                .property("quality", TIER)
                .property("sort_order_hint", INT)
                .property("motion_vectors", BOOL)
                .property("material", CgValueType.oneOf(hard("material"), VALUE)).flags(CgPropertyFlag.STRUCTURAL)
                .property("bind", mapOfValues())
                .property("values", mapOfValues())
                .property("keywords", CgValueType.listOf(STRING)).flags(CgPropertyFlag.STRUCTURAL);
        return b.onRegister(t -> RENDERERS.member(kind, t));
    }

    // ── Emitter ──────────────────────────────────────────────────────────────────────────

    /** The emitter's own fields: an emitter asset, and an inline one in a system's emitter element. */
    private static CgType.Builder emitterFields(CgType.Builder b) {
        return b.property("properties", CgValueType.struct(EMITTER_PROPERTIES))
                .property("emitter_update", CgValueType.idList(MODULE_ENTRY)).flags(CgPropertyFlag.STRUCTURAL)
                .property("particle_spawn", CgValueType.idList(MODULE_ENTRY)).flags(CgPropertyFlag.STRUCTURAL)
                .property("particle_update", CgValueType.idList(MODULE_ENTRY)).flags(CgPropertyFlag.STRUCTURAL)
                .property("events", CgValueType.idList(EVENT)).flags(CgPropertyFlag.STRUCTURAL)
                .property("renderers", CgValueType.idList(RENDERERS)).flags(CgPropertyFlag.STRUCTURAL);
    }

    /** {@code vfx/emitters/}: an emitter a second system shares ({@code UNiagaraEmitter}). */
    public static final CgType EMITTER = emitterFields(CgTypes.kind("crystalgraphics:emitter").folder("vfx/emitters").version(1))
            .register();

    /** An emitter written inline in its system. */
    static final CgType INLINE_EMITTER = emitterFields(CgTypes.struct("crystalgraphics:system.inline_emitter"))
            .property("unsupported", TREE)
            .register();

    // ── System ───────────────────────────────────────────────────────────────────────────

    /** A user parameter's declaration (what gameplay, sequences and parents set). */
    static final CgType USER_PARAMETER = CgTypes.struct("crystalgraphics:system.user_parameter")
            .property("type", CgValueType.enumOf("float", "int", "bool", "vec2", "vec3", "vec4", "colour", "position",
                    "rotation", "curve", "mesh", "texture", "material")).required().flags(CgPropertyFlag.STRUCTURAL)
            .property("quantity", CgCommonStructs.QUANTITY).defaultValue("none").flags(CgPropertyFlag.STRUCTURAL)
            .property("default", VALUE)
            .register();

    static final CgType EMITTER_HANDLE = CgTypes.element("crystalgraphics:system.emitter")
            .property("name", STRING).flags(CgPropertyFlag.LOCKED)
            .property("emitter", CgValueType.oneOf(hard("emitter"), CgValueType.struct(INLINE_EMITTER))).flags(CgPropertyFlag.STRUCTURAL)
            .property("enabled", BOOL).defaultValue(true).flags(CgPropertyFlag.STRUCTURAL)
            .property("after", CgValueType.LOCAL_ID)
            .property("before", CgValueType.LOCAL_ID)
            .register();

    static final CgType CHILD = CgTypes.element("crystalgraphics:system.child")
            .property("name", STRING)
            .property("system", hard("system")).flags(CgPropertyFlag.STRUCTURAL)
            .property("bind", mapOfValues())
            .register();

    /** A field the system places. */
    static final CgType PLACED_FIELD = CgTypes.element("crystalgraphics:system.field")
            .property("field", CgValueType.oneOf(hard("field"), CgValueType.struct(CgFieldKinds.INLINE_GRAPH)))
            .property("at", CgValueType.struct(CgCommonStructs.PLACE))
            .property("group", STRING)
            .property("weight", FLOAT).defaultValue(1.0)
            .property("enabled", BOOL).defaultValue(true)
            .property("orient", CgValueType.enumOf("anchor", "world"))
            .property("radius", FLOAT).quantity(CgQuantity.LENGTH)
            .register();

    static final CgType WARMUP = CgTypes.struct("crystalgraphics:system.warmup")
            .property("time", FLOAT).quantity(CgQuantity.TIME)
            .property("tick", FLOAT).quantity(CgQuantity.TIME)
            .register();

    static final CgType TICK = CgTypes.struct("crystalgraphics:system.tick")
            .property("fixed", FLOAT).quantity(CgQuantity.TIME)
            .register();

    /** A slot's light in place of a material: Unreal's point or spot light, its lengths times the slot's size. */
    static final CgType SLOT_LIGHT = CgTypes.struct("crystalgraphics:system.slot_light")
            .property("type", CgValueType.enumOf("point", "spot")).defaultValue("point")
            .property("light_color", CgValueType.COLOUR)
            .property("intensity", FLOAT).defaultValue(1.0)
            .property("attenuation_radius", FLOAT).defaultValue(1.0)
            .property("source_radius", FLOAT).defaultValue(0.0)
            .property("source_length", FLOAT).defaultValue(0.0)
            .property("inner_cone_angle", FLOAT).defaultValue(0.0)
            .property("outer_cone_angle", FLOAT).defaultValue(44.0)
            .property("cast_shadows", BOOL).defaultValue(true)
            .register();

    /** What a native behaviour's slot draws (ours, with no Unreal counterpart). */
    static final CgType SLOT = CgTypes.element("crystalgraphics:system.slot")
            .property("slot", CgValueType.NAMESPACED_ID).required()
            .property("material", hard("material")).flags(CgPropertyFlag.STRUCTURAL)
            .property("light", CgValueType.struct(SLOT_LIGHT)).flags(CgPropertyFlag.STRUCTURAL)
            .property("bind", mapOfValues())
            .property("size", FLOAT).quantity(CgQuantity.LENGTH).forms(CgValueForm.CONSTANT)
            .property("mesh", VALUE)
            .property("quality", TIER)
            .register();

    /** {@code vfx/systems/} ({@code UNiagaraSystem}). */
    public static final CgType SYSTEM = CgTypes.kind("crystalgraphics:system").folder("vfx/systems").version(1)
            .property("effect_type", hard("effect_type"))
            .property("user", CgValueType.mapOf(CgValueType.struct(USER_PARAMETER))).flags(CgPropertyFlag.HEADLESS, CgPropertyFlag.STRUCTURAL)
            .property("emitters", CgValueType.idList(EMITTER_HANDLE)).flags(CgPropertyFlag.STRUCTURAL)
            .property("children", CgValueType.idList(CHILD)).flags(CgPropertyFlag.STRUCTURAL)
            .property("fields", CgValueType.idList(PLACED_FIELD))
            .property("warmup", CgValueType.struct(WARMUP))
            .property("tick", CgValueType.struct(TICK))
            .property("delay", FLOAT).quantity(CgQuantity.TIME).defaultValue(0.0)
            .property("determinism", CgValueType.struct(DETERMINISM))
            .property("fixed_bounds", CgValueType.listOf(VEC3)).quantity(CgQuantity.POSITION)
            .property("effect", CgValueType.NAMESPACED_ID).flags(CgPropertyFlag.HEADLESS, CgPropertyFlag.STRUCTURAL)
            .property("slots", CgValueType.idList(SLOT)).flags(CgPropertyFlag.STRUCTURAL)
            .property("pool_prime_size", INT).range(0, null).defaultValue(0)
            .property("max_pool_size", INT).range(0, null).defaultValue(32)
            .register();

    // ── Module, effect type, ending ──────────────────────────────────────────────────────

    static final CgType MODULE_TEXTURE = CgTypes.struct("crystalgraphics:module.texture")
            .property("name", STRING).required()
            .property("kind", STRING)
            .property("input", STRING)
            .register();

    /** {@code vfx/modules/}: a data GPU module, declarations and GLSL. */
    public static final CgType MODULE = CgTypes.kind("crystalgraphics:module").folder("vfx/modules").version(1)
            .property("groups", CgValueType.listOf(CgValueType.enumOf("emitter_update", "particle_spawn", "particle_update"))).flags(CgPropertyFlag.STRUCTURAL)
            .property("order", CgValueType.enumOf("emitter", "spawn_init", "spawn_place", "spawn_launch", "forces", "after_solve", "events"))
            .property("once", BOOL).defaultValue(false)
            .property("analytic", BOOL).defaultValue(false)
            .property("inputs", CgValueType.listOf(CgValueType.struct(CgCommonStructs.INPUT_DECLARATION))).flags(CgPropertyFlag.STRUCTURAL)
            .property("lanes", CgValueType.listOf(STRING)).flags(CgPropertyFlag.STRUCTURAL)
            .property("world", CgValueType.listOf(CgValueType.enumOf("ground", "voxels", "entities", "neighbours", "depth", "gas", "gas_feed"))).flags(CgPropertyFlag.STRUCTURAL)
            .property("textures", CgValueType.listOf(CgValueType.struct(MODULE_TEXTURE))).flags(CgPropertyFlag.STRUCTURAL)
            .property("pull", FLOAT).quantity(CgQuantity.ACCELERATION)
            .property("glsl", STRING).flags(CgPropertyFlag.STRUCTURAL)
            .property("glsl_file", STRING).flags(CgPropertyFlag.STRUCTURAL)
            .property("unreal", TREE)
            .register();

    /** {@code FNiagaraSystemVisibilityCullingSettings}. */
    static final CgType VISIBILITY = CgTypes.struct("crystalgraphics:effect_type.visibility")
            .property("not_rendered", BOOL).defaultValue(false)
            .property("frustum", BOOL).defaultValue(false)
            .property("pre_cull_frustum", BOOL).defaultValue(false)
            .property("max_time_outside_frustum", FLOAT).quantity(CgQuantity.TIME).defaultValue(1.0)
            .property("max_time_without_render", FLOAT).quantity(CgQuantity.TIME).defaultValue(1.0)
            .register();

    /** A scale by the global budget's use: {@code FNiagaraLinearRamp}'s {@code [start_x, start_y, end_x, end_y]}. */
    private static final CgValueType RAMP = CgValueType.listOf(FLOAT);

    /**
     * {@code FNiagaraGlobalBudgetScaling}. Each ramp follows the toggle that switches it on, as Unreal's
     * {@code InlineEditConditionToggle}s do.
     */
    static final CgType BUDGET = CgTypes.struct("crystalgraphics:effect_type.budget")
            .property("cull", BOOL).defaultValue(false)
            .property("max_usage", FLOAT).defaultValue(1.0)
            .property("scale_distance", BOOL).defaultValue(false).toggleFor("distance_scale")
            .property("distance_scale", RAMP).defaultValue(Arrays.asList(0.5, 1.0, 1.0, 0.5))
            .property("scale_instances", BOOL).defaultValue(false).toggleFor("instance_scale")
            .property("instance_scale", RAMP).defaultValue(Arrays.asList(0.5, 1.0, 1.0, 0.5))
            .property("scale_system_instances", BOOL).defaultValue(false).toggleFor("system_instance_scale")
            .property("system_instance_scale", RAMP).defaultValue(Arrays.asList(0.5, 1.0, 1.0, 0.5))
            .register();

    /** {@code FNiagaraSystemScalabilitySettings}: each limit follows the toggle that switches it on. */
    static final CgType SYSTEM_SCALABILITY = CgTypes.struct("crystalgraphics:effect_type.system")
            .property("tiers", TIERS)
            .property("cull_by_distance", BOOL).defaultValue(false).toggleFor("max_distance")
            .property("max_distance", FLOAT).quantity(CgQuantity.LENGTH).defaultValue(0.0)
            .property("cull_max_instance_count", BOOL).defaultValue(false).toggleFor("max_instances")
            .property("max_instances", INT).defaultValue(0)
            .property("cull_per_system_max_instance_count", BOOL).defaultValue(false).toggleFor("max_system_instances")
            .property("max_system_instances", INT).defaultValue(0)
            .property("cull_proxy_mode", CgValueType.enumOf("none", "instanced_rendered")).defaultValue("none")
            .property("max_system_proxies", INT).defaultValue(32)
            .property("visibility", CgValueType.struct(VISIBILITY))
            .property("budget", CgValueType.struct(BUDGET))
            .register();

    /** {@code vfx/effect_types/} ({@code UNiagaraEffectType}). */
    public static final CgType EFFECT_TYPE = CgTypes.kind("crystalgraphics:effect_type").folder("vfx/effect_types").version(1)
            .property("update_frequency", CgValueType.enumOf("spawn_only", "low", "medium", "high", "continuous")).defaultValue("spawn_only")
            .property("cull_reaction", CgValueType.enumOf("deactivate", "deactivate_immediate", "deactivate_resume",
                    "deactivate_immediate_resume", "pause_resume", "sleep")).defaultValue("deactivate_immediate")
            .property("allow_culling_local_player", BOOL).defaultValue(false)
            .property("significance", CgValueType.enumOf("distance", "age"))
            .property("lod_distance", FLOAT).quantity(CgQuantity.LENGTH)
            .property("system", CgValueType.listOf(CgValueType.struct(SYSTEM_SCALABILITY)))
            .property("emitter", CgValueType.listOf(CgValueType.struct(EMITTER_SCALABILITY)))
            .register();

    static final CgType ANIMATE = CgTypes.element("crystalgraphics:ending.animate")
            .property("param", STRING)
            .property("to", VALUE)
            .property("easing", STRING)
            .register();

    /** {@code vfx/endings/}: how an effect eases out (ours). Its id is its network name. */
    public static final CgType ENDING = CgTypes.kind("crystalgraphics:ending").folder("vfx/endings").version(1)
            .property("seconds", FLOAT).required().quantity(CgQuantity.TIME).forms(CgValueForm.CONSTANT)
            .property("animate", CgValueType.idList(ANIMATE))
            .property("fade_out", STRING)
            .register();

    /** Loads this class, which registers its types; {@link CgAssetKinds#register()} calls it in order. */
    static void register() {
    }

    /**
     * A {@code crystalgraphics:} module is one the catalogue has, and each input one it declares, written in a form
     * it takes; a seeded module declares {@code seed}. A struct or array input checks its own shape.
     */
    private static void builtInModule(Map<?, ?> entry, CgPropertyPath at, CgValidation into) {
        Object id = entry.get("module");
        if (!(id instanceof String) || !((String) id).startsWith("crystalgraphics:")) return;
        CgModuleTypes.Module module = CgModuleTypes.builtIn().get((String) id);
        if (module == null) {
            into.error(at.key("module"), CgModuleTypes.UNKNOWN_MODULE, id + " is not a built-in module");
            return;
        }
        Object inputs = entry.get("inputs");
        if (!(inputs instanceof Map)) return;
        for (Map.Entry<?, ?> e : ((Map<?, ?>) inputs).entrySet()) {
            String name = String.valueOf(e.getKey());
            CgPropertyPath here = at.key("inputs").key(name);
            CgProperty p = module.inputs().property(name);
            if (p == null) {
                into.error(here, CgModuleTypes.UNKNOWN_INPUT, id + " has no input " + name);
                continue;
            }
            CgValueType.Kind k = p.type().kind();
            CgValueForm form = CgValueForm.of(e.getValue());
            if (k != CgValueType.Kind.STRUCT && k != CgValueType.Kind.ARRAY && !p.forms().contains(form)) {
                into.error(here, CgIssue.FORM, id + "'s " + name + " is written as a " + form.specName() + ", and takes "
                        + p.forms());
            }
        }
    }
}
