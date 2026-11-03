package com.crystalgraphics.render.post.stage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.kind.CgStageKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.object.value.CgQuantity;
import com.crystalgraphics.object.value.CgUnits;
import com.crystalgraphics.render.post.volume.CgPostBlendable;
import com.crystalgraphics.render.post.volume.CgPostBlock;
import com.crystalgraphics.render.post.volume.CgPostSettings;

/**
 * A stage asset compiled ({@code stages/<name>.yaml}): a game level's environment, its post volumes and its height fog,
 * in the host's blocks and axes, every place measured from its {@code anchor}. {@link CgStage#place} puts it over the
 * host's world.
 *
 * <pre>{@code
 * static final CgAssetRef<CgStageDefinition> ARENA = CgVfxAssets.ref(CgStageKinds.STAGE, "mymod:arena");
 * ARENA.request(CgLoadPriority.MOVE);                      // ahead: its materials and fog compiled and warmed
 * CgStage stage = CgStage.place(ARENA.get(), x, y, z);    // its anchor at (x, y, z)
 * stage.close();
 * }</pre>
 *
 * <pre>{@code
 * # assets/mymod/stages/arena.yaml
 * _v: 1
 * anchor: [0, 0, 7849]
 * volumes:
 *   - post: { tonemapper: legacy, vignette: 0.5,
 *             materials: [ { material: mymod:post/overlay, values: { SunVector: [0.8, -0.5, 0.3, 0] } } ] }
 *     unbound: true
 * fog: { density: 0.002, height_falloff: 0.1, height: 15799, inscattering_colour: [0.41, 0.89, 1], max_opacity: 0.65 }
 * }</pre>
 *
 * <p>The same stage in Java: {@link Builder#tree()} is exactly its file.
 * <pre>{@code
 * CgStageDefinition.Builder arena = CgStageDefinition.builder("mymod:arena").anchor(0, 0, 7849)
 *         .volume(new CgPostSettings().tonemap().vignette(0.5f), v -> v.unbound()
 *                 .material("mymod:post/overlay", Map.of("SunVector", List.of(0.8, -0.5, 0.3, 0))))
 *         .fog(f -> f.density(0.002).heightFalloff(0.1).height(15799).inscatteringColour(0.41, 0.89, 1).maxOpacity(0.65));
 * String yaml = CgAssetYaml.write(arena.tree(), CgStageKinds.STAGE);     // the file above
 * CgStageDefinition stage = arena.build(deps);                            // its materials from deps
 * }</pre>
 *
 * <ul>
 *   <li>A post material's {@code values} and {@code textures} make it a child of its material, checked at load.</li>
 *   <li>A fractional {@code priority} or a {@code screen_point} fails at load, naming it: nothing in a level is
 *       followed on screen.</li>
 *   <li>A dedicated server compiles it with no post materials: it draws nothing there.</li>
 * </ul>
 *
 * @param volumes its post volumes, in file order
 * @param fog     its height fog, or null for none
 */
public record CgStageDefinition(String id, List<Volume> volumes, @Nullable Fog fog) {

    /** The stage kind's compiler. */
    public static final CgAssetCompiler<CgStageDefinition> COMPILER = CgStageDefinition::compile;

    /** Sets {@link #COMPILER} as the stage kind's. {@code CgBuiltInCompilers.register} calls it. */
    public static void register() {
        CgAssetCompiler.register(CgStageKinds.STAGE, COMPILER);
    }

    /**
     * A post volume of the level.
     *
     * @param settings   its post block, its materials aside
     * @param priority   its whole priority
     * @param unbound    everywhere, or round {@code x, y, z}
     * @param x          where a bound one stands, in blocks from the anchor
     * @param radius     full within this many blocks
     * @param blend      fading over this many past it
     * @param blendables its post materials
     */
    public record Volume(CgPostSettings settings, int priority, boolean unbound, double x, double y, double z,
                         float radius, float blend, List<CgPostBlendable> blendables) {
    }

    /**
     * Its height fog in the host's units: {@code CgHeightFog}'s values.
     *
     * @param density per block: the component's {@code FogDensity} over 1000, as Unreal divides it
     * @param falloff per block: the component's {@code FogHeightFalloff} over 1000
     * @param height  the height its density is given at, in blocks above the anchor
     */
    public record Fog(float density, float falloff, double height, float red, float green, float blue, float maxOpacity,
                      float start, float cutoff) {
    }

    /** A stage built in Java, as its file would write it, in the game's units as the file is. */
    public static Builder builder(String id) {
        return new Builder(id);
    }

