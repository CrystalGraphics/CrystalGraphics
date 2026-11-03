package com.crystalgraphics.asset.kind;

import static com.crystalgraphics.object.CgValueType.BOOL;
import static com.crystalgraphics.object.CgValueType.FLOAT;
import static com.crystalgraphics.object.CgValueType.VEC3;

import java.util.Arrays;

import com.crystalgraphics.object.CgPropertyFlag;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.CgTypes;
import com.crystalgraphics.object.CgValueType;
import com.crystalgraphics.object.value.CgQuantity;

/**
 * The {@code stage} kind: a game level's environment, what its persistent level places over a whole fight. Its post
 * volumes ({@code APostProcessVolume}) and its height fog ({@code UExponentialHeightFogComponent}), placed by the host
 * over its world while it wants the game's arena look.
 *
 * <pre>{@code
 * CgType kind = CgStageKinds.STAGE;                 // stages/<name>.yaml
 * }</pre>
 */
public final class CgStageKinds {

    private CgStageKinds() {
    }

    /** A level's post volume: the {@code post} spawnable's fields, {@code at} a point of the game's world. */
    static final CgType VOLUME = CgTypes.struct("crystalgraphics:stage.volume")
            .property("at", VEC3).quantity(CgQuantity.POSITION)
            .property("post", CgValueType.struct(CgCameraKinds.POST)).required().flags(CgPropertyFlag.STRUCTURAL)
            .property("priority", FLOAT)
            .property("unbound", BOOL)
            .property("radius", FLOAT).quantity(CgQuantity.LENGTH)
            .property("blend_radius", FLOAT).quantity(CgQuantity.LENGTH)
            .register();

    /**
     * {@code UExponentialHeightFogComponent}'s one layer, at its defaults. {@code density} and {@code height_falloff}
     * are the component's: Unreal divides both by 1000 before drawing.
     */
    static final CgType FOG = CgTypes.struct("crystalgraphics:stage.fog")
            .property("density", FLOAT).defaultValue(0.02)
            .property("height_falloff", FLOAT).defaultValue(0.2)
            .property("height", FLOAT).defaultValue(0).quantity(CgQuantity.LENGTH)
            .property("inscattering_colour", VEC3).defaultValue(Arrays.asList(0.447, 0.638, 1.0))
            .property("max_opacity", FLOAT).defaultValue(1.0)
            .property("start_distance", FLOAT).defaultValue(0).quantity(CgQuantity.LENGTH)
            .property("cutoff_distance", FLOAT).defaultValue(0).quantity(CgQuantity.LENGTH)
            .register();

    /** {@code stages/<name>.yaml}: where it sits, its post volumes and its fog. */
    public static final CgType STAGE = CgTypes.kind("crystalgraphics:stage").folder("stages").version(1)
            .property("anchor", VEC3).quantity(CgQuantity.POSITION).defaultValue(0)
            .property("volumes", CgValueType.listOf(CgValueType.struct(VOLUME))).flags(CgPropertyFlag.STRUCTURAL)
            .property("fog", CgValueType.struct(FOG)).flags(CgPropertyFlag.STRUCTURAL)
            .register();

    static void register() {
    }
}
