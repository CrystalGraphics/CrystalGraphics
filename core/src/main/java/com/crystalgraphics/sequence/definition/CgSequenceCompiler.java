package com.crystalgraphics.sequence.definition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.annotation.Nullable;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.material.CgParameterCollection;
import com.crystalgraphics.asset.curve.CgRichCurve;
import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.easing.CgEasing;
import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.object.CgProperty;
import com.crystalgraphics.object.CgType;
import com.crystalgraphics.object.CgTypes;
import com.crystalgraphics.object.validate.CgIssue;
import com.crystalgraphics.object.validate.CgValidation;
import com.crystalgraphics.object.value.CgQuantity;
import com.crystalgraphics.object.value.CgUnits;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition.Child;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition.CompositeSection;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition.Locator;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition.Role;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition.SectionRef;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition.Spawnable;
import com.crystalgraphics.sequence.time.CgFrameRate;
import com.crystalgraphics.sequence.notify.CgNotifyKind;

/** Compiles a resolved, validated {@code sequence} into its {@link CgSequenceDefinition}. One per compile. */
final class CgSequenceCompiler {

    /** How far a baked weighted segment may stray from the solved one, relative to its ends. */
    private static final float WEIGHTED_TOLERANCE = 1e-4f;

    /** A section's keys every kind shares, read into its record rather than its channels or fields. */
    private static final Set<String> SECTION_KEYS = Set.of("id", "range", "start", "condition", "row", "overlap_priority",
            "blend", "ease", "completion", "pre_roll", "post_roll", "active", "editor", "unsupported");

    private static final String[] AXES = {"x", "y", "z"};
    private static final String[] CHANNEL_AXES = {"x", "y", "z", "r", "g", "b", "a"};

    private final String id;
    private final CgUnits units;
    private final CgAssetCompiler.Dependencies deps;
    private final List<Role> roles = new ArrayList<>();
    private final Map<String, Integer> roleByName = new HashMap<>(), roleById = new HashMap<>();
    private final Map<String, Integer> locatorById = new HashMap<>(), spawnableById = new HashMap<>();
    private final List<String> spawnableKinds = new ArrayList<>();
    /** The playback range: what a section of all time is measured by, for automatic easing. */
    private int start, end;

    private CgSequenceCompiler(String id, CgAssetCompiler.Dependencies deps) {
        this.id = id;
        this.deps = deps;
        this.units = deps.units();
    }

    static CgSequenceDefinition compile(CgAssetData asset, CgAssetCompiler.Dependencies deps) {
        CgValidation v = asset.validate(CgValidation.Usecase.LOAD);
        if (!v.valid()) {
            for (CgIssue issue : v.issues()) {
                if (issue.severity() == CgIssue.Severity.ERROR) throw new CgAssetException(asset.id() + ": " + issue.path() + ": " + issue.message());
            }
        }
        return new CgSequenceCompiler(asset.id(), deps).compile(asset.resolved());
    }

