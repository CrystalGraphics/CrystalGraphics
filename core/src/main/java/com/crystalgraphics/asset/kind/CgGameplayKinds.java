package com.crystalgraphics.asset.kind;

import com.crystalgraphics.object.CgPropertyFlag;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.CgTypes;
import com.crystalgraphics.object.CgValueType;
import com.crystalgraphics.object.value.CgQuantity;

import static com.crystalgraphics.asset.kind.CgCommonStructs.hard;
import static com.crystalgraphics.asset.kind.CgCommonStructs.soft;
import static com.crystalgraphics.object.CgValueType.BOOL;
import static com.crystalgraphics.object.CgValueType.FLOAT;
import static com.crystalgraphics.object.CgValueType.STRING;
import static com.crystalgraphics.object.CgValueType.VALUE;

/**
 * The {@code cue} kind of {@code spec/gameplay.md}: what gameplay's moments play, per phase and per event, and what
 * gameplay writes each frame. GAS's {@code UGameplayCueNotify_Burst} and {@code AGameplayCueNotify_Looping}, with
 * {@code FGameplayCueNotify_BurstEffects} as the effects block.
 *
 * <p>Registered by {@link CgAssetKinds#register()}.</p>
 */
public final class CgGameplayKinds {

    private CgGameplayKinds() {
    }

    static final CgType SURFACES = CgTypes.struct("crystalgraphics:cue.surfaces")
            .property("allowed", CgValueType.listOf(STRING))
            .property("rejected", CgValueType.listOf(STRING))
            .register();

    /** Who sees an effect, and how often ({@code FGameplayCueNotify_SpawnCondition}). Evaluated on each viewer. */
    static final CgType SPAWN_CONDITION = CgTypes.struct("crystalgraphics:cue.spawn_condition")
            .property("source", CgValueType.enumOf("instigator", "target"))
            .property("policy", CgValueType.enumOf("always", "local_only", "not_local")).defaultValue("always")
            .property("chance", FLOAT).defaultValue(1.0).range(0, 1)
            .property("surfaces", CgValueType.struct(SURFACES))
            .register();

    /** Where an effect is placed ({@code FGameplayCueNotify_PlacementInfo}). */
    static final CgType PLACEMENT = CgTypes.struct("crystalgraphics:cue.placement")
            .property("socket", STRING)
            .property("attach", CgValueType.enumOf("do_not_attach", "attach_to_target"))
            .property("rule", CgValueType.enumOf("keep_relative", "keep_world", "snap_to_target"))
            .property("rotation", CgValueType.ROTATION).quantity(CgQuantity.ROTATION)
            .property("scale", CgValueType.VEC3).quantity(CgQuantity.SCALE)
            .register();

    private static CgType.Builder entry(String id) {
        return CgTypes.struct("crystalgraphics:cue.effects." + id);
    }

    private static CgType where(CgType.Builder b) {
        return b.property("spawn_condition", CgValueType.struct(SPAWN_CONDITION))
                .property("placement", CgValueType.struct(PLACEMENT))
                .register();
    }

    static final CgType PARTICLES = where(entry("particles")
            .property("system", hard("system")).required().flags(CgPropertyFlag.STRUCTURAL)
            .property("cast_shadow", BOOL));

    static final CgType SOUND = where(entry("sound")
            .property("sound", VALUE).required()
            .property("fade_out", FLOAT).quantity(CgQuantity.TIME)
            .property("fade_volume", FLOAT).range(0, 1));

    static final CgType SHAKE = where(entry("shake")
            .property("shake", hard("shake")).required()
            .property("scale", FLOAT).defaultValue(1.0)
            .property("space", VALUE)
            .property("in_world", BOOL)
            .property("inner", FLOAT).quantity(CgQuantity.LENGTH)
            .property("outer", FLOAT).quantity(CgQuantity.LENGTH)
            .property("falloff", FLOAT));

    static final CgType LENS = where(entry("lens")
            .property("post", CgValueType.struct(CgCameraKinds.POST)).required()
            .property("in_world", BOOL)
            .property("inner", FLOAT).quantity(CgQuantity.LENGTH)
            .property("outer", FLOAT).quantity(CgQuantity.LENGTH));

