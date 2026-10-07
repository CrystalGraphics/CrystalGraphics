package com.crystalgraphics.vfx.particle.gpu.sim;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuEmitter;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuModule;
import com.crystalgraphics.vfx.particle.gpu.CgVfxLane;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWorldInput;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * What makes two emitter definitions share a pool and its kernels: their module kinds in stack order, what each kind
 * declares it takes, the renderer, and their events' triggers with whether each spawns children and reports to the
 * CPU. Never a number, so tuning a definition recompiles nothing. Also the layout of a pool's rows, which follows from
 * it. A module's textures are part of it too, so each sampled texture has a pool of its own; its kernels are keyed by
 * {@link #kernelKey()}, which names only whether each is 2D or 3D.
 *
 * <pre>{@code
 * CgVfxShape shape = CgVfxShape.of(EMBERS);
 * shape.key();                  // "QUADS|gravity:1|drag:1|buoyancy:1|turbulence:1:IVEC4,VEC4,IVEC4,VEC4|wind:1"
 * shape.paramRowVectors();      // spawn numbers then each module's numbers
 * shape.instanceRowVectors();   // the row's header then each module's lanes
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
     * The vec4s a parameter row gives its events, when it has any: each event's age or period, then each one's most
     * firings, {@link CgVfxEvent#MAX_EVENTS} of each.
     */
    public static final int EVENT_VECTORS = CgVfxEvent.MAX_EVENTS / 2;
    /** Each custom kind's text, as first seen: a kind name means one text. */
    private static final Map<String, String> SOURCES = new ConcurrentHashMap<>();
    /** A number per texture a shape has sampled, for the pool key: by identity. */
    private static final Map<CgTexture, Integer> TEXTURE_IDS = new IdentityHashMap<>();

    private final String key, kernelKey;
    private final CgTexture[][] textures;
    private final boolean[][] volumes;
    private final CgVfxEmitter.Renderer renderer;
    private final String[] kinds, sources;
    private final boolean[] afterSolve;
    private final int[] params, paramAt, lanesAt;
    private final CgVfxLane[][] lanes;
    private final CgVfxWorldInput[][] world;
    private final int paramRow, instanceRow;
    private final boolean readsWorld, usesDistance, usesDepth;
    private final CgVfxEvent.Trigger[] triggers;
    private final boolean[] eventSpawns, eventReports;
    private final int eventsAt;
    private final boolean spawns, reports;

    private CgVfxShape(CgVfxGpuEmitter emitter) {
        List<? extends CgVfxGpuModule> modules = emitter.modules();
        int n = modules.size();
        renderer = emitter.renderer();
        kinds = new String[n];
        sources = new String[n];
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
            if (world[i].length > 0 && !afterSolve[i]) {
                throw new IllegalArgumentException(emitter.name() + ": fx_" + kind + " takes world inputs but runs before the solver");
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
        List<CgVfxEvent> events = emitter.events();
        if (events.size() > CgVfxEvent.MAX_EVENTS) {
            throw new IllegalArgumentException(emitter.name() + " lists " + events.size() + " events, past " + CgVfxEvent.MAX_EVENTS);
        }
        triggers = new CgVfxEvent.Trigger[events.size()];
        eventSpawns = new boolean[events.size()];
        eventReports = new boolean[events.size()];
        boolean anySpawns = false, anyReports = false;
        for (int e = 0; e < triggers.length; e++) {
            CgVfxEvent event = events.get(e);
            triggers[e] = event.trigger();
            eventSpawns[e] = event.child() != null;
            eventReports[e] = event.readback() > 0;
            anySpawns |= eventSpawns[e];
            anyReports |= eventReports[e];
            key.append(e == 0 ? "|ev:" : ",").append(triggers[e].name()).append(eventSpawns[e] ? "+s" : "").append(eventReports[e] ? "+r" : "");
        }
        spawns = anySpawns;
        reports = anyReports;
        eventsAt = param;
        if (triggers.length > 0) param += EVENT_VECTORS;
        paramRow = param;
        instanceRow = lane;
        boolean reads = false, distance = false, depth = false;
        for (CgVfxWorldInput[] inputs : world) {
            for (CgVfxWorldInput input : inputs) {
                reads |= input != CgVfxWorldInput.DEPTH;
                distance |= input == CgVfxWorldInput.WORLD_DISTANCE;
                depth |= input == CgVfxWorldInput.DEPTH;
            }
        }
        readsWorld = reads;
        usesDistance = distance;
        usesDepth = depth;
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

    /** {@code emitter}'s shape. */
    public static CgVfxShape of(CgVfxGpuEmitter emitter) {
        return new CgVfxShape(emitter);
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

    /** Whether any module takes a voxel window input: its Step kernel reads the window. */
    public boolean readsWorld() {
        return readsWorld;
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

    /** Whether any event spawns children: its pool steps ahead of the pools of its children. */
    public boolean spawns() {
        return spawns;
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

    /** An instance row's vec4s: {@link CgVfxParticlePool#INSTANCE_HEADER}, then each module's lanes in stack order. */
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