    /**
     * Writes a stage's file tree from Java: what {@code assets/<ns>/stages/<name>.yaml} holds, never a second model.
     * Places and lengths are the file's: the kit's units and axes.
     */
    public static final class Builder {
        private final String id;
        private final Map<String, Object> tree = new LinkedHashMap<>();
        private final List<Object> volumes = new ArrayList<>();

        private Builder(String id) {
            this.id = id;
        }

        /** The game's point a placement lands on. The origin unless given. */
        public Builder anchor(double x, double y, double z) {
            tree.put("anchor", List.of(x, y, z));
            return this;
        }

        /** A post volume of {@code post}'s looks, unbound or placed as {@code volume} says. */
        public Builder volume(CgPostSettings post, Consumer<VolumeBuilder> volume) {
            VolumeBuilder v = new VolumeBuilder(post);
            volume.accept(v);
            volumes.add(v.tree());
            return this;
        }

        /** Its height fog, at Unreal's component defaults where {@code fog} sets nothing. */
        public Builder fog(Consumer<FogBuilder> fog) {
            FogBuilder f = new FogBuilder();
            fog.accept(f);
            tree.put("fog", f.tree);
            return this;
        }

        /** Its file's tree, as {@code CgAssetYaml.write} writes it. */
        public Map<String, Object> tree() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("_v", 1);
            if (tree.containsKey("anchor")) out.put("anchor", tree.get("anchor"));
            if (!volumes.isEmpty()) out.put("volumes", new ArrayList<>(volumes));
            if (tree.containsKey("fog")) out.put("fog", tree.get("fog"));
            return out;
        }

