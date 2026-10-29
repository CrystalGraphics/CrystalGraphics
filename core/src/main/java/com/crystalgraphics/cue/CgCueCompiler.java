package com.crystalgraphics.cue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.asset.kind.CgCameraKinds;
import com.crystalgraphics.asset.kind.CgLookKinds;
import com.crystalgraphics.asset.kind.CgParticleKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.cue.CgCueDefinition.Decal;
import com.crystalgraphics.cue.CgCueDefinition.Effects;
import com.crystalgraphics.cue.CgCueDefinition.Event;
import com.crystalgraphics.cue.CgCueDefinition.Field;
import com.crystalgraphics.cue.CgCueDefinition.ForceFeedback;
import com.crystalgraphics.cue.CgCueDefinition.Kind;
import com.crystalgraphics.cue.CgCueDefinition.Lens;
import com.crystalgraphics.cue.CgCueDefinition.Particles;
import com.crystalgraphics.cue.CgCueDefinition.Placement;
import com.crystalgraphics.cue.CgCueDefinition.Prediction;
import com.crystalgraphics.cue.CgCueDefinition.SequenceEntry;
import com.crystalgraphics.cue.CgCueDefinition.Shake;
import com.crystalgraphics.cue.CgCueDefinition.Slot;
import com.crystalgraphics.cue.CgCueDefinition.Sound;
import com.crystalgraphics.cue.CgCueDefinition.SpawnCondition;
import com.crystalgraphics.cue.CgCueDefinition.Where;
import com.crystalgraphics.cue.CgCueDefinition.Write;
import com.crystalgraphics.object.CgPropertyMeta;
import com.crystalgraphics.object.value.CgQuantity;
import com.crystalgraphics.object.value.CgUnits;
import com.crystalgraphics.object.validate.CgIssue;
import com.crystalgraphics.object.validate.CgValidation;
import com.crystalgraphics.tag.CgGameplayTag;
import com.crystalgraphics.tag.CgTagException;
import com.crystalgraphics.tag.CgTagRegistry;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.system.CgVfxSystemDefinition;

/** Compiles a resolved {@code cue} asset into a {@link CgCueDefinition}. */
final class CgCueCompiler {

    private static final Logger LOGGER = LogManager.getLogger("CgCue");
    private static final String[] LOOPING_PHASES = {"application", "looping", "recurring", "removal"};

    private final String id;
    private final CgAssetCompiler.Dependencies deps;
    private final CgUnits units;

    private CgCueCompiler(String id, CgAssetCompiler.Dependencies deps) {
        this.id = id;
        this.deps = deps;
        this.units = deps.units();
    }

    static CgCueDefinition compile(CgAssetData asset, CgAssetCompiler.Dependencies deps) {
        CgValidation v = asset.validate(CgValidation.Usecase.LOAD);
        if (!v.valid()) {
            for (CgIssue issue : v.issues()) {
                if (issue.severity() == CgIssue.Severity.ERROR) throw new CgAssetException(asset.id() + ": " + issue.path() + ": " + issue.message());
            }
        }
        return new CgCueCompiler(asset.id(), deps).compile(asset.resolved());
    }

