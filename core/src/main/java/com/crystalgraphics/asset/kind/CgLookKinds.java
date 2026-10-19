package com.crystalgraphics.asset.kind;

import com.crystalgraphics.object.CgPropertyFlag;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.CgTypes;
import com.crystalgraphics.object.CgValueType;

import static com.crystalgraphics.asset.kind.CgCommonStructs.hard;
import static com.crystalgraphics.asset.kind.CgCommonStructs.mapOfValues;
import static com.crystalgraphics.object.CgValueType.BOOL;
import static com.crystalgraphics.object.CgValueType.CURVE;
import static com.crystalgraphics.object.CgValueType.FLOAT;
import static com.crystalgraphics.object.CgValueType.INT;
import static com.crystalgraphics.object.CgValueType.STRING;
import static com.crystalgraphics.object.CgValueType.VEC4;

/**
 * The look kinds of {@code spec/looks.md} and {@code core.md} § Curves: {@code material}, {@code texture},
 * {@code mesh}, {@code parameters} and {@code curve}. Unreal's {@code UMaterialInstanceConstant}, texture and mesh
 * settings, {@code UMaterialParameterCollection} and {@code UCurveFloat}/{@code Vector}/{@code LinearColor}.
 *
 * <p>Registered by {@link CgAssetKinds#register()}.</p>
 */
public final class CgLookKinds {

    private CgLookKinds() {
    }

    // ── Material ─────────────────────────────────────────────────────────────────────────

    static final CgType STATE = CgTypes.struct("crystalgraphics:material.state")
            .property("blend", CgValueType.enumOf("opaque", "masked", "translucent", "additive", "modulate", "premultiplied", "subtract"))
                    .flags(CgPropertyFlag.STRUCTURAL)
            .property("mask_clip", FLOAT)
            .property("two_sided", BOOL).flags(CgPropertyFlag.STRUCTURAL)
            .property("cull", CgValueType.enumOf("back", "front")).flags(CgPropertyFlag.STRUCTURAL)
            .property("shading", CgValueType.oneOf(CgValueType.enumOf("unlit", "lit", "subsurface"), STRING)).flags(CgPropertyFlag.STRUCTURAL)
            .property("depth_test", BOOL).flags(CgPropertyFlag.STRUCTURAL)
            .property("depth_write", BOOL).flags(CgPropertyFlag.STRUCTURAL)
            .register();

    static final CgType STENCIL = CgTypes.struct("crystalgraphics:material.post.stencil")
            .property("compare", CgValueType.enumOf("less", "less_equal", "greater", "greater_equal", "equal", "not_equal",
                    "never", "always")).required()
            .property("ref", INT).required().range(0, 255)
            .register();

    static final CgType POST = CgTypes.struct("crystalgraphics:material.post")
            .property("stage", CgValueType.enumOf("before_translucency", "before_tonemapping", "ssr_input",
                    "replacing_tonemapper", "after_tonemapping")).flags(CgPropertyFlag.STRUCTURAL)
            .property("priority", INT)
            .property("blendable", BOOL)
            .property("output_alpha", BOOL)
            .property("stencil", CgValueType.struct(STENCIL))
            .register();

    /** A sampler: filtering, wrapping, anisotropy ({@code looks.md} § Textures). */
    static final CgType SAMPLER = CgTypes.struct("crystalgraphics:sampler")
            .property("filter", CgValueType.enumOf("nearest", "bilinear", "trilinear")).defaultValue("trilinear")
            .property("wrap", CgValueType.oneOf(CgValueType.enumOf("repeat", "clamp", "mirror"),
                    CgValueType.listOf(CgValueType.enumOf("repeat", "clamp", "mirror"))))
            .property("anisotropy", INT)
            .register();

    static final CgType TEXTURE_BINDING = CgTypes.struct("crystalgraphics:material.texture")
            .property("texture", CgValueType.binaryRef("textures")).required().flags(CgPropertyFlag.STRUCTURAL)
            .property("sampler", CgValueType.struct(SAMPLER))
            .register();

    /** {@code materials/} ({@code UMaterialInstanceConstant}). */
    public static final CgType MATERIAL = CgTypes.kind("crystalgraphics:material").folder("materials").version(1)
            .property("shader", STRING).flags(CgPropertyFlag.STRUCTURAL)
            .property("state", CgValueType.struct(STATE))
            .property("post", CgValueType.struct(POST))
            .property("order", INT)
            .property("sort_layer", CgValueType.enumOf("background", "default", "effects", "overlay"))
            .property("keywords", CgValueType.listOf(STRING)).flags(CgPropertyFlag.STRUCTURAL)
            .property("values", mapOfValues())
            .property("textures", CgValueType.mapOf(CgValueType.struct(TEXTURE_BINDING)))
            .register();

