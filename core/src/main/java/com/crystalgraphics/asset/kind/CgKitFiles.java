package com.crystalgraphics.asset.kind;

import com.crystalgraphics.object.CgPropertyFlag;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.CgTypes;
import com.crystalgraphics.object.CgValueType;

import static com.crystalgraphics.object.CgValueType.FLOAT;
import static com.crystalgraphics.object.CgValueType.INT;
import static com.crystalgraphics.object.CgValueType.TREE;
import static com.crystalgraphics.object.CgValueType.VEC2;

/**
 * The files a kit keeps at its root that are not assets: one per game, read once as the kit registers:
 * {@code subtitle_style.yaml}, {@code look.yaml} and {@code ui.yaml}.
 *
 * <pre>{@code
 * CgValidation v = CgAssetData.validate(file.toString(), CgKitFiles.SUBTITLE_STYLE, tree, CgValidation.Usecase.LOAD);
 * }</pre>
 */
public final class CgKitFiles {

    private CgKitFiles() {
    }

    private static final CgValueType WIDGET_NAME = CgValueType.string("^[A-Za-z0-9_]+$");

    /**
     * {@code subtitle_style.yaml}: the game's subtitle widget, the text blocks a line and its speaker are set into, and
     * the widget's own animations that show and hide a line.
     */
    public static final CgType SUBTITLE_STYLE = CgTypes.struct("crystalgraphics:subtitle_style")
            .property(CgType.VERSION, INT).required()
            .property("widget", CgCommonStructs.hard("widget")).required()
            .property("line", WIDGET_NAME).required()
            .property("name", WIDGET_NAME)
            .property("with_name", CgValueType.listOf(WIDGET_NAME))
            .property("lines", INT).defaultValue(1)
            .property("show", CgCommonStructs.hard("sequence"))
            .property("hide", CgCommonStructs.hard("sequence"))
            .property(CgType.UNSUPPORTED, TREE)
            .property(CgType.EDITOR, TREE).flags(CgPropertyFlag.EDITOR_ONLY)
            .register();

    /** {@code look.yaml}: the post a game's effects are graded by, and the exposure of its own draws. */
    public static final CgType LOOK = CgTypes.struct("crystalgraphics:look")
            .property(CgType.VERSION, INT).required()
            .property("post", CgValueType.struct(CgCameraKinds.POST)).required()
            .property("effect_exposure", FLOAT).defaultValue(1.0)
            .property(CgType.UNSUPPORTED, TREE)
            .property(CgType.EDITOR, TREE).flags(CgPropertyFlag.EDITOR_ONLY)
            .register();

    /** {@code ui.yaml}: the game's {@code UUserInterfaceSettings}, how many screen pixels a UI unit is. */
    public static final CgType UI = CgTypes.struct("crystalgraphics:ui")
            .property(CgType.VERSION, INT).required()
            .property("rule", CgValueType.enumOf("shortest_side", "longest_side", "horizontal", "vertical"))
            .defaultValue("shortest_side")
            .property("curve", CgValueType.listOf(VEC2))
            .property("application_scale", FLOAT).defaultValue(1.0)
            .property(CgType.UNSUPPORTED, TREE)
            .property(CgType.EDITOR, TREE).flags(CgPropertyFlag.EDITOR_ONLY)
            .register();

    static void register() {
    }
}
