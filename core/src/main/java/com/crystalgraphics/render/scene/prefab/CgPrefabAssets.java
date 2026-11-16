package com.crystalgraphics.render.scene.prefab;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.asset.kind.CgBodyKinds;
import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.object.value.CgQuantity;
import com.crystalgraphics.object.value.CgUnits;
import com.crystalgraphics.render.post.settings.CgPostBlendable;
import com.crystalgraphics.render.post.settings.CgPostBlock;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The {@code prefab} kind's compiler and writer: a {@code prefabs/} file to the {@link CgPrefab} it describes, and a
 * prefab back to its file. Unreal's Blueprint actor and its component tree, CC2's clump.
 *
 * <pre>{@code
 * CgPrefab shock = CgVfxAssets.<CgPrefab>ref(CgBodyKinds.PREFAB, "nsuns4_kit:1efcmn/1efc_shock08").get();
 * }</pre>
 *
 * <pre>{@code
 * Map<String, Object> tree = CgPrefabAssets.write(CORE);       // a prefab built in Java, as its file
 * Files.write(path, CgAssetYaml.write(tree, CgBodyKinds.PREFAB).getBytes(StandardCharsets.UTF_8));
 * }</pre>
 *
 * <pre>{@code
 * # assets/mymod/prefabs/core.yaml
 * _v: 1
 * nodes:
 *   - { id: n1, name: ball03 }
 *   - { id: n2, name: ball02, parent: ball03, location: [0, 1, 0], meshes: [ { mesh: mymod:models/core/ball02.glb, material: mymod:lens } ] }
 * }</pre>
 *
 * <pre>{@code
 * # a skeletal node, a node on its socket, an effect started by code
 *   - { id: n3, name: body, skeletal: { mesh: mymod:geo/wyvern.glb, play: idle }, materials: [ mymod:wyvern_skin ] }
 *   - { id: n4, name: flame, parent: "body#jaw", effect: { effect: mymod:vfx/breath, auto: false } }
 *   - { id: n5, name: breath_look, parent: "body#jaw", post: { settings: { bloom: 2.0 }, radius: 20, blend: 12, weight: 0 } }
 * }</pre>
 *
 * <pre>{@code
 * # a level's look: an unbound post volume with a post material, a height fog at its node's height, a light
 *   - { id: n6, name: look, post: { settings: { vignette: 0.5, materials: [ { material: mymod:post/overlay,
 *                                                     values: { SunVector: [0.8, -0.5, 0.3, 0] } } ] }, priority: 1 } }
 *   - { id: n7, name: fog, location: [0, 15799, 0], fog: { density: 0.002, height_falloff: 0.1, max_opacity: 0.65,
 *                                                          inscattering_colour: [0.41, 0.89, 1] } }
 *   - { id: n8, name: sun_lamp, location: [0, 200, 0], light: { kind: point, colour: [1, 0.6, 0.2], intensity: 40, radius: 600 } }
 * }</pre>
 *
 * <ul>
 *   <li>Its values convert from its units to the host's: a location and a scale through the axes, a rotation in its
 *       convention to a quaternion.</li>
 *   <li>A mesh slot is kept as written, its materials and its other fields: a character's several materials and its
 *       overlay compile, and fail only where what plays the prefab does not draw them.</li>
 *   <li>A material it names must be compiled with it: one that is not fails, naming the node.</li>
 *   <li>A fog's {@code density} and {@code height_falloff} are Unreal's {@code FogDensity} and {@code FogHeightFalloff}:
 *       divided by 1000, then per the file's length (Unreal's SceneCore.cpp), Unreal's defaults where absent.</li>
 *   <li>A light's {@code colour} is sRGB, three numbers or four with alpha; a spot's {@code cone} its inner and outer
 *       angles in degrees; a spot or a tube points along its node's +x.</li>
 *   <li>A post block's {@code materials} take no {@code screen_point}: nothing in a prefab is followed on screen.</li>
 *   <li>{@link #write} writes in the host's units; a part whose mesh was made in code has no file and fails there.</li>
 * </ul>
 */
public final class CgPrefabAssets {

    /** The prefab kind's compiler. */
    public static final CgAssetCompiler<CgPrefab> COMPILER = (asset, deps) -> compile(asset.id(), asset.resolved(), deps);

    private CgPrefabAssets() {
    }

    /** Sets {@link #COMPILER} as the prefab kind's; the engine does, on both sides, as the process's assets are made. */
    public static void register() {
        CgAssetCompiler.register(CgBodyKinds.PREFAB, COMPILER);
    }

    /** {@code tree}, a prefab file, as a prefab: its materials from {@code deps}, its values from {@code deps}' units. */
    public static CgPrefab compile(String id, Map<?, ?> tree, CgAssetCompiler.Dependencies deps) {
        CgUnits units = deps.units();
        CgPrefab.Builder b = CgPrefab.builder(id);
        Quaternionf turn = new Quaternionf();
        if (!(tree.get("nodes") instanceof List<?> nodes)) throw new CgAssetException(id + " has no nodes");
        for (Object o : nodes) {
            Map<?, ?> node = (Map<?, ?>) o;
            String name = (String) node.get("name");
            String where = id + ": nodes." + node.get("id");
            b.node(name).elementId(String.valueOf(node.get("id")));
            if (node.get("parent") instanceof String parent) {
                int socket = parent.indexOf('#');
                try {
                    if (socket < 0) b.parent(parent);
                    else b.parent(parent.substring(0, socket), parent.substring(socket + 1));
                } catch (IllegalArgumentException e) {
                    throw new CgAssetException(where + ".parent: " + e.getMessage());
                }
            }
            if (node.get("location") != null) {
                float[] v = three(units.toHost(vector(node.get("location"), where + ".location"), CgQuantity.POSITION));
                b.at(v[0], v[1], v[2]);
            }
            if (node.get("rotation") != null) {
                try {
                    units.rotationToHost(node.get("rotation"), turn);
                } catch (IllegalArgumentException e) {
                    throw new CgAssetException(where + ".rotation: " + e.getMessage());
                }
                b.rotation(turn.x, turn.y, turn.z, turn.w);
            }
            if (node.get("scale") != null) {
                float[] v = three(units.toHost(vector(node.get("scale"), where + ".scale"), CgQuantity.SCALE));
                b.scale(v[0], v[1], v[2]);
            }
            if (node.get("opacity") instanceof Number a) b.opacity(a.floatValue());
            if (node.get("values") instanceof Map<?, ?> values) {
                for (Map.Entry<?, ?> v : values.entrySet()) b.value((String) v.getKey(), floats(v.getValue(), where + ".values." + v.getKey()));
            }
            if (node.get("skeletal") instanceof Map<?, ?> skeletal) skeletal(b, node, skeletal, deps, where);
            if (node.get("effect") instanceof Map<?, ?> effect) {
                if (!(effect.get("effect") instanceof String system)) throw new CgAssetException(where + ".effect names no effect");
                b.effect(system, !Boolean.FALSE.equals(effect.get("auto")));
            }
            if (node.get("post") instanceof Map<?, ?> post) post(b, post, units, deps, where);
            if (node.get("fog") instanceof Map<?, ?> fog) b.fog(fog(fog, units));
            if (node.get("light") instanceof Map<?, ?> light) b.light(light(light, units, where + ".light"));
            if (node.get("meshes") instanceof List<?> meshes) {
                for (int k = 0; k < meshes.size(); k++) part(b, (Map<?, ?>) meshes.get(k), deps, where + ".meshes[" + k + "]");
            }
        }
        return b.build();
    }

    /** A post node: its look a post block, its materials its blendables, unbound without a radius, lengths in the file's units. */
    private static void post(CgPrefab.Builder b, Map<?, ?> post, CgUnits units, CgAssetCompiler.Dependencies deps, String where) {
        if (!(post.get("settings") instanceof Map<?, ?> settings)) throw new CgAssetException(where + ".post has no settings block");
        float priority = post.get("priority") instanceof Number p ? p.floatValue() : 0f;
        if (priority != Math.rint(priority)) throw new CgAssetException(where + ".post.priority: " + priority + " is not a whole priority");
        List<CgPostBlendable> materials = new ArrayList<>();
        if (settings.get("materials") instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                materials.add(CgPostBlendable.of((Map<?, ?>) list.get(i), where + ".post.settings.materials[" + i + "]", deps, null));
            }
        }
        Map<Object, Object> look = new LinkedHashMap<>(settings);
        look.remove("materials");
        b.post(CgPostBlock.settings(look), materials, post.get("radius") == null ? Float.POSITIVE_INFINITY : length(units, post.get("radius")),
                length(units, post.get("blend")), post.get("weight") instanceof Number w ? w.floatValue() : 1f, (int) priority);
    }

    /** A fog node: Unreal's {@code UExponentialHeightFogComponent} fields, its density and falloff per 1000 file lengths. */
    private static CgPrefab.Fog fog(Map<?, ?> f, CgUnits units) {
        float density = (float) perLength(units, number(f.get("density"), 0.02f) / 1000.0);
        float falloff = (float) perLength(units, number(f.get("height_falloff"), 0.2f) / 1000.0);
        float[] c = f.get("inscattering_colour") instanceof List<?> l ? floats(l, "inscattering_colour") : new float[] {0.447f, 0.638f, 1f};
        return new CgPrefab.Fog(density, falloff, c[0], c[1], c[2], number(f.get("max_opacity"), 1f),
                length(units, f.get("start_distance")), length(units, f.get("cutoff_distance")));
    }

    /** A light node: {@code CgLightComponent}'s fields, at Unreal's defaults where absent. */
    private static CgPrefab.Light light(Map<?, ?> l, CgUnits units, String where) {
        CgPrefab.Light.Kind kind;
        try {
            kind = CgPrefab.Light.Kind.valueOf(String.valueOf(l.get("kind") == null ? "point" : l.get("kind")).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new CgAssetException(where + ".kind: " + l.get("kind") + " is not point, spot or tube");
        }
        int argb = 0xFFFFFFFF;
        if (l.get("colour") != null) {
            float[] c = floats(l.get("colour"), where + ".colour");
            if (c.length < 3) throw new CgAssetException(where + ".colour: three or four numbers");
            argb = channel(c.length > 3 ? c[3] : 1f) << 24 | channel(c[0]) << 16 | channel(c[1]) << 8 | channel(c[2]);
        }
        float[] cone = l.get("cone") != null ? floats(l.get("cone"), where + ".cone") : new float[] {0f, 44f};
        if (cone.length != 2) throw new CgAssetException(where + ".cone: its inner and outer angles");
        return new CgPrefab.Light(kind, argb, number(l.get("intensity"), 1f), l.get("radius") == null ? 10f : length(units, l.get("radius")),
                number(l.get("exponent"), 0f), length(units, l.get("source_radius")), !Boolean.FALSE.equals(l.get("shadows")),
                number(l.get("volumetric"), 1f), cone[0], cone[1], length(units, l.get("length")));
    }

    private static int channel(float v) {
        return Math.round(Math.max(0f, Math.min(1f, v)) * 255f);
    }

    private static double perLength(CgUnits units, double v) {
        return ((Number) units.toHost(v, CgQuantity.PER_LENGTH)).doubleValue();
    }

    private static float number(Object v, float fallback) {
        return v instanceof Number n ? n.floatValue() : fallback;
    }

    private static float length(CgUnits units, Object v) {
        return v instanceof Number ? ((Number) units.toHost(v, CgQuantity.LENGTH)).floatValue() : 0f;
    }

    private static void skeletal(CgPrefab.Builder b, Map<?, ?> node, Map<?, ?> skeletal, CgAssetCompiler.Dependencies deps,
                                 String where) {
        if (!(skeletal.get("mesh") instanceof String mesh)) throw new CgAssetException(where + ".skeletal names no mesh");
        List<CgMaterialDefinition> materials = new ArrayList<>();
        if (node.get("materials") instanceof List<?> ms) {
            for (int i = 0; i < ms.size(); i++) materials.add(material((String) ms.get(i), deps, where + ".materials[" + i + "]"));
        }
        if (materials.isEmpty()) throw new CgAssetException(where + " is a skeletal mesh with no materials");
        b.skeletal(mesh, materials);
        if (skeletal.get("play") instanceof String clip) b.play(clip);
        if (skeletal.get("follow_pose") instanceof String leader) {
            try {
                b.followPose(leader);
            } catch (IllegalArgumentException e) {
                throw new CgAssetException(where + ".skeletal.follow_pose: " + e.getMessage());
            }
        }
    }

    private static void part(CgPrefab.Builder b, Map<?, ?> slot, CgAssetCompiler.Dependencies deps, String where) {
        List<CgMaterialDefinition> materials = new ArrayList<>();
        if (slot.get("material") instanceof String m) materials.add(material(m, deps, where + ".material"));
        if (slot.get("materials") instanceof List<?> ms) {
            for (int i = 0; i < ms.size(); i++) materials.add(material((String) ms.get(i), deps, where + ".materials[" + i + "]"));
        }
        if (materials.isEmpty()) throw new CgAssetException(where + " names no material");
        if (!(slot.get("mesh") instanceof String mesh)) throw new CgAssetException(where + " names no mesh");
        Map<String, Object> settings = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : slot.entrySet()) {
            String key = (String) e.getKey();
            if (!key.equals("mesh") && !key.equals("material") && !key.equals("materials")) settings.put(key, e.getValue());
        }
        b.part(materials, mesh, settings);
    }

    private static CgMaterialDefinition material(String id, CgAssetCompiler.Dependencies deps, String where) {
        if (deps.definition(CgLookKinds.MATERIAL, id) instanceof CgMaterialDefinition d) return d;
        throw new CgAssetException(where + ": " + id + " is not compiled with it");
    }

    /** {@code prefab} as its file's tree, in the host's units: compiling it gives a prefab that writes the same tree. */
    public static Map<String, Object> write(CgPrefab prefab) {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("_v", 1);
        List<Object> nodes = new ArrayList<>();
        float[] rest = new float[CgPrefab.REST];
        for (int n = 0; n < prefab.nodes(); n++) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", prefab.elementId(n));
            node.put("name", prefab.name(n));
            if (prefab.parent(n) >= 0) {
                String socket = prefab.parentSocket(n);
                node.put("parent", prefab.name(prefab.parent(n)) + (socket == null ? "" : "#" + socket));
            }
            prefab.rest(n, rest);
            if (rest[0] != 0 || rest[1] != 0 || rest[2] != 0) node.put("location", list(rest, 0, 3));
            if (rest[3] != 0 || rest[4] != 0 || rest[5] != 0 || rest[6] != 1) node.put("rotation", list(rest, 3, 4));
            if (rest[7] != 1 || rest[8] != 1 || rest[9] != 1) node.put("scale", list(rest, 7, 3));
            if (prefab.opacity(n) != 1f) node.put("opacity", prefab.opacity(n));
            if (!prefab.values(n).isEmpty()) {
                Map<String, Object> values = new LinkedHashMap<>();
                for (Map.Entry<String, float[]> v : prefab.values(n).entrySet()) values.put(v.getKey(), number(v.getValue()));
                node.put("values", values);
            }
            CgPrefab.Skeletal skeletal = prefab.skeletal(n);
            if (skeletal != null) {
                if (skeletal.path() == null) {
                    throw new IllegalArgumentException(prefab.id() + "'s node " + prefab.name(n) + " is a skeletal mesh made in code, which has no file");
                }
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("mesh", skeletal.path());
                if (skeletal.play() != null) s.put("play", skeletal.play());
                if (skeletal.followPose() != null) s.put("follow_pose", skeletal.followPose());
                node.put("skeletal", s);
                List<Object> ids = new ArrayList<>();
                for (CgMaterialDefinition m : skeletal.materials()) ids.add(m.id());
                node.put("materials", ids);
            }
            CgPrefab.Effect effect = prefab.effect(n);
            if (effect != null) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("effect", effect.effect());
                if (!effect.auto()) e.put("auto", false);
                node.put("effect", e);
            }
            CgPrefab.Post post = prefab.post(n);
            if (post != null) {
                Map<String, Object> p = new LinkedHashMap<>();
                Map<String, Object> settings = new LinkedHashMap<>(CgPostBlock.block(post.settings()));
                if (!post.materials().isEmpty()) {
                    List<Object> entries = new ArrayList<>();
                    for (CgPostBlendable m : post.materials()) entries.add(m.entry());
                    settings.put("materials", entries);
                }
                p.put("settings", settings);
                if (!post.unbound()) p.put("radius", post.radius());
                if (post.blend() != 0f) p.put("blend", post.blend());
                if (post.weight() != 1f) p.put("weight", post.weight());
                if (post.priority() != 0) p.put("priority", post.priority());
                node.put("post", p);
            }
            CgPrefab.Fog fog = prefab.fog(n);
            if (fog != null) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("density", fog.density() * 1000f);
                f.put("height_falloff", fog.falloff() * 1000f);
                f.put("inscattering_colour", List.of(fog.red(), fog.green(), fog.blue()));
                f.put("max_opacity", fog.maxOpacity());
                if (fog.start() != 0f) f.put("start_distance", fog.start());
                if (fog.cutoff() != 0f) f.put("cutoff_distance", fog.cutoff());
                node.put("fog", f);
            }
            CgPrefab.Light light = prefab.light(n);
            if (light != null) {
                Map<String, Object> l = new LinkedHashMap<>();
                int c = light.argb();
                l.put("kind", light.kind().name().toLowerCase(Locale.ROOT));
                List<Object> colour = new ArrayList<>(List.of((c >> 16 & 255) / 255f, (c >> 8 & 255) / 255f, (c & 255) / 255f));
                if ((c >>> 24) != 255) colour.add((c >>> 24) / 255f);
                l.put("colour", colour);
                l.put("intensity", light.intensity());
                l.put("radius", light.radius());
                if (light.exponent() != 0f) l.put("exponent", light.exponent());
                if (light.sourceRadius() != 0f) l.put("source_radius", light.sourceRadius());
                if (!light.castsShadow()) l.put("shadows", false);
                if (light.volumetric() != 1f) l.put("volumetric", light.volumetric());
                if (light.kind() == CgPrefab.Light.Kind.SPOT) l.put("cone", List.of(light.inner(), light.outer()));
                if (light.kind() == CgPrefab.Light.Kind.TUBE) l.put("length", light.length());
                node.put("light", l);
            }
            List<Object> meshes = new ArrayList<>();
            for (int k = prefab.firstPart(n); k < prefab.firstPart(n + 1); k++) {
                String path = prefab.partPath(k);
                if (path == null) throw new IllegalArgumentException(prefab.id() + "'s node " + prefab.name(n) + " draws a mesh made in code, which has no file");
                Map<String, Object> slot = new LinkedHashMap<>();
                slot.put("mesh", path);
                List<CgMaterialDefinition> materials = prefab.partMaterials(k);
                if (materials.size() == 1) {
                    slot.put("material", materials.get(0).id());
                } else {
                    List<Object> ids = new ArrayList<>();
                    for (CgMaterialDefinition m : materials) ids.add(m.id());
                    slot.put("materials", ids);
                }
                slot.putAll(prefab.partSettings(k));
                meshes.add(slot);
            }
            if (!meshes.isEmpty()) node.put("meshes", meshes);
            nodes.add(node);
        }
        tree.put("nodes", nodes);
        return tree;
    }

    /** A value as a file writes it: a number, or a list of them. */
    static Object number(float[] v) {
        if (v.length == 1) return v[0];
        List<Object> out = new ArrayList<>(v.length);
        for (float f : v) out.add(f);
        return out;
    }

    private static List<Object> list(float[] v, int from, int n) {
        List<Object> out = new ArrayList<>(n);
        for (int i = from; i < from + n; i++) out.add(v[i]);
        return out;
    }

    private static float[] floats(Object v, String where) {
        if (v instanceof Number n) return new float[] {n.floatValue()};
        if (v instanceof List<?> l) {
            float[] out = new float[l.size()];
            for (int i = 0; i < out.length; i++) out[i] = ((Number) l.get(i)).floatValue();
            return out;
        }
        throw new CgAssetException(where + ": " + v + " is not a number or a list of them");
    }

    private static List<?> vector(Object v, String where) {
        if (v instanceof List<?> l && l.size() == 3) return l;
        throw new CgAssetException(where + ": " + v + " is not three numbers");
    }

    private static float[] three(Object v) {
        List<?> l = (List<?>) v;
        return new float[] {((Number) l.get(0)).floatValue(), ((Number) l.get(1)).floatValue(), ((Number) l.get(2)).floatValue()};
    }
}
