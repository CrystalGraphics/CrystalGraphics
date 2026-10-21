package com.crystalgraphics.vfx.particle.renderer;

import com.crystalgraphics.settings.CgQuality;
import com.crystalgraphics.vfx.look.CgVfxFlipbook;
import com.crystalgraphics.vfx.particle.renderer.CgVfxRendererProperties.Common;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * An emitter's renderers, compiled from its file's {@code renderers:} and built in Java to the same tree.
 *
 * <pre>{@code
 * // From a file: each entry of an emitter's renderers:
 * List<CgVfxRendererProperties> renderers = CgVfxRenderers.compileAll("sparks", tree.get("renderers"));
 *
 * // In Java: the builder writes the entry a file would hold, then compiles it
 * CgVfxRendererProperties sprite = CgVfxRenderers.sprite("r1").material("crystalgraphics:materials/spark")
 *         .bind("_Color", "user.ember").value("_Soft", 0.5).alignment("velocity").build();
 * CgVfxRendererProperties tails = CgVfxRenderers.trails("r2").material("crystalgraphics:materials/streaks")
 *         .field("points", 12).build();
 * }</pre>
 *
 * <ul>
 *   <li>Built in Java or read from a file, a renderer is its tree: {@link CgVfxRendererProperties#tree()} writes
 *       back exactly what compiled it.</li>
 *   <li>{@code system}, {@code anim_trail}, {@code beam} and {@code light} fail at compile, naming what they need.</li>
 *   <li>A flipbook's {@code mode: direct} fails too: no module sets its frame yet.</li>
 * </ul>
 */
public final class CgVfxRenderers {

    private static final String[] COMMON_KEYS = {"enabled", "quality", "sort_order_hint", "motion_vectors", "material",
            "bind", "values", "keywords"};

    private CgVfxRenderers() {
    }

    /** Every entry of an emitter's {@code renderers:}, in order; an absent list is empty. */
    public static List<CgVfxRendererProperties> compileAll(String emitter, @Nullable Object renderers) {
        List<CgVfxRendererProperties> out = new ArrayList<>();
        if (renderers instanceof List<?> list) {
            for (Object entry : list) {
                if (!(entry instanceof Map<?, ?> m)) throw new IllegalArgumentException(emitter + ": a renderer is a map, not " + entry);
                out.add(compile(emitter, m));
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** One {@code renderers:} entry of {@code emitter}. */
    public static CgVfxRendererProperties compile(String emitter, Map<?, ?> entry) {
        Map<String, Object> tree = copy(entry);
        String kind = String.valueOf(tree.get("kind"));
        String who = emitter + "'s renderer " + tree.get("id");
        Common common = common(who, tree);
        return switch (kind) {
            case "sprite" -> new CgVfxSpriteRendererProperties(common, tree,
                    choice(who, tree, "alignment", CgVfxSpriteRendererProperties.Alignment.class, "unaligned"),
                    choice(who, tree, "facing", CgVfxSpriteRendererProperties.Facing.class, "camera"),
                    floats(tree.get("facing_blend"), 2), sort(who, tree),
                    tree.get("pivot") != null ? floats(tree.get("pivot"), 2) : new float[] {0.5f, 0.5f},
                    flipbook(who, tree.get("sub_image")), floats(tree.get("camera_distance"), 2),
                    Boolean.TRUE.equals(tree.get("cast_shadows")),
                    tree.get("soft_depth") instanceof Number n ? n.floatValue() : Float.NaN);
            case "mesh" -> mesh(who, common, tree);
            case "ribbon" -> new CgVfxRibbonRendererProperties(common, tree,
                    choice(who, tree, "facing", CgVfxRibbonRendererProperties.Facing.class, "screen"),
                    choice(who, tree, "shape", CgVfxRibbonRendererProperties.Shape.class, "plane"),
                    integer(tree.get("width_segments")), integer(tree.get("max_ribbons")), map(tree.get("uv0")),
                    map(tree.get("uv1")), map(tree.get("tessellation")), Boolean.TRUE.equals(tree.get("link_order_by_id")),
                    integer(tree.get("max_link_gap")));
            case "arc" -> new CgVfxArcRendererProperties(common, tree, integer(tree.get("subdivisions")));
            case "trails" -> {
                int points = tree.get("points") instanceof Number n ? n.intValue() : 8;
                if (points < 2 || points > 64) throw new IllegalArgumentException(who + " keeps " + points + " points; 2 to 64");
                yield new CgVfxTrailsRendererProperties(common, tree, points, integer(tree.get("subdivisions")),
                        tree.get("taper") instanceof Number n ? n.floatValue() : 1f);
            }
            case "sequence" -> {
                if (!(tree.get("sequence") instanceof String sequence)) throw new IllegalArgumentException(who + " names no sequence");
                yield new CgVfxSequenceRendererProperties(common, tree, sequence,
                        tree.get("play_rate") instanceof Number n ? n.floatValue() : 1f);
            }
            case "system" -> throw new IllegalArgumentException(who + ": a system renderer places analytic emitters, which are not built yet");
            case "anim_trail" -> throw new IllegalArgumentException(who + ": an anim trail renderer is not built yet");
            case "beam" -> throw new IllegalArgumentException(who + ": a beam renderer needs the beam modules, which are not built yet");
            case "light" -> throw new IllegalArgumentException(who + ": a light renderer needs the clustered light bins, which are not built yet");
            default -> throw new IllegalArgumentException(who + " has no kind '" + kind + "'");
        };
    }

    private static Common common(String who, Map<String, Object> tree) {
        if (!(tree.get("id") instanceof String id)) throw new IllegalArgumentException(who + " has no id");
        CgQuality quality = tree.get("quality") instanceof String q ? CgQuality.valueOf(q.toUpperCase(Locale.ROOT)) : null;
        String material = null, binding = null;
        Object m = tree.get("material");
        if (m instanceof String s) material = s;
        else if (m instanceof Map<?, ?> b && b.get("bind") instanceof String s) binding = s;
        else if (m != null) throw new IllegalArgumentException(who + "'s material is an asset or { bind: user.x }, not " + m);
        List<String> keywords = new ArrayList<>();
        if (tree.get("keywords") instanceof List<?> list) for (Object k : list) keywords.add(String.valueOf(k));
        return new Common(id, !Boolean.FALSE.equals(tree.get("enabled")), quality, integer(tree.get("sort_order_hint")),
                Boolean.TRUE.equals(tree.get("motion_vectors")), material, binding, stringKeyed(tree.get("bind")),
                stringKeyed(tree.get("values")), Collections.unmodifiableList(keywords));
    }

    private static CgVfxMeshRendererProperties mesh(String who, Common common, Map<String, Object> tree) {
        List<Map<String, Object>> meshes = new ArrayList<>();
        String prefab = null, binding = null;
        Object m = tree.get("meshes");
        if (m instanceof List<?> list) {
            for (Object e : list) meshes.add(copy((Map<?, ?>) e));
        } else if (m instanceof Map<?, ?> one && one.get("prefab") instanceof String p) {
            prefab = p;
        } else if (m instanceof Map<?, ?> one && one.get("bind") instanceof String b) {
            binding = b;
        } else if (m != null) {
            throw new IllegalArgumentException(who + "'s meshes are a list, { prefab } or { bind }, not " + m);
        }
        List<String> overrides = new ArrayList<>();
        if (tree.get("material_overrides") instanceof List<?> list) for (Object o : list) overrides.add(String.valueOf(o));
        return new CgVfxMeshRendererProperties(common, tree, Collections.unmodifiableList(meshes), prefab, binding,
                Collections.unmodifiableList(overrides),
                choice(who, tree, "facing", CgVfxMeshRendererProperties.Facing.class, "default"), map(tree.get("locked_axis")),
                sort(who, tree), flipbook(who, tree.get("sub_image")), floats(tree.get("camera_distance"), 2),
                Boolean.TRUE.equals(tree.get("cast_shadows")));
    }

    private static CgVfxSpriteRendererProperties.Sort sort(String who, Map<String, Object> tree) {
        return choice(who, tree, "sort", CgVfxSpriteRendererProperties.Sort.class, "none");
    }

    /** A renderer's {@code sub_image} as its flipbook; null for none. */
    @Nullable
    static CgVfxFlipbook flipbook(String who, @Nullable Object subImage) {
        if (!(subImage instanceof Map<?, ?> s)) return null;
        CgVfxFlipbook f;
        if (s.get("rects") instanceof List<?> rects) {
            float[] all = new float[rects.size() * 4];
            for (int i = 0; i < rects.size(); i++) System.arraycopy(floats(rects.get(i), 4), 0, all, i * 4, 4);
            f = CgVfxFlipbook.rects(all);
        } else {
            float[] size = floats(s.get("size"), 2);
            if (size == null) throw new IllegalArgumentException(who + "'s sub_image has neither size nor rects");
            f = CgVfxFlipbook.grid((int) size[0], (int) size[1]);
        }
        float rate = s.get("rate") instanceof Number n ? n.floatValue() : 0f;
        String mode = s.get("mode") instanceof String m ? m : "linear";
        f = switch (mode) {
            case "linear" -> f.linear();
            case "loop" -> f.loop(rate);
            case "random" -> f.random(rate);
            case "direct" -> throw new IllegalArgumentException(who + "'s sub_image mode direct needs a module setting its frame, not built yet");
            default -> throw new IllegalArgumentException(who + "'s sub_image has no mode '" + mode + "'");
        };
        if (s.get("frames") instanceof List<?> frames && frames.size() == 2) {
            f = f.frames(((Number) frames.get(0)).intValue(), ((Number) frames.get(1)).intValue());
        }
        f = f.blend(Boolean.TRUE.equals(s.get("blend")));
        return Boolean.TRUE.equals(s.get("random_start")) ? f.randomStart() : f;
    }

    private static <E extends Enum<E>> E choice(String who, Map<String, Object> tree, String key, Class<E> type, String fallback) {
        String value = tree.get(key) instanceof String s ? s : fallback;
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(who + " has no " + key + " '" + value + "'");
        }
    }

    @Nullable
    private static float[] floats(@Nullable Object value, int n) {
        if (!(value instanceof List<?> list)) return null;
        float[] out = new float[n];
        for (int i = 0; i < Math.min(n, list.size()); i++) out[i] = ((Number) list.get(i)).floatValue();
        return out;
    }

    private static int integer(@Nullable Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    @Nullable
    private static Map<String, Object> map(@Nullable Object value) {
        return value instanceof Map<?, ?> m ? copy(m) : null;
    }

    private static Map<String, Object> stringKeyed(@Nullable Object value) {
        return value instanceof Map<?, ?> m ? Collections.unmodifiableMap(copy(m)) : Map.of();
    }

    private static Map<String, Object> copy(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
        return out;
    }

    // ── Java ─────────────────────────────────────────────────────────────────────────────

    /** A sprite renderer of id {@code id}. */
    public static Builder sprite(String id) {
        return new Builder(id, "sprite");
    }

    /** A mesh renderer of id {@code id}. */
    public static Builder mesh(String id) {
        return new Builder(id, "mesh");
    }

    /** A ribbon renderer of id {@code id}. */
    public static Builder ribbon(String id) {
        return new Builder(id, "ribbon");
    }

    /** An arc renderer of id {@code id}. */
    public static Builder arc(String id) {
        return new Builder(id, "arc");
    }

    /** A trails renderer of id {@code id}. */
    public static Builder trails(String id) {
        return new Builder(id, "trails");
    }

    /** A sequence renderer of id {@code id}, each particle playing {@code sequence}. */
    public static Builder sequence(String id, String sequence) {
        return new Builder(id, "sequence").field("sequence", sequence);
    }

    /**
     * Writes a {@code renderers:} entry as a file would hold it, then compiles it: a renderer built in Java is the
     * renderer its file loads.
     *
     * <ul>
     *   <li>{@link #field} sets any of the kind's fields by its file key; the named setters are the common ones.</li>
     *   <li>A key the kind does not declare is refused when the emitter's asset validates.</li>
     * </ul>
     */
    public static final class Builder {

        private final Map<String, Object> tree = new LinkedHashMap<>();
        private final Map<String, Object> bind = new LinkedHashMap<>(), values = new LinkedHashMap<>();
        private final List<String> keywords = new ArrayList<>();

        private Builder(String id, String kind) {
            tree.put("id", id);
            tree.put("kind", kind);
        }

        /** Its material asset. */
        public Builder material(String material) {
            tree.put("material", material);
            return this;
        }

        /** Its material from a user parameter: {@code user.<name>}. */
        public Builder materialFrom(String userParameter) {
            tree.put("material", Map.of("bind", userParameter));
            return this;
        }

        /** Drawn from {@code quality} up. */
        public Builder quality(CgQuality quality) {
            tree.put("quality", quality.name().toLowerCase(Locale.ROOT));
            return this;
        }

        /** Its place among its emitter's renderers. */
        public Builder sortOrderHint(int hint) {
            tree.put("sort_order_hint", hint);
            return this;
        }

        /** Not drawn. */
        public Builder disabled() {
            tree.put("enabled", false);
            return this;
        }

        /** Material parameter {@code parameter} read per draw from {@code source}: {@code user.x} or {@code particles.x}. */
        public Builder bind(String parameter, String source) {
            bind.put(parameter, source);
            return this;
        }

        /** Material parameter {@code parameter} held at {@code value} on this renderer alone. */
        public Builder value(String parameter, Object value) {
            values.put(parameter, value);
            return this;
        }

        /** A material keyword it enables. */
        public Builder keyword(String keyword) {
            keywords.add(keyword);
            return this;
        }

        /** A sprite's alignment: {@code unaligned}, {@code velocity} or {@code custom}. */
        public Builder alignment(String alignment) {
            return field("alignment", alignment);
        }

        /** A sprite's or mesh's facing, by its file name. */
        public Builder facing(String facing) {
            return field("facing", facing);
        }

        /** A sprite's or mesh's flipbook, its {@code sub_image} map. */
        public Builder subImage(Map<String, Object> subImage) {
            return field("sub_image", subImage);
        }

        /** Any field of its kind, by its file key. */
        public Builder field(String key, Object value) {
            tree.put(key, value);
            return this;
        }

        /** The renderer, compiled from the entry written. */
        public CgVfxRendererProperties build() {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", tree.get("id"));
            entry.put("kind", tree.get("kind"));
            for (String key : COMMON_KEYS) {
                switch (key) {
                    case "bind" -> { if (!bind.isEmpty()) entry.put("bind", new LinkedHashMap<>(bind)); }
                    case "values" -> { if (!values.isEmpty()) entry.put("values", new LinkedHashMap<>(values)); }
                    case "keywords" -> { if (!keywords.isEmpty()) entry.put("keywords", new ArrayList<>(keywords)); }
                    default -> { if (tree.containsKey(key)) entry.put(key, tree.get(key)); }
                }
            }
            for (Map.Entry<String, Object> e : tree.entrySet()) entry.putIfAbsent(e.getKey(), e.getValue());
            return compile("java", entry);
        }
    }
}
