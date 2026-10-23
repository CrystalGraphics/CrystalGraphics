package com.crystalgraphics.vfx.particle.gpu.sim;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxModule;
import com.crystalgraphics.vfx.particle.CgVfxModuleTypes;
import com.crystalgraphics.vfx.particle.CgVfxLifeBind;
import com.crystalgraphics.vfx.particle.CgVfxLifeRoll;
import com.crystalgraphics.vfx.particle.CgVfxSockets;
import com.crystalgraphics.vfx.particle.form.CgVfxFormInput;
import com.crystalgraphics.vfx.particle.form.CgVfxModuleForm;
import com.crystalgraphics.vfx.particle.form.CgVfxSpawnForm;
import com.crystalgraphics.vfx.particle.form.CgVfxSpawnInput;
import com.crystalgraphics.vfx.particle.spawn.CgVfxSpawnFrom;
import com.crystalgraphics.vfx.particle.gpu.CgVfxDataInterface;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuEmitter;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuModule;
import com.crystalgraphics.vfx.particle.gpu.CgVfxLane;
import com.crystalgraphics.vfx.particle.gpu.CgVfxModuleWords;
import com.crystalgraphics.vfx.particle.gpu.CgVfxUserBinding;
import com.crystalgraphics.vfx.particle.gpu.CgVfxDrawLayout;
import com.crystalgraphics.vfx.particle.gpu.CgVfxDrawnChannel;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * What makes two emitter definitions share a pool and its kernels: their module kinds in stack order, what each kind
 * declares it takes, its draw layout, and their events' triggers with whether each spawns children and reports to the
 * CPU (and whether each fires at a clip node), and its feed tier: how many events deep its particles are spawned. Never a
 * number, so tuning a definition recompiles nothing. Also the layout of a pool's rows, which follows from
 * it. A module's textures are part of it too, so each sampled texture has a pool of its own; its kernels are keyed by
 * {@link #kernelKey()}, which names only whether each is 2D or 3D.
 *
 * <pre>{@code
 * CgVfxShape shape = CgVfxShape.of(EMBERS);
 * shape.key();                  // "QUADS|gravity:1|drag:1|buoyancy:1|turbulence:1:IVEC4,VEC4,IVEC4,VEC4|wind:1"
 * shape.paramRowVectors();      // spawn numbers then each module's numbers
 * shape.instanceRowVectors();   // the row's header then each module's lanes
 * CgVfxShape.of(SPARKS, 1);     // SPARKS spawned by another's events: a pool of its own, reading its parents' rows
 * }</pre>
 *
 * <ul>
 *   <li>{@link #of} checks every kind against {@link CgVfxGpuModule}'s rules and throws naming the definition and the
 *       kind that breaks one.</li>
 * </ul>
 */
public final class CgVfxShape {

    private static final Pattern KIND = Pattern.compile("[a-z][a-z0-9_]*");
    /**
     * The fields a parameter row gives its events, when it has any, in this order, each {@link #eventGroups()} vec4s of
     * an event a lane: each one's age or period, its most firings, its window's start and end, and its clip node's word
     * ({@code base | 1 << 31} where the node's matrix steps); then one vec4 of its clip, {@code (row length, frames a
     * second of age, frames, 0)}. {@link #eventField} is where one starts.
     */
    public static final int EVENT_AGES = 0, EVENT_FIRINGS = 1, EVENT_FROM = 2, EVENT_UNTIL = 3, EVENT_NODES = 4,
            EVENT_CLIP = 5;
    /** Feed tiers: a definition spawned by nothing, by a root's events, by a child's. */
    public static final int TIERS = CgVfxEvent.LEVELS + 1;
    /** Each custom kind's text, as first seen: a kind name means one text. */
    private static final Map<String, String> SOURCES = new ConcurrentHashMap<>();
    /** Each definition's shape per tier, made once: a cast prepares and opens its shapes every play. Held weakly. */
    private static final Map<CgVfxGpuEmitter, CgVfxShape[]> SHAPES = new WeakHashMap<>();

    private final String key;
    private final CgTexture[][] textures;
    private final boolean[][] volumes;
    private final CgVfxDrawLayout drawLayout;
    private final String[] kinds, sources, files;
    private final boolean[] afterSolve;
    private final int[] params, paramAt, lanesAt;
    private final CgVfxLane[][] lanes;
    private final CgVfxDataInterface[][] reads;
    private final int paramRow, instanceRow;
    private final boolean floors, spawnsOnMesh, turnsOverLife;
    private final int lifeRolls, lifeRollVectors;
    /** The drawn slots its particles carry after their drawn part ({@link CgVfxGpuEmitter#drawnMask}). */
    private final int drawnMask;
    /** Noise points each beam keeps, a power of two at least its noise's frequency + 1, 0 without; that module's words. */
    private final int beamPoints, beamNoiseAt;
    /** Whether a kind of it takes {@code CgVfxDataInterfaces.BEAM}: its particles are beams, each keeping a run. */
    private final boolean beams;
    /** Every data interface it takes, its kinds' and what they require, in registration order; where each one's lanes start. */
    private final CgVfxDataInterface[] interfaces;
    private final List<CgVfxDataInterface> interfaceList;
    private final int[] interfaceLanesAt;
    /** Each module's place among the textured ones, -1 for none; where their entry indices start in the instance row. */
    private final int[] textureEntry;
    private final int texturesAt;
    /**
     * Its skeleton points ({@code CgVfxSockets}): how many, 0 for none; where their lanes start in the instance row, one
     * a point (its place from the origin, 1 where answered); where their vec4s start in the parameter row, a header
     * {@code (count, 1 in turn, the pick's key, 0)} then each point's offset.
     */
    private final int sockets, socketsAt, socketParamsAt;
    private final boolean socketsFollow, socketsInherit;
    /** Its user bindings, the parameter-row word each replaces, and where their lanes start: four bindings a lane. */
    private final CgVfxUserBinding[] userBindings;
    private final int[] userWords;
    private final int userAt;
    /** Its look over life's bound multipliers' first vec4 in the instance row, or -1. */
    private final int lifeBindAt;
    /** Where its drawn slots' bound channels sit in the instance row, a vec4 a slot; -1 where none binds. */
    private final int drawnBindAt;
    /** Where its light's bound channels sit in the instance row, one vec4; -1 where none binds. */
    private final int lightBindAt;
    /** Its forms' user bindings, each one's parameter name, and its vec4 in the instance row. */
    private final CgVfxFormInput.Binding[] formBindings;
    private final String[] formBindingNames;
    private final int[] formBindingLanes;
    /** Its module forms and, per form, the row word each component replaces and where its own vec4s start. */
    private final CgVfxModuleForm[] forms;
    private final int[][] formWords;
    private final int[] formAt;
    /** Each module form's and spawn form's nodes in the form area and the key area. */
    private final CgVfxFormLayout[] layouts, spawnLayouts;
    private final int keyCount;
    /** Its spawn forms by target, and where each one's vec4s start. */
    private final CgVfxSpawnForm[] spawnForms;
    /** Its record layout, read from its code the first time it is asked. */
    private volatile CgVfxRecord record;
    private final int[] spawnFormAt;
    private final int formsAt, formVectors;
    /** Where its roll keys start: the launch's {@code ROLL_KEYS}, then each module form's, then each spawn form's. */
    private final int keysAt;
    private final CgVfxEvent.Trigger[] triggers;
    private final boolean[] eventSpawns, eventReports, eventNodes, eventAtEmitter;
    private final int eventsAt, tier;
    private final boolean spawns, reports, readsClips, publishes;
    @Nullable
    private final CgVfxSpawnFrom spawnFrom;

    private CgVfxShape(CgVfxGpuEmitter emitter, int tier) {
        if (tier < 0 || tier >= TIERS) throw new IllegalArgumentException(emitter.name() + " at feed tier " + tier);
        this.tier = tier;
        List<? extends CgVfxGpuModule> modules = emitter.modules();
        int n = modules.size();
        drawLayout = emitter.drawLayout();
        kinds = new String[n];
        sources = new String[n];
        files = new String[n];
        afterSolve = new boolean[n];
        params = new int[n];
        paramAt = new int[n];
        lanesAt = new int[n];
        lanes = new CgVfxLane[n][];
        reads = new CgVfxDataInterface[n][];
        textures = new CgTexture[n][];
        volumes = new boolean[n][];
        StringBuilder key = new StringBuilder(drawLayout.name());
        int param = CgVfxGpuEmitter.SPAWN_VECTORS, lane = CgVfxParticlePool.INSTANCE_HEADER;
        for (int i = 0; i < n; i++) {
            CgVfxGpuModule m = modules.get(i);
            String kind = m.gpuKind();
            if (kind == null || !KIND.matcher(kind).matches()) {
                throw new IllegalArgumentException(emitter.name() + ": module " + m.getClass().getSimpleName() + "'s gpuKind '"
                        + kind + "' is not a lower-case GLSL name");
            }
            kinds[i] = kind;
            sources[i] = m.gpuSource();
            files[i] = m.gpuFile();
            if (sources[i] != null) {
                String known = SOURCES.putIfAbsent(kind, sources[i]);
                if (known != null && !known.equals(sources[i])) {
                    throw new IllegalArgumentException(emitter.name() + ": fx_" + kind + " is given GLSL other than the "
                            + "text another definition gave it: a kind name means one text");
                }
            }
            afterSolve[i] = m.afterSolve();
            params[i] = CgVfxModuleTypes.vectors(m);
            lanes[i] = m.instanceLanes().clone();
            reads[i] = m.dataInterfaces().clone();
            for (CgVfxDataInterface di : reads[i]) {
                if (di == null) throw new IllegalArgumentException(emitter.name() + ": fx_" + kind + " takes a null data interface");
                if (!afterSolve[i] && !di.beforeSolver()) {
                    throw new IllegalArgumentException(emitter.name() + ": fx_" + kind + " takes " + di.id()
                            + " but runs before the solver, which takes only data interfaces that say beforeSolver()");
                }
            }
            paramAt[i] = param;
            lanesAt[i] = lane;
            param += params[i];
            lane += lanes[i].length;
            key.append('|').append(kind).append(afterSolve[i] ? ">" : ":").append(params[i]);
            for (int l = 0; l < lanes[i].length; l++) key.append(l == 0 ? ':' : ',').append(lanes[i][l].name());
            for (int w = 0; w < reads[i].length; w++) key.append(w == 0 ? '@' : ',').append(reads[i][w].id());
            textures[i] = m.textures().clone();
            volumes[i] = new boolean[textures[i].length];
            for (int t = 0; t < textures[i].length; t++) {
                CgTexture texture = textures[i][t];
                if (texture == null) throw new IllegalArgumentException(emitter.name() + ": fx_" + kind + "'s texture " + t + " is null");
                volumes[i][t] = texture instanceof CgGraphTexture graph ? graph.isVolume() : texture.getTarget() == CgGL.GL_TEXTURE_3D;
                key.append(t == 0 ? '#' : ',').append(volumes[i][t] ? "3d" : "2d");
            }
        }
        spawnsOnMesh = emitter.spawnsOnMesh();
        if (spawnsOnMesh) key.append("|mesh");
        drawnMask = CgVfxGpuEmitter.drawnMask(emitter);
        if (drawnMask != 0) key.append("|drawn:").append(drawnMask);
        int noisePoints = 0, noiseAt = -1;
        boolean beam = false;
        for (int i = 0; i < n; i++) {
            for (CgVfxDataInterface di : reads[i]) beam |= di == CgVfxDataInterfaces.BEAM;
            if (!(modules.get(i) instanceof CgVfxModule.BeamNoise noise)) continue;
            if (noiseAt >= 0) throw new IllegalArgumentException(emitter.name() + " has two beam_noise modules: Cascade reads one");
            noisePoints = Integer.highestOneBit(noise.frequency()) << 1;
            noiseAt = paramAt[i];
        }
        beamPoints = noisePoints;
        beamNoiseAt = noiseAt;
        beams = beam;
        if (beams) key.append("|beam:").append(beamPoints);
        lifeRolls = emitter.lifeRolls().size();
        int rollVectors = 0;
        StringBuilder rollKey = new StringBuilder();
        for (CgVfxLifeRoll roll : emitter.lifeRolls()) {
            rollVectors += roll.vectors(CgVfxGpuEmitter.CURVE_TEXELS);
            rollKey.append(roll.curved() ? 'v' : 'c');
        }
        lifeRollVectors = rollVectors;
        turnsOverLife = emitter.meshRotation() != null && !emitter.meshRotation().steps().isEmpty();
        if (turnsOverLife) key.append("|turnlife");
        if (lifeRolls > 0) key.append("|liferoll:").append(rollKey);
        List<CgVfxEvent> events = emitter.events();
        if (events.size() > CgVfxEvent.MAX_EVENTS) {
            throw new IllegalArgumentException(emitter.name() + " lists " + events.size() + " events, past " + CgVfxEvent.MAX_EVENTS);
        }
        triggers = new CgVfxEvent.Trigger[events.size()];
        eventSpawns = new boolean[events.size()];
        eventReports = new boolean[events.size()];
        eventNodes = new boolean[events.size()];
        eventAtEmitter = new boolean[events.size()];
        boolean anySpawns = false, anyReports = false, anyNodes = false;
        for (int e = 0; e < triggers.length; e++) {
            CgVfxEvent event = events.get(e);
            triggers[e] = event.trigger();
            eventSpawns[e] = event.child() != null;
            eventReports[e] = event.readback() > 0;
            eventNodes[e] = event.node() != CgVfxEvent.NO_NODE;
            eventAtEmitter[e] = event.atEmitter();
            anySpawns |= eventSpawns[e];
            anyReports |= eventReports[e];
            anyNodes |= eventNodes[e];
            key.append(e == 0 ? "|ev:" : ",").append(triggers[e].name()).append(eventSpawns[e] ? "+s" : "")
                    .append(eventReports[e] ? "+r" : "").append(eventNodes[e] ? "@n" : "").append(eventAtEmitter[e] ? "@e" : "");
        }
        if (anySpawns && tier == TIERS - 1) {
            throw new IllegalArgumentException(emitter.name() + " spawns at the last feed tier: " + CgVfxEvent.LEVELS
                    + " levels of children");
        }
        if (tier > 0) key.append("|tier").append(tier);
        spawns = anySpawns;
        reports = anyReports;
        readsClips = anyNodes;
        eventsAt = param;
        // Sized by the events it lists, so a row pays for no more than it has.
        if (triggers.length > 0) param += EVENT_CLIP * eventGroups() + 1;
        // The form area: each form's nodes (CgVfxFormLayout); the key area after it, the launch's keys first.
        List<CgVfxModuleForm> given = emitter.moduleForms();
        forms = given.toArray(new CgVfxModuleForm[0]);
        formWords = new int[forms.length][];
        formAt = new int[forms.length];
        layouts = new CgVfxFormLayout[forms.length];
        formsAt = param;
        int keySlot = CgVfxGpuEmitter.ROLL_KEYS;
        for (int f = 0; f < forms.length; f++) {
            CgVfxModuleForm form = forms[f];
            if (form.module() >= n) throw new IllegalArgumentException(emitter.name() + " gives module " + form.module() + " a form; it lacks it");
            CgVfxFormInput c = form.compiled();
            CgVfxModuleWords words = CgVfxModuleTypes.of(modules.get(form.module())).words();
            formWords[f] = new int[c.components()];
            for (int k = 0; k < c.components(); k++) {
                try {
                    formWords[f][k] = paramAt[form.module()] * 4 + words.lane(form.input(), k);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(emitter.name() + "'s module " + form.module() + ": " + e.getMessage(), e);
                }
            }
            formAt[f] = param;
            layouts[f] = CgVfxFormLayout.of(emitter.name() + "'s module " + form.module() + " input " + form.input(), c, true, false,
                    param, keySlot);
            param += layouts[f].vectors();
            keySlot += layouts[f].keys();
            key.append(f == 0 ? "|form:" : ",").append(form.module()).append('.').append(form.input()).append('=').append(formKey(c));
        }
        spawnForms = emitter.spawnForms().stream().sorted(Comparator.comparing(CgVfxSpawnForm::target)).toArray(CgVfxSpawnForm[]::new);
        spawnFormAt = new int[spawnForms.length];
        spawnLayouts = new CgVfxFormLayout[spawnForms.length];
        for (int f = 0; f < spawnForms.length; f++) {
            spawnFormAt[f] = param;
            // A range's or a pick's first end sits in its spawn-row word, where the value has one.
            CgVfxFormInput root = spawnForms[f].compiled();
            boolean top = spawnForms[f].target().word() >= 0
                    && (root instanceof CgVfxFormInput.Range || root instanceof CgVfxFormInput.Pick);
            spawnLayouts[f] = CgVfxFormLayout.of(emitter.name() + "'s " + spawnForms[f].target().module() + " " + spawnForms[f].input(),
                    root, top, true, param, keySlot);
            param += spawnLayouts[f].vectors();
            keySlot += spawnLayouts[f].keys();
            key.append(f == 0 ? "|spawn:" : ",").append(spawnForms[f].target()).append('=').append(formKey(spawnForms[f].compiled()));
        }
        formVectors = param - formsAt;
        keyCount = keySlot;
        keysAt = param;
        param += (keys() + 3) / 4;
        CgVfxSockets points = emitter.sockets();
        sockets = points == null ? 0 : points.names().size();
        socketsFollow = points != null && points.follow();
        socketsInherit = points != null && points.inherit() != 0f;
        socketParamsAt = param;
        if (sockets > 0) {
            param += 1 + sockets;
            key.append("|sockets:").append(sockets).append(points.sequential() ? "s" : "r").append(points.follow() ? "f" : "")
                    .append(socketsInherit ? "v" : "");
        }
        paramRow = param;
        List<CgVfxUserBinding> bound = emitter.userBindings();
        if (bound.size() > MAX_USER_BINDINGS) {
            throw new IllegalArgumentException(emitter.name() + " binds " + bound.size() + " inputs, past " + MAX_USER_BINDINGS);
        }
        userBindings = bound.toArray(new CgVfxUserBinding[0]);
        userWords = new int[userBindings.length];
        for (int b = 0; b < userBindings.length; b++) {
            CgVfxUserBinding u = userBindings[b];
            if (u.module() == CgVfxUserBinding.SPAWN) {
                userWords[b] = u.index();
            } else {
                if (u.module() >= n) throw new IllegalArgumentException(emitter.name() + " binds module " + u.module() + ", which it lacks");
                CgVfxGpuModule m = modules.get(u.module());
                try {
                    userWords[b] = paramAt[u.module()] * 4 + CgVfxModuleTypes.of(m).words().lane(u.input(), u.index());
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(emitter.name() + " binds module " + u.module() + ": " + e.getMessage(), e);
                }
            }
            for (int c = 0; c < b; c++) {
                if (userWords[c] == userWords[b]) throw new IllegalArgumentException(emitter.name() + " binds word " + userWords[b] + " twice");
            }
            key.append(b == 0 ? "|user:" : ",").append(userWords[b]);
        }
        userAt = lane;
        lane += userLanes(userBindings.length);
        // Each form's user bindings: a vec4 of the instance row a node.
        List<CgVfxFormInput.Binding> bindings = new ArrayList<>();
        List<Integer> bindingLanes = new ArrayList<>();
        for (CgVfxFormLayout[] group : new CgVfxFormLayout[][]{layouts, spawnLayouts}) {
            for (CgVfxFormLayout layout : group) {
                layout.laneBase(lane);
                for (CgVfxFormLayout.Node node : layout.nodes()) {
                    if (node.lane() < 0) continue;
                    bindings.add((CgVfxFormInput.Binding) node.form());
                    bindingLanes.add(lane + node.lane());
                }
                lane += layout.lanes();
            }
        }
        formBindings = bindings.toArray(new CgVfxFormInput.Binding[0]);
        formBindingNames = new String[formBindings.length];
        formBindingLanes = new int[formBindings.length];
        for (int b = 0; b < formBindings.length; b++) {
            formBindingNames[b] = formBindings[b].path().substring("user.".length());
            formBindingLanes[b] = bindingLanes.get(b);
        }
        boolean drawnBinds = false;
        for (int s = 0; s < CgVfxDrawnChannel.SLOTS; s++) {
            for (int c = 0; c < 4; c++) {
                CgVfxDrawnChannel ch = emitter.drawn(s, c);
                drawnBinds |= ch != null && ch.bind() != null;
            }
        }
        drawnBindAt = drawnBinds ? lane : -1;
        if (drawnBinds) {
            lane += CgVfxDrawnChannel.SLOTS;
            key.append("|drawnbind");
        }
        boolean lightBinds = false;
        for (int c = 0; c < 4; c++) {
            CgVfxDrawnChannel ch = emitter.light(c);
            lightBinds |= ch != null && ch.bind() != null;
        }
        lightBindAt = lightBinds ? lane : -1;
        if (lightBinds) {
            lane++;
            key.append("|lightbind");
        }
        lifeBindAt = emitter.lifeBinds().isEmpty() ? -1 : lane;
        if (lifeBindAt >= 0) {
            lane += CgVfxLifeBind.LANES / 4;
            key.append("|lifebind");
        }
        Set<CgVfxDataInterface> taken = new LinkedHashSet<>();
        for (CgVfxDataInterface[] read : reads) for (CgVfxDataInterface di : read) take(di, taken);
        if (spawnsOnMesh) take(CgVfxDataInterfaces.SPAWN_MESHES, taken);
        interfaces = taken.toArray(new CgVfxDataInterface[0]);
        Arrays.sort(interfaces, Comparator.comparingInt(CgVfxDataInterfaces::order));
        interfaceList = List.of(interfaces);
        interfaceLanesAt = new int[interfaces.length];
        for (int d = 0; d < interfaces.length; d++) {
            interfaceLanesAt[d] = lane;
            lane += interfaces[d].instanceLanes();
        }
        // Each textured module's entry in its pool's texture table, four a lane after the interfaces' lanes.
        textureEntry = new int[n];
        int textured = 0;
        for (int i = 0; i < n; i++) textureEntry[i] = textures[i].length > 0 ? textured++ : -1;
        texturesAt = lane;
        lane += (textured + 3) / 4;
        socketsAt = lane;
        lane += socketsInherit ? 2 * sockets : sockets;
        instanceRow = lane;
        floors = taken.contains(CgVfxDataInterfaces.FLOOR_Y);
        // Born on another emitter's particles: how they are picked and placed decides the launch's code.
        spawnFrom = emitter.spawnFrom();
        if (spawnFrom != null) {
            if (tier == 0) throw new IllegalArgumentException(emitter.name() + " reads another emitter's particles at feed tier 0");
            key.append("|reads:").append(spawnFrom.selection()).append(spawnFrom.placeAfter() ? ":after" : "")
                    .append(spawnFrom.follow() ? ":follow" : "");
        }
        publishes = emitter.publishes();
        if (publishes && tier == TIERS - 1) {
            throw new IllegalArgumentException(emitter.name() + " publishes its particles at the last feed tier, which no reader can follow");
        }
        if (publishes) key.append("|publishes");
        this.key = key.toString();
    }

    /** What its particles are born on ({@link CgVfxSpawnFrom}), read from the tier before; null for none. */
    @Nullable
    public CgVfxSpawnFrom spawnFrom() {
        return spawnFrom;
    }

    /** Whether its steps publish its live particles, a slot at a time, for the tier after to be born on. */
    public boolean publishes() {
        return publishes;
    }

    /** A form's part of the key: what decides its kernel code. */
    private static String formKey(CgVfxFormInput c) {
        if (c instanceof CgVfxFormInput.Range r) return "RANGE" + (r.perChannel() ? ":" + (r.locked() == null ? "c" : r.locked()) : "");
        if (c instanceof CgVfxFormInput.Curve curve) return "CURVE:" + curve.by();
        if (c instanceof CgVfxFormInput.Array array) return "ARRAY:" + array.by();
        if (c instanceof CgVfxFormInput.Function f) {
            return "FN:" + f.fn() + "(" + formKey(f.first()) + "," + formKey(f.second()) + ")";
        }
        // A user parameter is a lane, its value never in the code; a particle's attribute is read scaled in it.
        if (c instanceof CgVfxFormInput.Binding b) return b.path().startsWith("user.") ? "BIND:user" : "BIND:" + b.path() + "*" + b.scale();
        return c.form().name();
    }

    /** {@code di} and what it requires, each once, required first. */
    private static void take(CgVfxDataInterface di, Set<CgVfxDataInterface> taken) {
        if (taken.contains(di)) return;
        for (CgVfxDataInterface r : di.requires()) take(r, taken);
        taken.add(di);
    }

    /** {@code emitter}'s shape as a definition spawned by nothing. */
    public static CgVfxShape of(CgVfxGpuEmitter emitter) {
        return of(emitter, 0);
    }

    /** {@code emitter}'s shape at feed {@code tier}: 0 for a root, 1 for a root's events' children, 2 for theirs. */
    public static CgVfxShape of(CgVfxGpuEmitter emitter, int tier) {
        if (tier < 0 || tier >= TIERS) return new CgVfxShape(emitter, tier);   // which throws, naming both
        synchronized (SHAPES) {
            CgVfxShape[] tiers = SHAPES.get(emitter);
            if (tiers == null) SHAPES.put(emitter, tiers = new CgVfxShape[TIERS]);
            CgVfxShape shape = tiers[tier];
            if (shape == null) tiers[tier] = shape = new CgVfxShape(emitter, tier);
            return shape;
        }
    }

    /**
     * One string naming the shape: equal for definitions that share a pool and its kernels. Which textures a kind samples
     * is not part of it: a pool keeps a table of them ({@link CgVfxParticlePool#TEXTURE_ENTRIES} a kind), each slot
     * naming its entry.
     */
    public String key() {
        return key;
    }

    /** {@link #key()}: what its kernels are compiled under. */
    public String kernelKey() {
        return key;
    }

    public CgVfxDrawLayout drawLayout() {
        return drawLayout;
    }

    /** How many modules the stack has. */
    public int modules() {
        return kinds.length;
    }

    /** Module {@code i}'s {@code <kind>}. */
    public String kind(int i) {
        return kinds[i];
    }

    /** Module {@code i}'s GLSL given as text, or null for its file. */
    public String source(int i) {
        return sources[i];
    }

    /** Module {@code i}'s file where it names one ({@code CgVfxGpuModule.gpuFile}), else null for the default. */
    public String file(int i) {
        return files[i];
    }

    public boolean afterSolve(int i) {
        return afterSolve[i];
    }

    /** Module {@code i}'s vec4s of numbers. */
    public int paramVectors(int i) {
        return params[i];
    }

    /** Where module {@code i}'s numbers start in a parameter row, in vec4s. */
    public int paramAt(int i) {
        return paramAt[i];
    }

    /** Module {@code i}'s lanes; a copy. */
    public CgVfxLane[] lanes(int i) {
        return lanes[i].clone();
    }

    /** Where module {@code i}'s lanes start in an instance row, in vec4s. */
    public int lanesAt(int i) {
        return lanesAt[i];
    }

    /** Module {@code i}'s data interfaces, in the order its function takes them; a copy. */
    public CgVfxDataInterface[] dataInterfaces(int i) {
        return reads[i].clone();
    }

    /** Every data interface it takes, what its kinds require included, in registration order: the order the Step writes them in. */
    public List<CgVfxDataInterface> dataInterfaces() {
        return interfaceList;
    }

    /** Where interface {@code d}'s ({@link #dataInterfaces()}) lanes start in an instance row, in vec4s. */
    public int dataLanesAt(int d) {
        return interfaceLanesAt[d];
    }

    /** Whether it takes {@code di}, as a kind's or as what one requires. */
    public boolean takes(CgVfxDataInterface di) {
        for (CgVfxDataInterface t : interfaces) if (t == di) return true;
        return false;
    }

    /** Module {@code i}'s textures; a copy. */
    public CgTexture[] textures(int i) {
        return textures[i].clone();
    }

    /** Module {@code i}'s texture count. */
    public int textureCount(int i) {
        return textures[i].length;
    }

    /** Module {@code i}'s texture {@code t}, as the pool binds it. */
    public CgTexture texture(int i, int t) {
        return textures[i][t];
    }

    /**
     * Where module {@code i}'s texture entry index lies in an instance row: the word, from the row's start; -1 for a
     * module sampling none. Four modules' indices a vec4, as {@code uint}s.
     */
    public int textureEntryWord(int i) {
        return textureEntry[i] < 0 ? -1 : texturesAt * 4 + textureEntry[i];
    }

    /** Whether module {@code i}'s texture {@code t} is 3D: a {@code sampler3D}, else a {@code sampler2D}. */
    public boolean volume(int i, int t) {
        return volumes[i][t];
    }

    /** Whether its emitter spawns on a mesh ({@link CgVfxGpuEmitter#spawnsOnMesh}): its Step kernel reads {@code CgVfxSpawnMeshes}. */
    public boolean spawnsOnMesh() {
        return spawnsOnMesh;
    }

    /** The most user bindings a definition takes: a word each of its parameter row. */
    public static final int MAX_USER_BINDINGS = 16;

    /** The lanes {@code bindings} user bindings take: four a lane. */
    public static int userLanes(int bindings) {
        return (bindings + 3) / 4;
    }

    /** How many inputs its definitions bind to user parameters. */
    public int userBindings() {
        return userBindings.length;
    }

    /** User binding {@code b}'s parameter-row word: the float the kernel reads from its lane instead. */
    public int userWord(int b) {
        return userWords[b];
    }

    /** Where its user lanes start in an instance row, in vec4s: binding {@code b} is lane {@code b / 4}'s component {@code b % 4}. */
    public int userAt() {
        return userAt;
    }

    /** How many of its forms' nodes bind a user parameter. */
    public int formBindings() {
        return formBindings.length;
    }

    /** Form binding {@code b}: its node, its parameter's name without {@code user.}, its vec4 in the instance row. */
    public CgVfxFormInput.Binding formBinding(int b) {
        return formBindings[b];
    }

    public String formBindingName(int b) {
        return formBindingNames[b];
    }

    public int formBindingLane(int b) {
        return formBindingLanes[b];
    }

    /** The vec4 a drawn slot of its bound channels in the instance row ({@code CgVfxDrawnChannel.bound}), or -1. */
    public int drawnBindAt() {
        return drawnBindAt;
    }

    /** Where its light's bound channels sit in the instance row, one vec4; -1 where none binds. */
    public int lightBindAt() {
        return lightBindAt;
    }

    /** Whether its particles give light ({@link CgVfxDrawnChannel#LIGHT}): a third drawn element each. */
    public boolean lights() {
        return (drawnMask & 1 << CgVfxDrawnChannel.LIGHT) != 0;
    }

    /** Its light's block in a parameter row, as a drawn slot's: two curves and its rolls; -1 where it gives none. */
    public int lightAt() {
        return lights() ? rowVectors() - lifeRollVectors - LIGHT_VECTORS : -1;
    }

    /** The two vec4s of its look's bound multipliers in the instance row ({@code CgVfxLifeBind}), or -1 without binds. */
    public int lifeBindAt() {
        return lifeBindAt;
    }

    /** Whether any user binding replaces a word of parameter-row vector {@code v}. */
    public boolean userBound(int v) {
        for (int w : userWords) if (w / 4 == v) return true;
        return false;
    }

    /** Whether it takes {@link CgVfxDataInterfaces#FLOOR_Y}: Range then gives its draws the floor under each particle. */
    public boolean floors() {
        return floors;
    }

    /** The drawn slots its particles carry, a bit each ({@code CgVfxDrawnChannel.DYNAMIC_0} to {@code SPRITE}). */
    public int drawnMask() {
        return drawnMask;
    }

    /** Whether its particles are beams ({@code CgVfxModule.BeamSource} and the rest), each keeping a run in its pool. */
    public boolean beams() {
        return beams;
    }

    /** Noise points each beam of it keeps (a power of two), 0 without beam noise. */
    public int beamPoints() {
        return beamPoints;
    }

    /** Where its {@code beam_noise} module's words start in a parameter row, in vec4s; -1 without one. */
    public int beamNoiseAt() {
        return beamNoiseAt;
    }

    /**
     * The drawn buffer's elements a particle: its drawn part, then one holding its drawn slots where it carries any.
     * Range writes each particle's first element into the order, so a draw finds its slots at the next.
     */
    public int drawnStride() {
        return lights() ? 3 : drawnMask == 0 ? 1 : 2;
    }

    /**
     * A parameter row's vec4s in its pool: the curves and starts ({@code CgVfxParticlePool.CURVE_VECTORS}), size by
     * speed, then per drawn slot its two curves and its rolls, then a mesh orientation's start and rate, two ends each,
     * and its rate's two integrals over life where it changes over life, then a life roll's ends and roll slots.
     */
    public int rowVectors() {
        int slots = Integer.bitCount(drawnMask & (1 << CgVfxDrawnChannel.SLOTS) - 1);
        return CgVfxParticlePool.CURVE_VECTORS + 1 + slots * (2 * CgVfxGpuEmitter.CURVE_TEXELS + 1)
                + ((drawnMask & 1 << CgVfxDrawnChannel.ORIENTATION) != 0 ? ORIENTATION_VECTORS : 0)
                + (turnsOverLife ? 2 * CgVfxGpuEmitter.CURVE_TEXELS : 0)
                + (lights() ? LIGHT_VECTORS : 0)
                + lifeRollVectors;
    }

    /** Its life rolls' first vec4 in a parameter row, or -1 without any ({@code CgVfxLifeRoll}). */
    public int lifeRollAt() {
        return lifeRolls > 0 ? rowVectors() - lifeRollVectors : -1;
    }

    /** How many life rolls its rows hold. */
    public int lifeRolls() {
        return lifeRolls;
    }


    /** A mesh orientation's vec4s in a parameter row: its start's two ends, then its rate's. */
    public static final int ORIENTATION_VECTORS = 4;

    /** A light's vec4s in a parameter row: a drawn slot's block. */
    public static final int LIGHT_VECTORS = 2 * CgVfxGpuEmitter.CURVE_TEXELS + 1;

    /** How many events its definitions list. */
    public int events() {
        return triggers.length;
    }

    public CgVfxEvent.Trigger trigger(int e) {
        return triggers[e];
    }

    /** Whether event {@code e} spawns children. */
    public boolean eventSpawns(int e) {
        return eventSpawns[e];
    }

    /** Whether event {@code e} reports rows to the CPU. */
    public boolean eventReports(int e) {
        return eventReports[e];
    }

    /** Whether event {@code e}'s children are born at the effect's origin ({@link CgVfxEvent#fromEmitter}). */
    public boolean eventAtEmitter(int e) {
        return eventAtEmitter[e];
    }

    /** Whether event {@code e} fires at a clip node ({@link CgVfxEvent#at}). */
    public boolean eventAtNode(int e) {
        return eventNodes[e];
    }

    /** Whether any event spawns children: its rows feed the next tier. */
    public boolean spawns() {
        return spawns;
    }

    /** Its feed tier: what it steps after, and which spawn rows its children read ({@link #TIERS}). */
    public int tier() {
        return tier;
    }

    /** Whether an event fires at a clip node: its Step kernel reads {@code CgVfxClipAtlas}. */
    public boolean readsClips() {
        return readsClips;
    }

    /** Whether any event reports rows to the CPU. */
    public boolean reports() {
        return reports;
    }

    /** The vec4s each event field takes: a lane an event. */
    public int eventGroups() {
        return (triggers.length + 3) / 4;
    }

    /** Where event field {@code field} ({@link #EVENT_AGES} to {@link #EVENT_CLIP}) starts in a parameter row, in vec4s. */
    public int eventField(int field) {
        return eventsAt + field * eventGroups();
    }

    /** Where the events' ages and periods start in a parameter row, in vec4s ({@link #EVENT_AGES}); no events, none. */
    public int eventsAt() {
        return eventsAt;
    }

    int laneCount(int i) {
        return lanes[i].length;
    }

    /** How many module forms. */
    public int forms() {
        return forms.length;
    }

    /** Form {@code f}. */
    public CgVfxModuleForm form(int f) {
        return forms[f];
    }

    /** The parameter-row word component {@code k} of form {@code f} replaces. */
    public int formWord(int f, int k) {
        return formWords[f][k];
    }

    /** Where form {@code f}'s own vec4s start in a parameter row. */
    public int formAt(int f) {
        return formAt[f];
    }

    /** The form at row word {@code word} and its component there; null where the word is a constant. */
    public int[] formAtWord(int word) {
        for (int f = 0; f < forms.length; f++) {
            for (int k = 0; k < formWords[f].length; k++) if (formWords[f][k] == word) return new int[]{f, k};
        }
        return null;
    }

    /** How many skeleton points it spawns at; 0 for none. */
    public int sockets() {
        return sockets;
    }

    /** Whether its particles stay on their skeleton points each step. */
    public boolean socketsFollow() {
        return socketsFollow;
    }

    /** Whether its particles take a share of their point's velocity: each point's lane then a velocity lane after all. */
    public boolean socketsInherit() {
        return socketsInherit;
    }

    /** Where its skeleton points' lanes start in an instance row. */
    public int socketsAt() {
        return socketsAt;
    }

    /** Where its skeleton points' header and offsets start in a parameter row. */
    public int socketParamsAt() {
        return socketParamsAt;
    }

    /** Where the roll keys start in a parameter row, a word each. */
    public int keysAt() {
        return keysAt;
    }

    /** How many roll keys: the launch's, then each module form's nodes', then each spawn form's. */
    public int keys() {
        return keyCount;
    }

    /** Module form {@code f}'s nodes. */
    CgVfxFormLayout layout(int f) {
        return layouts[f];
    }

    /** Spawn form {@code f}'s nodes. */
    CgVfxFormLayout spawnLayout(int f) {
        return spawnLayouts[f];
    }

    /** How many spawn forms. */
    public int spawnForms() {
        return spawnForms.length;
    }

    /** Spawn form {@code f}, by target. */
    public CgVfxSpawnForm spawnForm(int f) {
        return spawnForms[f];
    }

    /** Where spawn form {@code f}'s own vec4s start in a parameter row. */
    public int spawnFormAt(int f) {
        return spawnFormAt[f];
    }

    /** The index of {@code target}'s spawn form; -1 where the spawn row's range gives it. */
    public int spawnFormOf(CgVfxSpawnInput target) {
        for (int f = 0; f < spawnForms.length; f++) if (spawnForms[f].target() == target) return f;
        return -1;
    }

    /**
     * Its particles' record layout: the core, then the tail attributes its code writes and a later step or stage
     * reads, so a pool of shapes that never rest nor land stores no flags and no previous position ({@link CgVfxRecord}).
     */
    public CgVfxRecord record() {
        CgVfxRecord r = record;
        if (r == null) record = r = CgVfxEmitterCompiler.record(this);
        return r;
    }

    /** A parameter row's vec4s: the spawn numbers, then each module's in stack order, its events', its forms', its roll keys. */
    public int paramRowVectors() {
        return paramRow;
    }

    /**
     * An instance row's vec4s: {@link CgVfxParticlePool#INSTANCE_HEADER}, then each module's lanes in stack order, then
     * the user lanes ({@link #userAt()}), then the gas's and the grid's.
     */
    public int instanceRowVectors() {
        return instanceRow;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CgVfxShape && ((CgVfxShape) o).key.equals(key);
    }

    @Override
    public int hashCode() {
        return key.hashCode();
    }

    @Override
    public String toString() {
        return key;
    }
}
