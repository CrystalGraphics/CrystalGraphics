package com.crystalgraphics.vfx.field;

import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.kind.CgFieldKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.object.value.CgQuantity;
import com.crystalgraphics.object.value.CgUnits;

/**
 * A field a system places, compiled: the field, the group whose {@code physics_field} readers it acts on, its weight,
 * and where it acts about its placement. Chaos's field command as a component adds it.
 *
 * <pre>{@code
 * for (CgFieldPlacement f : system.fields()) {
 *     if (!f.enabled()) continue;
 *     f.bounds().bounded();        // false: it acts on its group everywhere, as an unbounded Chaos field does
 * }
 * }</pre>
 *
 * <ul>
 *   <li>Its bounds are its field's, cut to its {@code radius} about its placement where it gives one.</li>
 *   <li>An unbounded field is legal and acts everywhere, centred on its placement.</li>
 * </ul>
 *
 * @param id      its id in its system
 * @param field   what it applies
 * @param group   who reads it: the {@code physics_field} modules of that group
 * @param weight  what its value is scaled by
 * @param enabled whether it acts
 * @param world   its vectors stay in the world's axes; false, they turn with what it rides
 * @param radius  the reach it is cut to, in blocks; infinite where it gives none
 * @param bounds  where it acts about its placement
 */
public record CgFieldPlacement(String id, CgFieldDefinition field, String group, float weight, boolean enabled,
                               boolean world, float radius, CgFieldBounds bounds) {

    /** The placement {@code tree} writes in a system's {@code fields:}, failing by {@code path}. */
    public static CgFieldPlacement read(String path, Map<?, ?> tree, @Nullable CgAssetCompiler.Dependencies deps) {
        CgUnits units = deps == null ? CgUnits.HOST : deps.units();
        CgFieldDefinition field;
        if (tree.get("field") instanceof Map<?, ?> inline) {
            field = CgFieldDefinition.Graph.read(path + ".field", inline, units);
        } else if (tree.get("field") instanceof String ref) {
            if (!(deps != null && deps.definition(CgFieldKinds.FIELD, ref) instanceof CgFieldDefinition d)) {
                throw new IllegalArgumentException(path + ".field: " + ref + " is no compiled field");
            }
            field = d;
        } else {
            throw new IllegalArgumentException(path + ".field: a field id or an inline graph, not " + tree.get("field"));
        }
        if (!(tree.get("group") instanceof String group)) throw new IllegalArgumentException(path + ".group: a placed field names its group");
        float radius = tree.get("radius") instanceof Number n
                ? ((Number) units.toHost(n, CgQuantity.LENGTH)).floatValue() : Float.POSITIVE_INFINITY;
        CgFieldBounds bounds = Float.isFinite(radius) ? field.bounds().intersect(CgFieldBounds.sphere(0f, 0f, 0f, radius)) : field.bounds();
        float weight = tree.get("weight") instanceof Number w ? w.floatValue() : 1f;
        return new CgFieldPlacement(String.valueOf(tree.get("id")), field, group, weight,
                !Boolean.FALSE.equals(tree.get("enabled")), "world".equals(tree.get("orient")), radius, bounds);
    }
}