    private CgSequenceDefinition compile(Map<Object, Object> tree) {
        CgFrameRate tick = rate(tree, "tick_resolution"), display = rate(tree, "display_rate");
        if (!(tree.get("range") instanceof List<?> range) || range.size() != 2
                || !(range.get(0) instanceof Number a) || !(range.get(1) instanceof Number b) || b.intValue() < a.intValue()) {
            throw fail("range", "two increasing ticks, not " + tree.get("range"));
        }
        start = a.intValue();
        end = b.intValue();
        CgSequenceDefinition.Evaluation evaluation = "frame_locked".equals(tree.get("evaluation"))
                ? CgSequenceDefinition.Evaluation.FRAME_LOCKED : CgSequenceDefinition.Evaluation.SUB_FRAMES;

        for (Map<?, ?> r : elements(tree, "roles")) {
            String rid = string(r.get("id")), name = string(r.get("name"));
            roleByName.put(name, roles.size());
            roleById.put(rid, roles.size());
            roles.add(new Role(rid, name, string(r.get("kind")), (String) r.get("character"),
                    Boolean.TRUE.equals(r.get("seed")), "skip".equals(r.get("absent"))));
        }
        List<Map<?, ?>> locatorTrees = elements(tree, "locators"), spawnableTrees = elements(tree, "spawnables");
        for (Map<?, ?> l : locatorTrees) locatorById.put(string(l.get("id")), locatorById.size());
        for (Map<?, ?> s : spawnableTrees) {
            spawnableById.put(string(s.get("id")), spawnableById.size());
            spawnableKinds.add(spawnableKind(s));
        }
        List<Locator> locators = new ArrayList<>();
        for (Map<?, ?> l : locatorTrees) {
            String lid = string(l.get("id"));
            locators.add(new Locator(lid, (String) l.get("name"), binding(l.get("parent"), "locators." + lid + ".parent"), l));
        }
        List<Spawnable> spawnables = new ArrayList<>();
        for (int i = 0; i < spawnableTrees.size(); i++) {
            Map<?, ?> s = spawnableTrees.get(i);
            String sid = string(s.get("id"));
            Object at = s.get("at");
            String kind = spawnableKinds.get(i);
            // The asset it names is a hard reference, compiled before the sequence.
            CgType named = s.get(kind) instanceof String ? CgTypes.get("crystalgraphics:" + kind) : null;
            Object definition = named != null ? deps.definition(named, (String) s.get(kind))
                    : kind.equals("post") && s.get("post") instanceof Map<?, ?> post ? blendables(post, "spawnables." + sid + ".post") : null;
            spawnables.add(new Spawnable(sid, (String) s.get("name"), kind, binding(at, "spawnables." + sid + ".at"),
                    !(at instanceof Map<?, ?> m) || !Boolean.FALSE.equals(m.get("follow")), s, definition));
        }

        List<CompositeSection> composites = compositeSections(elements(tree, "composite_sections"));

        List<CgSequenceTrack> tracks = new ArrayList<>();
        Map<String, List<SectionRef>> triggered = new LinkedHashMap<>();
        List<Child> children = new ArrayList<>();
        int depth = 0;
        for (Map<?, ?> t : elements(tree, "tracks")) {
            CgSequenceTrack track = track(t, tracks.size());
            tracks.add(track);
            for (CgSequenceSection s : track.sections()) {
                if (s.trigger() != null) triggered.computeIfAbsent(s.trigger(), k -> new ArrayList<>()).add(new SectionRef(track.index(), s.index()));
                if (track.kind().equals("subsequence")) {
                    Child child = child(track, s);
                    if (child != null) {
                        children.add(child);
                        depth = Math.max(depth, child.definition().depth() + 1);
                    }
                }
            }
        }
        if (depth > CgSequenceDefinition.MAX_DEPTH) {
            throw fail("tracks", "subsequences nest " + depth + " deep, past " + CgSequenceDefinition.MAX_DEPTH);
        }
        return new CgSequenceDefinition(id, tick, display, a.intValue(), b.intValue(), evaluation, units,
                Collections.unmodifiableList(roles), Collections.unmodifiableList(locators),
                Collections.unmodifiableList(spawnables), Collections.unmodifiableList(composites),
                Collections.unmodifiableList(tracks), CgSequenceField.build(tracks), Collections.unmodifiableMap(triggered),
                Collections.unmodifiableList(children), depth);
    }

