package com.crystalgraphics.sequence.binding;

import java.util.function.Consumer;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.shader.CgShaderBindings;
import com.crystalgraphics.gl.material.CgMaterialProperty;
import com.crystalgraphics.render.post.CgPostMaterial;
import com.crystalgraphics.sequence.CgSequenceTarget;

/**
 * A role's outline colour: the material contract's {@code outline} slot. A keyed colour draws the role's outline in
 * its own colour slot of an outline post material; a colour whose alpha is 0 leaves the outline its materials' own.
 * Kakarot's {@code ATAnimNotifyState_OutlineColor}: {@code ATCharacter_SetOutlineColor} sets the slot on each of the
 * character's dynamic materials, and {@code OutlinePost_SetTypeColor} writes the colour into the outline pass's slot.
 *
 * <pre>{@code
 * for (int s = 0; s < goku.materialSlots(); s++) goku.material(s, gokuMaterials.get(s));   // his own copies
 * CgRoleOutline outline = new CgRoleOutline(goku, battleOutline, 1, "OutlineColorType", "TypeColor1");
 *
 * // the role answers the slot through it
 * @Override public int slot(String name) { return outline.slot(name); }
 * @Override public void set(int slot, float[] values, int mask) { outline.set(slot, values, mask); }
 * @Override public void get(int slot, float[] out) { outline.get(slot, out); }
 * }</pre>
 *
 * <ul>
 *   <li>The slot is set on the instance's own materials ({@link CgAnimInstance#material(int,
 *       com.crystalgraphics.api.material.CgMaterialDefinition)}); a shared one is left alone. A material that does not
 *       declare {@code slotParameter} writes no outline slot.</li>
 *   <li>Slots are the outline's, one a role in play (Kakarot: the role's index among the battle's members plus 1); two
 *       roles given one slot share its colour.</li>
 * </ul>
 */
public final class CgRoleOutline implements CgSequenceTarget {

    /** The contract's slot name. */
    public static final String SLOT = "outline";

    private final CgAnimInstance instance;
    private final CgMaterial post;
    private final String slotParameter;
    private final int outlineSlot;
    /** The post's colour for this slot before any key: what switching back to the materials' own puts there. */
    private final float[] postOwn = {0, 0, 0, 1};
    private final float[] colour = new float[4];
    private final Consumer<CgShaderBindings> writeColour;
    private boolean keyed;

    /**
     * @param instance        the role's skinned instance, its own materials already set
     * @param post            the outline post material drawing the role
     * @param outlineSlot     the role's colour slot, 1 or more
     * @param slotParameter   the scalar its materials read their slot from (Kakarot's {@code OutlineColorType})
     * @param colourParameter the post's colour for that slot (Kakarot's {@code TypeColor1} for slot 1)
     */
    public CgRoleOutline(CgAnimInstance instance, CgPostMaterial post, int outlineSlot, String slotParameter,
                         String colourParameter) {
        if (outlineSlot < 1) throw new IllegalArgumentException("a role's outline slot is 1 or more, not " + outlineSlot);
        this.instance = instance;
        this.post = post.material();
        this.slotParameter = slotParameter;
        this.outlineSlot = outlineSlot;
        for (CgMaterialProperty p : this.post.properties()) {
            if (p.getName().equals(colourParameter)) System.arraycopy(p.getFloatValue(), 0, postOwn, 0, 4);
        }
        writeColour = b -> b.vec4(colourParameter, colour[0], colour[1], colour[2], 1f);
    }

    @Override
    public int slot(String name) {
        return name.equals(SLOT) ? 0 : -1;
    }

    @Override
    public void set(int slot, float[] values, int mask) {
        boolean moved = false;
        for (int i = 0; i < 4; i++) {
            if ((mask & 1 << i) == 0 || colour[i] == values[i]) continue;
            colour[i] = values[i];
            moved = true;
        }
        boolean on = colour[3] > 0f;
        if (on != keyed) {
            keyed = on;
            if (on) instance.scalar(slotParameter, outlineSlot);
            else instance.resetScalar(slotParameter);
            if (!on) System.arraycopy(postOwn, 0, colour, 0, 3);
            moved = true;
        }
        if (moved) post.applyProperties(writeColour);
    }

    @Override
    public void get(int slot, float[] out) {
        if (keyed) System.arraycopy(colour, 0, out, 0, 4);
        else {
            System.arraycopy(postOwn, 0, out, 0, 3);
            out[3] = 0f;
        }
    }
}