    private CgCueDefinition compile(Map<Object, Object> tree) {
        CgGameplayTag tag = null;
        if (tree.get("tag") instanceof String name) {
            try {
                tag = CgTagRegistry.global().require(name);
            } catch (CgTagException e) {
                throw fail("tag", e.getMessage());
            }
        }
        Kind kind = Kind.valueOf(upper(tree.get("kind")));
        if (kind == Kind.BURST) {
            for (String phase : LOOPING_PHASES) if (tree.containsKey(phase)) throw fail(phase, "a burst cue plays only burst");
        } else if (tree.containsKey("burst")) {
            throw fail("burst", "a looping cue plays application, looping, recurring and removal, not burst");
        }

        Where defaults = new Where(condition(tree.get("default_spawn_condition"), SpawnCondition.ALWAYS),
                placement(tree.get("default_placement"), Placement.DEFAULT, "default_placement"));
        Effects burst = effects(tree.get("burst"), "burst", defaults);
        Effects application = effects(tree.get("application"), "application", defaults);
        Effects looping = effects(tree.get("looping"), "looping", defaults);
        Effects recurring = effects(tree.get("recurring"), "recurring", defaults);
        Effects removal = effects(tree.get("removal"), "removal", defaults);

        List<Event> events = new ArrayList<>();
        int i = 0;
        for (Map<?, ?> e : elements(tree.get("events"))) {
            String path = "events[" + i++ + "]";
            events.add(new Event(String.valueOf(e.get("id")), String.valueOf(e.get("event")), effects(e, path, defaults)));
        }

        List<Field> fields = new ArrayList<>();
        for (Map<?, ?> f : elements(tree.get("fields"))) {
            fields.add(new Field(String.valueOf(f.get("name")), Field.Type.valueOf(upper(f.get("type"))), f.get("fallback")));
        }

        CgCueDefinition partial = new CgCueDefinition(id, tag, kind, burst, application, looping, recurring, removal,
                List.copyOf(events), Map.of(), List.of(), length(tree.get("range"), Float.NaN),
                Boolean.TRUE.equals(tree.get("authoritative")), Boolean.TRUE.equals(tree.get("persisted")),
                tree.get("prediction") == null ? Prediction.NONE : Prediction.valueOf(upper(tree.get("prediction"))),
                List.copyOf(fields), tree.get("sequence") instanceof String s ? s : null);
        return writes(partial, tree.get("writes"));
    }

    /** Resolves each written name to the user parameter of every played system that binds it. */
    private CgCueDefinition writes(CgCueDefinition cue, @Nullable Object written) {
        if (!(written instanceof Map<?, ?> names) || names.isEmpty()) return cue;
        List<Particles> played = cue.allParticles();
        boolean compiled = false;
        for (Particles p : played) compiled |= p.definition() != null;
        Map<String, Write> writes = new LinkedHashMap<>();
        List<String> unbound = new ArrayList<>();
        for (Map.Entry<?, ?> w : names.entrySet()) {
            String name = String.valueOf(w.getKey());
            List<Slot> slots = new ArrayList<>();
            List<CgVfxSystemDefinition> seen = new ArrayList<>();
            for (Particles p : played) {
                CgVfxSystemDefinition def = p.definition();
                if (def == null || seen.contains(def)) continue;
                seen.add(def);
                CgVfxParam param = def.look().layout().param(name);
                if (param != null) slots.add(new Slot(p.system(), def, param));
            }
            if (compiled && slots.isEmpty()) unbound.add(name);
            Map<?, ?> full = w.getValue() instanceof Map<?, ?> m ? m : Map.of();
            String meaning = full.isEmpty() ? String.valueOf(w.getValue()) : String.valueOf(full.get("meaning"));
            CgPropertyMeta.Replication mode = full.get("replication") == null ? CgPropertyMeta.Replication.INTERPOLATE
                    : CgPropertyMeta.Replication.valueOf(upper(full.get("replication")));
            writes.put(name, new Write(name, meaning, List.copyOf(slots), mode, number(full.get("rate"), Write.DEFAULT_RATE)));
        }
        if (!unbound.isEmpty()) LOGGER.warn("{}: writes {} that no system it plays binds", id, unbound);
        return new CgCueDefinition(cue.id(), cue.tag(), cue.kind(), cue.burst(), cue.application(), cue.looping(),
                cue.recurring(), cue.removal(), cue.events(), writes, List.copyOf(unbound), cue.range(),
                cue.authoritative(), cue.persisted(), cue.prediction(), cue.fields(), cue.sequence());
    }