    static final CgType FORCE_FEEDBACK = where(entry("force_feedback")
            .property("duration", FLOAT).quantity(CgQuantity.TIME)
            .property("channels", VALUE)
            .property("tag", STRING)
            .property("looping", BOOL)
            .property("intensity", FLOAT));

    static final CgType DECAL = where(entry("decal")
            .property("material", hard("material")).required()
            .property("size", VALUE)
            .property("fade", VALUE));

    /** A sequence played once from its start at the entry's placement ({@code aaa-design} §2.49). */
    static final CgType SEQUENCE = where(entry("sequence")
            .property("sequence", soft("sequence")).required());

    /** The effects members, in the order a block writes them, before its own condition and placement. */
    private static CgType.Builder effects(CgType.Builder b) {
        return b.property("particles", CgValueType.listOf(CgValueType.struct(PARTICLES)))
                .property("sounds", CgValueType.listOf(CgValueType.struct(SOUND)))
                .property("shake", CgValueType.struct(SHAKE))
                .property("lens", CgValueType.struct(LENS))
                .property("force_feedback", CgValueType.struct(FORCE_FEEDBACK))
                .property("decal", CgValueType.struct(DECAL))
                .property("sequences", CgValueType.listOf(CgValueType.struct(SEQUENCE)))
                .property("spawn_condition", CgValueType.struct(SPAWN_CONDITION))
                .property("placement", CgValueType.struct(PLACEMENT));
    }

    /** A phase's effects ({@code FGameplayCueNotify_BurstEffects}). */
    static final CgType EFFECTS = effects(CgTypes.struct("crystalgraphics:cue.effects")).register();

    /** Bursts on an event the gameplay names; carries an id so a child cue overrides one event. */
    static final CgType EVENT = effects(CgTypes.element("crystalgraphics:cue.event")
            .property("event", STRING).required().flags(CgPropertyFlag.HEADLESS)).register();

    static final CgType FIELD = CgTypes.struct("crystalgraphics:cue.field")
            .property("name", STRING).required().flags(CgPropertyFlag.LOCKED)
            .property("type", CgValueType.enumOf("int", "float", "double", "bool", "string")).required()
            .property("fallback", VALUE)
            .register();

    /** {@code cues/}: what gameplay's moments play. */
    public static final CgType CUE = CgTypes.kind("crystalgraphics:cue").folder("cues").version(1)
            .property("tag", STRING).flags(CgPropertyFlag.HEADLESS)
            .property("kind", CgValueType.enumOf("burst", "looping")).required().flags(CgPropertyFlag.HEADLESS, CgPropertyFlag.STRUCTURAL)
            .property("default_spawn_condition", CgValueType.struct(SPAWN_CONDITION))
            .property("default_placement", CgValueType.struct(PLACEMENT))
            .property("burst", CgValueType.struct(EFFECTS))
            .property("application", CgValueType.struct(EFFECTS))
            .property("looping", CgValueType.struct(EFFECTS))
            .property("recurring", CgValueType.struct(EFFECTS))
            .property("removal", CgValueType.struct(EFFECTS))
            .property("events", CgValueType.idList(EVENT))
            .property("writes", CgValueType.mapOf(STRING)).flags(CgPropertyFlag.HEADLESS, CgPropertyFlag.STRUCTURAL)
            .property("range", FLOAT).quantity(CgQuantity.LENGTH).flags(CgPropertyFlag.HEADLESS)
            .property("authoritative", BOOL).defaultValue(false).flags(CgPropertyFlag.HEADLESS, CgPropertyFlag.STRUCTURAL)
            .property("persisted", BOOL).defaultValue(false).flags(CgPropertyFlag.HEADLESS)
            .property("prediction", CgValueType.enumOf("none", "one_off", "keyed")).flags(CgPropertyFlag.HEADLESS)
            .property("fields", CgValueType.listOf(CgValueType.struct(FIELD))).flags(CgPropertyFlag.HEADLESS, CgPropertyFlag.STRUCTURAL)
            .property("sequence", soft("sequence")).flags(CgPropertyFlag.HEADLESS)
            .register();

    static void register() {
    }
}
