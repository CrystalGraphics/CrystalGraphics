package com.crystalgraphics.sequence.binding;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.material.CgMaterialDefinitions;
import com.crystalgraphics.api.shader.CgShaderBindings;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.gl.material.CgMaterialProperty;
import com.crystalgraphics.render.post.CgPostMaterial;
import com.crystalgraphics.sequence.CgSequenceTarget;

/**
 * A role's outline colour: the material contract's {@code outline} slot. A keyed colour draws the role's outline in
 * its own colour slot of an outline post material; a colour whose alpha is 0 leaves the outline its materials' own.
 * Kakarot's {@code ATAnimNotifyState_OutlineColor}: {@code ATCharacter_SetOutlineColor} sets each of the character's
 * materials to its type slot, and {@code OutlinePost_SetTypeColor} writes the colour into the outline pass's slot.
 *
 * <pre>{@code
 * CgRoleOutline outline = new CgRoleOutline(goku.instance(), gokuMaterials, battleOutline, 1,
 *         "OutlineColorType", "TypeColor1");
 *
 * // the role answers the slot through it
 * @Override public int slot(String name) { return outline.slot(name); }
 * @Override public void set(int slot, float[] values, int mask) { outline.set(slot, values, mask); }
 * @Override public void get(int slot, float[] out) { outline.get(slot, out); }
 * }</pre>
 *
 * <ul>
 *   <li>Build it after the role's materials are set on its instance: those are what it puts back.</li>
 *   <li>{@code materials} are the definitions of the instance's slots, in slot order. A material that does not declare
 *       {@code typeParameter} keeps its own: it writes no outline type.</li>
 *   <li>Slot types are the outline's, one a role in play (Kakarot: the role's index among the battle's members plus
 *       1); two roles given one type share its colour.</li>
 * </ul>
 */
public final class CgRoleOutline implements CgSequenceTarget {

    /** The contract's slot name. */
    public static final String SLOT = "outline";

    private final CgAnimInstance instance;
    private final CgMaterial[] own, typed;
    private final CgMaterial post;
    private final String colourParameter;
    /** The post's colour for this type before any key: what switching back to the materials' own puts there. */
    private final float[] postOwn = {0, 0, 0, 1};
    private final float[] colour = new float[4];
    private final Consumer<CgShaderBindings> writeColour;
    private boolean keyed;

    /**
     * @param instance        the role's skinned instance, its materials already set
     * @param materials       its slots' material definitions, in slot order
     * @param post            the outline post material drawing the role
     * @param type            the role's colour slot, 1 or more
     * @param typeParameter   the float each material reads its slot from (Kakarot's {@code OutlineColorType})
     * @param colourParameter the post's colour for that slot (Kakarot's {@code TypeColor1} for type 1)
     */
    public CgRoleOutline(CgAnimInstance instance, List<CgMaterialDefinition> materials, CgPostMaterial post, int type,
                         String typeParameter, String colourParameter) {
        if (type < 1) throw new IllegalArgumentException("a role's outline slot is 1 or more, not " + type);
        this.instance = instance;
        this.post = post.material();
        this.colourParameter = colourParameter;
        int n = instance.materialSlots();
        own = new CgMaterial[n];
        typed = new CgMaterial[n];
        for (int s = 0; s < n; s++) {
            own[s] = instance.material(s);
            typed[s] = own[s];
            if (s >= materials.size() || own[s] == null) continue;
            try {
                typed[s] = CgMaterial.of(CgMaterialDefinitions.derive(materials.get(s),
                        Map.of("values", Map.of(typeParameter, (double) type))));
            } catch (CgAssetException undeclared) {
                // no outline type of its own to take
            }
        }
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
            for (int s = 0; s < own.length; s++) instance.material(s, on ? typed[s] : own[s]);
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
