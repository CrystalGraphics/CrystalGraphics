package com.crystalgraphics.api.material;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The parameter collection kind's compiler ({@code parameters/}, Unreal's {@code UMaterialParameterCollection}): a
 * collection asset declares its {@link CgParameterCollection}, every scalar and vector at its default, so a kit's
 * materials read {@code CG_GLOBAL_<namespace>_<name>} with no Java of its own.
 *
 * <pre>{@code
 * CgParameterCollections.register();                               // at init; CgBuiltInCompilers does
 * CgParameterCollection skill = CgParameterCollections.compile(asset, deps);
 * skill.scalarNamed("darken").set(0.6f);
 * }</pre>
 *
 * <pre>{@code
 * # assets/mymod/parameters/skill.yaml
 * _v: 1
 * namespace: skill
 * scalars: [ { id: p1, name: darken, default: 0 } ]
 * vectors: [ { id: p2, name: tint, default: [1, 1, 1, 1] } ]
 *
 * # assets/mymod/parameters/skill_night.yaml: skill's parameters again, as CG_GLOBAL_skill_night_*
 * _v: 1
 * namespace: skill_night
 * base: mymod:parameters/skill
 * overrides: { p1: 0.5 }
 * }</pre>
 *
 * <ul>
 *   <li>A collection with a {@code base} takes the base's parameters first, in its own namespace, then its own;
 *       {@code overrides} set any of their defaults by id.</li>
 *   <li>Compiled again with the same parameters in the same order (a reload), it answers the collection it declared.
 *       Parameters added, removed or reordered throw: a placed collection's layout cannot move.</li>
 *   <li>A namespace a Java collection already took throws, as a second {@link CgParameterCollection#of} does.</li>
 * </ul>
 */
public final class CgParameterCollections {

    /** The parameter collection kind's compiler. */
    public static final CgAssetCompiler<CgParameterCollection> COMPILER = CgParameterCollections::compile;

    private static final Map<String, Declared> DECLARED = new HashMap<>();

    private CgParameterCollections() {
    }

    /** Registers {@link #COMPILER} as the parameter collection kind's. */
    public static void register() {
        CgAssetCompiler.register(CgLookKinds.PARAMETERS, COMPILER);
    }

    /** {@code asset}'s collection, declared at its first compile; its base, if it names one, from {@code deps}. */
    public static synchronized CgParameterCollection compile(CgAssetData asset, CgAssetCompiler.Dependencies deps) {
        Map<Object, Object> tree = asset.resolved();
        String namespace = String.valueOf(tree.get("namespace"));
        List<Param> params = new ArrayList<>();
        if (tree.get("base") != null) {
            String baseId = String.valueOf(tree.get("base"));
            if (!(deps.definition(CgLookKinds.PARAMETERS, baseId) instanceof CgParameterCollection base)) {
                throw new IllegalArgumentException(asset.id() + ": its base " + baseId + " is not a compiled collection");
            }
            params.addAll(DECLARED.get(base.namespace()).params);
        }
        for (Map<?, ?> s : elements(tree.get("scalars"))) params.add(Param.of(s, true));
        for (Map<?, ?> v : elements(tree.get("vectors"))) params.add(Param.of(v, false));
        Map<?, ?> overrides = tree.get("overrides") instanceof Map<?, ?> m ? m : Map.of();
        List<String> layout = new ArrayList<>();
        for (Param p : params) layout.add((p.scalar ? "s:" : "v:") + p.name);
        Declared had = DECLARED.get(namespace);
        if (had != null) {
            if (!had.layout.equals(layout)) {
                throw new IllegalStateException(asset.id() + ": collection '" + namespace + "' changed its parameters ("
                        + had.layout + " to " + layout + "); its layout is placed, so restart to take it");
            }
            return had.collection;
        }
        CgParameterCollection collection;
        // Whole under the lock placing collections: a worker compiles this while the render thread's upload places
        // every collection defined, and one placed half-declared refuses the rest
        synchronized (CgParameterCollection.class) {
            collection = CgParameterCollection.of(namespace);
            for (Param p : params) {
                float[] d = overrides.containsKey(p.id) ? Param.values(overrides.get(p.id)) : p.defaults;
                if (p.scalar) collection.scalar(p.name, d[0]);
                else collection.vector(p.name, d[0], d[1], d[2], d[3]);
            }
        }
        DECLARED.put(namespace, new Declared(collection, layout, params));
        return collection;
    }

    /**
     * Every collection asset under {@code dir}, a kit's {@code parameters/} folder, declared under {@code namespace}
     * as the kit's load would: what a tool compiling the kit's {@code .shader} files without its materials needs
     * first, since a {@code CG_GLOBAL_*} name exists only once its collection is declared.
     *
     * <pre>{@code
     * CgParameterCollections.declareAll("kakarot_kit", root.resolve("assets/kakarot_kit/parameters"));
     * CgMaterialShaderRegistry.getOrCreate("kakarot_kit:shaders/cell_tone.shader").recompile();
     * }</pre>
     *
     * <ul>
     *   <li>A missing folder declares nothing.</li>
     *   <li>A {@code base} in the same folder is declared first, whatever the files' order.</li>
     * </ul>
     */
    public static void declareAll(String namespace, Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        Map<String, Map<?, ?>> trees = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path file : walk.filter(f -> f.toString().endsWith(".yaml")).sorted().toList()) {
                String name = dir.relativize(file).toString().replace(File.separatorChar, '/').replaceFirst("[.]yaml$", "");
                trees.put(namespace + ":parameters/" + name, (Map<?, ?>) CgAssetYaml.read(Files.readString(file)));
            }
        }
        Map<String, CgParameterCollection> made = new HashMap<>();
        for (String id : trees.keySet()) declare(id, trees, made);
    }

    private static CgParameterCollection declare(String id, Map<String, Map<?, ?>> trees, Map<String, CgParameterCollection> made) {
        CgParameterCollection had = made.get(id);
        if (had != null) return had;
        Map<?, ?> tree = trees.get(id);
        if (tree == null) return null;
        CgParameterCollection collection = compile(CgAssetData.of(id, CgLookKinds.PARAMETERS, tree, null),
                (kind, dep) -> kind == CgLookKinds.PARAMETERS ? declare(dep, trees, made) : null);
        made.put(id, collection);
        return collection;
    }

    private static List<Map<?, ?>> elements(Object list) {
        List<Map<?, ?>> out = new ArrayList<>();
        if (list instanceof List<?> l) {
            for (Object e : l) if (e instanceof Map<?, ?> m) out.add(m);
        }
        return out;
    }

    private record Param(String id, String name, boolean scalar, float[] defaults) {
        static Param of(Map<?, ?> element, boolean scalar) {
            return new Param(String.valueOf(element.get("id")), String.valueOf(element.get("name")), scalar,
                    values(element.get("default")));
        }

        /** A number as {@code (x, 0, 0, 0)}, a list as up to four components. */
        static float[] values(Object value) {
            float[] out = new float[4];
            if (value instanceof Number n) out[0] = n.floatValue();
            if (value instanceof List<?> list) {
                for (int i = 0; i < Math.min(4, list.size()); i++) {
                    if (list.get(i) instanceof Number n) out[i] = n.floatValue();
                }
            }
            return out;
        }
    }

    private record Declared(CgParameterCollection collection, List<String> layout, List<Param> params) {
    }
}
