package com.crystalgraphics.vfx.particle.gpu.sim;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuEmitter;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuModule;
import com.crystalgraphics.vfx.particle.gpu.CgVfxLane;
import com.crystalgraphics.vfx.particle.gpu.CgVfxUserBinding;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWorldInput;

import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * What makes two emitter definitions share a pool and its kernels: their module kinds in stack order, what each kind
 * declares it takes, the renderer, and their events' triggers with whether each spawns children and reports to the
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
    /** What a kind before the solver may take: inputs the pool supplies whatever the window holds. */
    private static final Set<CgVfxWorldInput> BEFORE_SOLVER = EnumSet.of(CgVfxWorldInput.NEIGHBOURS, CgVfxWorldInput.GAS,
            CgVfxWorldInput.GAS_FEED, CgVfxWorldInput.GRID, CgVfxWorldInput.GRID_SPLAT);
    /**
     * The vec4s a parameter row gives its events, when it has any, {@link CgVfxEvent#MAX_EVENTS} of each in two vec4s
     * from these offsets: each one's age or period, its most firings, its window's start and end, and its clip node's
     * word ({@code base | 1 << 31} where the node's matrix steps); then one vec4 of its clip, {@code (row length,
     * frames a second of age, frames, 0)}.
     */
    public static final int EVENT_AGES = 0, EVENT_FIRINGS = 2, EVENT_FROM = 4, EVENT_UNTIL = 6, EVENT_NODES = 8,
            EVENT_CLIP = 10, EVENT_VECTORS = 11;
    /** Feed tiers: a definition spawned by nothing, by a root's events, by a child's. */
    public static final int TIERS = 3;
    /** Each custom kind's text, as first seen: a kind name means one text. */
    private static final Map<String, String> SOURCES = new ConcurrentHashMap<>();
    /** A number per texture a shape has sampled, for the pool key: by identity. */
    private static final Map<CgTexture, Integer> TEXTURE_IDS = new IdentityHashMap<>();

    private final String key, kernelKey;
    private final CgTexture[][] textures;
    private final boolean[][] volumes;
    private final CgVfxEmitter.Renderer renderer;
    private final String[] kinds, sources, files;
    private final boolean[] afterSolve;
    private final int[] params, paramAt, lanesAt;
    private final CgVfxLane[][] lanes;
    private final CgVfxWorldInput[][] world;
    private final int paramRow, instanceRow;
    private final boolean readsWorld, usesDistance, usesDepth, usesEntities, usesNeighbours, usesGas, feedsGas, usesGrid,
            splatsGrid, floors, spawnsOnMesh;
    /** Where the gas's and the grid's two lanes each start in the instance row, after every module's: the pool's, not a kind's. */
    private final int gasAt, gridAt;
    /** Its user bindings, the parameter-row word each replaces, and where their lanes start: four bindings a lane. */
    private final CgVfxUserBinding[] userBindings;
    private final int[] userWords;
    private final int userAt;
    private final CgVfxEvent.Trigger[] triggers;
    private final boolean[] eventSpawns, eventReports, eventNodes;
    private final int eventsAt, tier;
    private final boolean spawns, reports, readsClips;

    private CgVfxShape(CgVfxGpuEmitter emitter, int tier) {
        if (tier < 0 || tier >= TIERS) throw new IllegalArgumentException(emitter.name() + " at feed tier " + tier);
        this.tier = tier;
        List<? extends CgVfxGpuModule> modules = emitter.modules();
        int n = modules.size();
        renderer = emitter.renderer();
        kinds = new String[n];
        sources = new String[n];
        files = new String[n];
        afterSolve = new boolean[n];
        params = new int[n];
        paramAt = new int[n];
        lanesAt = new int[n];
        lanes = new CgVfxLane[n][];
        world = new CgVfxWorldInput[n][];
        textures = new CgTexture[n][];
        volumes = new boolean[n][];
        StringBuilder key = new StringBuilder(renderer.name()), identity = new StringBuilder();
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
            params[i] = m.paramVectors();
            lanes[i] = m.instanceLanes().clone();
            world[i] = m.worldInputs().clone();
            if (params[i] < 0) throw new IllegalArgumentException(emitter.name() + ": fx_" + kind + " declares " + params[i] + " param vectors");
            if (!afterSolve[i]) {
                for (CgVfxWorldInput input : world[i]) {
                    if (!BEFORE_SOLVER.contains(input)) {
                        throw new IllegalArgumentException(emitter.name() + ": fx_" + kind + " takes " + input
                                + " but runs before the solver, which takes " + BEFORE_SOLVER + " alone");
                    }
                }
            }
            paramAt[i] = param;
            lanesAt[i] = lane;
            param += params[i];
            lane += lanes[i].length;
            key.append('|').append(kind).append(afterSolve[i] ? ">" : ":").append(params[i]);
            for (int l = 0; l < lanes[i].length; l++) key.append(l == 0 ? ':' : ',').append(lanes[i][l].name());
            for (int w = 0; w < world[i].length; w++) key.append(w == 0 ? '@' : ',').append(world[i][w].name());
            textures[i] = m.textures().clone();
            volumes[i] = new boolean[textures[i].length];
            for (int t = 0; t < textures[i].length; t++) {
                CgTexture texture = textures[i][t];
                if (texture == null) throw new IllegalArgumentException(emitter.name() + ": fx_" + kind + "'s texture " + t + " is null");
                volumes[i][t] = texture instanceof CgGraphTexture graph ? graph.isVolume() : texture.getTarget() == CgGL.GL_TEXTURE_3D;
                key.append(t == 0 ? '#' : ',').append(volumes[i][t] ? "3d" : "2d");
                identity.append(t == 0 && identity.length() == 0 ? "|tex:" : ",").append(textureId(texture));
            }
        }
        spawnsOnMesh = emitter.spawnsOnMesh();
        if (spawnsOnMesh) key.append("|mesh");
        List<CgVfxEvent> events = emitter.events();
        if (events.size() > CgVfxEvent.MAX_EVENTS) {
            throw new IllegalArgumentException(emitter.name() + " lists " + events.size() + " events, past " + CgVfxEvent.MAX_EVENTS);
        }
        triggers = new CgVfxEvent.Trigger[events.size()];
        eventSpawns = new boolean[events.size()];
        eventReports = new boolean[events.size()];
        eventNodes = new boolean[events.size()];
        boolean anySpawns = false, anyReports = false, anyNodes = false;
        for (int e = 0; e < triggers.length; e++) {
            CgVfxEvent event = events.get(e);
            triggers[e] = event.trigger();
            eventSpawns[e] = event.child() != null;
            eventReports[e] = event.readback() > 0;
            eventNodes[e] = event.node() != CgVfxEvent.NO_NODE;
            anySpawns |= eventSpawns[e];
            anyReports |= eventReports[e];
            anyNodes |= eventNodes[e];
            key.append(e == 0 ? "|ev:" : ",").append(triggers[e].name()).append(eventSpawns[e] ? "+s" : "")
                    .append(eventReports[e] ? "+r" : "").append(eventNodes[e] ? "@n" : "");
        }
        if (anySpawns && tier == TIERS - 1) {
            throw new IllegalArgumentException(emitter.name() + " is a grandchild that spawns: two levels of children");
        }
        if (tier > 0) key.append("|tier").append(tier);
        spawns = anySpawns;
        reports = anyReports;
        readsClips = anyNodes;
        eventsAt = param;
        if (triggers.length > 0) param += EVENT_VECTORS;
        paramRow = param;
        List<CgVfxUserBinding> bound = emitter.userBindings();
        if (bound.size() > MAX_USER_BINDINGS) {
            throw new IllegalArgumentException(emitter.name() + " binds " + bound.size() + " inputs, past " + MAX_USER_BINDINGS);
        }
        userBindings = bound.toArray(new CgVfxUserBinding[0]);
        userWords = new int[userBindings.length];
        for (int b = 0; b < userBindings.length; b++) {
            CgVfxUserBinding u = userBindings[b];
            int block = u.module() == CgVfxUserBinding.SPAWN ? CgVfxGpuEmitter.SPAWN_VECTORS : u.module() < n ? params[u.module()] : -1;
            if (block < 0 || u.word() >= block * 4) {
                throw new IllegalArgumentException(emitter.name() + " binds word " + u.word() + " of "
                        + (u.module() == CgVfxUserBinding.SPAWN ? "its spawn numbers" : "module " + u.module()) + ", which it lacks");
            }
            userWords[b] = (u.module() == CgVfxUserBinding.SPAWN ? 0 : paramAt[u.module()] * 4) + u.word();
            for (int c = 0; c < b; c++) {
                if (userWords[c] == userWords[b]) throw new IllegalArgumentException(emitter.name() + " binds word " + userWords[b] + " twice");
            }
            key.append(b == 0 ? "|user:" : ",").append(userWords[b]);
        }
        userAt = lane;
        lane += userLanes(userBindings.length);
        boolean reads = false, distance = false, depth = false, entities = false, neighbours = false, gas = false,
                feeds = false, grid = false, splats = false, floor = false;
        for (CgVfxWorldInput[] inputs : world) {
            for (CgVfxWorldInput input : inputs) {
                reads |= input != CgVfxWorldInput.DEPTH && input != CgVfxWorldInput.ENTITIES && !BEFORE_SOLVER.contains(input);
                neighbours |= input == CgVfxWorldInput.NEIGHBOURS;
                gas |= input == CgVfxWorldInput.GAS || input == CgVfxWorldInput.GAS_FEED;
                feeds |= input == CgVfxWorldInput.GAS_FEED;
                grid |= input == CgVfxWorldInput.GRID || input == CgVfxWorldInput.GRID_SPLAT;
                splats |= input == CgVfxWorldInput.GRID_SPLAT;
                distance |= input == CgVfxWorldInput.WORLD_DISTANCE;
                depth |= input == CgVfxWorldInput.DEPTH;
                entities |= input == CgVfxWorldInput.ENTITIES;
                floor |= input == CgVfxWorldInput.FLOOR_Y;
            }
        }
        readsWorld = reads;
        usesDistance = distance;
        usesDepth = depth;
        usesEntities = entities;
        usesNeighbours = neighbours;
        usesGas = gas;
        feedsGas = feeds;
        usesGrid = grid;
        splatsGrid = splats;
        gasAt = lane;
        gridAt = lane + (gas ? GAS_LANES : 0);
        instanceRow = gridAt + (grid ? GRID_LANES : 0);
        floors = floor;
        kernelKey = key.toString();
        this.key = kernelKey + identity;
    }

    private static int textureId(CgTexture texture) {
        synchronized (TEXTURE_IDS) {
            Integer id = TEXTURE_IDS.get(texture);
            if (id == null) TEXTURE_IDS.put(texture, id = TEXTURE_IDS.size());
            return id;
        }
    }

    /** {@code emitter}'s shape as a definition spawned by nothing. */
    public static CgVfxShape of(CgVfxGpuEmitter emitter) {
        return new CgVfxShape(emitter, 0);
    }

    /** {@code emitter}'s shape at feed {@code tier}: 0 for a root, 1 for a root's events' children, 2 for theirs. */
    public static CgVfxShape of(CgVfxGpuEmitter emitter, int tier) {
        return new CgVfxShape(emitter, tier);
    }

    /** One string naming the shape: equal for definitions that share a pool. */
    public String key() {
        return key;
    }

    /** {@link #key()} less which textures are sampled: equal for definitions that share kernels. */
    public String kernelKey() {
        return kernelKey;
    }

    public CgVfxEmitter.Renderer renderer() {
        return renderer;
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

    /** Module {@code i}'s world inputs; a copy. */
    public CgVfxWorldInput[] worldInputs(int i) {
        return world[i].clone();
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

    /** Whether module {@code i}'s texture {@code t} is 3D: a {@code sampler3D}, else a {@code sampler2D}. */
    public boolean volume(int i, int t) {
        return volumes[i][t];
    }

    /** Whether any module takes {@link CgVfxWorldInput#DEPTH}: its Step kernel reads the scene's depth. */
    public boolean usesDepth() {
        return usesDepth;
    }

    /** Whether its emitter spawns on a mesh ({@link CgVfxGpuEmitter#spawnsOnMesh}): its Step kernel reads {@code CgVfxSpawnMeshes}. */
    public boolean spawnsOnMesh() {
        return spawnsOnMesh;
    }

    /** Whether any module takes {@link CgVfxWorldInput#ENTITIES}: its Step kernel reads the entity boxes. */
    public boolean usesEntities() {
        return usesEntities;
    }

    /** Whether any module takes {@link CgVfxWorldInput#NEIGHBOURS}: its pool builds a neighbour grid after each step. */
    public boolean usesNeighbours() {
        return usesNeighbours;
    }

    /**
     * Whether any module takes {@link CgVfxWorldInput#GAS} or {@link CgVfxWorldInput#GAS_FEED}: its instance rows carry
     * {@link #GAS_LANES} at {@link #gasAt()}.
     */
    public boolean usesGas() {
        return usesGas;
    }

    /** Whether any module takes {@link CgVfxWorldInput#GAS_FEED}: its Step appends deposits into the gas. */
    public boolean feedsGas() {
        return feedsGas;
    }

    /** The gas's lanes: the instance's origin from the gas's middle and its brick, then a cell's width. */
    public static final int GAS_LANES = 2;

    public int gasAt() {
        return gasAt;
    }

    /**
     * Whether any module takes {@link CgVfxWorldInput#GRID} or {@link CgVfxWorldInput#GRID_SPLAT}: its instance rows carry
     * {@link #GRID_LANES} at {@link #gridAt()}.
     */
    public boolean usesGrid() {
        return usesGrid;
    }

    /** Whether any module takes {@link CgVfxWorldInput#GRID_SPLAT}: its Step appends splats into the grid. */
    public boolean splatsGrid() {
        return splatsGrid;
    }

    /** The grid's lanes: the instance's origin from the grid's low corner and its brick, then a cell's width and its cells. */
    public static final int GRID_LANES = 2;

    public int gridAt() {
        return gridAt;
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

    /** Whether any user binding replaces a word of parameter-row vector {@code v}. */
    public boolean userBound(int v) {
        for (int w : userWords) if (w / 4 == v) return true;
        return false;
    }

    /** Whether any module takes a voxel window input: its Step kernel reads the window. */
    public boolean readsWorld() {
        return readsWorld;
    }

    /** Whether any module takes {@link CgVfxWorldInput#FLOOR_Y}: Range then gives its draws the floor under each particle. */
    public boolean floors() {
        return floors;
    }

    /** Whether any module takes {@link CgVfxWorldInput#WORLD_DISTANCE}: the window's distance field is kept current. */
    public boolean usesDistance() {
        return usesDistance;
    }

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

    /** Where the events' ages and periods start in a parameter row, in vec4s ({@link #EVENT_VECTORS}); no events, none. */
    public int eventsAt() {
        return eventsAt;
    }

    int laneCount(int i) {
        return lanes[i].length;
    }

    /** A parameter row's vec4s: the spawn numbers, then each module's in stack order, then its events' ages. */
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