    // ── Texture, mesh ────────────────────────────────────────────────────────────────────

    static final CgType RENDER_TARGET = CgTypes.struct("crystalgraphics:texture.render_target")
            .property("size", CgValueType.listOf(INT)).required().flags(CgPropertyFlag.STRUCTURAL)
            .property("format", CgValueType.enumOf("rgba8", "rgba16f", "r16f", "rgba32f")).flags(CgPropertyFlag.STRUCTURAL)
            .property("clear", VEC4)
            .register();

    /** {@code textures/}: the settings beside an image of the same path. */
    public static final CgType TEXTURE = CgTypes.kind("crystalgraphics:texture").folder("textures").version(1)
            .property("colour", CgValueType.enumOf("srgb", "linear")).flags(CgPropertyFlag.STRUCTURAL)
            .property("mips", BOOL).flags(CgPropertyFlag.STRUCTURAL)
            .property("compression", CgValueType.enumOf("bc7", "normal_map", "masks", "grayscale", "alpha", "hdr", "none"))
                    .flags(CgPropertyFlag.STRUCTURAL)
            .property("sampler", CgValueType.struct(SAMPLER))
            .property("render_target", CgValueType.struct(RENDER_TARGET))
            .register();

    static final CgType LOD = CgTypes.struct("crystalgraphics:mesh.lod")
            .property("mesh", CgValueType.binaryRef("models")).required()
            .property("screen_size", FLOAT)
            .register();

    /** {@code models/}: the settings beside a glTF of the same path. */
    public static final CgType MESH = CgTypes.kind("crystalgraphics:mesh").folder("models").version(1)
            .property("lods", CgValueType.listOf(CgValueType.struct(LOD))).flags(CgPropertyFlag.STRUCTURAL)
            .property("bounds_scale", FLOAT).defaultValue(1.0)
            .property("skeleton", hard("skeleton"))
            .property("springs", hard("springs"))
            .register();

    // ── Parameters, curve ────────────────────────────────────────────────────────────────

    static final CgType SCALAR = CgTypes.element("crystalgraphics:parameters.scalar")
            .property("name", STRING).flags(CgPropertyFlag.LOCKED)
            .property("default", FLOAT)
            .register();

    static final CgType VECTOR = CgTypes.element("crystalgraphics:parameters.vector")
            .property("name", STRING).flags(CgPropertyFlag.LOCKED)
            .property("default", VEC4)
            .register();

    /** {@code parameters/}: global values sequences key and shaders read ({@code UMaterialParameterCollection}). */
    public static final CgType PARAMETERS = CgTypes.kind("crystalgraphics:parameters").folder("parameters").version(1)
            .property("namespace", STRING).required().flags(CgPropertyFlag.STRUCTURAL)
            .property("base", hard("parameters")).flags(CgPropertyFlag.STRUCTURAL)
            .property("scalars", CgValueType.idList(SCALAR)).flags(CgPropertyFlag.STRUCTURAL)
            .property("vectors", CgValueType.idList(VECTOR)).flags(CgPropertyFlag.STRUCTURAL)
            .property("overrides", mapOfValues())
            .register();

    static final CgType ADJUST = CgTypes.struct("crystalgraphics:curve.adjust")
            .property("hue", FLOAT)
            .property("saturation", FLOAT)
            .property("brightness", FLOAT)
            .property("brightness_curve", CURVE)
            .property("vibrance", FLOAT)
            .property("min_alpha", FLOAT)
            .property("max_alpha", FLOAT)
            .register();

    /** {@code curves/}: a shared curve ({@code core.md} § Curves). */
    public static final CgType CURVE_KIND = CgTypes.kind("crystalgraphics:curve").folder("curves").version(1)
            .property("type", CgValueType.enumOf("float", "vector", "colour")).required().flags(CgPropertyFlag.HEADLESS)
            .property("curves", CgValueType.listOf(CURVE)).required().flags(CgPropertyFlag.HEADLESS)
            .property("adjust", CgValueType.struct(ADJUST))
            .register();

    static void register() {
    }
}