    private Effects effects(@Nullable Object block, String path, Where defaults) {
        if (block == null) return Effects.NONE;
        if (!(block instanceof Map<?, ?> b)) throw fail(path, "an effects block, not " + block);
        Where blockWhere = where(b, path, defaults);
        List<Particles> particles = new ArrayList<>();
        int i = 0;
        for (Map<?, ?> p : elements(b.get("particles"))) {
            String system = String.valueOf(p.get("system"));
            CgVfxSystemDefinition def = deps.definition(CgParticleKinds.SYSTEM, system) instanceof CgVfxSystemDefinition d ? d : null;
            particles.add(new Particles(system, def, Boolean.TRUE.equals(p.get("cast_shadow")), number(p.get("time_dilation"), 1f),
                    time(p.get("warmup"), 0f), where(p, path + ".particles[" + i++ + "]", blockWhere)));
        }
        List<Sound> sounds = new ArrayList<>();
        i = 0;
        for (Map<?, ?> s : elements(b.get("sounds"))) {
            sounds.add(new Sound(s.get("sound"), time(s.get("fade_out"), 0.5f), number(s.get("fade_volume"), 0f),
                    where(s, path + ".sounds[" + i++ + "]", blockWhere)));
        }
        Shake shake = null;
        if (b.get("shake") instanceof Map<?, ?> s) {
            String ref = String.valueOf(s.get("shake"));
            shake = new Shake(ref, deps.definition(CgCameraKinds.SHAKE, ref), number(s.get("scale"), 1f), s.get("space"),
                    Boolean.TRUE.equals(s.get("in_world")), length(s.get("inner"), 0f), length(s.get("outer"), 0f),
                    number(s.get("falloff"), 1f), where(s, path + ".shake", blockWhere));
        }
        Lens lens = null;
        if (b.get("lens") instanceof Map<?, ?> l) {
            // A lens lasts as long as what plays it: a looping block's, the cue's life. A burst names no duration.
            if (!path.equals("looping")) throw fail(path + ".lens", "a lens in a burst has no duration yet: only looping plays one");
            lens = new Lens((Map<?, ?>) l.get("post"), Boolean.TRUE.equals(l.get("in_world")), length(l.get("inner"), 0f),
                    length(l.get("outer"), 0f), where(l, path + ".lens", blockWhere));
        }
        ForceFeedback feedback = null;
        if (b.get("force_feedback") instanceof Map<?, ?> f) {
            feedback = new ForceFeedback(time(f.get("duration"), 0f), f.get("channels"),
                    f.get("tag") instanceof String t ? t : null, Boolean.TRUE.equals(f.get("looping")),
                    number(f.get("intensity"), 1f), where(f, path + ".force_feedback", blockWhere));
        }
        Decal decal = null;
        if (b.get("decal") instanceof Map<?, ?> d) {
            String material = String.valueOf(d.get("material"));
            decal = new Decal(material, deps.definition(CgLookKinds.MATERIAL, material) instanceof CgMaterialDefinition m ? m : null,
                    d.get("size"), d.get("fade"), where(d, path + ".decal", blockWhere));
        }
        List<SequenceEntry> sequences = new ArrayList<>();
        i = 0;
        for (Map<?, ?> s : elements(b.get("sequences"))) {
            sequences.add(new SequenceEntry(String.valueOf(s.get("sequence")), where(s, path + ".sequences[" + i++ + "]", blockWhere)));
        }
        return new Effects(List.copyOf(particles), List.copyOf(sounds), shake, lens, feedback, decal, List.copyOf(sequences));
    }

    /** An entry's own condition and placement, each whole, else {@code outer}'s: Unreal's override, never merged. */
    private Where where(Map<?, ?> entry, String path, Where outer) {
        Object sc = entry.get("spawn_condition"), pl = entry.get("placement");
        if (sc == null && pl == null) return outer;
        return new Where(condition(sc, outer.condition()), placement(pl, outer.placement(), path + ".placement"));
    }

    private static SpawnCondition condition(@Nullable Object written, SpawnCondition otherwise) {
        if (!(written instanceof Map<?, ?> c)) return otherwise;
        Map<?, ?> surfaces = c.get("surfaces") instanceof Map<?, ?> s ? s : Map.of();
        return new SpawnCondition(
                c.get("source") == null ? SpawnCondition.Source.INSTIGATOR : SpawnCondition.Source.valueOf(upper(c.get("source"))),
                c.get("policy") == null ? SpawnCondition.Policy.ALWAYS : SpawnCondition.Policy.valueOf(upper(c.get("policy"))),
                number(c.get("chance"), 1f), strings(surfaces.get("allowed")), strings(surfaces.get("rejected")));
    }