    /** A post block's {@code materials}, each material a hard reference compiled before the sequence. */
    private List<CgSequenceDefinition.Blendable> blendables(Map<?, ?> post, String path) {
        if (!(post.get("materials") instanceof List<?> list)) return List.of();
        List<CgSequenceDefinition.Blendable> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Map<?, ?> m = asMap(list.get(i), path + ".materials[" + i + "]");
            String at = path + ".materials[" + i + "]";
            String id = (String) m.get("material");
            Object d = deps.definition(CgLookKinds.MATERIAL, id);
            if (d == null) throw fail(at + ".material", "no material " + id);
            // Null where materials have no compiler (a dedicated server, a headless gate): it draws nothing there
            CgMaterialDefinition material = d instanceof CgMaterialDefinition m2 ? m2 : null;
            Map<?, ?> screen = m.get("screen_point") instanceof Map<?, ?> s && !Boolean.FALSE.equals(s.get("enabled")) ? s : null;
            out.add(new CgSequenceDefinition.Blendable(m.get("name") instanceof String n ? n : id, material,
                    m.get("weight") instanceof Number w ? w.floatValue() : 1f, !Boolean.FALSE.equals(m.get("enabled")),
                    screen == null ? null : binding(screen.get("target"), at + ".screen_point.target"),
                    screen == null ? null : (String) screen.get("vector"), screen == null ? null : (String) screen.get("x"),
                    screen == null ? null : (String) screen.get("y"), screen != null && Boolean.TRUE.equals(screen.get("clamp"))));
        }
        return List.copyOf(out);
    }

    // ── Bindings ─────────────────────────────────────────────────────────────────────────

    /** A binding or a place as written: a map, {@code environment}, a bare id, or nothing. */
    private CgSequenceBinding binding(@Nullable Object b, String path) {
        if (b == null) return CgSequenceBinding.NONE;
        if (b instanceof String s) {
            if (s.equals("environment")) return CgSequenceBinding.ENVIRONMENT;
            if (roleById.containsKey(s)) return new CgSequenceBinding(CgSequenceBinding.Subject.ROLE, roleById.get(s), CgSequenceBinding.Part.WHOLE, null);
            if (locatorById.containsKey(s)) return new CgSequenceBinding(CgSequenceBinding.Subject.LOCATOR, locatorById.get(s), CgSequenceBinding.Part.WHOLE, null);
            if (spawnableById.containsKey(s)) return new CgSequenceBinding(CgSequenceBinding.Subject.SPAWNABLE, spawnableById.get(s), CgSequenceBinding.Part.WHOLE, null);
            throw fail(path, "no role, locator or spawnable has the id " + s);
        }
        if (!(b instanceof Map<?, ?> m)) throw fail(path, "a binding is a map or an id, not " + b);
        CgSequenceBinding.Subject subject;
        int index;
        if (m.get("role") instanceof String role) {
            subject = CgSequenceBinding.Subject.ROLE;
            index = lookup(roleByName, role, path, "role named");
        } else if (m.get("locator") instanceof String locator) {
            subject = CgSequenceBinding.Subject.LOCATOR;
            index = lookup(locatorById, locator, path, "locator");
        } else if (m.get("spawnable") instanceof String spawnable) {
            subject = CgSequenceBinding.Subject.SPAWNABLE;
            index = lookup(spawnableById, spawnable, path, "spawnable");
        } else if (m.get("camera") instanceof String camera) {
            subject = CgSequenceBinding.Subject.SPAWNABLE;
            index = lookup(spawnableById, camera, path, "camera");
            if (!spawnableKinds.get(index).equals("camera")) throw fail(path, camera + " is a " + spawnableKinds.get(index) + ", not a camera");
        } else {
            return CgSequenceBinding.NONE;
        }
        if (m.get("socket") instanceof String socket) return new CgSequenceBinding(subject, index, CgSequenceBinding.Part.SOCKET, socket);
        if (m.get("node") instanceof String node) return new CgSequenceBinding(subject, index, CgSequenceBinding.Part.NODE, node);
        if (m.get("material") instanceof String material) return new CgSequenceBinding(subject, index, CgSequenceBinding.Part.MATERIAL, material);
        if ("velocity".equals(m.get("value"))) return new CgSequenceBinding(subject, index, CgSequenceBinding.Part.VELOCITY, null);
        return new CgSequenceBinding(subject, index, CgSequenceBinding.Part.WHOLE, null);
    }

    private int lookup(Map<String, Integer> by, String key, String path, String what) {
        Integer i = by.get(key);
        if (i == null) throw fail(path, "no " + what + " " + key);
        return i;
    }

    private String spawnableKind(Map<?, ?> s) {
        for (String k : List.of("system", "prefab", "puppet", "pose_copy", "camera", "light", "post", "field", "decal",
                "spline_mesh", "capture", "colour_region")) {
            if (s.containsKey(k)) return k;
        }
        throw fail("spawnables." + s.get("id"), "holds no kind");
    }

    // ── Composite sections ───────────────────────────────────────────────────────────────

    private List<CompositeSection> compositeSections(List<Map<?, ?>> trees) {
        Map<String, Integer> byId = new HashMap<>();
        for (Map<?, ?> c : trees) byId.put(string(c.get("id")), byId.size());
        List<CompositeSection> out = new ArrayList<>();
        for (Map<?, ?> c : trees) {
            String cid = string(c.get("id")), path = "composite_sections." + cid;
            int[] range = range(c.get("range"), path + ".range");
            int next = -1;
            if (c.get("next") instanceof String n) next = lookup(byId, n, path + ".next", "composite section");
            out.add(new CompositeSection(cid, String.valueOf(c.get("name")), range[0], range[1], next));
        }
        return out;
    }

    // ── Tracks and sections ──────────────────────────────────────────────────────────────

    private CgSequenceTrack track(Map<?, ?> t, int index) {
        String tid = string(t.get("id")), kind = string(t.get("kind")), path = "tracks." + tid;
        CgType sectionType = CgSequenceKinds.sectionType(kind);
        if (sectionType == null) throw fail(path + ".kind", "no track kind " + kind);
        CgSequenceBinding binding = binding(t.get("binding"), path + ".binding");
        String side = (String) t.get("side");
        List<CgSequenceSection> sections = new ArrayList<>();
        for (Object o : t.get("sections") instanceof List<?> l ? l : List.of()) {
            Map<?, ?> s = (Map<?, ?>) o;
            CgSequenceSection section = section(kind, sectionType, s, sections.size(), path + ".sections." + s.get("id"));
            if (kind.equals("notify")) notifyKind(section, path + ".sections." + section.id());
            if (section.condition() != null && kind.equals("event") && "rpg".equals(side)) {
                throw fail(path + ".sections." + section.id() + ".condition", "an rpg event plays on the server, which"
                        + " never reads a condition: its section may not hold one");
            }
            sections.add(section);
        }
        autoEase(sections);
        return new CgSequenceTrack(tid, index, kind, binding, Boolean.TRUE.equals(t.get("muted")), side,
                (String) t.get("handle"), (String) t.get("parameter"), (String) t.get("property"), (String) t.get("collection"),
                t.get("collection") instanceof String c && deps.definition(CgLookKinds.PARAMETERS, c) instanceof CgParameterCollection pc ? pc : null,
                !Boolean.FALSE.equals(t.get("fire_forwards")), Boolean.TRUE.equals(t.get("fire_backwards")),
                CgSequenceTrack.populationOf(kind), Collections.unmodifiableList(sections), t);
    }

    private CgSequenceSection section(String kind, CgType type, Map<?, ?> s, int index, String path) {
        String trigger = s.get("start") instanceof Map<?, ?> start ? string(start.get("event")) : null;
        int[] range;
        if (s.containsKey("range")) range = range(s.get("range"), path + ".range");
        else if (kind.equals("notify") && s.get("at") instanceof Number at) range = new int[] {at.intValue(), at.intValue() + 1};
        // No range: Unreal's infinite section, TRange::All.
        else range = new int[] {CgSequenceSection.OPEN_START, CgSequenceSection.OPEN_END};
        int row = s.get("row") instanceof Number n ? n.intValue() : 0;
        if (row < 0) throw fail(path + ".row", "a row is 0 or more, not " + row);

        List<CgSequenceChannel> channels = new ArrayList<>();
        Map<Object, Object> fields = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : s.entrySet()) {
            String key = String.valueOf(e.getKey());
            if (SECTION_KEYS.contains(key)) continue;
            CgProperty p = type.property(key);
            CgQuantity q = p != null ? p.quantity() : CgQuantity.NONE;
            String at = path + "." + key;
            switch (CgSequenceKinds.channelForm(type, key)) {
                case ONE -> {
                    // An undeclared key holding named channels ({ scalars: { BG_Brightness: ... } }): a map of them.
                    if (p == null && e.getValue() instanceof Map<?, ?> m && !m.isEmpty() && !isChannelMap(m)) {
                        for (Map.Entry<?, ?> c : m.entrySet()) one(kind, key + "." + c.getKey(), c.getValue(), q, at + "." + c.getKey(), channels);
                    } else {
                        one(kind, key, e.getValue(), q, at, channels);
                    }
                }
                case AXES -> axes(kind, key, e.getValue(), q, at, channels);
                case MAP_OF_ONE -> {
                    for (Map.Entry<?, ?> c : asMap(e.getValue(), at).entrySet()) one(kind, key + "." + c.getKey(), c.getValue(), q, at + "." + c.getKey(), channels);
                }
                case MAP_OF_AXES -> {
                    for (Map.Entry<?, ?> c : asMap(e.getValue(), at).entrySet()) axes(kind, key + "." + c.getKey(), c.getValue(), q, at + "." + c.getKey(), channels);
                }
                case NONE -> fields.put(key, e.getValue());
            }
        }
        CgSequenceSection.Blend blend = null;
        if (CgSequenceTrack.blends(kind, channels)) {
            blend = s.get("blend") instanceof String bl ? CgSequenceSection.Blend.valueOf(bl.toUpperCase(Locale.ROOT))
                    : CgSequenceSection.Blend.ABSOLUTE;
        }
        CgSequenceSection.Ease ease = null;
        if (s.get("ease") instanceof Map<?, ?> m) {
            ease = new CgSequenceSection.Ease(m.get("in") instanceof Number i ? i.intValue() : 0,
                    m.get("out") instanceof Number o ? o.intValue() : 0, easing(m.get("in_fn"), path + ".ease.in_fn"),
                    easing(m.get("out_fn"), path + ".ease.out_fn"), Boolean.TRUE.equals(m.get("auto")));
        }
        CgSequenceSection.Completion completion = "keep_state".equals(s.get("completion")) ? CgSequenceSection.Completion.KEEP_STATE
                : "restore_state".equals(s.get("completion")) ? CgSequenceSection.Completion.RESTORE_STATE
                : CgSequenceSection.Completion.PROJECT_DEFAULT;
        return new CgSequenceSection(string(s.get("id")), index, range[0], range[1], trigger,
                s.containsKey("condition") ? condition(s.get("condition"), path + ".condition") : null, row,
                s.get("overlap_priority") instanceof Number n ? n.intValue() : 0, blend, ease, completion,
                s.get("pre_roll") instanceof Number n ? n.intValue() : 0, s.get("post_roll") instanceof Number n ? n.intValue() : 0,
                !Boolean.FALSE.equals(s.get("active")), Collections.unmodifiableList(channels), Collections.unmodifiableMap(fields));
    }

    /**
     * {@code ease: { auto: true }} takes its durations from where the sections of its row overlap it: Unreal's
     * {@code UMovieSceneTrack::UpdateEasing} ({@code MovieSceneTrack.cpp:229-290}). A section's in eases over the
     * longest overlap of one ending inside it, its out over the longest of one starting inside it; one lying wholly
     * inside another eases a quarter of its length each way. A section of all time is measured by the playback range.
     */
    private void autoEase(List<CgSequenceSection> sections) {
        for (int i = 0; i < sections.size(); i++) {
            CgSequenceSection cur = sections.get(i);
            if (cur.ease() == null || !cur.ease().auto()) continue;
            int in = 0, out = 0;
            boolean underlapped = false;
            for (CgSequenceSection other : sections) {
                if (other == cur || other.row() != cur.row()) continue;
                int oFrom = other.from(), oTo = other.to();
                if (oFrom == CgSequenceSection.OPEN_START && oTo == CgSequenceSection.OPEN_END) {
                    oFrom = start;
                    oTo = end;
                }
                underlapped |= oFrom <= cur.from() && cur.to() <= oTo;
                boolean holdsUpper = oTo != CgSequenceSection.OPEN_END && cur.from() != CgSequenceSection.OPEN_START && cur.contains(oTo);
                boolean holdsLower = oFrom != CgSequenceSection.OPEN_START && cur.to() != CgSequenceSection.OPEN_END && cur.contains(oFrom);
                if (holdsUpper && !holdsLower) in = Math.max(in, oTo - cur.from());
                if (holdsLower && !holdsUpper) out = Math.max(out, cur.to() - oFrom);
            }
            boolean finite = cur.from() != CgSequenceSection.OPEN_START && cur.to() != CgSequenceSection.OPEN_END;
            int size = finite ? cur.to() - cur.from() : Integer.MAX_VALUE;
            if (in == 0 && out == 0 && underlapped) in = out = size / 4;
            in = Math.max(0, Math.min(in, size));
            out = Math.max(0, Math.min(out, size));
            CgSequenceSection.Ease e = cur.ease();
            sections.set(i, new CgSequenceSection(cur.id(), cur.index(), cur.from(), cur.to(), cur.trigger(), cur.condition(),
                    cur.row(), cur.overlapPriority(), cur.blend(), new CgSequenceSection.Ease(in, out, e.inFunction(), e.outFunction(), true),
                    cur.completion(), cur.preRoll(), cur.postRoll(), cur.active(), cur.channels(), cur.fields()));
        }
    }

    /** A notify section's kind is registered, and its properties are that kind's, checked as an asset's values are. */
    private void notifyKind(CgSequenceSection s, String path) {
        CgNotifyKind kind = CgNotifyKind.get(String.valueOf(s.fields().get("kind")));
        if (kind == null) throw fail(path + ".kind", "no notify kind " + s.fields().get("kind") + " is registered");
        Map<?, ?> properties = s.fields().get("properties") instanceof Map<?, ?> m ? m : Map.of();
        if (kind.properties() == null) {
            if (!properties.isEmpty()) throw fail(path + ".properties", kind.id() + " declares no properties");
            return;
        }
        CgValidation v = CgAssetData.validate(id + " " + path + ".properties", kind.properties(), properties, CgValidation.Usecase.LOAD);
        for (CgIssue issue : v.issues()) {
            if (issue.severity() == CgIssue.Severity.ERROR) throw new CgAssetException(issue.path() + ": " + issue.message());
        }
    }

    /** One channel, or one per axis where a map holds {@code x}, {@code y}, {@code z} (or a colour's channels). */
    private void one(String kind, String name, Object value, CgQuantity q, String path, List<CgSequenceChannel> out) {
        if (value instanceof Map<?, ?> m && hasAxes(m)) {
            if (q != CgQuantity.ROTATION && m.containsKey("x") && m.containsKey("y") && m.containsKey("z")) {
                axes(kind, name, value, q, path, out);
                return;
            }
            for (String a : CHANNEL_AXES) if (m.containsKey(a)) out.add(channel(kind, name + "." + a, m.get(a), q, -1, path + "." + a));
            return;
        }
        int width = vectorWidth(value);
        if (width > 0) {
            // A colour's or a vector's keys written whole, [tick, [r, g, b, a]]: one channel a component.
            String[] names = width == 4 || name.startsWith("colours.") ? new String[] {"r", "g", "b", "a"} : AXES;
            if (width > names.length) throw fail(path, "a key of " + width + " components: at most " + names.length);
            for (int c = 0; c < width; c++) out.add(channel(kind, name + "." + names[c], component(value, c), q, -1, path + "." + names[c]));
            return;
        }
        out.add(channel(kind, name, value, q, -1, path));
    }

    /** How many numbers each key's value holds, where they are lists ({@code [tick, [r, g, b, a]]}); 0 for one number. */
    private static int vectorWidth(Object channel) {
        List<?> keys = channel instanceof List<?> l ? l : channel instanceof Map<?, ?> m && m.get("keys") instanceof List<?> l ? l : null;
        Object def = channel instanceof Map<?, ?> m ? m.get("default") : null;
        if (def instanceof List<?> d) return d.size();
        if (keys == null || keys.isEmpty() || !(keys.get(0) instanceof List<?> k) || k.size() < 2) return 0;
        return k.get(1) instanceof List<?> v ? v.size() : 0;
    }

    /** Component {@code c} of a channel whose keys hold vectors: each key's value and the default replaced by theirs. */
    private static Object component(Object channel, int c) {
        if (channel instanceof List<?> keys) return componentKeys(keys, c);
        Map<Object, Object> out = new LinkedHashMap<>((Map<?, ?>) channel);
        if (out.get("default") instanceof List<?> d) out.put("default", d.get(c));
        if (out.get("keys") instanceof List<?> keys) out.put("keys", componentKeys(keys, c));
        return out;
    }

    private static List<Object> componentKeys(List<?> keys, int c) {
        List<Object> out = new ArrayList<>(keys.size());
        for (Object k : keys) {
            List<Object> key = new ArrayList<>((List<?>) k);
            if (key.get(1) instanceof List<?> v) key.set(1, v.get(c));
            out.add(key);
        }
        return out;
    }

    /**
     * Three channels, {@code [x, y, z]} or a map by axis, named by the host axis each fills: a position's or a scale's
     * permute through the units' axes. A rotation's keep their source order (its convention is rebuilt whole).
     */
    private void axes(String kind, String name, Object value, CgQuantity q, String path, List<CgSequenceChannel> out) {
        Object[] source = new Object[3];
        if (value instanceof List<?> l && !l.isEmpty() && l.get(0) instanceof Map) {
            if (l.size() != 3) throw fail(path, "three channels, [x, y, z], not " + l.size());
            for (int i = 0; i < 3; i++) source[i] = l.get(i);
        } else if (value instanceof List<?> l && l.size() == 3 && l.get(0) instanceof List<?> first && !first.isEmpty()
                && first.get(0) instanceof List) {
            for (int i = 0; i < 3; i++) source[i] = l.get(i);
        } else if (value instanceof Map<?, ?> m && hasAxes(m)) {
            for (int i = 0; i < 3; i++) source[i] = m.get(AXES[i]);
        } else {
            out.add(channel(kind, name, value, q, -1, path));
            return;
        }
        boolean permute = q != CgQuantity.ROTATION && q != CgQuantity.NONE;
        for (int i = 0; i < 3; i++) {
            int from = permute ? Math.abs(units.axes()[i]) - 1 : i;
            if (source[from] == null) continue;
            out.add(channel(kind, name + "." + AXES[i], source[from], q, permute ? i : -1, path + "." + AXES[from]));
        }
    }

    /** Whether {@code m} is one channel as a map: its keys, samples, default or extrapolation, or one per axis. */
    private static boolean isChannelMap(Map<?, ?> m) {
        for (String k : List.of("keys", "samples", "default", "pre", "post", "interp")) if (m.containsKey(k)) return true;
        return hasAxes(m);
    }

    private static boolean hasAxes(Map<?, ?> m) {
        for (String a : CHANNEL_AXES) if (m.containsKey(a)) return true;
        return false;
    }

    /** {@code kind}'s channel {@code name}: what its keys hold, by its kind's declaration, else by its values. */
    private static CgSequenceChannel.Type typeOf(String kind, String name, @Nullable List<?> keys, @Nullable Object def) {
        switch (kind + "." + name) {
            case "system.keys": return CgSequenceChannel.Type.PARTICLE;
            case "event.keys": return CgSequenceChannel.Type.EVENT;
            case "spawned.spawned", "visibility.visible", "world_visibility.visible": return CgSequenceChannel.Type.BOOL;
            case "stencil.value": return CgSequenceChannel.Type.INTEGER;
            default:
        }
        if (kind.equals("material") && name.startsWith("bools.")) return CgSequenceChannel.Type.BOOL;
        // The first value written; a key holding null is an object or actor set to none.
        Object sample = def;
        boolean none = false;
        if (keys != null) {
            for (Object o : keys) {
                if (!(o instanceof List<?> k) || k.size() < 2) continue;
                if (k.get(1) == null) {
                    none = true;
                    continue;
                }
                sample = k.get(1);
                break;
            }
        }
        if (sample instanceof Boolean) return CgSequenceChannel.Type.BOOL;
        if (sample instanceof String || (sample == null && none)) return CgSequenceChannel.Type.STRING;
        if (sample instanceof Map) return CgSequenceChannel.Type.EVENT;
        return CgSequenceChannel.Type.FLOAT;
    }

    private CgSequenceChannel channel(String kind, String name, Object value, CgQuantity q, int hostAxis, String path) {
        List<?> keys;
        Object def = null;
        if (value instanceof List<?> l) {
            keys = l;
        } else if (value instanceof Map<?, ?> m) {
            keys = m.get("keys") instanceof List<?> l ? l : null;
            def = m.get("default");
        } else {
            throw fail(path, "a channel is keys or a map, not " + value);
        }
        CgSequenceChannel.Type type = typeOf(kind, name, keys, def);
        if (type == CgSequenceChannel.Type.FLOAT) {
            Object tree = value instanceof List<?> ? Map.of("keys", value) : value;
            CgRichCurve curve;
            try {
                if (q != CgQuantity.NONE) tree = units.channelToHost(tree, q, hostAxis);
                curve = CgRichCurve.decode(tree);
            } catch (RuntimeException e) {
                throw fail(path, String.valueOf(e));
            }
            for (int i = 1; i < curve.size(); i++) {
                if (curve.time(i) < curve.time(i - 1)) throw fail(path, "key " + i + " at tick " + curve.time(i) + " comes before the one before it");
            }
            if (curve.hasWeighted()) curve.bakeWeighted(WEIGHTED_TOLERANCE);
            return CgSequenceChannel.ofCurve(name, curve, def != null);
        }
        int n = keys == null ? 0 : keys.size();
        int[] ticks = new int[n], ints = new int[n];
        String[] strings = type == CgSequenceChannel.Type.STRING || type == CgSequenceChannel.Type.EVENT ? new String[n] : null;
        Object[] args = type == CgSequenceChannel.Type.EVENT ? new Object[n] : null;
        for (int i = 0; i < n; i++) {
            if (!(keys.get(i) instanceof List<?> k) || k.size() < 2 || !(k.get(0) instanceof Number t)) {
                throw fail(path + "[" + i + "]", "a key is [tick, value], not " + keys.get(i));
            }
            ticks[i] = t.intValue();
            if (i > 0 && ticks[i] < ticks[i - 1]) throw fail(path + "[" + i + "]", "tick " + ticks[i] + " comes before the key before it");
            Object v = k.get(1);
            switch (type) {
                case BOOL -> ints[i] = v instanceof Boolean bo ? (bo ? 1 : 0) : number(v, path + "[" + i + "]") != 0 ? 1 : 0;
                case INTEGER -> ints[i] = number(v, path + "[" + i + "]");
                case STRING -> strings[i] = String.valueOf(v);
                case PARTICLE -> ints[i] = particle(v, path + "[" + i + "]");
                case EVENT -> {
                    if (v instanceof Map<?, ?> ev) {
                        strings[i] = string(ev.get("event"));
                        args[i] = ev.get("args");
                    } else {
                        strings[i] = String.valueOf(v);
                    }
                }
                default -> throw new IllegalStateException(type.name());
            }
        }
        int defaultInt = def instanceof Boolean bo ? (bo ? 1 : 0) : def instanceof Number dn ? dn.intValue() : 0;
        return CgSequenceChannel.ofKeys(name, type, ticks, ints, strings, args, def != null, defaultInt,
                def instanceof String ds ? ds : null);
    }

    private int particle(Object v, String path) {
        try {
            return CgSequenceChannel.Particle.valueOf(String.valueOf(v).toUpperCase(Locale.ROOT)).ordinal();
        } catch (IllegalArgumentException e) {
            throw fail(path, "a particle key is activate, deactivate, trigger, kill or detach, not " + v);
        }
    }

    /**
     * Unreal's built-in easing names ({@code EMovieSceneBuiltInEasing}, {@code MovieSceneEasingCurves.cpp:55-100}) on
     * the engine's easings, which are the same functions. Its {@code Cubic} and {@code HermiteCubicInOut} are both
     * smoothstep: the Bezier with its handles at thirds.
     */
    private static final Map<String, CgEasing> EASINGS = Map.ofEntries(
            Map.entry("linear", CgEasings.LINEAR),
            Map.entry("sin_in", CgEasings.IN_SINE), Map.entry("sin_out", CgEasings.OUT_SINE), Map.entry("sin_in_out", CgEasings.IN_OUT_SINE),
            Map.entry("quad_in", CgEasings.IN_QUAD), Map.entry("quad_out", CgEasings.OUT_QUAD), Map.entry("quad_in_out", CgEasings.IN_OUT_QUAD),
            Map.entry("cubic", CgEasings.cubicBezier(1.0 / 3, 0, 2.0 / 3, 1)),
            Map.entry("cubic_in", CgEasings.IN_CUBIC), Map.entry("cubic_out", CgEasings.OUT_CUBIC), Map.entry("cubic_in_out", CgEasings.IN_OUT_CUBIC),
            Map.entry("hermite_cubic_in_out", CgEasings.cubicBezier(1.0 / 3, 0, 2.0 / 3, 1)),
            Map.entry("quart_in", CgEasings.IN_QUART), Map.entry("quart_out", CgEasings.OUT_QUART), Map.entry("quart_in_out", CgEasings.IN_OUT_QUART),
            Map.entry("quint_in", CgEasings.IN_QUINT), Map.entry("quint_out", CgEasings.OUT_QUINT), Map.entry("quint_in_out", CgEasings.IN_OUT_QUINT),
            Map.entry("expo_in", CgEasings.IN_EXPO), Map.entry("expo_out", CgEasings.OUT_EXPO), Map.entry("expo_in_out", CgEasings.IN_OUT_EXPO),
            Map.entry("circ_in", CgEasings.IN_CIRC), Map.entry("circ_out", CgEasings.OUT_CIRC), Map.entry("circ_in_out", CgEasings.IN_OUT_CIRC));

    /** An ease's function by its name; absent, Unreal's default for a new section, cubic in-out. */
    private CgEasing easing(Object name, String path) {
        if (name == null) return CgEasings.IN_OUT_CUBIC;
        CgEasing e = EASINGS.get(String.valueOf(name));
        if (e == null) throw fail(path, "no easing " + name + ": " + new TreeSet<>(EASINGS.keySet()));
        return e;
    }

    // ── Conditions and subsequences ──────────────────────────────────────────────────────

    private CgSequenceCondition condition(Object c, String path) {
        Map<?, ?> m = asMap(c, path);
        if (m.get("all") instanceof List<?> all) return new CgSequenceCondition.All(conditions(all, path + ".all"));
        if (m.get("any") instanceof List<?> any) return new CgSequenceCondition.Any(conditions(any, path + ".any"));
        if (m.containsKey("not")) return new CgSequenceCondition.Not(condition(m.get("not"), path + ".not"));
        if (!(m.get("kind") instanceof String kind) || kind.indexOf(':') < 0) {
            throw fail(path + ".kind", "a condition names its kind, namespaced: not " + m.get("kind"));
        }
        int role = m.get("role") instanceof String r ? lookup(roleByName, r, path + ".role", "role named") : -1;
        Map<Object, Object> fields = new LinkedHashMap<>(m);
        fields.keySet().removeAll(List.of("kind", "role", "is"));
        return new CgSequenceCondition.Leaf(kind, role, m.get("is") instanceof List<?> is ? is : List.of(),
                "any".equals(m.get("is")), Collections.unmodifiableMap(fields));
    }

    private List<CgSequenceCondition> conditions(List<?> list, String path) {
        List<CgSequenceCondition> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) out.add(condition(list.get(i), path + "[" + i + "]"));
        return Collections.unmodifiableList(out);
    }

    /** A subsequence section's child: compiled first, as a hard dependency; its roles filled by name or by its map. */
    @Nullable
    private Child child(CgSequenceTrack track, CgSequenceSection s) {
        String path = "tracks." + track.id() + ".sections." + s.id();
        if (!(s.fields().get("sequence") instanceof String ref)) return null;
        if (!(deps.definition(CgSequenceKinds.SEQUENCE, ref) instanceof CgSequenceDefinition child)) {
            // A definition compiled without its dependencies (a test, a tool) has no children to resolve.
            return null;
        }
        Map<?, ?> map = s.fields().get("roles") instanceof Map<?, ?> m ? m : Map.of();
        int[] filled = new int[child.roles().size()];
        for (int i = 0; i < filled.length; i++) {
            String name = child.roles().get(i).name();
            Object parent = map.get(name);
            if (parent != null) filled[i] = lookup(roleByName, String.valueOf(parent), path + ".roles." + name, "role named");
            else filled[i] = roleByName.getOrDefault(name, -1);
        }
        for (Object k : map.keySet()) {
            if (child.role(String.valueOf(k)) < 0) throw fail(path + ".roles." + k, ref + " has no role named " + k);
        }
        return new Child(track.index(), s.index(), child, filled);
    }

    // ── Values ───────────────────────────────────────────────────────────────────────────

    private int[] range(Object r, String path) {
        if (!(r instanceof List<?> l) || l.size() != 2) throw fail(path, "two ticks, not " + r);
        int from = l.get(0) == null ? CgSequenceSection.OPEN_START : number(l.get(0), path);
        int to = l.get(1) == null ? CgSequenceSection.OPEN_END : number(l.get(1), path);
        if (to < from) throw fail(path, "ends at " + to + ", before it starts at " + from);
        return new int[] {from, to};
    }

    private int number(Object v, String path) {
        if (!(v instanceof Number n)) throw fail(path, "a number, not " + v);
        return n.intValue();
    }

    private CgFrameRate rate(Map<?, ?> tree, String key) {
        if (!(tree.get(key) instanceof Number n) || n.doubleValue() <= 0) throw fail(key, "a positive number, not " + tree.get(key));
        try {
            return CgFrameRate.of(n.doubleValue());
        } catch (IllegalArgumentException e) {
            throw fail(key, e.getMessage());
        }
    }

    private static List<Map<?, ?>> elements(Map<?, ?> tree, String key) {
        List<Map<?, ?>> out = new ArrayList<>();
        if (tree.get(key) instanceof List<?> l) for (Object o : l) out.add((Map<?, ?>) o);
        return out;
    }

    private Map<?, ?> asMap(Object v, String path) {
        if (!(v instanceof Map<?, ?> m)) throw fail(path, "a map, not " + v);
        return m;
    }

    private static String string(Object v) {
        return String.valueOf(v);
    }

    private CgAssetException fail(String path, String message) {
        return new CgAssetException(id + ": " + path + ": " + message);
    }
}
