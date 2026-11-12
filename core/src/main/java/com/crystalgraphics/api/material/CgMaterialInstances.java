package com.crystalgraphics.api.material;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * An instance per keyword variant of one material, each made at its first ask and kept: for a drawer whose keywords
 * follow its values, as a glass pane's do. A keyword is part of a material, never of an instance, so a drawer that
 * switches one switches instance.
 *
 * <pre>{@code
 * CgMaterialInstances panes = new CgMaterialInstances(CgMaterial.load("mymod:shaders/pane.shader"));
 *
 * // each draw:
 * CgMaterialInstance pane = panes.of(panes.material().keyword("WITH_NOISE", noise > 0f));
 * pane.set1f("_Noise", noise);
 * canvas.quad(pane).at(x, y).size(w, h).submit();
 * }</pre>
 *
 * <ul>
 *   <li>Each instance keeps its own values: write every value a draw reads after switching.</li>
 *   <li>Allocates nothing once each variant drawn has its instance.</li>
 *   <li>Not thread-safe: one drawer's.</li>
 * </ul>
 */
public final class CgMaterialInstances {

    private final CgMaterial material;
    private final Map<CgMaterial, CgMaterialInstance> made = new IdentityHashMap<>();

    public CgMaterialInstances(CgMaterial material) {
        this.material = material;
    }

    /** The material whose variants these are instances of, with its own keywords. */
    public CgMaterial material() {
        return material;
    }

    /** The instance of {@code variant}, one of {@link #material()}'s keyword variants: made at its first ask. */
    public CgMaterialInstance of(CgMaterial variant) {
        CgMaterialInstance had = made.get(variant);
        if (had == null) {
            had = variant.instance();
            made.put(variant, had);
        }
        return had;
    }

    /** Frees every instance made. */
    public void delete() {
        for (CgMaterialInstance instance : made.values()) instance.delete();
        made.clear();
    }
}
