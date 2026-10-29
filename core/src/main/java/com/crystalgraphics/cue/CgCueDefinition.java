package com.crystalgraphics.cue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.asset.kind.CgGameplayKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.object.CgPropertyMeta;
import com.crystalgraphics.tag.CgGameplayTag;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.system.CgVfxSystemDefinition;

/**
 * A {@code cue} asset compiled: what a gameplay moment plays, once (a burst) or over a lasting state (looping), per
 * phase and on named events, and the user parameters gameplay writes each frame. Mirrors GAS's
 * {@code UGameplayCueNotify_Burst} and {@code AGameplayCueNotify_Looping}, each phase an
 * {@code FGameplayCueNotify_BurstEffects}.
 *
 * <p>From a file, through the asset loader:
 * <pre>{@code
 * # assets/mymod/cues/beam.yaml
 * _v: 1
 * tag: mymod:cue.beam
 * kind: looping
 * default_placement: { socket: hands, attach: attach_to_target }
 * application: { particles: [ { system: mymod:beam/start } ] }
 * looping: { particles: [ { system: mymod:beam/body } ] }
 * removal: { particles: [ { system: mymod:beam/end } ] }
 * events: [ { id: e1, event: hit_world, particles: [ { system: mymod:beam/hit } ] } ]
 * writes: { BeamLength: "the head's distance from the root" }
 * }</pre>
 * <pre>{@code
 * CgCueDefinition beam = CgVfxAssets.<CgCueDefinition>ref(CgGameplayKinds.CUE, "mymod:beam").get();
 * for (CgCueDefinition.Particles p : beam.application().particles()) play(p.definition(), p.where());
 * for (CgCueDefinition.Slot s : beam.writes().get("BeamLength").slots()) bind(s.system(), s.param());
 * }</pre>
 *
 * <p>The same cue in Java: {@link #tree()} is exactly its file, so it round-trips through {@code CgAssetYaml}.
 * <pre>{@code
 * CgCueDefinition.Builder beam = CgCueDefinition.builder("mymod:beam").tag("mymod:cue.beam").looping()
 *         .defaultPlacement(CgCueDefinition.Placement.attached("hands"))
 *         .application(e -> e.particles("mymod:beam/start"))
 *         .looping(e -> e.particles("mymod:beam/body"))
 *         .removal(e -> e.particles("mymod:beam/end"))
 *         .event("e1", "hit_world", e -> e.particles("mymod:beam/hit"))
 *         .writes("BeamLength", "the head's distance from the root");
 * String yaml = CgAssetYaml.write(beam.tree(), CgGameplayKinds.CUE);     // the file above
 * CgCueDefinition cue = beam.build();                                     // compiled, its systems unresolved
 * }</pre>
 *
 * <ul>
 *   <li>Its tag must be declared ({@code tags.yaml}, or {@code CgTagRegistry.register}) before it compiles, or it
 *       fails at load by name.</li>
 *   <li>An entry's spawn condition and placement are its own whole, else its block's, else the cue's defaults, as
 *       {@code FGameplayCueNotify_SpawnContext::GetPlacementInfo} picks: an override is never merged field by field.
 *       {@link Where} holds the resolved pair.</li>
 *   <li>Unwritten placement attaches nothing and keeps world, as Unreal's: name {@code attach_to_target} to follow.</li>
 *   <li>A {@code writes} name reaches every played system binding it as a user parameter. A name none binds is
 *       reported once, at compile, and listed in {@link #unbound()}; a system binding a name the cue never writes
 *       keeps its default.</li>
 *   <li>Where systems are not compiled (a dedicated server, {@link Builder#build()}) {@link Particles#definition()} is
 *       null and {@code writes} bind nothing, so nothing is reported unbound.</li>
 *   <li>Lengths, times and rotations are in the host's units.</li>
 * </ul>
 *
 * @param id            its asset id
 * @param tag           the tag gameplay names it by, or null for a cue played only by its id
 * @param kind          burst or looping
 * @param burst         a burst cue's one phase
 * @param application   a looping cue's start
 * @param looping       what lasts while it is active
 * @param recurring     a burst each recurrence
 * @param removal       its end
 * @param events        bursts on events gameplay names, in file order
 * @param writes        what gameplay writes each frame, by name, in file order
 * @param unbound       the {@code writes} names no played system binds
 * @param range         how far round its point it reaches when it follows nothing, in blocks; NaN for the default
 * @param authoritative whether the server runs it too
 * @param persisted     whether its lasting plays are saved with the world
 * @param prediction    how a client may predict it
 * @param fields        its own networked values, beyond what it plays
 * @param sequence      a looping cue's timeline, by id, started with {@code application}; or null
 */
