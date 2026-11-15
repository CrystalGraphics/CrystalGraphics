package com.crystalgraphics.sequence.binding;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.object.value.CgQuantity;
import com.crystalgraphics.object.value.CgUnits;

/**
 * A {@code figure} asset compiled: a body type's own transforms for the effects placed on it, by record and key
 * (Sparking! Zero's {@code EffectFigureTransformData}: a hit's spark scaled 0.75 to 4 by the struck one's size). A
 * sequence's locator takes its role's figure, composing the key's transform onto its own.
 *
 * <pre>{@code
 * // the gameplay names each role's figure with its presentation
 * final class Fighter implements CgSequenceRole {
 *     private static final CgAssetRef<CgFigureDefinition> LARGE = CgVfxAssets.ref(CgSequenceKinds.FIGURE, "mymod:figures/large");
 *     @Override public CgFigureDefinition figure() { return LARGE.get(); }
 *     ...
 * }
 *
 * // or read: the transform a locator with figure { record: LSG_Hit, key: Hit_01 } is given
 * CgFigureDefinition.Transform t = figure.transform("LSG_Hit", "Hit_01");   // null: the figure has no such record
 * }</pre>
 *
 * <ul>
 *   <li>A key the record lacks takes the record's own transform; a record the figure lacks gives nothing.</li>
 *   <li>Records and keys are found by {@code name}, as the format's locators name them; ids are the file's.</li>
 *   <li>Transforms are in the host's units, converted from the figure's file as a sequence's locators are.</li>
 * </ul>
 *
 * @param id      its asset id
 * @param records its records by name
 */
public record CgFigureDefinition(String id, Map<String, Record> records) {

    /** A location, a turn and a scale, each the identity where the file leaves it out. */
    public record Transform(Vector3fc location, Quaternionfc rotation, Vector3fc scale) {
        public static final Transform IDENTITY = new Transform(new Vector3f(), new Quaternionf(), new Vector3f(1f));

        public Transform {
            location = new Vector3f(location);
            rotation = new Quaternionf(rotation);
            scale = new Vector3f(scale);
        }
    }

    /** A record: its own transform, and its keys' by name. */
    public record Record(String name, Transform transform, Map<String, Transform> keys) {
        public Record {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(transform, "transform");
            keys = Collections.unmodifiableMap(new LinkedHashMap<>(keys));
        }
    }

    public static final CgAssetCompiler<CgFigureDefinition> COMPILER =
            (asset, deps) -> compile(asset.id(), asset.resolved(), deps.units());

    public CgFigureDefinition {
        Objects.requireNonNull(id, "id");
        records = Collections.unmodifiableMap(new LinkedHashMap<>(records));
    }

    /** Registers {@link #COMPILER} as the figure kind's. */
    public static void register() {
        CgAssetCompiler.register(CgSequenceKinds.FIGURE, COMPILER);
    }

    /**
     * Record {@code record}'s key {@code key} transform, its record's own where the key is null or missing; null where
     * the figure has no such record.
     */
    @Nullable
    public Transform transform(String record, @Nullable String key) {
        Record r = records.get(record);
        if (r == null) return null;
        Transform k = key == null ? null : r.keys().get(key);
        return k != null ? k : r.transform();
    }

    /** {@code tree}'s records, its values written in {@code units}. */
    public static CgFigureDefinition compile(String id, Map<?, ?> tree, CgUnits units) {
        Map<String, Record> records = new LinkedHashMap<>();
        if (tree.get("records") instanceof List<?> list) {
            for (Object o : list) {
                Map<?, ?> r = (Map<?, ?>) o;
                String name = String.valueOf(r.get("name"));
                Map<String, Transform> keys = new LinkedHashMap<>();
                if (r.get("keys") instanceof List<?> ks) {
                    for (Object k : ks) {
                        Map<?, ?> km = (Map<?, ?>) k;
                        keys.put(String.valueOf(km.get("name")), transform(km.get("transform"), units));
                    }
                }
                if (records.put(name, new Record(name, transform(r.get("transform"), units), keys)) != null) {
                    throw new IllegalArgumentException(id + ": two records named " + name);
                }
            }
        }
        return new CgFigureDefinition(id, records);
    }

    private static Transform transform(@Nullable Object o, CgUnits units) {
        if (!(o instanceof Map<?, ?> t)) return Transform.IDENTITY;
        return new Transform(vector(units.toHost(t.get("location"), CgQuantity.POSITION), 0f),
                units.rotationToHost(t.get("rotation"), new Quaternionf()),
                vector(units.toHost(t.get("scale"), CgQuantity.SCALE), 1f));
    }

    private static Vector3f vector(@Nullable Object v, float absent) {
        if (!(v instanceof List<?> l) || l.size() != 3) return new Vector3f(absent);
        return new Vector3f(((Number) l.get(0)).floatValue(), ((Number) l.get(1)).floatValue(), ((Number) l.get(2)).floatValue());
    }
}