    private Placement placement(@Nullable Object written, Placement otherwise, String path) {
        if (!(written instanceof Map<?, ?> p)) return otherwise;
        Quaternionf rotation = null;
        if (p.get("rotation") != null) {
            try {
                rotation = units.rotationToHost(p.get("rotation"), new Quaternionf());
            } catch (IllegalArgumentException e) {
                throw fail(path + ".rotation", e.getMessage());
            }
        }
        Vector3f scale = null;
        if (p.get("scale") instanceof List<?> s) {
            List<?> host = (List<?>) units.toHost(s, CgQuantity.SCALE);
            scale = new Vector3f(number(host.get(0), 1f), number(host.get(1), 1f), number(host.get(2), 1f));
        }
        return new Placement(p.get("socket") instanceof String s ? s : null,
                p.get("attach") == null ? Placement.Attach.DO_NOT_ATTACH : Placement.Attach.valueOf(upper(p.get("attach"))),
                p.get("rule") == null ? Placement.Rule.KEEP_WORLD : Placement.Rule.valueOf(upper(p.get("rule"))), rotation, scale);
    }

    private float length(@Nullable Object v, float otherwise) {
        return v instanceof Number ? number(units.toHost(v, CgQuantity.LENGTH), otherwise) : otherwise;
    }

    private float time(@Nullable Object v, float otherwise) {
        return v instanceof Number ? number(units.toHost(v, CgQuantity.TIME), otherwise) : otherwise;
    }

    private static float number(@Nullable Object v, float otherwise) {
        return v instanceof Number n ? n.floatValue() : otherwise;
    }

    private static List<String> strings(@Nullable Object v) {
        if (!(v instanceof List<?> l)) return List.of();
        List<String> out = new ArrayList<>(l.size());
        for (Object o : l) out.add(String.valueOf(o));
        return List.copyOf(out);
    }

    private static List<Map<?, ?>> elements(@Nullable Object v) {
        List<Map<?, ?>> out = new ArrayList<>();
        if (v instanceof List<?> l) for (Object o : l) out.add((Map<?, ?>) o);
        return out;
    }

    private static String upper(Object v) {
        return String.valueOf(v).toUpperCase(Locale.ROOT);
    }

    private CgAssetException fail(String path, String why) {
        return new CgAssetException(id + ": " + path + ": " + why);
    }

    /** {@code e}'s name as a file writes it. */
    static String name(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT);
    }

    /** {@code c} as a file writes it. */
    static Map<String, Object> tree(SpawnCondition c) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("source", name(c.source()));
        t.put("policy", name(c.policy()));
        t.put("chance", (double) c.chance());
        if (!c.allowed().isEmpty() || !c.rejected().isEmpty()) {
            Map<String, Object> s = new LinkedHashMap<>();
            if (!c.allowed().isEmpty()) s.put("allowed", new ArrayList<>(c.allowed()));
            if (!c.rejected().isEmpty()) s.put("rejected", new ArrayList<>(c.rejected()));
            t.put("surfaces", s);
        }
        return t;
    }

    /** {@code p} as a file in the host's units writes it. */
    static Map<String, Object> tree(Placement p) {
        Map<String, Object> t = new LinkedHashMap<>();
        if (p.socket() != null) t.put("socket", p.socket());
        t.put("attach", name(p.attach()));
        t.put("rule", name(p.rule()));
        if (p.rotation() != null) {
            Quaternionf r = p.rotation();
            t.put("rotation", new ArrayList<>(List.of((double) r.x, (double) r.y, (double) r.z, (double) r.w)));
        }
        if (p.scale() != null) {
            Vector3f s = p.scale();
            t.put("scale", new ArrayList<>(List.of((double) s.x, (double) s.y, (double) s.z)));
        }
        return t;
    }
}