public record CgCueDefinition(String id, @Nullable CgGameplayTag tag, Kind kind, Effects burst, Effects application,
                              Effects looping, Effects recurring, Effects removal, List<Event> events,
                              Map<String, Write> writes, List<String> unbound, float range, boolean authoritative,
                              boolean persisted, Prediction prediction, List<Field> fields, @Nullable String sequence) {

    /** The cue kind's compiler. */
    public static final CgAssetCompiler<CgCueDefinition> COMPILER = CgCueCompiler::compile;

    /** Sets {@link #COMPILER} as the cue kind's. {@code CgBuiltInCompilers.register} calls it. */
    public static void register() {
        CgAssetCompiler.register(CgGameplayKinds.CUE, COMPILER);
    }

    /** {@code asset} compiled with nothing it references compiled: what a test or a tool reads. */
    public static CgCueDefinition compile(CgAssetData asset) {
        return CgCueCompiler.compile(asset, (kind, ref) -> null);
    }

    /** A cue built in Java, as its file would write it. */
    public static Builder builder(String id) {
        return new Builder(id);
    }

    /** Every system it plays: burst, application, looping, recurring, removal, then each event's, in file order. */
    public List<Particles> allParticles() {
        List<Particles> out = new ArrayList<>();
        for (Effects e : List.of(burst, application, looping, recurring, removal)) out.addAll(e.particles());
        for (Event e : events) out.addAll(e.effects().particles());
        return out;
    }

    /** {@code GameplayCueNotify_Burst} or {@code _Looping}. */
    public enum Kind { BURST, LOOPING }

    /** {@code prediction}: never, a one-off a client may play first, or under a prediction key. */
    public enum Prediction { NONE, ONE_OFF, KEYED }

    /**
     * One phase's effects: {@code FGameplayCueNotify_BurstEffects}, and the sequences it starts.
     *
     * @param shake         at most one, as Unreal's
     * @param lens          at most one
     * @param forceFeedback at most one
     * @param decal         at most one
     */
    public record Effects(List<Particles> particles, List<Sound> sounds, @Nullable Shake shake, @Nullable Lens lens,
                          @Nullable ForceFeedback forceFeedback, @Nullable Decal decal, List<SequenceEntry> sequences) {
        /** A phase the cue does not have. */
        public static final Effects NONE = new Effects(List.of(), List.of(), null, null, null, null, List.of());

        /** Whether it plays nothing. */
        public boolean isEmpty() {
            return particles.isEmpty() && sounds.isEmpty() && shake == null && lens == null && forceFeedback == null
                    && decal == null && sequences.isEmpty();
        }
    }

    /** An entry's resolved spawn condition and placement. */
    public record Where(SpawnCondition condition, Placement placement) {
        /** Unreal's defaults for both. */
        public static final Where DEFAULT = new Where(SpawnCondition.ALWAYS, Placement.DEFAULT);
    }

    /**
     * {@code FGameplayCueNotify_SpawnCondition}: who sees an effect, and how often. Evaluated on each viewer.
     *
     * @param chance   0 to 1
     * @param allowed  surface names it plays on; empty for any
     * @param rejected surface names it never plays on
     */
    public record SpawnCondition(Source source, Policy policy, float chance, List<String> allowed, List<String> rejected) {
        /** Unreal's default: the instigator, always, every time. */
        public static final SpawnCondition ALWAYS = new SpawnCondition(Source.INSTIGATOR, Policy.ALWAYS, 1f, List.of(), List.of());

        /** Whose local control {@link #policy} tests. */
        public enum Source { INSTIGATOR, TARGET }

        /** Every viewer, only the controlling one, or all but it. */
        public enum Policy { ALWAYS, LOCAL_ONLY, NOT_LOCAL }
    }

    /**
     * {@code FGameplayCueNotify_PlacementInfo}: where an effect goes.
     *
     * <pre>{@code
     * CgCueDefinition.Placement.attached("hands")         // follows the hands socket
     * new CgCueDefinition.Placement("hands", Attach.DO_NOT_ATTACH, Rule.KEEP_WORLD, null, null)   // spawns there, stays
     * }</pre>
     *
     * @param socket   a socket of the target, or null for its root
     * @param rotation a turn in the host's axes, or null for none
     * @param scale    or null for one
     */
    public record Placement(@Nullable String socket, Attach attach, Rule rule, @Nullable Quaternionf rotation,
                            @Nullable Vector3f scale) {
        /** Unreal's default: not attached, keeping world. */
        public static final Placement DEFAULT = new Placement(null, Attach.DO_NOT_ATTACH, Rule.KEEP_WORLD, null, null);

        /** Attached to {@code socket}, keeping its offset relative. */
        public static Placement attached(@Nullable String socket) {
            return new Placement(socket, Attach.ATTACH_TO_TARGET, Rule.KEEP_RELATIVE, null, null);
        }

        /** Whether it follows its target. */
        public enum Attach { DO_NOT_ATTACH, ATTACH_TO_TARGET }

        /** {@code EAttachmentRule}. */
        public enum Rule { KEEP_RELATIVE, KEEP_WORLD, SNAP_TO_TARGET }
    }

    /**
     * A system played: its compiled definition where systems compile, else null.
     *
     * @param timeDilation its component's {@code CustomTimeDilation}: it ages at this multiple of its clock, 1 unless set
     * @param warmup       seconds it is simulated before it is first shown ({@code WarmupTime}), 0 for none
     */
    public record Particles(String system, @Nullable CgVfxSystemDefinition definition, boolean castShadow, float timeDilation,
                            float warmup, Where where) {
    }

    /**
     * A sound, as written until sounds compile.
     *
     * @param fadeOut    a looping sound's fade at removal, in seconds
     * @param fadeVolume the volume it fades to, 0 to 1
     */
    public record Sound(Object sound, float fadeOut, float fadeVolume, Where where) {
    }

    /**
     * A camera shake.
     *
     * @param definition its asset as compiled, or as resolved until shakes compile; null where it was not loaded
     * @param inner      full strength within this distance, in blocks
     * @param outer      none past it
     */
    public record Shake(String shake, @Nullable Object definition, float scale, @Nullable Object space, boolean inWorld,
                        float inner, float outer, float falloff, Where where) {
    }

    /** A lens: its post block as written, and how far it reaches in the world, in blocks. */
    public record Lens(Map<?, ?> post, boolean inWorld, float inner, float outer, Where where) {
    }

    /** Force feedback, as written: {@code duration} in seconds. */
    public record ForceFeedback(float duration, @Nullable Object channels, @Nullable String tag, boolean looping,
                                float intensity, Where where) {
    }

    /** A decal: its material, compiled where materials compile, and its size and fade as written. */
    public record Decal(String material, @Nullable CgMaterialDefinition definition, @Nullable Object size,
                        @Nullable Object fade, Where where) {
    }

    /** A sequence played once from its start, by id. */
    public record SequenceEntry(String sequence, Where where) {
    }

    /** A burst on an event gameplay names; {@code id} is what a child cue overrides it by. */
    public record Event(String id, String event, Effects effects) {
    }

    /**
     * A {@code writes} name: what it means, every user parameter it is written into, and how an authoritative cue
     * sends it to viewers.
     *
     * <pre>{@code
     * writes:
     *   BeamLength: { meaning: "the head's distance from the root", replication: goal }   # a spring toward it, ω 20
     *   Glow: "how bright"                                                              # interpolated
     * }</pre>
     *
     * @param slots       each played system binding it, and its parameter there; empty where none does
     * @param replication as written, else {@code INTERPOLATE}: a {@code vec3} does not say whether it is a point, so a
     *                    goal is always written
     * @param rate        a goal's spring rate ω, in 1/s; {@link #DEFAULT_RATE} unwritten
     */
    public record Write(String name, String meaning, List<Slot> slots, CgPropertyMeta.Replication replication, float rate) {
        /** A goal's spring rate when its file names none. */
        public static final float DEFAULT_RATE = 20f;
    }

    /** A played system's user parameter a {@code writes} name sets. */
    public record Slot(String system, CgVfxSystemDefinition definition, CgVfxParam param) {
    }

    /** One of its own networked values. */
    public record Field(String name, Type type, @Nullable Object fallback) {
        /** What it holds. */
        public enum Type { INT, FLOAT, DOUBLE, BOOL, STRING }
    }

    /**
     * Writes a cue's file tree from Java: what {@code assets/<ns>/cues/<path>.yaml} holds, never a second model.
     *
     * <ul>
     *   <li>A phase set twice is replaced, not merged.</li>
     *   <li>{@link #burst} belongs to a {@link #burst() burst} cue; the four others to a {@link #looping() looping}
     *       one. The compiler, not the builder, refuses the mix.</li>
     * </ul>
     */
    public static final class Builder {
        private final String id;
        private final Map<String, Object> tree = new LinkedHashMap<>();
        private final Map<String, Object> writes = new LinkedHashMap<>();
        private final List<Object> events = new ArrayList<>(), fields = new ArrayList<>();

        private Builder(String id) {
            this.id = id;
            tree.put("_v", 1);
            tree.put("kind", "burst");
        }

        public Builder tag(String tag) {
            tree.put("tag", tag);
            return this;
        }

        /** Plays once: its effects go in {@link #burst}. The default. */
        public Builder burst() {
            tree.put("kind", "burst");
            return this;
        }

        /** Lasts while active: {@link #application}, {@link #looping}, {@link #recurring}, {@link #removal}. */
        public Builder looping() {
            tree.put("kind", "looping");
            return this;
        }

        public Builder defaultSpawnCondition(SpawnCondition condition) {
            tree.put("default_spawn_condition", CgCueCompiler.tree(condition));
            return this;
        }

        public Builder defaultPlacement(Placement placement) {
            tree.put("default_placement", CgCueCompiler.tree(placement));
            return this;
        }

        public Builder burst(Consumer<EffectsBuilder> effects) {
            return phase("burst", effects);
        }

        public Builder application(Consumer<EffectsBuilder> effects) {
            return phase("application", effects);
        }

        public Builder looping(Consumer<EffectsBuilder> effects) {
            return phase("looping", effects);
        }

        public Builder recurring(Consumer<EffectsBuilder> effects) {
            return phase("recurring", effects);
        }

        public Builder removal(Consumer<EffectsBuilder> effects) {
            return phase("removal", effects);
        }

        /** A burst on {@code event}, under {@code id}. */
        public Builder event(String id, String event, Consumer<EffectsBuilder> effects) {
            EffectsBuilder b = new EffectsBuilder();
            b.tree.put("id", id);
            b.tree.put("event", event);
            effects.accept(b);
            events.add(b.tree);
            return this;
        }

        /** A name gameplay writes each frame, and what it means; interpolated to viewers. */
        public Builder writes(String name, String meaning) {
            writes.put(name, meaning);
            return this;
        }

        /** A name gameplay writes each frame, sent as {@code replication}; {@code rate} a goal's ω, NaN for the default. */
        public Builder writes(String name, String meaning, CgPropertyMeta.Replication replication, float rate) {
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("meaning", meaning);
            w.put("replication", CgCueCompiler.name(replication));
            if (!Float.isNaN(rate)) w.put("rate", (double) rate);
            writes.put(name, w);
            return this;
        }

        /** In blocks. */
        public Builder range(float blocks) {
            tree.put("range", (double) blocks);
            return this;
        }

        public Builder authoritative(boolean authoritative) {
            tree.put("authoritative", authoritative);
            return this;
        }

        public Builder persisted(boolean persisted) {
            tree.put("persisted", persisted);
            return this;
        }

        public Builder prediction(Prediction prediction) {
            tree.put("prediction", CgCueCompiler.name(prediction));
            return this;
        }

        public Builder field(String name, Field.Type type, @Nullable Object fallback) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("name", name);
            f.put("type", CgCueCompiler.name(type));
            if (fallback != null) f.put("fallback", fallback);
            fields.add(f);
            return this;
        }

        /** A looping cue's timeline. */
        public Builder sequence(String sequence) {
            tree.put("sequence", sequence);
            return this;
        }

        private Builder phase(String name, Consumer<EffectsBuilder> effects) {
            EffectsBuilder b = new EffectsBuilder();
            effects.accept(b);
            tree.put(name, b.tree);
            return this;
        }

        /** Its file's tree, as {@code CgAssetYaml.write} writes it. */
        public Map<String, Object> tree() {
            Map<String, Object> out = new LinkedHashMap<>(tree);
            if (!events.isEmpty()) out.put("events", new ArrayList<>(events));
            if (!writes.isEmpty()) out.put("writes", new LinkedHashMap<>(writes));
            if (!fields.isEmpty()) out.put("fields", new ArrayList<>(fields));
            return out;
        }

        /** Its id. */
        public String id() {
            return id;
        }

        /** Compiled with nothing it references compiled; the asset loader compiles a file with its systems. */
        public CgCueDefinition build() {
            return compile(CgAssetData.of(id, CgGameplayKinds.CUE, tree(), null));
        }
    }

    /**
     * One effects block in Java. Each entry takes the block's spawn condition and placement unless it names its own.
     *
     * <pre>{@code
     * e -> e.particles("mymod:beam/body").particles("mymod:beam/glow", Placement.attached("hands"))
     *       .shake("mymod:rumble").sound("mymod:beam_loop")
     * }</pre>
     */
    public static final class EffectsBuilder {
        private final Map<String, Object> tree = new LinkedHashMap<>();

        private EffectsBuilder() {
        }

        public EffectsBuilder particles(String system) {
            return particles(system, null);
        }

        public EffectsBuilder particles(String system, @Nullable Placement placement) {
            return add("particles", entry("system", system, placement));
        }

        public EffectsBuilder sound(Object sound) {
            return add("sounds", entry("sound", sound, null));
        }

        public EffectsBuilder shake(String shake) {
            tree.put("shake", entry("shake", shake, null));
            return this;
        }

        public EffectsBuilder decal(String material) {
            tree.put("decal", entry("material", material, null));
            return this;
        }

        public EffectsBuilder sequence(String sequence) {
            return add("sequences", entry("sequence", sequence, null));
        }

        /** The block's spawn condition, for entries that name none. */
        public EffectsBuilder spawnCondition(SpawnCondition condition) {
            tree.put("spawn_condition", CgCueCompiler.tree(condition));
            return this;
        }

        /** The block's placement, for entries that name none. */
        public EffectsBuilder placement(Placement placement) {
            tree.put("placement", CgCueCompiler.tree(placement));
            return this;
        }

        @SuppressWarnings("unchecked")
        private EffectsBuilder add(String list, Map<String, Object> entry) {
            ((List<Object>) tree.computeIfAbsent(list, k -> new ArrayList<>())).add(entry);
            return this;
        }

        private static Map<String, Object> entry(String key, Object value, @Nullable Placement placement) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put(key, value);
            if (placement != null) e.put("placement", CgCueCompiler.tree(placement));
            return e;
        }
    }
}
