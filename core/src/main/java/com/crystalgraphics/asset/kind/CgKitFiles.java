package com.crystalgraphics.asset.kind;

import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.CgTypes;
import com.crystalgraphics.object.CgValueType;

import static com.crystalgraphics.object.CgValueType.BOOL;
import static com.crystalgraphics.object.CgValueType.COLOUR;
import static com.crystalgraphics.object.CgValueType.FLOAT;
import static com.crystalgraphics.object.CgValueType.INT;
import static com.crystalgraphics.object.CgValueType.STRING;
import static com.crystalgraphics.object.CgValueType.VEC2;

/**
 * The files a kit keeps at its root that are not assets: one per game, read once as the kit registers.
 *
 * <pre>{@code
 * CgValidation v = CgAssetData.validate(file.toString(), CgKitFiles.SUBTITLE_STYLE, tree, CgValidation.Usecase.LOAD);
 * }</pre>
 */
public final class CgKitFiles {

    private CgKitFiles() {
    }

    static final CgType OUTLINE = CgTypes.struct("crystalgraphics:subtitle_style.outline")
            .property("size", FLOAT).required()
            .property("colour", COLOUR).required()
            .register();

    static final CgType UNDERLINE = CgTypes.struct("crystalgraphics:subtitle_style.underline")
            .property("image", CgValueType.NAMESPACED_ID).required()
            .property("size", VEC2).required()
            .property("offset", VEC2)
            .register();

    /** A run of text placed as a Slate text block in a canvas slot: the line, or the speaker's name. */
    static final CgType TEXT = CgTypes.struct("crystalgraphics:subtitle_style.text")
            .property("font", CgValueType.NAMESPACED_ID).required()
            .property("size", FLOAT).required()
            .property("bold", BOOL)
            .property("colour", COLOUR)
            .property("outline", CgValueType.struct(OUTLINE))
            .property("line_height", FLOAT).defaultValue(1.0)
            .property("wrap", FLOAT)
            .property("justify", CgValueType.enumOf("left", "centre", "right"))
            .property("anchor", VEC2)
            .property("alignment", VEC2)
            .property("offset", VEC2)
            .property("underline", CgValueType.struct(UNDERLINE))
            .register();

    static final CgType BAND = CgTypes.struct("crystalgraphics:subtitle_style.band")
            .property("colour", COLOUR).required()
            .property("anchor", VEC2)
            .property("height", FLOAT).required()
            .property("offset", VEC2)
            .register();

    /**
     * A widget animation over seconds: the band's alpha, the text's alpha and its slide along x in UI units, keyed and
     * eased as {@code interp} says (UMG's tracks: {@code cubic} is a rich curve's auto tangents).
     */
    static final CgType FADE = CgTypes.struct("crystalgraphics:subtitle_style.fade")
            .property("band", CgValueType.listOf(VEC2))
            .property("text", CgValueType.listOf(VEC2))
            .property("slide", CgValueType.listOf(VEC2))
            .property("interp", CgValueType.enumOf("linear", "cubic"))
            .register();

    /** {@code subtitle_style.yaml}: how a game draws its subtitles, in its UI units. */
    public static final CgType SUBTITLE_STYLE = CgTypes.struct("crystalgraphics:subtitle_style")
            .property(CgType.VERSION, INT).required()
            .property("reference_height", FLOAT).required()
            .property("ui_scale", FLOAT).defaultValue(1.0)
            .property("lines", INT).defaultValue(1)
            .property("line", CgValueType.struct(TEXT)).required()
            .property("name", CgValueType.struct(TEXT))
            .property("band", CgValueType.struct(BAND))
            .property("fade_in", CgValueType.struct(FADE))
            .property("fade_out", CgValueType.struct(FADE))
            .register();

    static void register() {
    }
}
