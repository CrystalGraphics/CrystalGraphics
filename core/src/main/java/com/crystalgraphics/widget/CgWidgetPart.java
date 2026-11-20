package com.crystalgraphics.widget;

import static com.crystalgraphics.widget.CgUserWidget.ANGLE;
import static com.crystalgraphics.widget.CgUserWidget.BLUR;
import static com.crystalgraphics.widget.CgUserWidget.COLOUR;
import static com.crystalgraphics.widget.CgUserWidget.CONTENT_COLOUR;
import static com.crystalgraphics.widget.CgUserWidget.MATERIAL_PARAMETERS;
import static com.crystalgraphics.widget.CgUserWidget.RENDER_OPACITY;
import static com.crystalgraphics.widget.CgUserWidget.SCALE;
import static com.crystalgraphics.widget.CgUserWidget.SHEAR;
import static com.crystalgraphics.widget.CgUserWidget.SLOTS;
import static com.crystalgraphics.widget.CgUserWidget.TRANSLATION;

import com.crystalgraphics.sequence.CgSequenceTarget;

/** One widget of a user widget as a sequence sees it: its property slots by name, written through its owner. */
final class CgWidgetPart implements CgSequenceTarget {

    private final CgUserWidget owner;
    private final int index;

    CgWidgetPart(CgUserWidget owner, int index) {
        this.owner = owner;
        this.index = index;
    }

    /** A property's slot by its sequence name; -1 for one a widget has not. */
    static int slotOf(String name) {
        return switch (name) {
            case "translation" -> TRANSLATION;
            case "scale" -> SCALE;
            case "shear" -> SHEAR;
            case "angle" -> ANGLE;
            case "render_opacity" -> RENDER_OPACITY;
            case "colour_and_opacity" -> COLOUR;
            case "content_colour_and_opacity" -> CONTENT_COLOUR;
            case "material_parameters" -> MATERIAL_PARAMETERS;
            case "blur" -> BLUR;
            default -> -1;
        };
    }

    @Override
    public int slot(String name) {
        return slotOf(name);
    }

    @Override
    public void set(int slot, float[] values, int mask) {
        owner.write(index, slot, values, mask);
    }

    @Override
    public void get(int slot, float[] out) {
        System.arraycopy(owner.state, (index * SLOTS + slot) * 4, out, 0, 4);
    }
}