        /** Compiled, its post materials from {@code deps}; the asset loader compiles a file with them. */
        public CgStageDefinition build(CgAssetCompiler.Dependencies deps) {
            return compile(CgAssetData.of(id, CgStageKinds.STAGE, tree(), null), deps);
        }
    }

    /** One post volume in Java. */
    public static final class VolumeBuilder {
        private final Map<String, Object> tree = new LinkedHashMap<>();
        private final Map<String, Object> post;
        private final List<Object> materials = new ArrayList<>();

        private VolumeBuilder(CgPostSettings post) {
            this.post = new LinkedHashMap<>(CgPostBlock.block(post));
        }

        /** Standing round the game's point {@code (x, y, z)}, full within {@code radius}, fading over {@code blend}. */
        public VolumeBuilder at(double x, double y, double z, double radius, double blend) {
            tree.put("at", List.of(x, y, z));
            tree.put("radius", radius);
            tree.put("blend_radius", blend);
            return this;
        }

        public VolumeBuilder priority(int priority) {
            tree.put("priority", priority);
            return this;
        }

        /** Everywhere, as a level's unbound volume. */
        public VolumeBuilder unbound() {
            tree.put("unbound", true);
            return this;
        }

        /** A post material at full weight. */
        public VolumeBuilder material(String material) {
            return material(material, Map.of());
        }

        /** A post material made a dynamic instance by {@code values}: its parameters by name, a number or a list. */
        public VolumeBuilder material(String material, Map<String, ?> values) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("material", material);
            if (!values.isEmpty()) entry.put("values", new LinkedHashMap<>(values));
            materials.add(entry);
            return this;
        }

        private Map<String, Object> tree() {
            Map<String, Object> out = new LinkedHashMap<>();
            if (tree.containsKey("at")) out.put("at", tree.get("at"));
            Map<String, Object> block = new LinkedHashMap<>(post);
            if (!materials.isEmpty()) block.put("materials", new ArrayList<>(materials));
            out.put("post", block);
            for (String k : List.of("priority", "unbound", "radius", "blend_radius")) if (tree.containsKey(k)) out.put(k, tree.get(k));
            return out;
        }
    }

    /** The fog in Java: the component's fields, in the file's units. */
    public static final class FogBuilder {
        private final Map<String, Object> tree = new LinkedHashMap<>();

        private FogBuilder() {
        }

        /** The component's {@code FogDensity}: Unreal divides it by 1000. */
        public FogBuilder density(double density) {
            tree.put("density", density);
            return this;
        }

        /** The component's {@code FogHeightFalloff}: Unreal divides it by 1000. */
        public FogBuilder heightFalloff(double falloff) {
            tree.put("height_falloff", falloff);
            return this;
        }

        /** The actor's height, the game's up. */
        public FogBuilder height(double height) {
            tree.put("height", height);
            return this;
        }

        /** Linear. */
        public FogBuilder inscatteringColour(double r, double g, double b) {
            tree.put("inscattering_colour", List.of(r, g, b));
            return this;
        }

        public FogBuilder maxOpacity(double opacity) {
            tree.put("max_opacity", opacity);
            return this;
        }

        public FogBuilder startDistance(double distance) {
            tree.put("start_distance", distance);
            return this;
        }

        /** 0 for none. */
        public FogBuilder cutoffDistance(double distance) {
            tree.put("cutoff_distance", distance);
            return this;
        }
    }

    /** {@code asset} compiled, its references from {@code deps}, its values in {@code deps.units()}. */
    public static CgStageDefinition compile(CgAssetData asset, CgAssetCompiler.Dependencies deps) {
        Map<Object, Object> tree = asset.resolved();
        String id = asset.id();
        CgUnits units = deps.units();
        double[] anchor = position(units, tree.get("anchor"));
        List<Volume> volumes = new ArrayList<>();
        if (tree.get("volumes") instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                String at = id + ": volumes[" + i + "]";
                volumes.add(volume((Map<?, ?>) list.get(i), at, anchor, units, deps));
            }
        }
        Fog fog = tree.get("fog") instanceof Map<?, ?> f ? fog(f, anchor, units) : null;
        return new CgStageDefinition(id, List.copyOf(volumes), fog);
    }

    private static Volume volume(Map<?, ?> v, String at, double[] anchor, CgUnits units, CgAssetCompiler.Dependencies deps) {
        float priority = number(v.get("priority"), 0f);
        if (priority != Math.rint(priority)) throw new CgAssetException(at + ".priority: " + priority + " is not a whole priority");
        Map<?, ?> post = v.get("post") instanceof Map<?, ?> p ? p : Map.of();
        List<CgPostBlendable> blendables = new ArrayList<>();
        if (post.get("materials") instanceof List<?> materials) {
            for (int i = 0; i < materials.size(); i++) {
                blendables.add(CgPostBlendable.of((Map<?, ?>) materials.get(i), at + ".post.materials[" + i + "]", deps, null));
            }
        }
        Map<Object, Object> look = new LinkedHashMap<>(post);
        look.remove("materials");
        double[] place = v.get("at") == null ? anchor.clone() : position(units, v.get("at"));
        return new Volume(CgPostBlock.settings(look), (int) priority, Boolean.TRUE.equals(v.get("unbound")),
                place[0] - anchor[0], place[1] - anchor[1], place[2] - anchor[2],
                length(units, v.get("radius")), length(units, v.get("blend_radius")), List.copyOf(blendables));
    }

    /** The component's fields to {@code CgHeightFog}'s: Unreal's / 1000 (SceneCore.cpp), then per block. */
    private static Fog fog(Map<?, ?> f, double[] anchor, CgUnits units) {
        float density = (float) perLength(units, number(f.get("density"), 0.02f) / 1000.0);
        float falloff = (float) perLength(units, number(f.get("height_falloff"), 0.2f) / 1000.0);
        // The fog's height is the game's up, the anchor's host Y
        double height = (f.get("height") instanceof Number h ? ((Number) units.toHost(h, CgQuantity.LENGTH)).doubleValue() : 0) - anchor[1];
        double[] colour = f.get("inscattering_colour") instanceof List<?> c ? vector(c) : new double[] {0.447, 0.638, 1.0};
        return new Fog(density, falloff, height, (float) colour[0], (float) colour[1], (float) colour[2],
                number(f.get("max_opacity"), 1f), length(units, f.get("start_distance")), length(units, f.get("cutoff_distance")));
    }

    private static double perLength(CgUnits units, double v) {
        return ((Number) units.toHost(v, CgQuantity.PER_LENGTH)).doubleValue();
    }

    private static float length(CgUnits units, @Nullable Object v) {
        return v instanceof Number ? ((Number) units.toHost(v, CgQuantity.LENGTH)).floatValue() : 0f;
    }

    /** A point of the game's world in the host's axes and blocks; absent is the origin, a number each axis's. */
    private static double[] position(CgUnits units, @Nullable Object v) {
        if (v == null) return new double[3];
        return vector(units.toHost(v instanceof Number n ? List.of(n, n, n) : v, CgQuantity.POSITION));
    }

    private static double[] vector(Object v) {
        List<?> list = (List<?>) v;
        return new double[] {((Number) list.get(0)).doubleValue(), ((Number) list.get(1)).doubleValue(),
                ((Number) list.get(2)).doubleValue()};
    }

    private static float number(@Nullable Object v, float fallback) {
        return v instanceof Number n ? n.floatValue() : fallback;
    }
}
