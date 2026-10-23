package com.crystalgraphics.vfx.particle.gpu.sim;

import com.crystalgraphics.vfx.particle.form.CgVfxCurveBlock;
import com.crystalgraphics.vfx.particle.form.CgVfxFormInput;
import com.crystalgraphics.vfx.particle.spawn.CgVfxSpawnFrom;
import com.crystalgraphics.vfx.particle.form.CgVfxSpawnInput;
import java.util.Locale;
import com.crystalgraphics.compute.CgCompute;
import com.crystalgraphics.vfx.particle.gpu.CgVfxDataInterface;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuEmitter;
import com.crystalgraphics.vfx.particle.gpu.CgVfxLane;

import com.crystalgraphics.util.io.CgIO;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Writes a shape's Step kernel: one {@code append} kernel that runs a particle step for every particle of a pool
 * (vfx-gpu §13.2). Element {@code e} below the live count is a particle of the pool's newest version; one past it spawns
 * from the step's spawn rows, born where its emitter's shape puts it ({@code step_place}) and stepped for the part of the
 * step after its birth. Then the modules before the solver, in stack order; the solver; the modules after it;
 * ageing; and the survivors append into the pool's next version. A shape with events appends a row each time one
 * fires ({@link CgVfxParticlePool#EVENT_GLSL}); a shape at a feed tier past 0 is a child, so past its spawn rows it
 * spawns children from the rows the tier before appended this step ({@code step_child}). An event at a clip node fires
 * where the baked clip puts that node ({@code step_node}, {@code CgVfxClipAtlas}).
 *
 * <pre>{@code
 * CgKernel step = CgVfxEmitterCompiler.compile(shape).kernel("Step");
 * String glsl = CgVfxEmitterCompiler.source(shape);     // what it compiles: a .compute
 * }</pre>
 *
 * <ul>
 *   <li>Every tier: an {@code append} kernel lowers, so G40 and G33 run it through transform feedback.</li>
 *   <li>A kind's file is {@code crystalgraphics:shaders/lib/vfx/sim/fx_<kind>.glsl}; a missing one throws from
 *       {@link #compile}, naming it.</li>
 * </ul>
 */
public final class CgVfxEmitterCompiler {

    private static final String KIND_DIR = "crystalgraphics:shaders/lib/vfx/sim/fx_";

    private CgVfxEmitterCompiler() {
    }

    /** Each kernel key's kernels, so a cast that prepares its shape again writes no source. */
    private static final Map<String, CgCompute> COMPILED = new ConcurrentHashMap<>();

    /** {@code shape}'s kernels, parsed once per shape. Any thread; its programs are the render thread's. */
    public static CgCompute compile(CgVfxShape shape) {
        CgCompute compiled = COMPILED.get(shape.kernelKey());
        return compiled != null ? compiled
                : COMPILED.computeIfAbsent(shape.kernelKey(), k -> CgCompute.fromSource(key(shape), source(shape)));
    }


    /** What {@link #compile} registers the source under. */
    public static String key(CgVfxShape shape) {
        return "crystalgraphics:vfx/pool/" + shape.kernelKey();
    }

    /**
     * {@code step_unpack} and {@code step_pack} for {@code record}'s layout, the simulated part alone: a tail attribute
     * it lacks unpacks as its default (PREVIOUS the position, FLAGS none) and is not stored.
     */
    private static void recordCode(StringBuilder s, CgVfxRecord record) {
        int previous = record.word(CgVfxRecord.Tail.PREVIOUS), flags = record.word(CgVfxRecord.Tail.FLAGS);
        int color = record.word(CgVfxRecord.Tail.COLOR), scale = record.word(CgVfxRecord.Tail.SCALE);
        int turn = record.word(CgVfxRecord.Tail.TURN), turnRate = record.word(CgVfxRecord.Tail.TURN_RATE);
        int beam = record.word(CgVfxRecord.Tail.BEAM), light = record.word(CgVfxRecord.Tail.LIGHT);
        int orbit = record.word(CgVfxRecord.Tail.ORBIT);
        int turnState = record.word(CgVfxRecord.Tail.TURN_STATE);
        s.append("\nFxParticle step_unpack(FxRecord r) {\n    FxParticle p;\n");
        unpack(s, "position", "uintBitsToFloat", CgVfxRecord.POSITION, 3);
        unpack(s, "age", "uintBitsToFloat", CgVfxRecord.AGE, 1);
        unpack(s, "velocity", "uintBitsToFloat", CgVfxRecord.VELOCITY, 3);
        unpack(s, "size", "uintBitsToFloat", CgVfxRecord.SIZE, 1);
        unpack(s, "life", "uintBitsToFloat", CgVfxRecord.LIFE, 1);
        unpack(s, "seed", "uintBitsToFloat", CgVfxRecord.SEED, 1);
        unpack(s, "spin", "uintBitsToFloat", CgVfxRecord.SPIN, 1);
        unpack(s, "spinRate", "uintBitsToFloat", CgVfxRecord.SPIN_RATE, 1);
        unpack(s, "heat", "uintBitsToFloat", CgVfxRecord.HEAT, 1);
        unpack(s, "id", "", CgVfxRecord.ID, 1);
        unpack(s, "slot", "", CgVfxRecord.SLOT, 1);
        if (previous >= 0) unpack(s, "previous", "uintBitsToFloat", previous, 3);
        else s.append("    p.previous = p.position;\n");
        if (color >= 0) unpack(s, "color", "uintBitsToFloat", color, 4);
        else s.append("    p.color = vec4(1.0);\n");
        if (scale >= 0) unpack(s, "scale", "uintBitsToFloat", scale, 3);
        else s.append("    p.scale = vec3(1.0);\n");
        if (turn >= 0) unpack(s, "turn", "uintBitsToFloat", turn, 3);
        else s.append("    p.turn = vec3(0.0);\n");
        if (turnRate >= 0) unpack(s, "turnRate", "uintBitsToFloat", turnRate, 3);
        else s.append("    p.turnRate = vec3(0.0);\n");
        for (int v = 0; v < BEAM_FIELDS.length; v++) {
            if (beam >= 0) unpack(s, BEAM_FIELDS[v], "uintBitsToFloat", beam + v * 4, 4);
            else s.append("    p.").append(BEAM_FIELDS[v]).append(" = vec4(0.0);\n");
        }
        if (light >= 0) unpack(s, "light", "uintBitsToFloat", light, 2);
        else s.append("    p.light = vec2(0.0);\n");
        if (orbit >= 0) {
            unpack(s, "orbitOffset", "uintBitsToFloat", orbit, 3);
            unpack(s, "orbitTurn", "uintBitsToFloat", orbit + 3, 3);
            unpack(s, "orbitRate", "uintBitsToFloat", orbit + 6, 3);
        } else {
            s.append("    p.orbitOffset = p.orbitTurn = p.orbitRate = vec3(0.0);\n");
        }
        if (turnState >= 0) {
            unpack(s, "turnBase", "uintBitsToFloat", turnState, 3);
            unpack(s, "turnScale", "uintBitsToFloat", turnState + 3, 3);
        } else {
            s.append("    p.turnBase = vec3(0.0);\n    p.turnScale = vec3(1.0);\n");
        }
        if (flags >= 0) {
            String w = CgVfxRecord.lane("r", flags);
            s.append("    p.resting = (").append(w).append(" & 1u) != 0u;\n    p.state = ").append(w)
                    .append(" & 0xFFFEu;\n    p.collisions = ").append(w).append(" >> 16u;\n");
        } else {
            s.append("    p.resting = false;\n    p.state = 0u;\n    p.collisions = 0u;\n");
        }
        s.append("    p.hit = vec4(0.0);\n    p.hitEntity = 0u;\n    return p;\n}\n");

        String[] w = new String[record.words()];
        pack(w, "p.position", CgVfxRecord.POSITION, 3);
        pack(w, "p.age", CgVfxRecord.AGE, 1);
        pack(w, "p.velocity", CgVfxRecord.VELOCITY, 3);
        pack(w, "p.size", CgVfxRecord.SIZE, 1);
        pack(w, "p.life", CgVfxRecord.LIFE, 1);
        pack(w, "p.seed", CgVfxRecord.SEED, 1);
        pack(w, "p.spin", CgVfxRecord.SPIN, 1);
        pack(w, "p.spinRate", CgVfxRecord.SPIN_RATE, 1);
        pack(w, "p.heat", CgVfxRecord.HEAT, 1);
        w[CgVfxRecord.ID] = "p.id";
        w[CgVfxRecord.SLOT] = "p.slot";
        w[CgVfxRecord.PARAM_ROW] = "paramRow";
        if (previous >= 0) pack(w, "p.previous", previous, 3);
        if (flags >= 0) w[flags] = "((p.resting ? 1u : 0u) | (p.state & 0xFFFEu) | p.collisions << 16u)";
        if (color >= 0) pack(w, "p.color", color, 4);
        if (scale >= 0) pack(w, "p.scale", scale, 3);
        if (turn >= 0) pack(w, "p.turn", turn, 3);
        if (turnRate >= 0) pack(w, "p.turnRate", turnRate, 3);
        if (beam >= 0) for (int v = 0; v < BEAM_FIELDS.length; v++) pack(w, "p." + BEAM_FIELDS[v], beam + v * 4, 4);
        if (light >= 0) pack(w, "p.light", light, 2);
        if (orbit >= 0) {
            pack(w, "p.orbitOffset", orbit, 3);
            pack(w, "p.orbitTurn", orbit + 3, 3);
            pack(w, "p.orbitRate", orbit + 6, 3);
        }
        if (turnState >= 0) {
            pack(w, "p.turnBase", turnState, 3);
            pack(w, "p.turnScale", turnState + 3, 3);
        }
        // The simulated part; the drawn part is the Look stage's (env/compute/vfx/look.compute).
        s.append("\nFxRecord step_pack(FxParticle p, uint paramRow) {\n    FxRecord r;\n");
        for (int v = 0; v < record.vectors(); v++) {
            s.append("    r.w").append(v).append(" = uvec4(");
            for (int k = 0; k < 4; k++) {
                String word = w[v * 4 + k];
                s.append(k > 0 ? ", " : "").append(word != null ? word : "0u");
            }
            s.append(");\n");
        }
        s.append("    return r;\n}\n");
    }

    /** {@code p.<field> = <decode>(the n words from word)}: a uint, or a uvecN, decoded. */
    private static void unpack(StringBuilder s, String field, String decode, int word, int n) {
        s.append("    p.").append(field).append(" = ").append(decode).append('(');
        if (n > 1) s.append("uvec").append(n).append('(');
        for (int k = 0; k < n; k++) s.append(k > 0 ? ", " : "").append(CgVfxRecord.lane("r", word + k));
        s.append(n > 1 ? "));\n" : ");\n");
    }

    /** The n words from {@code word} as {@code value}'s components' bits. */
    private static void pack(String[] w, String value, int word, int n) {
        for (int k = 0; k < n; k++) w[word + k] = "floatBitsToUint(" + value + (n > 1 ? "." + "xyzw".charAt(k) : "") + ")";
    }

    /** The libraries every Step includes: the step's own code, not a kind's. */
    private static final List<String> STEP_LIBRARIES = List.of("crystalgraphics:shaders/lib/vfx/sim/fx_types.glsl",
            "crystalgraphics:shaders/lib/vfx/fx_rand.glsl", "crystalgraphics:shaders/lib/vfx/fx_event.glsl");

    /** The libraries {@code shape}'s inputs bring, in include order. */
    private static List<String> libraries(CgVfxShape shape) {
        List<String> libs = new ArrayList<>();
        if (shape.readsClips()) libs.add("crystalgraphics:shaders/lib/vfx/fx_clip.glsl");
        for (CgVfxDataInterface di : shape.dataInterfaces()) libs.addAll(di.libraries());
        return libs;
    }

    /** What writes each tail attribute in a kind's or a library's code: a value only read stays its default. */
    private static final Pattern[] TAIL_WRITES = {
            Pattern.compile("\\bp\\.previous\\s*([-+|]?=(?!=))"),
            Pattern.compile("\\bp\\.(resting|collisions|state)\\s*([-+|]?=(?!=))|\\bfx_hit\\s*\\("),
            Pattern.compile("\\bp\\.color\\s*([-+*]?=(?!=))"),
            Pattern.compile("\\bp\\.scale\\s*([-+*]?=(?!=))"),
            Pattern.compile("\\bp\\.turn\\s*([-+*]?=(?!=))"),
            Pattern.compile("\\bp\\.turnRate\\s*([-+*]?=(?!=))"),
            Pattern.compile("\\bp\\.beam\\w*\\s*([-+*]?=(?!=))"),
            Pattern.compile("\\bp\\.light\\s*([-+*]?=(?!=))"),
            Pattern.compile("\\bp\\.turn(Base|Scale)\\s*([-+*]?=(?!=))"),
            Pattern.compile("\\bp\\.orbit(Offset|Turn|Rate)\\s*([-+*]?=(?!=))")};
    /** The {@code BEAM} tail's vec4s, in order: {@code FxParticle}'s fields. */
    private static final String[] BEAM_FIELDS = {"beamSource", "beamSourceTangent", "beamTarget", "beamTargetTangent", "beamNoise", "beamTurn"};
    private static final Pattern INCLUDE = Pattern.compile("#include\\s+\"([^\"]+)\"");

    /**
     * {@code shape}'s record layout ({@link CgVfxShape#record()}): the tail attributes whose values must outlive a step,
     * in the order its code first writes them, as Niagara's translator collects a script's attribute writes: FLAGS
     * where its kinds or their libraries rest a particle or count a hit, or an event fires on landing or collision;
     * PREVIOUS where its particles land on a floor, which the Look stage tests the step's start against. The step sets
     * PREVIOUS itself before its kinds run, so a kind reading it needs none stored.
     */
    static CgVfxRecord record(CgVfxShape shape) {
        StringBuilder code = new StringBuilder();
        Set<String> read = new HashSet<>(STEP_LIBRARIES);
        for (String lib : libraries(shape)) appendCode(code, lib, read);
        for (int i = 0; i < shape.modules(); i++) {
            if (shape.source(i) != null) code.append(shape.source(i)).append('\n');
            else appendCode(code, shape.file(i) != null ? shape.file(i) : KIND_DIR + shape.kind(i) + ".glsl", read);
        }
        CgVfxRecord.Tail[] tails = CgVfxRecord.Tail.values();
        int[] first = new int[tails.length];
        for (int t = 0; t < tails.length; t++) {
            Matcher m = TAIL_WRITES[t].matcher(code);
            first[t] = m.find() ? m.start() : Integer.MAX_VALUE;
        }
        for (int e = 0; e < shape.events(); e++) {
            CgVfxEvent.Trigger trigger = shape.trigger(e);
            if (trigger == CgVfxEvent.Trigger.LANDING || trigger == CgVfxEvent.Trigger.COLLISION) {
                first[CgVfxRecord.Tail.FLAGS.ordinal()] = Math.min(first[CgVfxRecord.Tail.FLAGS.ordinal()], code.length());
            }
        }
        if (shape.floors()) {
            first[CgVfxRecord.Tail.PREVIOUS.ordinal()] = Math.min(first[CgVfxRecord.Tail.PREVIOUS.ordinal()], code.length() + 1);
        }
        // A start in a form is the launch's to store: the Look stage reads it.
        if (shape.spawnFormOf(CgVfxSpawnInput.COLOR) >= 0) {
            first[CgVfxRecord.Tail.COLOR.ordinal()] = Math.min(first[CgVfxRecord.Tail.COLOR.ordinal()], code.length() + 2);
        }
        if (shape.spawnFormOf(CgVfxSpawnInput.MESH_SCALE) >= 0) {
            first[CgVfxRecord.Tail.SCALE.ordinal()] = Math.min(first[CgVfxRecord.Tail.SCALE.ordinal()], code.length() + 3);
        }
        if (shape.spawnFormOf(CgVfxSpawnInput.MESH_ORIENTATION) >= 0) {
            first[CgVfxRecord.Tail.TURN.ordinal()] = Math.min(first[CgVfxRecord.Tail.TURN.ordinal()], code.length() + 4);
        }
        if (shape.spawnFormOf(CgVfxSpawnInput.MESH_ROTATION_RATE) >= 0) {
            first[CgVfxRecord.Tail.TURN_RATE.ordinal()] = Math.min(first[CgVfxRecord.Tail.TURN_RATE.ordinal()], code.length() + 5);
        }
        if (shape.spawnFormOf(CgVfxSpawnInput.LIGHT_RADIUS) >= 0) {
            first[CgVfxRecord.Tail.LIGHT.ordinal()] = Math.min(first[CgVfxRecord.Tail.LIGHT.ordinal()], code.length() + 6);
        }
        if (shape.spawnFormOf(CgVfxSpawnInput.ORBIT_OFFSET) >= 0) {
            first[CgVfxRecord.Tail.ORBIT.ordinal()] = Math.min(first[CgVfxRecord.Tail.ORBIT.ordinal()], code.length() + 7);
        }
        List<CgVfxRecord.Tail> tail = new ArrayList<>();
        for (CgVfxRecord.Tail t : tails) if (first[t.ordinal()] != Integer.MAX_VALUE) tail.add(t);
        tail.sort(Comparator.comparingInt(t -> first[t.ordinal()]));
        return CgVfxRecord.of(tail);
    }

    /** {@code path}'s text, each include it names before it once, into {@code code}; none for a missing file. */
    private static void appendCode(StringBuilder code, String path, Set<String> read) {
        if (!read.add(path)) return;
        String text = CgIO.loadSource(path);
        if (text == null) return;   // the compile names it
        Matcher m = INCLUDE.matcher(text);
        while (m.find()) appendCode(code, m.group(1), read);
        code.append(text).append('\n');
    }

    /** {@code shape}'s Step kernel as {@code .compute} text. */
    public static String source(CgVfxShape shape) {
        StringBuilder s = new StringBuilder(4096);
        s.append("// Written by CgVfxEmitterCompiler for ").append(shape.kernelKey()).append("\n");
        s.append("#pragma kernel Step append\n\n");
        for (String lib : STEP_LIBRARIES) s.append("#include \"").append(lib).append("\"\n");
        for (String lib : libraries(shape)) s.append("#include \"").append(lib).append("\"\n");
        // Declared ahead of the kinds' functions that call them; defined with the other step_ functions.
        for (CgVfxDataInterface di : shape.dataInterfaces()) s.append(di.declarations());
        Set<String> kinds = new LinkedHashSet<>();
        for (int i = 0; i < shape.modules(); i++) {
            if (!kinds.add(shape.kind(i))) continue;
            if (shape.source(i) == null) {
                String file = shape.file(i) != null ? shape.file(i) : KIND_DIR + shape.kind(i) + ".glsl";
                s.append("#include \"").append(file).append("\"\n");
            }
            else s.append("\n// fx_").append(shape.kind(i)).append(", given as text\n").append(shape.source(i)).append('\n');
        }
        s.append("""

                Properties {
                    _Step       ("Step seconds, then the wind",      vec4) = (0, 0, 0, 0)
                    _InstanceAt ("This step's instance rows",        int)  = 0
                    _SpawnAt    ("This step's spawn rows",           int)  = 0
                    _SpawnRows  ("How many spawn rows",              int)  = 0
                    _Spawned    ("Spawn candidates, every row's",    int)  = 0
                    _PoolId     ("The pool, as event rows name it",  int)  = 0
                    _ChildRows  ("Parent event rows read at most",   int)  = 0
                    _ParentRegions ("Regions of the parents' events", int) = 0
                    _RegionBase ("This pool's first region in its tier", int) = 0
                    _ChildMax   ("Children an event spawns at most", int)  = 0
                    _Feeds      ("Feed rows",                        int)  = 0
                """);
        if (shape.publishes()) s.append("    _BinKeyBase (\"This pool's first published slot in its tier\", int) = 0\n");
        if (shape.spawnFrom() != null && shape.spawnFrom().itself()) {
            s.append("    _StepIndex  (\"This step's place in the frame's\", int) = 0\n");
        }
        for (CgVfxDataInterface di : shape.dataInterfaces()) s.append(di.properties());
        for (int i = 0; i < shape.modules(); i++) {
            for (int t = 0; t < shape.textureCount(i); t++) {
                for (int e = 0; e < CgVfxParticlePool.TEXTURE_ENTRIES; e++) {
                    s.append("    ").append(texture(i, t, e)).append(" (\"fx_").append(shape.kind(i)).append("'s texture ").append(t)
                            .append(", entry ").append(e).append("\", ").append(shape.volume(i, t) ? "sampler3D" : "sampler2D")
                            .append(") = \"black\"\n");
                }
            }
        }
        s.append("}\n\n");
        CgVfxRecord record = shape.record();
        s.append(record.glsl()).append("\n");
        s.append(CgVfxParticlePool.EVENT_GLSL).append("\n\n");
        for (CgVfxDataInterface di : shape.dataInterfaces()) s.append(di.structs());
        s.append("""
                Buffers {
                    IN        ("Particles",       FxRecord, readonly)
                    OUT       ("Particles after", FxRecord, append)
                    LIVE      ("Live",            uint,     readonly)
                    PARAMS    ("Parameter rows",  uvec4,    readonly)
                    INSTANCES ("Instance rows",   uvec4,    readonly)
                    SPAWNS    ("Spawn rows",      uvec4,    readonly)
                """);
        if (shape.spawns()) s.append("    SPAWN_EVENTS  (\"Events that spawn children, each tagged with its region\", FxEvent, append)\n");
        if (shape.reports()) s.append("    REPORT_EVENTS (\"Events the CPU hears\",       FxEvent, append)\n");
        for (CgVfxDataInterface di : shape.dataInterfaces()) s.append(di.buffers());
        if (shape.readsClips()) s.append("    FX_CLIPS      (\"Baked clips\", vec4, readonly)\n");
        if (shape.publishes()) {
            s.append("    PUBLISHED     (\"Live particles, keyed by slot, for the tier after to be born on\", FxEvent, append)\n");
        }
        if (shape.spawnFrom() != null) {
            s.append("    SOURCE_ROWS   (\"The tier before's published particles, sorted by slot\", FxEvent, readonly)\n");
            s.append("    SOURCE_RUNS   (\"Each published slot's first sorted row, then its end\", uint, readonly)\n");
            s.append("    READS         (\"Each slot's source: its published slot, inherited velocity and spin\", uvec4, readonly)\n");
            if (shape.spawnFrom().follow()) {
                s.append("    SOURCE_DIRECT (\"The tier before's published particles by slot and id\", FxEvent, readonly)\n");
                s.append("    SOURCE_SLOTS  (\"Each published slot's direct base, then its capacity\", uint, readonly)\n");
            }
        }
        if (shape.tier() > 0) {
            s.append("    PARENT_EVENTS  (\"This step's parents' events, sorted by region\",      FxEvent, readonly)\n");
            s.append("    PARENT_RUNS    (\"Each region's first sorted row, then its end\",       uint,    readonly)\n");
            s.append("    PARENT_REGIONS (\"Each region's first capped row and its cap\",         uvec4,   readonly)\n");
            s.append("    FEEDS          (\"Child slots by parent slot, two vec4s each\",          uvec4,   readonly)\n");
        }
        s.append("}\n\n");
        s.append("const int STEP_PARAM_ROW = ").append(shape.paramRowVectors()).append(";\n");
        s.append("const int STEP_KEYS = ").append(shape.keysAt()).append(";\n");
        // The launch's roll keys by name (CgVfxGpuEmitter.KEY_*).
        s.append("const int STEP_KEY_SHAPE = ").append(CgVfxGpuEmitter.KEY_SHAPE).append(", STEP_KEY_CONE = ")
                .append(CgVfxGpuEmitter.KEY_CONE).append(", STEP_KEY_SEED = ").append(CgVfxGpuEmitter.KEY_SEED)
                .append(", STEP_KEY_SPIN = ").append(CgVfxGpuEmitter.KEY_SPIN).append(", STEP_KEY_ROTATION = ")
                .append(CgVfxGpuEmitter.KEY_ROTATION).append(", STEP_KEY_THIN = ").append(CgVfxGpuEmitter.KEY_THIN).append(";\n");
        s.append("const int STEP_CURVE_TEXELS = ").append(CgVfxCurveBlock.TEXELS).append(";\n");
        s.append("const int STEP_INSTANCE_ROW = ").append(shape.instanceRowVectors()).append(";\n");
        s.append("const int STEP_SPAWN_ROW = ").append(CgVfxParticlePool.SPAWN_ROW_WORDS / 4).append(";\n");
        if (shape.events() > 0) {
            s.append("const int STEP_EVENT_NODES = ").append(shape.eventField(CgVfxShape.EVENT_NODES)).append(";\n");
            s.append("const int STEP_EVENT_CLIP = ").append(shape.eventField(CgVfxShape.EVENT_CLIP)).append(";\n");
        }
        // Past the first level a key hashes every bit of its parent's, a full key itself.
        if (shape.tier() == 1) {
            s.append("#define STEP_CHILD_KEY(I) (ev.ids.w == 0u ? fx_child_key(ev.ids.x, ev.ids.z, uint(I)) "
                    + ": fx_child_key_at(ev.ids.x, ev.ids.z, uint(I), ev.ids.w))\n");
        } else if (shape.tier() > 1) {
            s.append("#define STEP_CHILD_KEY(I) fx_grandchild_key(ev.ids.x, ev.ids.z, uint(I), ev.ids.w)\n");
        }
        if (shape.tier() > 0) s.append("const int STEP_MAX_CHILDREN = ").append(CgVfxEvent.MAX_CHILDREN).append(";\n");
        s.append("""

                vec4 step_param(int at) {
                    return uintBitsToFloat(PARAMS(at));
                }

                vec4 step_instance(int at) {
                    return uintBitsToFloat(INSTANCES(at));
                }
""");
        stepVector(s, shape);
        recordCode(s, record);
        List<CgVfxDataInterface> interfaces = shape.dataInterfaces();
        for (int d = 0; d < interfaces.size(); d++) s.append(interfaces.get(d).functions(shape.dataLanesAt(d)));
        s.append("""

                // A launch's local (x, up, z) turned so up is n: Duff et al. 2017's basis, the identity for straight up.
                vec3 step_orient(vec3 v, vec3 n) {
                    if (n.x == 0.0 && n.z == 0.0 && n.y > 0.0) return v;
                    float sg = n.z >= 0.0 ? 1.0 : -1.0;
                    float a = -1.0 / (sg + n.z), b = n.x * n.y * a;
                    vec3 t = vec3(1.0 + sg * n.x * n.x * a, sg * b, -sg * n.x);
                    vec3 u = vec3(b, sg + n.y * n.y * a, -n.y);
                    return t * v.x + n * v.y + u * v.z;
                }

                // A form's curve (CgVfxCurveBlock) from the vec4 at `at`: its domain's start and span, then its texels,
                // sampled linearly at t, clamped at the ends.
                // Roll key i of the definition at row: its element's id and input's name (CgVfxShape.keys), xored into
                // the seed of every draw of that input.
                uint step_key(int row, int i) {
                    return floatBitsToUint(step_param(row + STEP_KEYS + i / 4)[i % 4]);
                }

                vec4 step_curve(int at, float t) {
                    vec4 d = step_param(at);
                    float u = d.y > 0.0 ? clamp((t - d.x) / d.y, 0.0, 1.0) * float(STEP_CURVE_TEXELS - 1) : 0.0;
                    int i = min(STEP_CURVE_TEXELS - 2, int(u));
                    return mix(step_param(at + 1 + i), step_param(at + 2 + i), u - float(i));
                }

                // An array's index i addressed as its header says (length, interpolate, clamp 0 | wrap 1 | mirror 2).
                int step_array_index(vec4 h, int i) {
                    int n = int(h.x), a = int(h.z);
                    if (a == 1) return ((i % n) + n) % n;
                    if (a == 2) {
                        int period = 2 * (n - 1);
                        if (period == 0) return 0;
                        int m = ((i % period) + period) % period;
                        return m < n ? m : period - m;
                    }
                    return clamp(i, 0, n - 1);
                }

                // A form's array (CgVfxFormInput.Array) from the vec4 at `at`: by a random draw (mode 0), by an index
                // (1), or by a lookup across it (2), nearest or interpolated.
                vec4 step_array(int at, float t, int mode) {
                    vec4 h = step_param(at);
                    int n = int(h.x);
                    if (mode == 0) return step_param(at + 1 + min(n - 1, int(t * float(n))));
                    if (mode == 1) return step_param(at + 1 + step_array_index(h, int(t)));
                    float u = t * float(n - 1);
                    int i = int(floor(u));
                    if (h.y == 0.0) return step_param(at + 1 + step_array_index(h, int(floor(u + 0.5))));
                    return mix(step_param(at + 1 + step_array_index(h, i)), step_param(at + 1 + step_array_index(h, i + 1)), u - float(i));
                }

                const float STEP_TAU = 6.2831853;

                vec3 step_rotate(vec4 q, vec3 v) {
                    vec3 t = 2.0 * cross(q.xyz, v);
                    return v + q.w * t + cross(q.xyz, t);
                }

                // The heading round a shape's axis: spawn k's place among `ordered` even ones, else drawn, within its arc.
                float step_heading(uint seed, uint k, uint draw, vec4 s6) {
                    int n = int(s6.w);
                    if (n == 0) return fx_rand(seed, k, draw) * s6.z;
                    float i = float(k % uint(n));
                    if (s6.z >= STEP_TAU - 1e-4) return i / float(n) * s6.z;
                    return n > 1 ? i / float(n - 1) * s6.z : 0.0;
                }

                // A box of half extents e: filled, its faces by area, or its edges by length.
                void step_box(vec3 e, int placement, bool upper, float u1, float u2, float u3, float u4, out vec3 pos,
                              out vec3 way) {
                    if (placement == 0) {
                        pos = (vec3(u1, u2, u3) * 2.0 - 1.0) * e;
                    } else if (placement == 1) {
                        vec3 area = vec3(e.y * e.z, e.x * e.z, e.x * e.y);
                        float t = u1 * (area.x + area.y + area.z);
                        int axis = t < area.x ? 0 : t < area.x + area.y ? 1 : 2;
                        float face = u4 < 0.5 ? -1.0 : 1.0;
                        if (axis == 0) pos = vec3(face * e.x, (u2 * 2.0 - 1.0) * e.y, (u3 * 2.0 - 1.0) * e.z);
                        else if (axis == 1) pos = vec3((u2 * 2.0 - 1.0) * e.x, face * e.y, (u3 * 2.0 - 1.0) * e.z);
                        else pos = vec3((u2 * 2.0 - 1.0) * e.x, (u3 * 2.0 - 1.0) * e.y, face * e.z);
                    } else {
                        // An axis, which of its four, then along it.
                        float t = u1 * (e.x + e.y + e.z);
                        int axis = t < e.x ? 0 : t < e.x + e.y ? 1 : 2;
                        int corner = int(u2 * 4.0);
                        vec3 c = vec3((corner & 1) != 0 ? 1.0 : -1.0, (corner & 2) != 0 ? 1.0 : -1.0, 1.0);
                        if (axis == 0) pos = vec3((u3 * 2.0 - 1.0) * e.x, c.x * e.y, c.y * e.z);
                        else if (axis == 1) pos = vec3(c.x * e.x, (u3 * 2.0 - 1.0) * e.y, c.y * e.z);
                        else pos = vec3(c.x * e.x, c.y * e.y, (u3 * 2.0 - 1.0) * e.z);
                    }
                    if (upper) pos.y = abs(pos.y);
                    float d = dot(pos, pos);
                    way = d > 1e-12 ? pos / sqrt(d) : vec3(0.0, 1.0, 0.0);
                }

                // CgVfxSpawnShape: where spawn k of the instance at inst is born in its shape's sampling frame (axis
                // +Y), and the way out of the shape's middle there; a cylinder's half height is the launch's.
                void step_place(uint seed, uint k, int row, int inst, float halfHeight, out vec3 pos, out vec3 way) {
                    seed ^= step_key(row, STEP_KEY_SHAPE);
                    vec4 s6 = step_vector(row, inst, 6), n = step_vector(row, inst, 7);
                    int kind = int(s6.x), flags = int(s6.y), placement = flags & 3;
                    bool upper = (flags & 4) != 0;
                    float u1 = fx_rand(seed, k, 11u), u2 = fx_rand(seed, k, 12u), u3 = fx_rand(seed, k, 13u);
                    float u4 = fx_rand(seed, k, 14u);
                    if (kind <= 1) {
                        // A point or a sphere: born along a direction drawn evenly, at a heading its arc and order allow.
                        float up = fx_rand(seed, k, 0u) * 2.0 - 1.0;
                        if (upper) up = abs(up);
                        if (placement == 2) up = 0.0;
                        float h = step_heading(seed, k, 1u, s6), across = sqrt(max(1.0 - up * up, 0.0));
                        way = vec3(across * cos(h), up, across * sin(h));
                        float r = kind == 0 ? 0.0 : placement == 0
                                ? pow(mix(n.x * n.x * n.x, n.y * n.y * n.y, fx_rand(seed, k, 2u)), 1.0 / 3.0) : n.y;
                        pos = way * r;
                        return;
                    }
                    if (kind == 2) {
                        step_box(n.xyz, placement, upper, u1, u2, u3, u4, pos, way);
                        return;
                    }
                    if (kind == 8) {
                        // Cascade's triangle (LocationPrimitiveTriangle::SpawnEx): height, half base, thickness; its
                        // corners weighted by three uniforms over their sum, launched out of its middle.
                        float hx = n.x * 0.5, sum = max(u1 + u2 + u3, 1e-4);
                        pos = (u1 * vec3(hx, 0.0, 0.0) + u2 * vec3(-hx, 0.0, n.y) + u3 * vec3(-hx, 0.0, -n.y)) / sum;
                        pos.y = u4 * n.z - 0.5 * n.z;
                        vec3 from = pos - vec3(-n.x / 6.0, 0.0, 0.0);
                        float d = dot(from, from);
                        way = d > 1e-12 ? from * inversesqrt(d) : vec3(0.0, 1.0, 0.0);
                        return;
                    }
                    if (kind == 7) {
                        // The followed entity's box, from the instance row: its centre from the source, its halves.
                        step_box(step_instance(inst + 6).xyz, placement, upper, u1, u2, u3, u4, pos, way);
                        pos += step_instance(inst + 5).xyz;
                        return;
                    }
                """);
        if (shape.spawnsOnMesh()) s.append("""
                    if (kind == 6) {
                        step_mesh(k, n, placement, s6, u1, u2, u3, u4, pos, way);
                        return;
                    }
                """);
        s.append("""
                    float h = step_heading(seed, k, 11u, s6);
                    vec3 ring = vec3(cos(h), 0.0, sin(h));
                    if (kind == 3) {
                        // A cylinder or ring: inner and outer radius, half height.
                        float r, y, hz = halfHeight;
                        if (placement == 0) {
                            r = sqrt(mix(n.x * n.x, n.y * n.y, u2));
                            y = (u3 * 2.0 - 1.0) * hz;
                        } else if (placement == 1) {
                            float outer = n.y * 2.0 * hz, inner = n.x * 2.0 * hz, caps = n.y * n.y - n.x * n.x;
                            float t = u2 * (outer + inner + caps);
                            if (t < outer + inner) {
                                r = t < outer ? n.y : n.x;
                                y = (u3 * 2.0 - 1.0) * hz;
                            } else {
                                r = sqrt(mix(n.x * n.x, n.y * n.y, u3));
                                y = u4 < 0.5 ? -hz : hz;
                            }
                        } else {
                            r = n.y;
                            y = hz == 0.0 ? 0.0 : u3 < 0.5 ? -hz : hz;
                        }
                        pos = vec3(ring.x * r, upper ? abs(y) : y, ring.z * r);
                        way = ring;
                        return;
                    }
                    if (kind == 4) {
                        // A cone from its apex: range, half angle; a spherical sector, its cap and its side by area.
                        float ca = cos(n.y), ct, r;
                        if (placement == 0) {
                            ct = 1.0 - u2 * (1.0 - ca);
                            r = n.x * pow(u3, 1.0 / 3.0);
                        } else if (placement == 1) {
                            float cap = 2.0 * (1.0 - ca), side = sin(n.y);
                            if (u2 * (cap + side) < cap) {
                                ct = 1.0 - u3 * (1.0 - ca);
                                r = n.x;
                            } else {
                                ct = ca;
                                r = n.x * sqrt(u3);
                            }
                        } else {
                            ct = ca;
                            r = n.x;
                        }
                        float st = sqrt(max(1.0 - ct * ct, 0.0));
                        way = vec3(ring.x * st, ct, ring.z * st);
                        pos = way * r;
                        return;
                    }
                    // A capsule along its segment: half length, radius; its side and its ends by volume or area.
                    float L = n.x, rr = n.y;
                    if (placement == 2) {
                        int count = int(s6.w);
                        float y = count > 1 ? -L + 2.0 * L * float(k % uint(count)) / float(count - 1)
                                : count == 1 ? 0.0 : (u3 * 2.0 - 1.0) * L;
                        float h2 = fx_rand(seed, k, 11u) * STEP_TAU;
                        pos = vec3(0.0, upper ? abs(y) : y, 0.0);
                        way = vec3(cos(h2), 0.0, sin(h2));
                        return;
                    }
                    bool side = placement == 0 ? u2 * (2.0 * L + 4.0 / 3.0 * rr) < 2.0 * L : u2 * (L + rr) < L;
                    float rho = placement == 0 ? rr * (side ? sqrt(u4) : pow(u4, 1.0 / 3.0)) : rr;
                    if (side) {
                        pos = vec3(ring.x * rho, (u3 * 2.0 - 1.0) * L, ring.z * rho);
                        way = ring;
                    } else {
                        float e = u3 * 2.0 - 1.0, across = sqrt(max(1.0 - e * e, 0.0));
                        way = vec3(ring.x * across, e, ring.z * across);
                        pos = way * rho + vec3(0.0, e >= 0.0 ? L : -L, 0.0);
                    }
                    if (upper && pos.y < 0.0) {
                        pos.y = -pos.y;
                        way.y = -way.y;
                    }
                }

                // Spawn k of the instance at inst from seed with the row's numbers, at `at`, its frame turned by `turn` (the
                // instance's rotation, a quaternion) then its up turned to n (an event's normal), front blocks further out;
                // `loop` is its loop's seconds, what a spawn form by the loop reads. False where it spawns nothing.
                bool step_launch(uint seed, uint k, int row, int inst, vec3 at, vec3 n, vec4 turn, float front, float loop,
                                 out FxParticle p) {
                    vec4 s0 = step_vector(row, inst, 0), s1 = step_vector(row, inst, 1), s2 = step_vector(row, inst, 2);
                    vec4 s4 = step_vector(row, inst, 4);
                    vec4 q = step_vector(row, inst, 8);
                    vec3 local, way;
                    step_place(seed, k, row, inst, @HALF_HEIGHT@, local, way);
                    vec3 outward = step_rotate(q, way), dir;
                    int mode = int(s4.w);
                    if (mode == 0) dir = outward;
                    else if (mode == 1) dir = -outward;
                    else {
                        // Niagara's cone: the angle from its axis drawn evenly between its inner and outer half angles,
                        // the turn round it evenly (NiagaraStatelessModule_SolveVelocitiesAndForces.ush).
                        float angle = mix(s4.x, @CONE@, fx_rand(seed ^ step_key(row, STEP_KEY_CONE), k, 0u)),
                              phi = fx_rand(seed ^ step_key(row, STEP_KEY_CONE), k, 1u) * STEP_TAU;
                        float st = sin(angle);
                        dir = step_orient(vec3(st * cos(phi), cos(angle), st * sin(phi)), step_vector(row, inst, 5).xyz);
                    }
                    vec3 place = step_rotate(q, local) + @OFFSET@;
                    float speed = @SPEED@;
                    p.position = at + step_orient(step_rotate(turn, place + outward * front), n);
                    p.previous = p.position;
                    p.velocity = step_orient(step_rotate(turn, dir), n) * speed@VELOCITY@;
                    p.age = 0.0;
                    p.life = @LIFE@;
                    p.size = @SIZE@;
                    p.seed = fx_rand(seed ^ step_key(row, STEP_KEY_SEED), k, 0u);
                    uint spinKey = seed ^ step_key(row, STEP_KEY_SPIN);
                    float spin = s2.x + (s2.y - s2.x) * fx_rand(spinKey, k, 0u);
                    p.spinRate = fx_rand(spinKey, k, 1u) < 0.5 ? -spin : spin;
                    p.spin = @SPIN@;
                    p.heat = s1.w;
                    p.color = @COLOR@;
                    p.scale = @SCALE@;
                    p.turn = @TURN@;
                    p.turnRate = @TURN_RATE@;
                    p.light = @LIGHT@;
                    p.orbitOffset = @ORBIT_OFFSET@;
                    p.orbitTurn = @ORBIT_TURN@;
                    p.orbitRate = @ORBIT_RATE@;
                    p.resting = false;
                    p.state = 0u;
                    p.turnBase = vec3(0.0);
                    p.turnScale = vec3(1.0);
                    p.collisions = 0u;
                    p.hit = vec4(0.0);
                    p.hitEntity = 0u;
@SOCKETS@                    return true;
                }

@DIRECT@                // Spawn k, the i-th of the n of the spawn row at `at` (one CPU tick's), of the instance in slot, unless its
                // share thins it out: a burst where the source was as the tick began, else a rate spawn (i - bursts + 1) /
                // (n - bursts) of the way across the tick to where it ended. `born` is the seconds into the step it was
                // born at: it is stepped for the rest.
                bool step_spawn(int at, uint slot, uint k, int i, int n, out FxParticle p, out float born) {
                    born = 0.0;
                    int inst = _InstanceAt + int(slot) * STEP_INSTANCE_ROW;
                    uvec4 head = INSTANCES(inst);
                    float share = step_instance(inst + 2).x;
                    uint seed = head.y;
                    int row = int(head.x) * STEP_PARAM_ROW;
                    if (share < 1.0 && fx_rand(seed ^ step_key(row, STEP_KEY_THIN), k, 0u) >= share) return false;
                    vec4 when = uintBitsToFloat(SPAWNS(at + 1)), from = uintBitsToFloat(SPAWNS(at + 2));
                    vec4 to = uintBitsToFloat(SPAWNS(at + 3));
                    int bursts = int(when.z);
                    float along = i >= bursts ? float(i - bursts + 1) / float(n - bursts) : 0.0;
                    born = mix(when.x, when.y, along);
                    float loop = mix(from.w, to.w, along);
                    if (!step_launch(seed, k, row, inst, mix(from.xyz, to.xyz, along), vec3(0.0, 1.0, 0.0), step_instance(inst + 7),
                            @FRONT@, loop, p)) return false;
                    p.velocity += step_vector(row, inst, 2).z * (to.xyz - from.xyz) / when.w;
@READ@                    p.id = k;
                    p.slot = slot;
                    return true;
                }

                """.replace("@SPEED@", spawnValue(shape, CgVfxSpawnInput.SPEED, "s0.x", "s0.x + (s0.y - s0.x) * " + roll(CgVfxGpuEmitter.KEY_SPEED), ""))
                .replace("@LIFE@", spawnValue(shape, CgVfxSpawnInput.LIFETIME, "s0.z", "s0.z + (s0.w - s0.z) * " + roll(CgVfxGpuEmitter.KEY_LIFE), ""))
                .replace("@SIZE@", spawnValue(shape, CgVfxSpawnInput.SPRITE_SIZE, "s1.x", "s1.x + (s1.y - s1.x) * " + roll(CgVfxGpuEmitter.KEY_SIZE), ""))
                .replace("@FRONT@", front(shape))
                .replace("@READ@", read(shape))
                .replace("@DIRECT@", direct(shape))
                .replace("@SOCKETS@", sockets(shape))
                .replace("@CONE@", spawnValue(shape, CgVfxSpawnInput.CONE_ANGLE, null, "s4.y", "0.5 * "))
                .replace("@OFFSET@", spawnValue(shape, CgVfxSpawnInput.OFFSET, null, "step_vector(row, inst, 9).xyz", ""))
                .replace("@VELOCITY@", shape.spawnFormOf(CgVfxSpawnInput.VELOCITY) < 0 ? ""
                        : " + step_orient(step_rotate(turn, " + spawnValue(shape, CgVfxSpawnInput.VELOCITY, null, null, "") + "), n)")
                .replace("@COLOR@", colorStart(shape))
                .replace("@HALF_HEIGHT@", spawnValue(shape, CgVfxSpawnInput.CYLINDER_HEIGHT, null, "step_vector(row, inst, 7).z", "0.5 * "))
                .replace("@SCALE@", spawnValue(shape, CgVfxSpawnInput.MESH_SCALE, null, "vec3(1.0)", ""))
                .replace("@TURN@", spawnValue(shape, CgVfxSpawnInput.MESH_ORIENTATION, null, "vec3(0.0)", ""))
                .replace("@TURN_RATE@", spawnValue(shape, CgVfxSpawnInput.MESH_ROTATION_RATE, null, "vec3(0.0)", ""))
                .replace("@ORBIT_OFFSET@", spawnValue(shape, CgVfxSpawnInput.ORBIT_OFFSET, null, "vec3(0.0)", ""))
                .replace("@ORBIT_TURN@", spawnValue(shape, CgVfxSpawnInput.ORBIT_ROTATION, null, "vec3(0.0)", ""))
                .replace("@ORBIT_RATE@", spawnValue(shape, CgVfxSpawnInput.ORBIT_RATE, null, "vec3(0.0)", ""))
                .replace("@LIGHT@", "vec2(" + spawnValue(shape, CgVfxSpawnInput.LIGHT_RADIUS, null, "0.0", "") + ", "
                        + spawnValue(shape, CgVfxSpawnInput.LIGHT_EXPONENT, null, "0.0", "") + ")")
                .replace("@SPIN@", spawnValue(shape, CgVfxSpawnInput.SPRITE_ROTATION, null,
                        "fx_rand(seed ^ step_key(row, STEP_KEY_ROTATION), k, 0u) * 6.2831853", "")));
        if (shape.tier() > 0) s.append("""
                // Child c of this step's parent events: row c / _ChildMax's child c % _ChildMax, for the slot its feed
                // names, launched about the event's normal from where it fired, with a share of its velocity. The
                // tier's rows come sorted by region (a pool's event, env/compute/vfx/event_cap.compute): row r is a
                // region's local-th, and only its first max-per-frame spawn.
                bool step_child(int c, out FxParticle p) {
                    if (_ChildMax == 0 || _Feeds == 0) return false;
                    int r = c / _ChildMax, i = c - r * _ChildMax;
                    if (r >= _ChildRows) return false;
                    int lo = 0, hi = _ParentRegions - 1;
                    while (lo < hi) {
                        int mid = (lo + hi + 1) >> 1;
                        if (int(PARENT_REGIONS(mid).x) <= r) lo = mid; else hi = mid - 1;
                    }
                    uint local = uint(r) - PARENT_REGIONS(lo).x, row = PARENT_RUNS(lo * 2) + local;
                    if (local >= PARENT_REGIONS(lo).y || row >= PARENT_RUNS(lo * 2 + 1)) return false;
                    FxEvent ev = PARENT_EVENTS(int(row));
                    // Feeds sorted by the parent's pool and slot, then its event in y's high half.
                    uint key = ev.ids.y;
                    lo = 0;
                    hi = _Feeds - 1;
                    while (lo < hi) {
                        int mid = (lo + hi) >> 1;
                        uvec4 probe = FEEDS(mid * 2);
                        if (probe.x < key || probe.x == key && (probe.y >> 16u) < ev.ids.z) lo = mid + 1; else hi = mid;
                    }
                    uvec4 feed = FEEDS(lo * 2);
                    // Its count: from z's low half to its high half, rolled per firing where they differ.
                    uint least = feed.z & 0xFFFFu, most = feed.z >> 16u;
                    if (feed.x != key || (feed.y >> 16u) != ev.ids.z || uint(i) >= most) return false;
                    uint slot = feed.y & 0xFFFFu;
                    int inst = _InstanceAt + int(slot) * STEP_INSTANCE_ROW;
                    uvec4 head = INSTANCES(inst);
                    float share = step_instance(inst + 2).x;
                    // A repeating event's firing, from 1, in w: each firing's children are other particles.
                    uint seed = head.y, k = STEP_CHILD_KEY(i);
                    uvec4 more = FEEDS(lo * 2 + 1);
                    if (more.w != 0u) {
                        // Cascade's: a float between the ends, rounded to the nearest (RoundToInt).
                        float low = uintBitsToFloat(feed.w), high = uintBitsToFloat(more.w);
                        float roll = fx_rand(seed, STEP_CHILD_KEY(STEP_MAX_CHILDREN), 0u);
                        if (float(i) >= floor(low + roll * (high - low) + 0.5)) return false;
                    } else if (least < most) {
                        uint n = least + min(uint(fx_rand(seed, STEP_CHILD_KEY(STEP_MAX_CHILDREN), 0u) * float(most - least + 1u)),
                                most - least);
                        if (uint(i) >= n) return false;
                    }
                    if (share < 1.0 && fx_rand(seed ^ step_key(int(head.x) * STEP_PARAM_ROW, STEP_KEY_THIN), k, 0u) >= share) return false;
                    // From the parent's block and the place within it to this slot's origin.
                    vec3 at = vec3(ev.block.xyz - ivec3(INSTANCES(inst + 3).xyz)) + ev.position.xyz - step_instance(inst + 4).xyz;
                    // Launched about the event's normal: the instance's rotation turned its parent already.
                    if (!step_launch(seed, k, int(head.x) * STEP_PARAM_ROW, inst, at, ev.normal.xyz, vec4(0.0, 0.0, 0.0, 1.0), 0.0, 0.0, p)) {
                        return false;
                    }
                    p.velocity += uintBitsToFloat(more.xyz) * ev.velocityAge.xyz;
                    p.id = k;
                    p.slot = slot;
                    return true;
                }
""");
        if (shape.events() > 0 && shape.readsClips()) s.append("""
                // Event e's clip node at p's age, from the slot's origin, sized by p and turned with its effect: where
                // it fires, and the node's +Y its normal. Its word is the node's first vec4, its top bit set where the
                // node's matrix steps.
                void step_node(FxParticle p, int inst, int row, int e, out vec3 at, out vec3 n) {
                    uint word = floatBitsToUint(step_param(row + STEP_EVENT_NODES + e / 4)[e % 4]);
                    vec4 clip = step_param(row + STEP_EVENT_CLIP);
                    int a, b, len = int(clip.x), base = int(word & 0x7FFFFFFFu);
                    float t;
                    fx_clip_frames(p.age, clip.y, clip.z, (word >> 31u) != 0u, a, b, t);
                    vec4 r0 = mix(FX_CLIPS(base + a), FX_CLIPS(base + b), t);
                    vec4 r1 = mix(FX_CLIPS(base + len + a), FX_CLIPS(base + len + b), t);
                    vec4 r2 = mix(FX_CLIPS(base + 2 * len + a), FX_CLIPS(base + 2 * len + b), t);
                    vec4 turn = step_instance(inst + 7);
                    at = p.position + step_rotate(turn, fx_clip_origin(r0, r1, r2) * p.size);
                    vec3 up = step_rotate(turn, fx_clip_up(r0, r1, r2));
                    float d = dot(up, up);
                    n = d > 1e-12 ? up / sqrt(d) : vec3(0.0, 1.0, 0.0);
                }
""");
        if (shape.events() > 0) s.append("""
                // The direction of v, or up when it barely moves: a death's and an age's normal.
                vec3 step_normal(vec3 v) {
                    float d = dot(v, v);
                    return d > 1e-12 ? v / sqrt(d) : vec3(0.0, 1.0, 0.0);
                }

                // Event e of p at `at` from its slot's origin, its velocity v and normal n, what it met (an FX_HIT_ code)
                // and for a repeating trigger which firing: where it fired, as its slot's origin's block and the place
                // within it.
                // A batch's event (CgVfxEvent.Trigger.BURST): at its source as its tick began, no velocity, up its
                // normal; its parent id the batch's first spawn index.
                FxEvent step_burst(int at, uint slot, uint first, uint e) {
                    int inst = _InstanceAt + int(slot) * STEP_INSTANCE_ROW;
                    vec4 from = uintBitsToFloat(SPAWNS(at + 2));
                    FxEvent ev;
                    ev.position = vec4(step_instance(inst + 4).xyz + from.xyz, 0.0);
                    ev.velocityAge = vec4(0.0, 0.0, 0.0, from.w);
                    ev.normal = vec4(0.0, 1.0, 0.0, 0.0);
                    ev.block = ivec4(ivec3(INSTANCES(inst + 3).xyz), 0);
                    ev.ids = uvec4(first, uint(_PoolId) << 16u | slot, e, 0u);
                    return ev;
                }

                FxEvent step_event(FxParticle p, int inst, uint e, vec3 at, vec3 v, vec3 n, float hit, uint firing) {
                    FxEvent ev;
                    ev.position = vec4(step_instance(inst + 4).xyz + at, 0.0);
                    ev.velocityAge = vec4(v, p.age);
                    ev.normal = vec4(n, hit);
                    ev.block = ivec4(ivec3(INSTANCES(inst + 3).xyz), int(p.hitEntity));
                    ev.ids = uvec4(p.id, uint(_PoolId) << 16u | p.slot, e, firing);
                    return ev;
                }
""");
        s.append("""

                void Step() {
                    int live = int(LIVE(0));
                    FxParticle p;
                    float born = 0.0;
                    if (CG_ELEMENT < live) {
                        p = step_unpack(IN(CG_ELEMENT));
                    } else {
                        int j = CG_ELEMENT - live;
                        if (j >= _Spawned) {
                """);
        s.append(shape.tier() == 0 ? "            return;\n" : "            if (!step_child(j - _Spawned, p)) return;\n");
        StringBuilder bursts = new StringBuilder();
        for (int e = 0; e < shape.events(); e++) {
            if (shape.trigger(e) != CgVfxEvent.Trigger.BURST) continue;
            if (bursts.isEmpty()) {
                bursts.append("int batch = int(uintBitsToFloat(SPAWNS(at + 1)).z);\n");
                bursts.append("                if (i == 0 || (i == batch && batch < int(spawn.z))) {\n");
            }
            bursts.append("                    FxEvent ev").append(e).append(" = step_burst(at, spawn.x, spawn.y + uint(i), ")
                    .append(e).append("u);\n");
            if (shape.eventSpawns(e)) {
                bursts.append("                    ev").append(e).append(".position.w = uintBitsToFloat(uint(_RegionBase + ")
                        .append(region(shape, e)).append("));\n");
                bursts.append("                    SPAWN_EVENTS_APPEND(ev").append(e).append(");\n");
            }
            if (shape.eventReports(e)) bursts.append("                    REPORT_EVENTS_APPEND(ev").append(e).append(");\n");
        }
        if (!bursts.isEmpty()) bursts.append("                }");
        s.append("""
                        } else {
                            // The last spawn row starting at or before j: rows hold their first candidate in w.
                            int lo = 0, hi = _SpawnRows - 1;
                            while (lo < hi) {
                                int mid = (lo + hi + 1) >> 1;
                                if (int(SPAWNS(_SpawnAt + mid * STEP_SPAWN_ROW).w) <= j) lo = mid; else hi = mid - 1;
                            }
                            int at = _SpawnAt + lo * STEP_SPAWN_ROW;
                            uvec4 spawn = SPAWNS(at);
                            int i = j - int(spawn.w);
                @BURSTS@
                            if (!step_spawn(at, spawn.x, spawn.y + uint(i), i, int(spawn.z), p, born)) return;
                        }
                    }
                    bool wasResting = p.resting;
                    int inst = _InstanceAt + int(p.slot) * STEP_INSTANCE_ROW;
                    // A killed slot: its particles end here, firing nothing.
                    if (step_instance(inst + 2).w > 0.0) return;
                    // A local slot's live particles turn with it about its origin (Niagara's Local Space); the identity
                    // for any other.
                    if (CG_ELEMENT < live) {
                        vec4 turn = step_instance(inst + 8);
                        if (turn.w < 1.0) {
                            p.position = step_rotate(turn, p.position);
                            p.previous = step_rotate(turn, p.previous);
                            p.velocity = step_rotate(turn, p.velocity);
                        }
                    }
                    uint paramRow = INSTANCES(inst).x;
                    int row = int(paramRow) * STEP_PARAM_ROW;
                    // What a form's rolls key on, with its module and input: the instance's seed.
                    uint formSeed = INSTANCES(inst).y;
                    FxStep s;
                    // A slot held still while another effect prewarms steps by 0: unmoved, unaged. A newborn steps for
                    // the part of the step after its birth.
                    s.dt = max(_Step.x - born, 0.0) * step_instance(inst + 2).z;
                    s.wind = _Step.yzw;
                    FxForces f;
                    f.accel = vec3(0.0);
                    f.drag = 0.0;
                    f.dragQuad = 0.0;
                    f.motionScale = vec3(1.0);
                    f.motionSet = vec3(0.0);
                """);
        for (int i = 0; i < shape.modules(); i++) if (!shape.afterSolve(i)) call(s, shape, i);
        s.append("""
                    // The solve: forces into velocity, drag implicit, then position
                    // Held still, it keeps the step it is drawn between.
                    if (s.dt > 0.0) p.previous = p.position;
                    // Held still after a collision's completion, it keeps its place and still turns (Cascade's
                    // STATE_Particle_FreezeTranslation, ParticleEmitterInstances.cpp:1337-1350).
                    if (!p.resting && (p.state & 4u) == 0u) {
                        vec3 v = p.velocity + f.accel * s.dt;
                        float keep = 1.0 / (1.0 + (f.drag + f.dragQuad * length(v)) * s.dt);
                        p.velocity = v * keep;
                        p.position += (p.velocity * f.motionScale + f.motionSet) * s.dt;
                    }
                    if (!p.resting) p.spin += p.spinRate * s.dt;
@FOLLOW@                    // What it struck with: a landing's and a collision's velocity.
                    vec3 impact = p.velocity;
                    p.hit = vec4(0.0);
                    p.hitEntity = 0u;
                """.replace("@FOLLOW@", follow(shape)));
        for (int i = 0; i < shape.modules(); i++) if (shape.afterSolve(i)) call(s, shape, i);
        s.append("""
                    p.age += s.dt;
                """).append(directUpdate(shape));
        for (int e = 0; e < shape.events(); e++) {
            switch (shape.trigger(e)) {
                case LANDING -> event(s, shape, e, "p.resting && !wasResting", "impact", "vec3(0.0, 1.0, 0.0)", "FX_HIT_GROUND", "0u");
                // At 0, the first step: a particle placed at age 0 ends it at exactly dt.
                case AGE -> event(s, shape, e, "(p.age - s.dt < " + eventValue(shape, e) + " || " + eventValue(shape, e)
                        + " <= 0.0 && p.age <= s.dt) && " + eventValue(shape, e) + " <= p.age", "p.velocity",
                        "step_normal(p.velocity)", "0.0", "0u");
                case COLLISION -> event(s, shape, e, "p.hit.w > 0.0 && float(p.collisions) <= " + eventLimit(shape, e),
                        "impact", "p.hit.xyz", "p.hit.w", "p.collisions");
                case RATE -> {
                    // Periods counted from the window's start, never firing at it, and not past its end.
                    String since = "(p.age - " + eventParam(shape, CgVfxShape.EVENT_FROM, e) + ")", period = eventValue(shape, e);
                    s.append("    float since").append(e).append(" = floor(").append(since).append(" / ").append(period).append(");\n");
                    event(s, shape, e, "floor((" + since + " - s.dt) / " + period + ") < since" + e + " && since" + e
                            + " >= 1.0 && since" + e + " <= " + eventLimit(shape, e) + " && p.age <= "
                            + eventParam(shape, CgVfxShape.EVENT_UNTIL, e), "p.velocity", "step_normal(p.velocity)", "0.0",
                            "uint(since" + e + ")");
                }
                // Every step it lives; firing n its age's bits, so each step's children differ.
                case LOCATION -> event(s, shape, e, "true", "p.velocity", "step_normal(p.velocity)", "0.0", "floatBitsToUint(p.age)");
                case DEATH, BURST -> { }
            }
        }
        s.append("    if (p.age >= p.life) {\n");
        for (int e = 0; e < shape.events(); e++) {
            if (shape.trigger(e) == CgVfxEvent.Trigger.DEATH) event(s, shape, e, "true", "p.velocity", "step_normal(p.velocity)", "0.0", "0u");
        }
        s.append("""
                        return;
                    }
                    OUT_APPEND(step_pack(p, paramRow));
@PUBLISH@                }
                """);
        int publishAt = s.indexOf("@PUBLISH@");
        s.replace(publishAt, publishAt + "@PUBLISH@".length(), !shape.publishes() ? ""
                : "    PUBLISHED_APPEND(FxEvent(vec4(p.position, uintBitsToFloat(uint(_BinKeyBase + int(p.slot)))),\n"
                + "            vec4(p.velocity, p.spin), vec4(p.age, p.life, 0.0, 0.0), ivec4(0), uvec4(p.id, 0u, 0u, 0u)));\n");
        int burstsAt = s.indexOf("@BURSTS@");
        s.replace(burstsAt, burstsAt + "@BURSTS@".length(), bursts.toString());
        String out = s.toString();
        // A text block's @NAME@ the code forgot to fill compiles as a syntax error far from its cause.
        Matcher left = PLACEHOLDER.matcher(out);
        if (left.find()) throw new IllegalStateException(shape.kernelKey() + ": " + left.group() + " left unfilled in its Step kernel");
        return out;
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("@[A-Z_]+@");

    /**
     * {@code step_vector}: parameter-row vector {@code v} of the row at {@code row}, each word a user binding replaces
     * read from the instance's user lanes. Spawn numbers and bound module numbers read through it.
     */
    private static void stepVector(StringBuilder s, CgVfxShape shape) {
        s.append("\nvec4 step_vector(int row, int inst, int v) {\n    vec4 x = step_param(row + v);\n");
        for (int b = 0; b < shape.userBindings(); b++) {
            int word = shape.userWord(b);
            s.append("    if (v == ").append(word / 4).append(") x[").append(word % 4).append("] = step_instance(inst + ")
                    .append(shape.userAt() + b / 4).append(")[").append(b % 4).append("];\n");
        }
        s.append("    return x;\n}\n");
    }

    /** Event {@code e}'s place among its shape's spawning events: its region past the pool's first. */
    static int region(CgVfxShape shape, int e) {
        int n = 0;
        for (int k = 0; k < e; k++) if (shape.eventSpawns(k)) n++;
        return n;
    }

    /** Event {@code e}'s age or period, from the parameter row. */
    private static String eventValue(CgVfxShape shape, int e) {
        return eventParam(shape, CgVfxShape.EVENT_AGES, e);
    }

    /** Repeating event {@code e}'s most firings, from the parameter row. */
    private static String eventLimit(CgVfxShape shape, int e) {
        return eventParam(shape, CgVfxShape.EVENT_FIRINGS, e);
    }

    /** Event {@code e}'s number in the parameter row's event field {@code field} ({@link CgVfxShape#eventField}). */
    private static String eventParam(CgVfxShape shape, int field, int e) {
        return "step_param(row + " + (shape.eventField(field) + e / 4) + ")[" + e % 4 + "]";
    }

    /**
     * Event {@code e}'s row, appended to each stream it goes to when {@code when} holds: where the particle is, or at its
     * clip node with the node's normal.
     */
    private static void event(StringBuilder s, CgVfxShape shape, int e, String when, String velocity, String normal,
                              String hit, String firing) {
        s.append("    if (").append(when).append(") {\n");
        if (shape.eventAtNode(e)) {
            s.append("        vec3 at, n;\n");
            s.append("        step_node(p, inst, row, ").append(e).append(", at, n);\n");
            s.append("        FxEvent ev = step_event(p, inst, ").append(e).append("u, at, ").append(velocity).append(", n");
        } else {
            s.append("        FxEvent ev = step_event(p, inst, ").append(e)
                    .append(shape.eventAtEmitter(e) ? "u, vec3(0.0), " : "u, p.position, ").append(velocity).append(", ").append(normal);
        }
        s.append(", ").append(hit).append(", ").append(firing).append(");\n");
        if (shape.eventSpawns(e)) {
            s.append("        ev.position.w = uintBitsToFloat(uint(_RegionBase + ").append(region(shape, e)).append("));\n");
            s.append("        SPAWN_EVENTS_APPEND(ev);\n");
        }
        if (shape.eventReports(e)) s.append("        REPORT_EVENTS_APPEND(ev);\n");
        s.append("    }\n");
    }

    /**
     * Module {@code i}'s call: its numbers from the parameter row, its lanes from the instance row, its data interfaces,
     * its textures. A kind sampling textures is called once per entry of its pool's table, under a switch on the slot's
     * entry: each call passes fixed samplers, which every tier and Vulkan's GLSL take.
     */
    private static void call(StringBuilder out, CgVfxShape shape, int i) {
        StringBuilder s = new StringBuilder();
        s.append("fx_").append(shape.kind(i)).append(shape.afterSolve(i) ? "(p, s" : "(p, f, s");
        for (int j = 0, at = shape.paramAt(i); j < shape.paramVectors(i); j++) {
            int v = at + j;
            String base = shape.userBound(v) ? "step_vector(row, inst, " + v + ")" : "step_param(row + " + v + ")";
            boolean formed = false;
            for (int l = 0; l < 4; l++) formed |= shape.formAtWord(v * 4 + l) != null;
            if (!formed) {
                s.append(", ").append(base);
                continue;
            }
            // A vector a form fills: each lane its form's expression, or its word.
            s.append(", vec4(");
            for (int l = 0; l < 4; l++) {
                if (l > 0) s.append(", ");
                int[] fk = shape.formAtWord(v * 4 + l);
                s.append(fk == null ? base + "." + XYZW.charAt(l)
                        : formExpr(shape.layout(fk[0]), shape.form(fk[0]).compiled(), fk[1], base + "." + XYZW.charAt(l),
                                "formSeed", "p.id", LOOP_SECONDS));
            }
            s.append(')');
        }
        CgVfxLane[] lanes = shape.lanes(i);
        for (int l = 0, at = shape.lanesAt(i); l < lanes.length; l++) {
            switch (lanes[l]) {
                case VEC4 -> s.append(", step_instance(inst + ").append(at + l).append(')');
                case IVEC4 -> s.append(", ivec4(INSTANCES(inst + ").append(at + l).append("))");
                case UVEC4 -> s.append(", INSTANCES(inst + ").append(at + l).append(')');
            }
        }
        for (CgVfxDataInterface di : shape.dataInterfaces(i)) s.append(", ").append(di.call());
        int textures = shape.textureCount(i);
        if (textures == 0) {
            out.append("    ").append(s).append(");\n");
            return;
        }
        int word = shape.textureEntryWord(i);
        out.append("    switch (int(INSTANCES(inst + ").append(word / 4).append(").").append(XYZW.charAt(word % 4)).append(")) {\n");
        for (int e = 0; e < CgVfxParticlePool.TEXTURE_ENTRIES; e++) {
            out.append(e == 0 ? "    default: " : "    case " + e + ": ").append(s);
            for (int t = 0; t < textures; t++) out.append(", ").append(texture(i, t, e));
            out.append("); break;\n");
        }
        out.append("    }\n");
    }

    private static final String XYZW = "xyzw";

    /** Spawn k's draw of the launch's roll {@code key} ({@code CgVfxGpuEmitter.KEY_*}). */
    private static String roll(int key) {
        return "fx_rand(seed ^ step_key(row, " + key + "), k, 0u)";
    }

    /** A spawn value: its spawn form's expression for channel 0, else the spawn row's range as {@code otherwise}. */
    /**
     * {@code target}'s value at the launch: its spawn form's expression, {@code times} before it, a vector as a
     * {@code vecN} of its components; {@code otherwise} where it has no form. {@code first} is its spawn-row word.
     */
    private static String spawnValue(CgVfxShape shape, CgVfxSpawnInput target, String first, String otherwise, String times) {
        int f = shape.spawnFormOf(target);
        if (f < 0) return otherwise;
        CgVfxFormInput form = shape.spawnForm(f).compiled();
        int n = target.components();
        StringBuilder s = new StringBuilder(n > 1 ? "vec" + n + "(" : "(").append(times);
        for (int k = 0; k < n; k++) {
            s.append(k > 0 ? ", " : "").append(formExpr(shape.spawnLayout(f), form, Math.min(k, form.components() - 1), first, "seed", "k", "loop"));
        }
        return s.append(')').toString();
    }

    /**
     * A reader's birth on its source's particles ({@link CgVfxShape#spawnFrom}): Cascade's LocationEmitter
     * ({@code ParticleModules_Location.cpp:449-553}), one of its source slot's published particles (its run of the sorted
     * rows) picked at random or in turn; its place for the emitter's, plus the shape's offset where the shape comes after; a share of its velocity
     * and spin. With none published the new particle stays where its emitter put it, as Cascade's finds none to copy.
     * Born on its own particles, a frame's first step reads its slot's key from the frame before, and later ones this
     * frame's: what its own tier sorted the step before.
     */
    private static String read(CgVfxShape shape) {
        CgVfxSpawnFrom from = shape.spawnFrom();
        if (from == null) return "";
        if (from.follow()) {
            return """
                                    {
                                        FxEvent b;
                                        if (step_direct(slot, k, b)) {
                                            p.position = b.position.xyz;
                                            p.previous = p.position;
                                            p.velocity = b.velocityAge.xyz;
                                        }
                                    }
                    """;
        }
        String pick = from.selection() == CgVfxSpawnFrom.Selection.RANDOM
                ? "min(uint(fx_rand(seed ^ 0x53524345u, k, 0u) * float(count)), count - 1u)" : "(k + 1u) % count";
        String place = from.placeAfter() ? "b.position.xyz + (p.position - mix(from.xyz, to.xyz, along))" : "b.position.xyz";
        return """
                                    {
                                        uvec4 rd = READS(int(slot));
                                        uint key = @KEY@, first = 0u, count = 0u;
                                        if (key != 0xFFFFFFFFu) {
                                            first = SOURCE_RUNS(int(key) * 2);
                                            count = SOURCE_RUNS(int(key) * 2 + 1) - first;
                                        }
                                        if (count > 0u) {
                                            FxEvent b = SOURCE_ROWS(int(first + @PICK@));
                                            p.position = @PLACE@;
                                            p.previous = p.position;
                                            p.velocity += b.velocityAge.xyz * uintBitsToFloat(rd.z);
                                            p.spin += b.velocityAge.w * uintBitsToFloat(rd.w);
                                        }
                                    }
                """.replace("@PICK@", pick).replace("@PLACE@", place)
                .replace("@KEY@", from.itself() ? "(_StepIndex == 0 ? rd.x : rd.y)" : "rd.x");
    }

    /**
     * A follower's source particle ({@link CgVfxSpawnFrom#follow}): the one of its own id its source slot published this
     * step into the direct rows, Cascade's {@code GetParticle(Index)} ({@code ParticleModules_Location.cpp:583-660}),
     * null past the source's live ones.
     */
    private static String direct(CgVfxShape shape) {
        if (shape.spawnFrom() == null || !shape.spawnFrom().follow()) return "";
        return """
                bool step_direct(uint slot, uint id, out FxEvent b) {
                    b = FxEvent(vec4(0.0), vec4(0.0), vec4(0.0), ivec4(0), uvec4(0u));
                    uint key = READS(int(slot)).x;
                    if (key == 0xFFFFFFFFu) return false;
                    uint cap = SOURCE_SLOTS(int(key) * 2 + 1);
                    if (cap == 0u) return false;
                    b = SOURCE_DIRECT(int(SOURCE_SLOTS(int(key) * 2) + id % cap));
                    return b.ids.x == id;
                }

                """;
    }

    /**
     * A follower on its source particle each step, once aged: its place, velocity, age and life, as Cascade's update
     * copies its location, velocity and relative time; where the source is gone it keeps its own.
     */
    private static String directUpdate(CgVfxShape shape) {
        if (shape.spawnFrom() == null || !shape.spawnFrom().follow()) return "";
        return """
                    {
                        FxEvent b;
                        if (step_direct(p.slot, p.id, b)) {
                            p.position = b.position.xyz;
                            p.velocity = b.velocityAge.xyz;
                            p.age = b.normal.x;
                            p.life = b.normal.y;
                        }
                    }
                """;
    }

    /** A colour start's form as a vec4: an rgb form's alpha -1, which the Look stage takes from the row's starts. */
    private static String colorStart(CgVfxShape shape) {
        int f = shape.spawnFormOf(CgVfxSpawnInput.COLOR);
        if (f < 0) return "vec4(1.0)";
        CgVfxFormInput form = shape.spawnForm(f).compiled();
        StringBuilder s = new StringBuilder("vec4(");
        for (int k = 0; k < 4; k++) {
            s.append(k > 0 ? ", " : "").append(k < form.components()
                    ? formExpr(shape.spawnLayout(f), form, k, null, "seed", "k", "loop") : "-1.0");
        }
        return s.append(')').toString();
    }

    /** How far out a spawn is born past its shape: a radius form's value less the shape's radius; 0 without one. */
    private static String front(CgVfxShape shape) {
        int f = shape.spawnFormOf(CgVfxSpawnInput.RADIUS);
        if (f < 0) return "0.0";
        String radius = "step_vector(row, inst, 7).y";
        return "(" + formExpr(shape.spawnLayout(f), shape.spawnForm(f).compiled(), 0, radius, "seed", "k", "loop") + " - " + radius + ")";
    }

    /**
     * The launch's skeleton point, where {@code shape} spawns at its effect's ({@code CgVfxSockets}): picked in turn by
     * the spawn's key or at random as Cascade draws it ({@code ParticleModules_Location.cpp:1438-1449}), the particle
     * placed there plus its offset; an unanswered point spawns nothing.
     */
    private static String sockets(CgVfxShape shape) {
        if (shape.sockets() == 0) return "";
        int head = shape.socketParamsAt(), at = shape.socketsAt();
        return """
                    {
                        uvec4 points = PARAMS(row + %d);
                        float count = float(points.x);
                        int s = points.y != 0u ? int(k %% points.x) : int(fx_rand(seed ^ points.z, k, 0u) * (count - 0.5));
                        vec4 point = step_instance(inst + %d + s);
                        if (point.w == 0.0) return false;
                        p.position = point.xyz + step_orient(step_rotate(turn, step_param(row + %d + s).xyz), n);
                        p.previous = p.position;
                        %s
                    }
                """.formatted(head, at, head + 1, shape.socketsInherit()
                        ? "p.velocity += uintBitsToFloat(points.w) * step_instance(inst + " + (at + shape.sockets()) + " + s).xyz;" : "");
    }

    /**
     * A following shape's particle put back on its skeleton point after the solve, the one its launch took from its id
     * and its instance's seed ({@link #sockets}), as Cascade's update each frame sets it ({@code ParticleModules_Location.cpp:1632-1686}).
     */
    private static String follow(CgVfxShape shape) {
        if (!shape.socketsFollow()) return "";
        int head = shape.socketParamsAt(), at = shape.socketsAt();
        return """
                    {
                        uvec4 points = PARAMS(row + %d);
                        float count = float(points.x);
                        int sp = points.y != 0u ? int(p.id %% points.x) : int(fx_rand(formSeed ^ points.z, p.id, 0u) * (count - 0.5));
                        vec4 point = step_instance(inst + %d + sp);
                        if (point.w != 0.0) p.position = point.xyz + step_rotate(step_instance(inst + 7), step_param(row + %d + sp).xyz);
                    }
                """.formatted(head, at, head + 1);
    }

    /** The loop's seconds an update reads: the instance header's (CgVfxParticlePool.INSTANCE_HEADER). */
    private static final String LOOP_SECONDS = "step_instance(inst + 5).w";

    /**
     * Component {@code k} of form node {@code c} of {@code layout}: its expression in the kernel, the Java evaluation's
     * twin ({@code CgVfxFormInput.channel}), its arguments' nested as the Java nests them. {@code first} is the word
     * holding a top-level range's or pick's first end; {@code seed} and {@code id} seed its rolls (the instance's seed
     * and the particle's id, or at spawn its key); a lookup by the loop reads {@code loop}.
     */
    private static String formExpr(CgVfxFormLayout layout, CgVfxFormInput c, int k, String first, String seed, String id,
                                   String loop) {
        CgVfxFormLayout.Node n = layout.node(c);
        String keyed = n.key() < 0 ? seed : seed + " ^ step_key(row, " + n.key() + ")";
        String lane = "." + XYZW.charAt(k);
        String at0 = "step_param(row + " + n.at() + ")" + lane, at1 = "step_param(row + " + (n.at() + 1) + ")" + lane;
        if (c instanceof CgVfxFormInput.Constant) return at0;
        if (c instanceof CgVfxFormInput.Range r) {
            return "mix(" + (n.top() ? first : at0) + ", " + (n.top() ? at0 : at1) + ", fx_rand(" + keyed + ", " + id + ", "
                    + r.rollOf(k) + "u))";
        }
        if (c instanceof CgVfxFormInput.Pick) {
            return "(fx_rand(" + keyed + ", " + id + ", 0u) < 0.5 ? " + (n.top() ? first : at0) + " : " + (n.top() ? at0 : at1) + ")";
        }
        if (c instanceof CgVfxFormInput.Curve curve) {
            return "step_curve(row + " + n.at() + ", " + lookup(curve.by(), keyed, id, loop) + ")" + lane;
        }
        if (c instanceof CgVfxFormInput.Array array) {
            int mode = array.by() == CgVfxFormInput.Lookup.RANDOM ? 0 : array.by() == CgVfxFormInput.Lookup.ID ? 1 : 2;
            return "step_array(row + " + n.at() + ", " + lookup(array.by(), keyed, id, loop) + ", " + mode + ")" + lane;
        }
        if (c instanceof CgVfxFormInput.Function f) {
            String a = formExpr(layout, f.first(), Math.min(k, f.first().components() - 1), null, seed, id, loop);
            String b = formExpr(layout, f.second(), Math.min(k, f.second().components() - 1), null, seed, id, loop);
            if (f.fn().endsWith(":add")) return "(" + a + " + " + b + ")";
            return "mix(" + a + ", " + b + ", fx_rand(" + keyed + ", " + id + ", 0u))";
        }
        CgVfxFormInput.Binding b = (CgVfxFormInput.Binding) c;
        // A user parameter's is its instance lane, written each step remapped and scaled.
        if (n.lane() >= 0) return "step_instance(inst + " + (layout.laneBase() + n.lane()) + ")" + lane;
        return "(p.age * " + glsl(b.scale()) + ")";
    }

    /** A lookup's value in the kernel: {@code CgVfxFormInput.Lookup.read}'s twin. */
    private static String lookup(CgVfxFormInput.Lookup by, String keyed, String id, String loop) {
        return switch (by) {
            case AGE -> "(p.age / max(p.life, 1e-6))";
            case PARTICLES_AGE -> "p.age";
            case SPEED -> "length(p.velocity)";
            case ID -> "float(" + id + ")";
            case RANDOM -> "fx_rand(" + keyed + ", " + id + ", 31u)";
            case LOOP -> loop;
            default -> throw new IllegalStateException("a lookup by " + by);
        };
    }

    private static String glsl(float v) {
        String s = Float.toString(v);
        return s.contains("E") ? String.format(Locale.ROOT, "%.9e", v) : s;
    }

    /** Module {@code i}'s texture {@code t}'s sampler property: what the pool binds it as. */
    /** Module {@code i}'s texture {@code t} in entry {@code e} of its pool's table: the property the pool binds. */
    static String texture(int i, int t, int e) {
        return "_Texture" + i + "_" + t + "_" + e;
    }
}
