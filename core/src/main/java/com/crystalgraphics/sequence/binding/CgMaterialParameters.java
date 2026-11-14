package com.crystalgraphics.sequence.binding;

import com.crystalgraphics.render.scene.component.CgSkeletalMeshComponent;
import com.crystalgraphics.sequence.CgSequenceTarget;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A skeletal mesh's material parameters as a sequence target: any parameter its materials declare is a slot by its own
 * name, written on every slot of the mesh that declares it. Unreal's component material parameter track over
 * {@code SetScalarParameterValueOnMaterials}. A role hands it out for its material tracks.
 *
 * <pre>{@code
 * final class Fighter implements CgSequenceRole {
 *     private final CgSkeletalMeshComponent body;
 *     private final CgMaterialParameters materials;            // new CgMaterialParameters(body), kept
 *
 *     @Override public int slot(String name) { return materials.slot(name); }
 *     @Override public void set(int slot, float[] values, int mask) { materials.set(slot, values, mask); }
 *     @Override public void get(int slot, float[] out) { materials.get(slot, out); }
 * }
 * }</pre>
 *
 * <p>A sequence then keys a parameter by its name on the role:</p>
 *
 * <pre>{@code
 * - { kind: material, binding: { role: caster }, sections: [ { range: [0, 4800],
 *     colours: { FresnelColor: { default: [0.2, 0.6, 1.0, 1.0] } }, scalars: { FresnelInfluence: { default: 1.0 } } } ] }
 * }</pre>
 *
 * <ul>
 *   <li>A name no slot's material declares has no slot ({@link #slot} answers -1): the play fails at bind, naming it.</li>
 *   <li>Before a sequence keys it, a slot reads as unkeyed, and writing it unkeyed puts every material slot back to its
 *       own material's value: Unreal's restore per material, never one value for all.</li>
 *   <li>A vector keyed in part keeps its material's own value in the other components.</li>
 *   <li>{@code set} allocates nothing; {@code slot} once a name, at bind.</li>
 * </ul>
 */
public final class CgMaterialParameters implements CgSequenceTarget {

    private final CgSkeletalMeshComponent mesh;
    private final List<String> names = new ArrayList<>();
    /** Per slot: how many numbers, what is keyed (NaN while unkeyed), and the material's own value. */
    private int[] components = new int[4];
    private float[][] keyed = new float[4][], own = new float[4][];

    public CgMaterialParameters(CgSkeletalMeshComponent mesh) {
        this.mesh = mesh;
    }

    @Override
    public int slot(String name) {
        int at = names.indexOf(name);
        if (at >= 0) return at;
        int n = mesh.parameterComponents(name);
        if (n == 0) return -1;
        at = names.size();
        if (at == components.length) {
            components = Arrays.copyOf(components, at * 2);
            keyed = Arrays.copyOf(keyed, at * 2);
            own = Arrays.copyOf(own, at * 2);
        }
        names.add(name);
        components[at] = n;
        keyed[at] = new float[4];
        Arrays.fill(keyed[at], Float.NaN);
        own[at] = new float[4];
        mesh.parameterDefault(name, own[at]);
        return at;
    }

    @Override
    public void set(int slot, float[] values, int mask) {
        float[] k = keyed[slot];
        for (int i = 0; i < 4; i++) if ((mask & 1 << i) != 0) k[i] = values[i];
        String name = names.get(slot);
        boolean any = false;
        for (int i = 0; i < components[slot]; i++) any |= !Float.isNaN(k[i]);
        if (components[slot] == 1) {
            if (any) mesh.scalar(name, k[0]);
            else mesh.resetScalar(name);
            return;
        }
        if (!any) {
            mesh.resetVector(name);
            return;
        }
        float[] o = own[slot];
        mesh.vector(name, pick(k[0], o[0]), pick(k[1], o[1]), pick(k[2], o[2]), pick(k[3], o[3]));
    }

    private static float pick(float keyed, float own) {
        return Float.isNaN(keyed) ? own : keyed;
    }

    @Override
    public void get(int slot, float[] out) {
        System.arraycopy(keyed[slot], 0, out, 0, 4);
    }

    @Override
    public String toString() {
        return mesh + "'s material parameters";
    }
}
