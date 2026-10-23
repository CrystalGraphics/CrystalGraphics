package com.crystalgraphics.vfx.particle;

import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.particle.renderer.CgVfxArcRendererProperties;
import com.crystalgraphics.vfx.particle.renderer.CgVfxMeshRendererProperties;
import com.crystalgraphics.vfx.particle.renderer.CgVfxRendererProperties;
import com.crystalgraphics.vfx.particle.renderer.CgVfxSequenceRendererProperties;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.vfx.particle.gpu.CgVfxDrawnChannel;
import com.crystalgraphics.shape.CgShape;
import com.crystalgraphics.vfx.look.CgVfxSlot;
import com.crystalgraphics.vfx.particle.form.CgVfxFormCompiler;
import com.crystalgraphics.vfx.particle.form.CgVfxModuleForm;
import com.crystalgraphics.vfx.particle.form.CgVfxSpawnForm;
import com.crystalgraphics.vfx.particle.form.CgVfxSpawnInput;
import com.crystalgraphics.vfx.particle.gpu.CgVfxDrawLayout;
import com.crystalgraphics.vfx.particle.gpu.CgVfxEvent;
import com.crystalgraphics.vfx.particle.gpu.CgVfxGpuEmitter;
import com.crystalgraphics.vfx.particle.gpu.CgVfxUserBinding;
import com.crystalgraphics.vfx.particle.gpu.CgVfxWords;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxDataInterfaces;
import com.crystalgraphics.vfx.particle.spawn.CgVfxEmitterState;
import com.crystalgraphics.vfx.particle.spawn.CgVfxSpawnBurst;
import com.crystalgraphics.vfx.particle.spawn.CgVfxSpawnFrom;
import com.crystalgraphics.vfx.particle.spawn.CgVfxSpawnPerUnit;
import com.crystalgraphics.vfx.particle.spawn.CgVfxSpawnRate;
import com.crystalgraphics.vfx.sequence.CgVfxClipAtlas;
import com.crystalgraphics.vfx.sequence.CgVfxNodeClip;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What one kind of particle is and how it behaves: when and how many spawn, how each starts, the {@link CgVfxModule}
 * stack its physics is built from, and what changes over its life. Immutable and shared: every effect playing it makes
 * its own {@link CgVfxEmitterInstance}. Units are blocks and seconds. The analogue of a Niagara emitter.
 *
 * <pre>{@code
 * static final CgVfxEmitter EMBERS = CgVfxEmitter.builder("embers")
 *         .state(CgVfxEmitterState.ONCE).capacity(400).burst(0f, 360)       // once: unset, it loops for ever
 *         .shape(CgSphere.of(0.5)).hemisphere().speed(6f, 18f)             // up and out, 6..18 blocks a second
 *         .life(4f, 7f).size(0.05f, 0.25f, 2f).heat(1f)
 *         .module(new CgVfxModule.Gravity(9.8f))
 *         .module(new CgVfxModule.Drag(4f, 0f))
 *         .module(new CgVfxModule.Buoyancy(16f, 0.9f))
 *         .module(new CgVfxModule.Turbulence(8f, 0.12f, 0.6f))
 *         .module(new CgVfxModule.Wind(1f))
 *         .opacity(CgKeyframes.start(0f, 0f).to(0.05f, 1f, CgEasings.LINEAR).to(1f, 0f, CgEasings.OUT_QUAD).build())
 *         .build();
 *
 * // a variant that changes only what differs
 * CgVfxEmitter sparse = EMBERS.toBuilder().capacity(120).burst(0f, 100).build();
 * }</pre>
 *
 * Where particles are born and which way they fly:
 *
 * <pre>{@code
 * CgVfxEmitter.builder("spray").shape(CgCone.of(3, Math.toRadians(25))).speed(4f, 8f)   // a muzzle's spray
 * CgVfxEmitter.builder("ring").shape(CgRing.of(2.8, 3, 0)).edge().ordered(32)            // 32 evenly round a ring
 * CgVfxEmitter.builder("dome").shape(CgSphere.of(2)).surface().hemisphere()              // on a dome, outward
 * CgVfxEmitter.builder("sheet").shape(CgBox.of(2, 0.05, 2)).direction(0, 1, 0, 10f)      // up, within 10 degrees
 * CgVfxEmitter.builder("wake").capacity(600).ratePerBlock(6f).inherit(0.4f)              // six a block the source moves
 * CgVfxEmitter.builder("glow").mesh("mymod:models/sword.obj")                            // off a sword's surface
 * CgVfxEmitter.builder("aura").entity().surface().local()                                // off whoever its effect follows
 * }</pre>
 *
 * Its colour and its size on each axis, multiplying what its layers draw:
 *
 * <pre>{@code
 * CgVfxEmitter.builder("sparks").color(0xFFFFD27A, 0xFFFF5A1F)                           // gold to red-orange
 *         .intensityOverLife(CgKeyframes.start(0f, 4f).to(0.2f, 1f, CgEasings.OUT_QUAD).build())
 * CgVfxEmitter.builder("streak").renderer(MESHES).size(0.4f, 0.4f).scale(1f, 1f, 6f)     // a mesh six times as long
 * }</pre>
 *
 * <ul>
 *   <li>{@link Builder#capacity} bounds the live particles; a spawn into a full set is dropped.</li>
 *   <li>A source that moves within a step spreads that step's rate along its path, each particle stepped for the part of
 *       the step after its birth: a fast emitter leaves an even line, never clumps a step apart.</li>
 *   <li>The player's density setting thins every emitter; one marked {@link Builder#optional} spawns half again at the
 *       Low quality tier.</li>
 *   <li>Particles live in the world: an effect that moves ({@code follow}) spawns them where it is now, and those already
 *       spawned stay where they are. One marked {@link Builder#local} rides the effect instead, particles and all.</li>
 * </ul>
 */
public final class CgVfxEmitter implements CgVfxGpuEmitter {

    final String name;
    private final CgVfxSlot slot;
    final CgVfxDrawLayout drawLayout;
    final List<CgVfxRendererProperties> renderers;
    final boolean optional, local;
    final CgVfxEmitterState state;
    final int capacity;
    /** Its bursts, by time, and each one's element id. */
    final CgVfxSpawnBurst[] bursts;
    final String[] burstIds;
    /**
     * Its elements' ids by the writer's name for each ({@code init}, {@code shape}, {@code velocity}, {@code state},
     * {@code spin_rate}): a file's as read, else the name itself. What its rolls are keyed by ({@code core.md} § Values).
     */
    private final Map<String, String> ids;
    /** Each module's element id from its file; null for one made in code, which the writer names. */
    final String[] moduleIds;
    private final int[] rollKeys;
    /** The books' keys: the loop's delay and duration, each burst's count and probability. */
    final int delayKey, durationKey, rateKey;
    final int[] burstCountKeys, burstProbabilityKeys;
    final CgVfxSpawnRate rate;
    final CgVfxSpawnPerUnit perUnit;
    /** The highest of each range, or of its spawn form's values: what its bounds and books read. */
    final float speedMin, speedMax, lifeMin, lifeMax;
    final CgVfxSpawnShape spawn;
    /** The most blocks a step may move and still spread its spawns and give its velocity; its velocity's share. */
    final float maxStep, inherit;
    /** Each particle's last positions its pool keeps for a {@code trails()} layer; 0 for none. */
    final int trailPoints;
    /** The density each particle draws into its grid's four channels each frame; null for none. */
    final float[] rasterized;
    /** What its particles carry for their draws alone, [slot][channel]; null where unset. */
    final CgVfxDrawnChannel[][] drawn;
    /** Its light's colour scale and brightness ({@link CgVfxDrawnChannel#LIGHT_RED} on); null where it gives none. */
    @Nullable
    final CgVfxDrawnChannel[] light;
    /** Cascade's size by speed: x and y scales a block a second, then their most. */
    final float[] sizeBySpeed;
    /** How its particles play their sheets' frames; null shows frame 0. */
    @Nullable
    final CgVfxSubUvAnimation subUv;
    final float sizeMin, sizeMax, spinMin, spinMax, heat;
    /** Its spawn values given in a form, one a target; how far a radius form moves its birth out at most. */
    final List<CgVfxSpawnForm> spawnForms;
    @Nullable
    final CgVfxSockets sockets;
    @Nullable
    final CgVfxMeshRotation meshRotation;
    final List<CgVfxLifeRoll> lifeRolls;
    final List<CgVfxLifeBind> lifeBinds;
    /** The events it raises, its {@code generate_event} entries: what a handler's name and trigger are matched against. */
    final List<CgVfxEventGenerator> generators;
    /** Where its particles are born on another emitter's, or null. */
    @Nullable
    final CgVfxSpawnFrom spawnFrom;
    /** Whether another emitter is born on its particles. */
    final boolean publishes;
    private final float sweptOut;
    /** Whether its lifetime is a form: the books then book every particle at its longest. */
    final boolean lifeFormed;
    final List<CgVfxModule> modules;
    /** Its modules' numbers from their kinds' declarations, made once: what a pool copies into a row. */
    private final int[] moduleWords;
    /** Its own events, then its clip's bursts and streams as events; how many are its own. */
    final List<CgVfxEvent> events;
    private final int ownEvents;
    /** The handlers its file declares, which its system resolves into its sources' events. */
    final List<CgVfxEventHandler> handlers;
    /** Its inputs bound to user parameters. */
    final List<CgVfxUserBinding> userBindings;
    /** Its module inputs given in a form other than a constant: an emitter file's. */
    final List<CgVfxModuleForm> forms;
    @Nullable
    private final CgVfxNodeClip clip;
    private final float playRate;
    final CgKeyframes sizeOverLife, opacityOverLife;
    /** Each axis's scale over life, then the colour's channels and intensity over life. */
    final CgKeyframes scaleX, scaleY, scaleZ, red, green, blue, intensity;
    /** Its start colour's two ends (rgb times intensity, alpha) and its start scale's two ends (xyz). */
    final float[] colorFrom, colorTo, scaleFrom, scaleTo;
    /** Its largest axis times its size curve's peak at the curve row's samples: what the GPU path draws at most. */
    private final float sizePeak;
    private final boolean feedsGas, splatsGrid;
    /** {@link #scaled} copies, one per scale asked for. */
    private final Map<Float, CgVfxEmitter> scaledCopies = new ConcurrentHashMap<>();
    private static final CgKeyframes ONE = CgKeyframes.constant(1f);
    private static final float[] WHITE = {1f, 1f, 1f, 1f}, UNIT = {1f, 1f, 1f};

    private CgVfxEmitter(Builder b) {
        name = b.name;
        slot = CgVfxSlot.particles(b.name);
        renderers = List.copyOf(b.renderers);
        drawLayout = drawLayout(b.name, renderers, b.clip);
        optional = b.optional;
        local = b.local;
        state = b.state;
        capacity = b.capacity;
        // Stable: two at one time fire in the order given.
        Integer[] byTime = new Integer[b.bursts.size()];
        for (int i = 0; i < byTime.length; i++) byTime[i] = i;
        Arrays.sort(byTime, (x, y) -> Float.compare(b.bursts.get(x).time(), b.bursts.get(y).time()));
        bursts = new CgVfxSpawnBurst[byTime.length];
        burstIds = new String[byTime.length];
        burstCountKeys = new int[byTime.length];
        burstProbabilityKeys = new int[byTime.length];
        for (int i = 0; i < byTime.length; i++) {
            bursts[i] = b.bursts.get(byTime[i]);
            burstIds[i] = b.burstIds.get(byTime[i]);
            burstCountKeys[i] = CgVfxFormCompiler.key(burstIds[i], "count");
            burstProbabilityKeys[i] = CgVfxFormCompiler.key(burstIds[i], "probability");
        }
        ids = Map.copyOf(b.ids);
        moduleIds = b.moduleIds.toArray(new String[0]);
        rollKeys = new int[ROLL_KEYS];
        for (int k = 0; k < ROLL_KEYS; k++) {
            String[] roll = CgVfxGpuEmitter.rollOf(k);
            rollKeys[k] = CgVfxFormCompiler.key(id(roll[0]), roll[1]);
        }
        delayKey = CgVfxFormCompiler.key(id("state"), "loop_delay");
        durationKey = CgVfxFormCompiler.key(id("state"), "loop_duration");
        rateKey = CgVfxFormCompiler.key(id("rate"), "rate");
        rate = b.rate;
        spawn = b.spawn.build(b.name);
        perUnit = b.perUnit;
        maxStep = b.maxStep;
        inherit = b.inherit;
        trailPoints = b.trailPoints;
        rasterized = b.rasterized != null ? b.rasterized.clone() : null;
        drawn = new CgVfxDrawnChannel[CgVfxDrawnChannel.SLOTS][];
        for (int i = 0; i < drawn.length; i++) drawn[i] = b.drawn[i].clone();
        light = b.light != null ? b.light.clone() : null;
        sizeBySpeed = b.sizeBySpeed.clone();
        subUv = b.subUv;
        spawnForms = List.copyOf(b.spawnForms);
        sockets = b.sockets;
        meshRotation = b.meshRotation;
        lifeRolls = List.copyOf(b.lifeRolls);
        lifeBinds = List.copyOf(b.lifeBinds);
        generators = List.copyOf(b.generators);
        spawnFrom = b.spawnFrom;
        publishes = b.publishes;
        speedMin = b.speedMin;
        speedMax = Math.max(b.speedMax, high(CgVfxSpawnInput.SPEED, b.speedMax));
        lifeMin = b.lifeMin;
        lifeMax = Math.max(b.lifeMax, high(CgVfxSpawnInput.LIFETIME, b.lifeMax));
        lifeFormed = spawnForm(CgVfxSpawnInput.LIFETIME) != null;
        sizeMin = b.sizeMin;
        sizeMax = Math.max(b.sizeMax, high(CgVfxSpawnInput.SPRITE_SIZE, b.sizeMax));
        CgVfxSpawnForm radius = spawnForm(CgVfxSpawnInput.RADIUS);
        sweptOut = radius == null ? 0f : Math.max(0f, radius.bounds()[1] - radius.firstEnd());
        spinMin = b.spinMin;
        spinMax = b.spinMax;
        heat = b.heat;
        modules = Collections.unmodifiableList(new ArrayList<>(b.modules));
        moduleWords = CgVfxModuleTypes.words(modules);
        boolean feeds = false, splats = false;
        for (CgVfxModule m : modules) {
            feeds |= Arrays.asList(m.dataInterfaces()).contains(CgVfxDataInterfaces.GAS_FEED);
            splats |= Arrays.asList(m.dataInterfaces()).contains(CgVfxDataInterfaces.GRID_SPLAT);
        }
        feedsGas = feeds;
        splatsGrid = splats;
        clip = b.clip;
        playRate = b.playRate;
        ownEvents = b.events.size();
        handlers = List.copyOf(b.handlers);
        userBindings = List.copyOf(b.userBindings);
        forms = List.copyOf(b.forms);
        for (CgVfxModuleForm f : forms) {
            if (f.module() >= modules.size()) throw new IllegalStateException(name + " gives module " + f.module() + " a form; it has " + modules.size());
        }
        List<CgVfxEvent> all = new ArrayList<>(b.events);
        if (clip != null) all.addAll(clipEvents(clip, playRate));
        events = Collections.unmodifiableList(all);
        sizeOverLife = b.sizeOverLife;
        opacityOverLife = b.opacityOverLife;
        scaleX = b.scaleX;
        scaleY = b.scaleY;
        scaleZ = b.scaleZ;
        red = b.red;
        green = b.green;
        blue = b.blue;
        intensity = b.intensity;
        colorFrom = b.colorFrom;
        colorTo = b.colorTo;
        scaleFrom = b.scaleFrom;
        scaleTo = b.scaleTo;
        float start = 0f;
        for (int a = 0; a < 3; a++) start = Math.max(start, Math.max(Math.abs(scaleFrom[a]), Math.abs(scaleTo[a])));
        float peak = 0f;
        for (int i = 0; i < CgVfxGpuEmitter.CURVE_TEXELS; i++) {
            float p = (float) i / (CgVfxGpuEmitter.CURVE_TEXELS - 1);
            float axis = Math.max(Math.abs(scaleX.at(p)), Math.max(Math.abs(scaleY.at(p)), Math.abs(scaleZ.at(p))));
            peak = Math.max(peak, sizeAt(p) * axis);
        }
        sizePeak = peak * start;
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    /**
     * A clip's bursts and streams as events at their nodes, on the particle's age: a burst an age event at its frame
     * (just past birth for frame 0), a stream a rate within its frames.
     */
    private static List<CgVfxEvent> clipEvents(CgVfxNodeClip clip, float playRate) {
        float perSecond = CgVfxNodeClip.FRAMES_PER_SECOND * playRate;
        List<CgVfxEvent> out = new ArrayList<>();
        for (CgVfxNodeClip.Spawn s : clip.spawns()) {
            CgVfxEvent e = s.isStream()
                    ? CgVfxEvent.every(1f / (s.perSecond() * playRate)).between(s.from() / perSecond, s.until() / perSecond)
                    : CgVfxEvent.onAge(Math.max(s.from() / perSecond, FIRST_FRAME));
            // A clip's every particle bursts on its frame: no frame's cap.
            out.add(e.spawn(s.child(), s.count()).at(s.node()).maxPerFrame(Integer.MAX_VALUE));
        }
        return out;
    }

    /** A burst on a clip's frame 0 fires in the particle's first step: an age event never fires at birth itself. */
    private static final float FIRST_FRAME = 1e-6f;

    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * This emitter at {@code scale} times its size, on the same clock: where particles start, how fast they fly, how big
     * they are, how far apart a rate per block spaces them, and each module's lengths and forces
     * ({@link CgVfxModule#scaled}), so the same burst plays smaller or larger. Event children scale with it. One copy per
     * scale, kept: an effect may ask at every start.
     *
     * <pre>{@code
     * // a kit authored for a 9.6-block blast, at 3.6 blocks
     * CgVfxEmitterInstance billows = new CgVfxEmitterInstance(CgVfxExplosion.BILLOWS.scaled(3.6f / 9.6f), seed);
     * }</pre>
     *
     * The copy keeps the name, so a look still finds it by name.
     */
    public CgVfxEmitter scaled(float scale) {
        if (scale == 1f) return this;
        if (!forms.isEmpty()) throw new IllegalStateException(name + "'s module forms do not scale yet");
        return scaledCopies.computeIfAbsent(scale, s -> {
            Builder b = toBuilder();
            b.spawn = new CgVfxSpawnShape.Builder(spawn.scaled(s, name));
            b.perUnit = perUnit.scaled(s);
            b.maxStep *= s;
            b.spawnForms.replaceAll(f -> f.scaled(s));
            if (b.sockets != null) b.sockets = b.sockets.scaled(s);
            b.speedMin *= s;
            b.speedMax *= s;
            b.sizeMin *= s;
            b.sizeMax *= s;
            b.modules.replaceAll(m -> m.scaled(s));
            b.events.replaceAll(e -> e.child() == null ? e : e.spawn(((CgVfxEmitter) e.child()).scaled(s), e.countMin(), e.count()));
            return b.build();
        });
    }

    public String name() {
        return name;
    }

    /** The slot a look's layers draw these particles on: {@code CgVfxSlot.particles(name())}. */
    public CgVfxSlot slot() {
        return slot;
    }

    /** How its pool lays out what it draws: from its renderers and its clip ({@link CgVfxDrawLayout}). */
    @Override
    public CgVfxDrawLayout drawLayout() {
        return drawLayout;
    }

    /**
     * The layout {@code renderers} draw from: a clip's parts, meshes, arcs, else quads; renderers needing two layouts
     * fail, since one pool lays its records out one way.
     */
    private static CgVfxDrawLayout drawLayout(String name, List<CgVfxRendererProperties> renderers, CgVfxNodeClip clip) {
        CgVfxDrawLayout layout = clip != null ? CgVfxDrawLayout.SEQUENCE : null;
        for (CgVfxRendererProperties r : renderers) {
            CgVfxDrawLayout needs = r instanceof CgVfxSequenceRendererProperties ? CgVfxDrawLayout.SEQUENCE
                    : r instanceof CgVfxMeshRendererProperties ? CgVfxDrawLayout.MESHES
                    : r instanceof CgVfxArcRendererProperties ? CgVfxDrawLayout.ARCS : CgVfxDrawLayout.QUADS;
            if (layout != null && layout != needs) {
                throw new IllegalStateException(name + " has renderers drawing " + layout + " and " + needs
                        + ": one emitter's particles lay out one way; drawing them both ways is not built yet");
            }
            layout = needs;
        }
        if (layout == CgVfxDrawLayout.SEQUENCE && clip == null) {
            throw new IllegalStateException(name + " has a sequence renderer, but plays no clip: use sequence(clip)");
        }
        return layout == null ? CgVfxDrawLayout.QUADS : layout;
    }

    /** Its renderers ({@code renderers:}), in their order: how its particles draw, each with its own material. */
    public List<CgVfxRendererProperties> renderers() {
        return renderers;
    }

    /** The clip each particle plays, a {@link CgVfxDrawLayout#SEQUENCE} emitter's; null for any other. */
    @Override
    @Nullable
    public CgVfxNodeClip clip() {
        return clip;
    }

    @Override
    public float playRate() {
        return playRate;
    }

    public int capacity() {
        return capacity;
    }

    /** Whether the Low quality tier halves it ({@link Builder#optional}). */
    public boolean optional() {
        return optional;
    }

    /** Whether its particles ride the effect rather than stay in the world ({@link Builder#local}). */
    public boolean local() {
        return local;
    }

    /** Its life cycle: loops, their duration and delay, and its inactive response ({@link Builder#state}). */
    public CgVfxEmitterState state() {
        return state;
    }

    public CgVfxSpawnRate rate() {
        return rate;
    }

    /** Its bursts, by time. A copy. */
    public CgVfxSpawnBurst[] bursts() {
        return bursts.clone();
    }

    public CgVfxSpawnPerUnit perUnit() {
        return perUnit;
    }

    @Override
    public List<CgVfxModule> modules() {
        return modules;
    }

    @Override
    public int[] moduleWords() {
        return moduleWords;
    }

    /** The event handlers its file declares ({@link CgVfxEventHandler}): what its system resolves into events. */
    public List<CgVfxEventHandler> handlers() {
        return handlers;
    }

    /** Its own events, without its clip's: what a file writes as handlers. */
    public List<CgVfxEvent> ownEvents() {
        return events.subList(0, ownEvents);
    }

    @Override
    public List<CgVfxEvent> events() {
        return events;
    }

    @Override
    public List<CgVfxUserBinding> userBindings() {
        return userBindings;
    }

    @Override
    public List<CgVfxModuleForm> moduleForms() {
        return forms;
    }

    @Override
    public int trailPoints() {
        return trailPoints;
    }

    @Override
    public float[] rasterized() {
        return rasterized;
    }

    @Override
    @Nullable
    public CgVfxDrawnChannel drawn(int slot, int channel) {
        return drawn[slot][channel];
    }

    @Override
    public void sizeBySpeed(float[] out, int at) {
        System.arraycopy(sizeBySpeed, 0, out, at, 4);
    }

    /** How its particles play their sheets' frames; null where they show frame 0. */
    @Nullable
    public CgVfxSubUvAnimation subUv() {
        return subUv;
    }

    @Override
    public boolean spawnsOnMesh() {
        return spawn.kind() == CgVfxSpawnShape.MESH;
    }

    /** Whether a module of it pours into its instance's gas ({@link CgVfxModule.Feed}). */
    public boolean feedsGas() {
        return feedsGas;
    }

    /** Whether a module of it splats into its instance's grid ({@link CgVfxModule.Splat}). */
    public boolean splatsGrid() {
        return splatsGrid;
    }

    @Override
    public void writeSpawn(CgVfxWords out) {
        out.vec4(speedMin, speedMax, lifeMin, lifeMax)
           .vec4(sizeMin, sizeMax, 0f, heat)
           .vec4(spinMin, spinMax, inherit, 0f)
           .vec4(0f, 0f, 0f, 0f);
        spawn.write(out);
    }

    @Override
    public List<CgVfxSpawnForm> spawnForms() {
        return spawnForms;
    }

    @Override
    @Nullable
    public CgVfxSockets sockets() {
        return sockets;
    }

    @Override
    @Nullable
    public CgVfxMeshRotation meshRotation() {
        return meshRotation;
    }

    @Override
    public List<CgVfxLifeRoll> lifeRolls() {
        return lifeRolls;
    }

    @Override
    @Nullable
    public CgVfxDrawnChannel light(int channel) {
        return light != null ? light[channel] : null;
    }

    /**
     * The events it raises ({@link CgVfxEventGenerator}): its system puts a handler's event on it when one matches the
     * handler's trigger and name.
     */
    @Override
    @Nullable
    public CgVfxSpawnFrom spawnFrom() {
        return spawnFrom;
    }

    @Override
    public boolean publishes() {
        return publishes;
    }

    public List<CgVfxEventGenerator> generators() {
        return generators;
    }

    @Override
    public List<CgVfxLifeBind> lifeBinds() {
        return lifeBinds;
    }

    @Override
    public int[] rollKeys() {
        return rollKeys;
    }

    /** The id of its element the writer names {@code element}: a file's, or the name itself. */
    String id(String element) {
        return ids.getOrDefault(element, element);
    }

    /** Its spawn form for {@code target}; null where the spawn row's range gives it. */
    @Nullable
    CgVfxSpawnForm spawnForm(CgVfxSpawnInput target) {
        for (CgVfxSpawnForm f : spawnForms) if (f.target() == target) return f;
        return null;
    }

    /** The most {@code target}'s spawn form takes; {@code otherwise} without one. */
    private float high(CgVfxSpawnInput target, float otherwise) {
        CgVfxSpawnForm f = spawnForm(target);
        return f == null ? otherwise : f.bounds()[1];
    }

    @Override
    public void writeCurves(float[] out, int at, int texels) {
        int tint = at + 4 * texels, starts = at + 8 * texels;
        for (int i = 0; i < texels; i++) {
            float progress = texels > 1 ? (float) i / (texels - 1) : 0f, k = intensity.at(progress);
            out[at + 4 * i] = scaleX.at(progress);
            out[at + 4 * i + 1] = scaleY.at(progress);
            out[at + 4 * i + 2] = scaleZ.at(progress);
            out[at + 4 * i + 3] = sizeAt(progress);
            out[tint + 4 * i] = red.at(progress) * k;
            out[tint + 4 * i + 1] = green.at(progress) * k;
            out[tint + 4 * i + 2] = blue.at(progress) * k;
            out[tint + 4 * i + 3] = opacityAt(progress);
        }
        System.arraycopy(colorFrom, 0, out, starts, 4);
        System.arraycopy(colorTo, 0, out, starts + 4, 4);
        System.arraycopy(scaleFrom, 0, out, starts + 8, 3);
        out[starts + 11] = 0f;
        System.arraycopy(scaleTo, 0, out, starts + 12, 3);
        out[starts + 15] = 0f;
    }

    /** How much of its starting size a particle is at {@code progress} 0..1 through its life. */
    public float sizeAt(float progress) {
        return sizeOverLife.at(progress);
    }

    /** How opaque a particle is at {@code progress} 0..1 through its life. */
    public float opacityAt(float progress) {
        return opacityOverLife.at(progress);
    }

    /**
     * The most particles one instance can have alive at once, at full share: what a GPU pool sizes its slot by. Every
     * burst within any span of its longest life, plus its rate over that span, and never past its {@link #capacity}, which
     * the instance's books hold it to.
     *
     * <pre>{@code
     * int slot = pool.open(EMBERS, EMBERS.peakAlive());
     * }</pre>
     */
    public int peakAlive() {
        return Math.min(capacity, uncappedPeak());
    }

    /** The most alive at once if nothing held it to its capacity: unbounded for a rate per block. */
    int uncappedPeak() {
        // A particle can outlast its life by its last step: a tenth of a second covers any particle step.
        return perUnit.perBlock() > 0f ? Integer.MAX_VALUE : spawnsWithin(lifeMax + 0.1f);
    }

    /**
     * The most children event {@code event} (its index in {@link #events()}) of one instance can have alive at once:
     * what its child slot is sized by.
     *
     * <pre>{@code
     * int childSlot = childPool.open(DUST, DEBRIS.peakChildren(0));
     * }</pre>
     */
    public int peakChildren(int event) {
        return peakChildren(event, -1);
    }

    /**
     * {@link #peakChildren(int)} for an instance that is itself a child, at most {@code fedAlive} of its particles alive
     * at once (its own slot's size): a grandchild slot's size. Its births within a span are bounded by that many, renewed
     * once per its shortest life.
     *
     * <pre>{@code
     * int middle = ROOT.peakChildren(0);
     * int grandchildren = MIDDLE.peakChildren(0, middle);
     * }</pre>
     */
    public int peakChildren(int event, int fedAlive) {
        CgVfxEvent e = events.get(event);
        CgVfxEmitter child = (CgVfxEmitter) e.child();
        if (child == null) return 0;
        float within = child.lifeMax + 0.1f;
        // Its firings a frame spawn at most maxPerFrame's worth, frames at STEP_RATE a second at most.
        long capped = (long) e.maxPerFrame() * e.count() * (long) Math.ceil(within * STEP_RATE);
        if (e.trigger() == CgVfxEvent.Trigger.BURST) {
            // A batch a step while a rate runs, and every burst of a loop.
            long batches = (rate != CgVfxSpawnRate.NONE || perUnit.perBlock() > 0f ? (long) Math.ceil(within * STEP_RATE) : 0L)
                    + (long) bursts.length * Math.max(1, Math.min(state.loops(), (long) Math.ceil(within / Math.max(state.durationMin(), 1e-3f)) + 1));
            return saturate(Math.min(capped, batches * e.count()));
        }
        if (e.repeats()) {
            // Each parent that can have fired within a child's life fires at most this often in it: a rate by its period,
            // a location every step, a collision at most once a 60 Hz step, and none past its firings. An upper bound.
            double often = Math.ceil(switch (e.trigger()) {
                case RATE -> within / e.age();
                case LOCATION -> within * STEP_RATE;
                default -> within * 60f;
            });
            int per = (int) Math.min(e.firings(), often);
            return saturate(Math.min(capped, (long) births(lifeMax + within, fedAlive) * per * e.count()));
        }
        // A parent fires once, this long after its birth at most; its children then live up to the child's longest life.
        float spread = switch (e.trigger()) {
            case AGE -> 0f;
            case DEATH -> lifeMax - lifeMin;
            case LANDING, COLLISION, RATE, LOCATION, BURST -> lifeMax;
        };
        return saturate(Math.min(capped, (long) births(spread + within, fedAlive) * e.count()));
    }

    /** The particles born within {@code span} seconds: its own spawns, or a fed instance's renewals of {@code fedAlive}. */
    private int births(float span, int fedAlive) {
        if (fedAlive < 0) return spawnsWithin(span);
        return saturate((long) fedAlive * (1L + (long) Math.ceil(span / Math.max(lifeMin, 1f / 60f))));
    }

    /** Steps a second at most that a frame's cap is counted over: a host's frame rate past it under-sizes a child slot. */
    private static final float STEP_RATE = 240f;

    private static int saturate(long n) {
        return (int) Math.min(n, Integer.MAX_VALUE);
    }

    /** The most spawn candidates within any {@code span} seconds. */
    private int spawnsWithin(float span) {
        float duration = state.durationMax();
        int loops = state.loops();
        long fromBursts = 0;
        if (loops == 1) {
            // One loop: the most its bursts put inside any window of the span.
            for (CgVfxSpawnBurst from : bursts) {
                long alive = 0;
                for (CgVfxSpawnBurst b : bursts) {
                    if (b.time() >= from.time() && b.time() < from.time() + span && b.time() <= duration) alive += b.countMax();
                }
                fromBursts = Math.max(fromBursts, alive);
            }
        } else {
            long perLoop = 0;
            for (CgVfxSpawnBurst b : bursts) if (b.time() <= duration) perLoop += b.countMax();
            // Every loop that can begin within the span, the one under way at its start too.
            // A loop lasts a step at least: its emitter crosses one a step.
            fromBursts = perLoop * Math.min(loops, (long) Math.ceil(span / Math.max(state.shortestPeriod(), CgVfxSystem.TICK)) + 1);
        }
        float active = loops == Integer.MAX_VALUE ? Float.POSITIVE_INFINITY : duration * loops;
        float peak = rate.peak(duration);
        long fromRate = peak > 0f ? (long) Math.ceil(peak * Math.min(span, active)) + 2 : 0;
        return (int) Math.min(Integer.MAX_VALUE, fromBursts + fromRate);
    }

    /**
     * How far from its source one of its particles can get within its life, in blocks: its spawn radius, its launch
     * speed, and what its modules can add pulling one way the whole time, {@code windSpeed} being the most the system's
     * wind blows ({@link CgVfxAir#maxSpeed()}). What a look drawn about the source, an arc, is culled by.
     *
     * <pre>{@code
     * float reach = SPARKS.reach(system.air().maxSpeed()) + SPARKS.largestSize() * stretch;
     * }</pre>
     */
    public float reach(float windSpeed) {
        float pull = 0f;
        boolean wind = false;
        for (int i = 0; i < modules.size(); i++) {
            pull += modules.get(i).pull(heat);
            wind |= modules.get(i) instanceof CgVfxModule.Wind;
        }
        return spawn.extent() + sweptOut + speedMax * lifeMax + 0.5f * pull * lifeMax * lifeMax + (wind ? windSpeed * lifeMax : 0f);
    }

    /** The largest a particle draws, before a look's own scale: its largest size times its size curve's peak. */
    public float largestSize() {
        return sizeMax * sizePeak;
    }

    /** Whether its loops spawn by time or distance, not by bursts alone. */
    boolean spawnsContinuously() {
        return rate.perSecond() > 0f || perUnit.perBlock() > 0f;
    }

    public static final class Builder {

        private String name;
        private final List<CgVfxRendererProperties> renderers = new ArrayList<>();
        private boolean optional, local;
        private CgVfxEmitterState state = CgVfxEmitterState.DEFAULT;
        private int capacity = 256;
        private final List<CgVfxSpawnBurst> bursts = new ArrayList<>();
        private CgVfxSpawnRate rate = CgVfxSpawnRate.NONE;
        private CgVfxSpawnPerUnit perUnit = CgVfxSpawnPerUnit.NONE;
        private CgVfxSpawnShape.Builder spawn = new CgVfxSpawnShape.Builder();
        private float sweep, inherit, maxStep = 8f;
        private final List<CgVfxSpawnForm> spawnForms = new ArrayList<>();
        @Nullable
        private CgVfxSockets sockets;
        @Nullable
        private CgVfxMeshRotation meshRotation;
        private final List<CgVfxLifeRoll> lifeRolls = new ArrayList<>();
        private final List<CgVfxLifeBind> lifeBinds = new ArrayList<>();
        private final List<CgVfxEventGenerator> generators = new ArrayList<>();
        @Nullable
        private CgVfxSpawnFrom spawnFrom;
        private boolean publishes;
        private final Map<String, String> ids = new HashMap<>();
        private final List<String> burstIds = new ArrayList<>();
        private final List<String> moduleIds = new ArrayList<>();
        private int trailPoints;
        private float[] rasterized;
        private final CgVfxDrawnChannel[][] drawn = new CgVfxDrawnChannel[CgVfxDrawnChannel.SLOTS][4];
        private CgVfxDrawnChannel[] light;
        private float[] sizeBySpeed = {0f, 0f, 1f, 1f};
        private CgVfxSubUvAnimation subUv;
        private float speedMin, speedMax;
        private float lifeMin = 1f, lifeMax = 1f;
        private float sizeMin = 1f, sizeMax = 1f;
        private float spinMin, spinMax;
        private float heat;
        private final List<CgVfxModule> modules = new ArrayList<>();
        private final List<CgVfxEvent> events = new ArrayList<>();
        private final List<CgVfxEventHandler> handlers = new ArrayList<>();
        private final List<CgVfxUserBinding> userBindings = new ArrayList<>();
        private final List<CgVfxModuleForm> forms = new ArrayList<>();
        private CgVfxNodeClip clip;
        private float playRate = 1f;
        private CgKeyframes sizeOverLife = CgKeyframes.constant(1f);
        private CgKeyframes opacityOverLife = CgKeyframes.start(0f, 1f).to(0.8f, 1f, CgEasings.LINEAR)
                .to(1f, 0f, CgEasings.OUT_QUAD).build();
        private CgKeyframes scaleX = ONE, scaleY = ONE, scaleZ = ONE, red = ONE, green = ONE, blue = ONE, intensity = ONE;
        private float[] colorFrom = WHITE, colorTo = WHITE, scaleFrom = UNIT, scaleTo = UNIT;

        private Builder(String name) {
            this.name = name;
        }

        private Builder(CgVfxEmitter e) {
            name = e.name;
            renderers.addAll(e.renderers);
            optional = e.optional;
            local = e.local;
            state = e.state;
            capacity = e.capacity;
            bursts.addAll(Arrays.asList(e.bursts));
            burstIds.addAll(Arrays.asList(e.burstIds));
            ids.putAll(e.ids);
            rate = e.rate;
            spawn = new CgVfxSpawnShape.Builder(e.spawn);
            perUnit = e.perUnit;
            maxStep = e.maxStep;
            inherit = e.inherit;
            trailPoints = e.trailPoints;
            rasterized = e.rasterized;
            for (int i = 0; i < drawn.length; i++) drawn[i] = e.drawn[i].clone();
            light = e.light != null ? e.light.clone() : null;
            sizeBySpeed = e.sizeBySpeed.clone();
            subUv = e.subUv;
            spawnForms.addAll(e.spawnForms);
            sockets = e.sockets;
            meshRotation = e.meshRotation;
            lifeRolls.addAll(e.lifeRolls);
            lifeBinds.addAll(e.lifeBinds);
            generators.addAll(e.generators);
            spawnFrom = e.spawnFrom;
            publishes = e.publishes;
            speedMin = e.speedMin;
            speedMax = e.speedMax;
            lifeMin = e.lifeMin;
            lifeMax = e.lifeMax;
            sizeMin = e.sizeMin;
            sizeMax = e.sizeMax;
            spinMin = e.spinMin;
            spinMax = e.spinMax;
            heat = e.heat;
            modules.addAll(e.modules);
            moduleIds.addAll(Arrays.asList(e.moduleIds));
            events.addAll(e.events.subList(0, e.ownEvents));
            handlers.addAll(e.handlers);
            userBindings.addAll(e.userBindings);
            forms.addAll(e.forms);
            clip = e.clip;
            playRate = e.playRate;
            sizeOverLife = e.sizeOverLife;
            opacityOverLife = e.opacityOverLife;
            scaleX = e.scaleX;
            scaleY = e.scaleY;
            scaleZ = e.scaleZ;
            red = e.red;
            green = e.green;
            blue = e.blue;
            intensity = e.intensity;
            colorFrom = e.colorFrom;
            colorTo = e.colorTo;
            scaleFrom = e.scaleFrom;
            scaleTo = e.scaleTo;
        }

        /** Renames it: a look holds one emitter per name, and replaces by it. */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Draws its particles with {@code renderer} too, after those added before: a renderer from
         * {@code CgVfxRenderers}, as its file's {@code renderers:} would hold it.
         */
        public Builder renderer(CgVfxRendererProperties renderer) {
            renderers.add(renderer);
            return this;
        }

        /**
         * Each particle plays {@code clip} at its age, drawn as {@link CgVfxDrawLayout#SEQUENCE}: its prefab's parts where its
         * nodes are, sized by the particle's size, turned with its effect; its bursts and streams spawn their children
         * at their nodes, as events after this emitter's own.
         *
         * <pre>{@code
         * static final CgVfxEmitter SHURIKEN = CgVfxEmitter.builder("shuriken").sequence(SPIN).capacity(4)
         *         .state(CgVfxEmitterState.ONCE).burst(0f, 1).life(2f, 2f).size(1.5f, 1.5f).build();
         * CgVfxEmitter.builder("slow").sequence(SPIN, 0.5f)...                // the clip at half speed
         * }</pre>
         *
         * <ul>
         *   <li>Its particles are a pure function of their seed and age: a module reading the world (floor, voxels,
         *       depth, entities, neighbours, gas, grid) is refused at {@link #build}. Its children take any.</li>
         *   <li>Its layers are the clip's prefab's materials: the look takes {@code clip.prefab().kit()}.</li>
         *   <li>Its clip's bursts and streams count toward {@link CgVfxEvent#MAX_EVENTS}.</li>
         * </ul>
         */
        public Builder sequence(CgVfxNodeClip clip) {
            return sequence(clip, 1f);
        }

        /** As {@link #sequence(CgVfxNodeClip)}, the clip played {@code playRate} times as fast. */
        public Builder sequence(CgVfxNodeClip clip, float playRate) {
            if (!(playRate > 0f)) throw new IllegalArgumentException(name + " plays its clip at " + playRate);
            this.clip = Objects.requireNonNull(clip, "clip");
            this.playRate = playRate;
            return this;
        }

        /** Detail the effect reads as whole without: the Low quality tier spawns half of it. */
        public Builder optional() {
            this.optional = true;
            return this;
        }

        /**
         * Rides the effect: when it moves ({@code follow}), its particles move with it, as an aura's sparkles orbit the
         * player. Unmarked, they stay in the world where they spawned, as sparks a moving hand throws off. Unity's
         * simulation space and Niagara's Local Space; an event's children take their parent's.
         *
         * <pre>{@code
         * CgVfxEmitter.builder("auraSparkles").local().rate(40f).shape(CgShell.of(0.6f, 0.9f))...
         * }</pre>
         */
        public Builder local() {
            this.local = true;
            return this;
        }

        /**
         * Its life cycle: how long a loop lasts, how many run, the wait before each, and what its live particles do once
         * it stops. Niagara's Emitter State. {@link CgVfxEmitterState#DEFAULT} unless given: loops for ever, so an
         * emitter that bursts once says {@link CgVfxEmitterState#ONCE}.
         *
         * <pre>{@code
         * CgVfxEmitter.builder("sparks").state(CgVfxEmitterState.ONCE).burst(0f, 120)...
         * CgVfxEmitter.builder("smoke").state(CgVfxEmitterState.once(1.6f)).rate(400f)...       // 1.6 s of it
         * CgVfxEmitter.builder("core").state(CgVfxEmitterState.infinite(2.9f)).burst(0f, 1)...  // renewed every 2.9 s
         * }</pre>
         */
        public Builder state(CgVfxEmitterState state) {
            this.state = Objects.requireNonNull(state, "state");
            return this;
        }

        /**
         * Live particles at most, 256 unless given: a spawn that would pass it is dropped. The slot it plays in is sized by
         * the least of this and what its bursts and rate can have alive ({@link CgVfxEmitter#peakAlive}).
         */
        public Builder capacity(int capacity) {
            this.capacity = capacity;
            return this;
        }

        /**
         * {@code count} particles at once, {@code time} seconds into each loop ({@link CgVfxSpawnBurst}). Unless its
         * {@link #state} is once, it fires again every loop.
         */
        public Builder burst(float time, int count) {
            return burst(CgVfxSpawnBurst.at(time, count));
        }

        /**
         * A burst: a count range rolled each loop, a probability.
         *
         * <pre>{@code
         * .burst(CgVfxSpawnBurst.at(0.25f, 8, 16).probability(0.5f))
         * }</pre>
         */
        public Builder burst(CgVfxSpawnBurst burst) {
            return burst(burst, "burst_" + bursts.size());
        }

        /** A burst with its element id, what its rolls are keyed by: an emitter file's ({@link CgVfxEmitterAssets}). */
        Builder burst(CgVfxSpawnBurst burst, String id) {
            bursts.add(Objects.requireNonNull(burst, "burst"));
            burstIds.add(id);
            return this;
        }

        /**
         * {@code perSecond} particles a second for as long as each loop runs ({@link CgVfxSpawnRate}): an aura, a fire.
         * Under the default {@link #state} that is until the instance is {@link CgVfxEmitterInstance#stop stopped}; give
         * it a {@link #capacity}.
         *
         * <pre>{@code
         * CgVfxEmitter.builder("embers").capacity(400).rate(60f)...                                  // until stopped
         * CgVfxEmitter.builder("smoke").state(CgVfxEmitterState.once(1.6f)).rate(400f)...           // for 1.6 s
         * }</pre>
         */
        public Builder rate(float perSecond) {
            return rate(CgVfxSpawnRate.of(perSecond));
        }

        /**
         * {@code perSecond} particles a second times {@code scale} at each second of the loop: a rate that swells and
         * thins, or one that starts part-way through (a step: two keys at one time).
         *
         * <pre>{@code
         * .rate(300f, CgKeyframes.start(0.15f, 0f).to(0.15f, 1f, CgEasings.LINEAR).build())   // from 0.15 s in
         * }</pre>
         */
        public Builder rate(float perSecond, CgKeyframes scale) {
            return rate(new CgVfxSpawnRate(perSecond, scale));
        }

        public Builder rate(CgVfxSpawnRate rate) {
            this.rate = Objects.requireNonNull(rate, "rate");
            return this;
        }

        /**
         * Where particles are born: filling {@code shape}, about the source. A point unless given.
         *
         * <pre>{@code
         * .shape(CgSphere.of(1.2))                     // anywhere in a ball
         * .shape(CgShell.of(9, 11))                    // between two spheres: a cloud rolling out from an edge
         * .shape(CgCone.of(3, Math.toRadians(25)))     // a spray along +Z; pointing(...) turns it
         * .shape(CgRing.of(2.8, 3, 0)).edge()          // on a circle on the ground
         * .shape(CgCapsule.between(0, 0, 0, 0, 0, 12, 0.3)).edge()   // along a line, 12 blocks forward
         * }</pre>
         *
         * <table>
         *   <tr><th>Shape</th><th>Its axis</th><th>{@link #edge}</th></tr>
         *   <tr><td>{@code CgSphere}, {@code CgShell}</td><td>+Y</td><td>its equator</td></tr>
         *   <tr><td>{@code CgBox}</td><td>+Y</td><td>its twelve edges</td></tr>
         *   <tr><td>{@code CgCylinder}, {@code CgRing}</td><td>+Y</td><td>its outer circles</td></tr>
         *   <tr><td>{@code CgCone}</td><td>+Z, from its apex</td><td>its rim</td></tr>
         *   <tr><td>{@code CgCapsule}</td><td>along its segment</td><td>the segment</td></tr>
         * </table>
         *
         * <ul>
         *   <li>Uniform throughout: a ball fills by volume, a surface by area, an edge by length.</li>
         *   <li>A shell's or ring's front, a plane, a compound or a hull throws: none has an inside to be born in.</li>
         * </ul>
         */
        public Builder shape(CgShape shape) {
            this.spawn.shape(shape, name);
            return this;
        }

        /**
         * Born on {@code mesh}'s surface, by area, and launched along its normal there unless told otherwise: a blade
         * glowing, a rune burning off a model. Turned from its +Y by {@link #pointing} and moved by {@link #at}, as a
         * shape is.
         *
         * <pre>{@code
         * .mesh(SWORD).rate(80f).speed(0.2f, 0.6f)                                  // glowing off the blade
         * .mesh(CgProceduralMesh.revolve(CgProceduralMesh.sphere(2f, 0f, 2f)).build())   // a dome of your own making
         * }</pre>
         *
         * <ul>
         *   <li>Read once, at its first spawn: an edit after that is not seen. Triangles, float positions.</li>
         *   <li>Its surface or {@link #vertices()}: {@link #edge()}, {@link #hemisphere()} and {@link #arc} throw.</li>
         * </ul>
         */
        public Builder mesh(CgMesh mesh) {
            if (mesh == null) throw new IllegalArgumentException(name + ": mesh(null)");
            spawn.mesh(mesh, null);
            return this;
        }

        /** As {@link #mesh(CgMesh)}, an OBJ or glTF file loaded at its first spawn: a server naming it loads nothing. */
        public Builder mesh(String path) {
            if (path == null) throw new IllegalArgumentException(name + ": mesh(null)");
            spawn.mesh(null, path);
            return this;
        }

        /**
         * Born at the {@link #mesh}'s vertices instead of across its surface, each launched along its normal: one at
         * random, or with {@link #ordered} spawn {@code k} at the {@code k mod n}-th of {@code n} spread through them
         * in their order.
         *
         * <pre>{@code
         * .mesh("mymod:models/rune.obj").vertices().ordered(64).burst(0f, 64)   // one at each of 64 of its vertices
         * }</pre>
         */
        public Builder vertices() {
            spawn.placement = CgVfxSpawnShape.VERTICES;
            return this;
        }

        /**
         * Born in Cascade's triangle: corners at {@code (height / 2, 0)} and {@code (-height / 2, ±height·tan(apex / 2))}
         * across the shape's plane, {@code thickness} along its axis. A point is three uniform weights over their sum, as
         * Cascade's {@code LocationPrimitiveTriangle} draws it, so its middle is fuller than its corners.
         *
         * <pre>{@code
         * .triangle(4f, 70f, 0f).pointing(0, 0, 1)   // a flat wedge 4 blocks long, its apex 70 degrees
         * }</pre>
         *
         * <ul>
         *   <li>Degrees, more than 0 and less than 180; a thickness of 0 is flat.</li>
         *   <li>It fills itself: {@link #surface}, {@link #edge}, {@link #hemisphere}, {@link #arc} and {@link #ordered} throw.</li>
         * </ul>
         */
        public Builder triangle(float height, float apexDegrees, float thickness) {
            spawn.triangle(height, (float) Math.toRadians(apexDegrees), thickness, name);
            return this;
        }

        /**
         * Born in the box of the entity its effect follows ({@code CgVfxEffect.follow}), as it stands each step, and
         * launched out of its middle: an aura round whoever casts it, embers off a burning mob. {@link #surface} and
         * {@link #edge} put them on its faces or edges.
         *
         * <pre>{@code
         * CgVfxEmitter.builder("aura").entity().surface().rate(60f).speed(0.1f, 0.4f).local()
         * }</pre>
         *
         * <ul>
         *   <li>An effect following nothing has no box: they are born at the source.</li>
         *   <li>The box is axis aligned where the entity stands: {@link #pointing}, {@link #at}, {@link #arc} and
         *       {@link #ordered} throw.</li>
         * </ul>
         */
        public Builder entity() {
            spawn.entity();
            return this;
        }

        /** Its mesh orientation so far: an emitter file's reader composes its rate onto its start. */
        @Nullable
        CgVfxMeshRotation meshRotation() {
            return meshRotation;
        }

        /** Its mesh particles turned as {@code turn} says ({@link CgVfxMeshRotation}); null for at random by their seed. */
        public Builder meshRotation(@Nullable CgVfxMeshRotation turn) {
            meshRotation = turn;
            return this;
        }

        /** Its size or colour over life multiplied by a range rolled anew each step ({@link CgVfxLifeRoll}), with any others. */
        public Builder lifeRoll(CgVfxLifeRoll roll) {
            lifeRolls.add(roll);
            return this;
        }

        /**
         * Its particles born on another emitter's of its system ({@link CgVfxSpawnFrom}): a system file's
         * {@code spawn_from_emitter}, resolved by its system.
         */
        public Builder spawnFrom(@Nullable CgVfxSpawnFrom from) {
            spawnFrom = from;
            return this;
        }

        /**
         * Whether another emitter of its system is born on its particles ({@link #spawnFrom}): a system file sets it on
         * the source its readers name; in Java, set it on the source.
         */
        public Builder publishes(boolean publishing) {
            publishes = publishing;
            return this;
        }

        /** An event it raises ({@link CgVfxEmitter#generators()}). */
        public Builder generator(CgVfxEventGenerator generator) {
            generators.add(generator);
            return this;
        }

        /** Its look over life multiplied by a user parameter ({@link CgVfxLifeBind}), with any others. */
        public Builder lifeBind(CgVfxLifeBind bind) {
            lifeBinds.add(bind);
            return this;
        }

        /** Spawns on its effect's skeleton, at these points ({@link CgVfxSockets}); null for none. */
        public Builder sockets(@Nullable CgVfxSockets points) {
            sockets = points;
            return this;
        }

        /** Born on the shape's surface, by area, rather than filling it. */
        public Builder surface() {
            spawn.placement = CgVfxSpawnShape.SURFACE;
            return this;
        }

        /** Born on the shape's edge ({@link #shape}'s table), by length: a ring's circle, a capsule's segment. */
        public Builder edge() {
            spawn.placement = CgVfxSpawnShape.EDGE;
            return this;
        }

        /** Only the half of the shape above its middle along its axis: a dome, a half pillar. */
        public Builder hemisphere() {
            spawn.hemisphere = true;
            return this;
        }

        /** Only {@code degrees} of the way round its axis from its +X: a crescent of a ring. Not on a box. */
        public Builder arc(float degrees) {
            if (!(degrees > 0f && degrees <= 360f)) throw new IllegalArgumentException(name + ": an arc of " + degrees);
            spawn.arc = (float) Math.toRadians(degrees);
            return this;
        }

        /**
         * Spawn {@code k} at the {@code k mod n}-th of {@code n} even places round the shape's axis (along a capsule's
         * edge), rather than at random: the evenly spaced rings anime effects are made of (Effekseer's ordered spawning).
         * A partial {@link #arc} puts one at each end. Not on a box.
         *
         * <pre>{@code
         * .shape(CgRing.of(3, 3, 0)).edge().ordered(24).burst(0f, 24)   // 24 evenly round, once
         * }</pre>
         */
        public Builder ordered(int n) {
            if (n < 1) throw new IllegalArgumentException(name + ": ordered(" + n + ")");
            spawn.ordered = n;
            return this;
        }

        /** Turns the shape so its axis ({@link #shape}'s table) points along {@code (x, y, z)}: a cone aimed up, a ring stood on its edge. */
        public Builder pointing(float x, float y, float z) {
            spawn.pointing(x, y, z, name);
            return this;
        }

        /** Moves the shape {@code (x, y, z)} blocks from the source, after it is turned. */
        public Builder at(float x, float y, float z) {
            spawn.atX = x;
            spawn.atY = y;
            spawn.atZ = z;
            return this;
        }

        /** Launched away from the shape's middle: its centre, or a cylinder's or capsule's axis. The default. */
        public Builder outward() {
            spawn.mode = CgVfxSpawnShape.OUTWARD;
            return this;
        }

        /** Launched toward the shape's middle: a gathering, an implosion. */
        public Builder inward() {
            spawn.mode = CgVfxSpawnShape.INWARD;
            return this;
        }

        /**
         * Launched along {@code (x, y, z)}, in the effect's frame, within {@code spreadDegrees} of it, wherever it is born:
         * Niagara's Add Velocity in Cone, whose angle from the axis is drawn evenly. An event's children take the event's
         * normal as up.
         */
        public Builder direction(float x, float y, float z, float spreadDegrees) {
            return direction(x, y, z, 0f, spreadDegrees);
        }

        /**
         * Launched between {@code innerDegrees} and {@code spreadDegrees} of {@code (x, y, z)}, the angle drawn evenly
         * between them and the turn round it evenly: Niagara's cone with an inner angle (half its inner and outer cone
         * angles). A band of headings, up and out:
         *
         * <pre>{@code
         * .shape(CgSphere.of(3)).direction(0, 1, 0, 30f, 85f)   // 30 to 85 degrees off straight up
         * }</pre>
         */
        public Builder direction(float x, float y, float z, float innerDegrees, float spreadDegrees) {
            spawn.direction(x, y, z, innerDegrees, spreadDegrees, name);
            return this;
        }

        /**
         * The shape runs outward at {@code speed} blocks a second from its loop's start, easing to a stop at its end, so
         * particles are born on a front sweeping out: a shock front lifting dust off the ground as it passes. It covers
         * {@code speed} times the loop's duration, halved; a size curve of {@code OUT_QUAD} over it follows it.
         *
         * <pre>{@code
         * CgVfxEmitter.builder("skirt").state(CgVfxEmitterState.once(1.6f)).rate(400f).shape(CgShell.of(9f, 9.5f))
         *         .sweep(46f)                                    // 9 blocks out at the start, 46 at 1.6 s
         *         ...
         * }</pre>
         *
         * <ul>
         *   <li>Its radius by the loop's seconds ({@link CgVfxSpawnForm#swept}), over its longest loop: a sphere, shell,
         *       cylinder or ring, with a finite loop, or {@link #build} throws.</li>
         *   <li>An event's children spawn where the event fired, never on the front.</li>
         * </ul>
         */
        public Builder sweep(float speed) {
            sweep = speed;
            return this;
        }

        /**
         * {@code perBlock} particles for each block its source moves while each loop runs, beside its {@link #rate}: a
         * wake, a trail of footsteps ({@link CgVfxSpawnPerUnit}). A step moving farther than {@link #maxStep} is a
         * teleport and spawns nothing by distance. How fast its source travels is unbounded, so its {@link #capacity} is
         * what holds it: give it one.
         *
         * <pre>{@code
         * CgVfxEmitter.builder("wake").capacity(600).ratePerBlock(6f).inherit(0.4f)...
         * wake.moveTo(x, y, z);   // each tick: the books count how far it went
         * }</pre>
         */
        public Builder ratePerBlock(float perBlock) {
            this.perUnit = new CgVfxSpawnPerUnit(perBlock);
            return this;
        }

        /** Spawns by distance travelled, {@code perUnit} a block scaled by its curve over the loop's seconds. */
        public Builder ratePerBlock(CgVfxSpawnPerUnit perUnit) {
            this.perUnit = perUnit;
            return this;
        }

        /** The farthest a source may move in one step and still spawn by distance and give its velocity: 8 blocks unless given. */
        public Builder maxStep(float blocks) {
            this.maxStep = blocks;
            return this;
        }

        /** Each particle starts with {@code share} of its source's velocity, as an event's children take their parent's. */
        public Builder inherit(float share) {
            this.inherit = share;
            return this;
        }

        /**
         * Keeps each particle's last {@code points} positions, one a particle step (60 a second), so a trails renderer
         * draws a trail behind every particle: sparks streaking along curved paths, embers swirling.
         * 2 to 64; a trail lasts {@code points - 1} steps.
         *
         * <pre>{@code
         * CgVfxEmitter.builder("embers").capacity(400).trail(16)...   // a quarter of a second behind each
         *         .renderer(CgVfxRenderers.trails("streaks").material(STREAKS).field("points", 16).build())
         * }</pre>
         */
        public Builder trail(int points) {
            if (points < 2 || points > 64) throw new IllegalArgumentException("trail(" + points + "): 2 to 64 points");
            this.trailPoints = points;
            return this;
        }

        /**
         * Draws its particles into their instance's grid each frame ({@code CgVfxEmitterInstance.inGrid}): each a soft
         * ball as wide as it is drawn, holding this density at its middle in the grid's four channels, scaled by its
         * opacity over life. The grid's rebuilt channels ({@code CgVfxGrid3D.channel(c)}) hold only this frame's balls, so
         * a volume drawn from them is where the particles are drawn, as a sprite is: Niagara's rasterization grid.
         *
         * <pre>{@code
         * CgVfxEmitter.builder("smoke").capacity(6000).rate(1500f).size(0.6f, 0.6f)...rasterize(0.3f, 0f, 0f, 0f).build();
         * new CgVfxGrid3D(effect).place(0f, 5f, 0f).channel(0);   // channel x rebuilt each frame from them
         * }</pre>
         *
         * <ul>
         *   <li>A particle under a cell wide is drawn a cell wide: give it a size of a few cells.</li>
         *   <li>Into a channel that fades ({@code channel(c, fade, spread)}) it draws nothing: that is {@code Splat}'s.</li>
         * </ul>
         */
        public Builder rasterize(float x, float y, float z, float w) {
            this.rasterized = new float[] {x, y, z, w};
            return this;
        }

        /**
         * Channel {@code channel} (0 to 3) of drawn slot {@code slot} ({@link CgVfxDrawnChannel#DYNAMIC_0} to
         * {@link CgVfxDrawnChannel#SPRITE}): a value each particle carries for its draws alone.
         *
         * <pre>{@code
         * .drawn(CgVfxDrawnChannel.DYNAMIC_0, 0, CgVfxDrawnChannel.over(CgKeyframes.start(0f, 0f).to(1f, 1f, CgEasings.LINEAR).build()))
         * }</pre>
         *
         * <ul>
         *   <li>Only a pool whose emitters set the same slots shares a draw: each slot set is a vec4 a particle.</li>
         * </ul>
         */
        public Builder drawn(int slot, int channel, CgVfxDrawnChannel value) {
            if (slot < 0 || slot >= CgVfxDrawnChannel.SLOTS || channel < 0 || channel > 3) {
                throw new IllegalArgumentException("no drawn slot " + slot + " channel " + channel);
            }
            drawn[slot][channel] = value;
            return this;
        }

        /**
         * Its particles' light, Cascade's {@code Light} module: each a light {@code radiusScale} times its size across,
         * falling off by {@code exponent} (0 for the inverse square), its colour times the colour scale and brightness,
         * which change by age. A {@code light} renderer draws them.
         *
         * <pre>{@code
         * .light(15f, 16f, CgVfxDrawnChannel.over(CgKeyframes.constant(1f)), CgVfxDrawnChannel.over(CgKeyframes.constant(0.5f)),
         *        CgVfxDrawnChannel.over(CgKeyframes.constant(0.2f)), CgVfxDrawnChannel.update(20f, 40f))   // a flicker
         * }</pre>
         *
         * <ul>
         *   <li>The radius scale and exponent are read once at birth ({@link CgVfxSpawnInput#LIGHT_RADIUS},
         *       {@code LIGHT_EXPONENT}); an emitter file gives them in any form, by the emitter's time.</li>
         *   <li>A channel that does not change is {@code over(constant)}.</li>
         *   <li>A particle gives light only through a {@code light} renderer, which also says which share of them do.</li>
         * </ul>
         */
        public Builder light(float radiusScale, float exponent, CgVfxDrawnChannel red, CgVfxDrawnChannel green,
                             CgVfxDrawnChannel blue, CgVfxDrawnChannel brightness) {
            light = new CgVfxDrawnChannel[] {red, green, blue, brightness};
            String element = ids.getOrDefault("light", "light");
            spawnForm(CgVfxSpawnForm.of(CgVfxSpawnInput.LIGHT_RADIUS, element, "radius", (double) radiusScale));
            spawnForm(CgVfxSpawnForm.of(CgVfxSpawnInput.LIGHT_EXPONENT, element, "exponent", (double) exponent));
            return this;
        }

        /**
         * How its particles play their renderers' sheets ({@link CgVfxSubUvAnimation}); unset, they show frame 0.
         *
         * <pre>{@code
         * .subUv(CgVfxSubUvAnimation.loop(24f).randomStart())
         * }</pre>
         */
        public Builder subUv(@Nullable CgVfxSubUvAnimation subUv) {
            this.subUv = subUv;
            return this;
        }

        /**
         * Cascade's size by speed: x and y each times {@code clamp(speed * scale, 1, max)}, speed in blocks a second,
         * multiplying the size over life after it.
         *
         * <pre>{@code
         * .sizeBySpeed(0f, 0.5f, 1f, 3f)   // stretched up to three times as tall, from 2 blocks a second
         * }</pre>
         */
        public Builder sizeBySpeed(float scaleX, float scaleY, float maxX, float maxY) {
            sizeBySpeed = new float[] {scaleX, scaleY, maxX, maxY};
            return this;
        }

        /** Launch speed, blocks a second. */
        public Builder speed(float min, float max) {
            speedMin = min;
            speedMax = max;
            return this;
        }

        /** Seconds a particle lives. */
        public Builder life(float min, float max) {
            lifeMin = min;
            lifeMax = max;
            return this;
        }

        /** Starting size in blocks, evenly between. */
        public Builder size(float min, float max) {
            return size(min, max, 1f);
        }

        /**
         * Starting size in blocks; a {@code skew} over 1 makes most small and a few large: the size by a random draw
         * through {@code min + (max - min) x^skew} ({@link CgVfxSpawnForm#skewed}).
         */
        public Builder size(float min, float max, float skew) {
            sizeMin = min;
            sizeMax = max;
            spawnForms.removeIf(f -> f.target() == CgVfxSpawnInput.SPRITE_SIZE);
            if (skew != 1f) {
                spawnForms.add(CgVfxSpawnForm.of(CgVfxSpawnInput.SPRITE_SIZE, ids.getOrDefault("init", "init"), "sprite_size",
                        CgVfxSpawnForm.skewed(min, max, skew)));
            }
            return this;
        }

        /** One spawn value given in a form, replacing any for its target: an emitter file's ({@link CgVfxEmitterAssets}). */
        Builder spawnForm(CgVfxSpawnForm form) {
            spawnForms.removeIf(f -> f.target() == form.target());
            spawnForms.add(form);
            return this;
        }

        /**
         * Draws each particle away from its place: {@code offset} turned by Euler angles {@code (roll, pitch, yaw)}
         * plus their rates times its age, Cascade's Orbit taken at birth ({@code ParticleModules_Orbit.cpp:111-165}).
         * The particle itself does not move; only where it is drawn.
         *
         * <pre>{@code
         * CgVfxEmitter.builder("halo").orbit(0.5f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, (float) Math.PI)   // half a turn a second round y
         * }</pre>
         *
         * <ul>
         *   <li>Angles are Unreal's roll, pitch and yaw, about x, y and z of Unreal's axes, in radians; the offset is
         *       the host's, in metres.</li>
         *   <li>A file gives each of the three in any form: a range, a curve by the loop, a pick.</li>
         * </ul>
         */
        public Builder orbit(float x, float y, float z, float roll, float pitch, float yaw, float rollRate, float pitchRate,
                             float yawRate) {
            spawnForm(CgVfxSpawnForm.of(CgVfxSpawnInput.ORBIT_OFFSET, "orbit", "offset", vector(x, y, z)));
            spawnForm(CgVfxSpawnForm.of(CgVfxSpawnInput.ORBIT_ROTATION, "orbit", "rotation", vector(roll, pitch, yaw)));
            return spawnForm(CgVfxSpawnForm.of(CgVfxSpawnInput.ORBIT_RATE, "orbit", "rotation_rate", vector(rollRate, pitchRate, yawRate)));
        }

        private static List<Object> vector(float x, float y, float z) {
            return List.of((double) x, (double) y, (double) z);
        }

        /** Its spawn value for {@code target} given so far in a form, or null. */
        @Nullable
        CgVfxSpawnForm spawnForm(CgVfxSpawnInput target) {
            for (CgVfxSpawnForm f : spawnForms) if (f.target() == target) return f;
            return null;
        }

        /** Spin rate in radians a second, either way. */
        public Builder spin(float min, float max) {
            spinMin = min;
            spinMax = max;
            return this;
        }

        /** Heat at birth, 0..1, which {@link CgVfxModule.Buoyancy} turns into lift. */
        public Builder heat(float heat) {
            this.heat = heat;
            return this;
        }

        public Builder module(CgVfxModule module) {
            return module(module, null);
        }

        /** A module with its element id from its file; null for one made in code ({@link CgVfxEmitterAssets}). */
        Builder module(CgVfxModule module, String id) {
            modules.add(module);
            moduleIds.add(id);
            return this;
        }

        public Builder clearModules() {
            modules.clear();
            moduleIds.clear();
            forms.clear();
            return this;
        }

        /** The id of its element the writer names {@code element}: an emitter file's ({@link CgVfxEmitterAssets}). */
        Builder id(String element, String id) {
            ids.put(element, id);
            return this;
        }

        /** One module input given in a form: an emitter file's ({@link CgVfxEmitterAssets}). */
        Builder form(CgVfxModuleForm form) {
            forms.add(form);
            return this;
        }

        /** Clears its bursts: an asset's {@code spawn_burst} entries set them again ({@link CgVfxEmitterAssets}). */
        Builder clearBursts() {
            bursts.clear();
            burstIds.clear();
            return this;
        }

        /** Rides the effect or not, as an asset's {@code local_space} says. */
        Builder local(boolean local) {
            this.local = local;
            return this;
        }

        /** Halved at the Low tier or not, as an asset's spawn count scale says. */
        Builder optional(boolean optional) {
            this.optional = optional;
            return this;
        }

        /** Where particles are born, as an asset's {@code shape_location} and {@code add_velocity} set it. */
        CgVfxSpawnShape.Builder spawn() {
            return spawn;
        }

        /** Its start colour's two ends as rgb times intensity and alpha: an asset's floats, kept exactly. */
        Builder colors(float[] from, float[] to) {
            colorFrom = from.clone();
            colorTo = to.clone();
            return this;
        }

        /**
         * What an emitter file does not carry yet, taken from {@code t}: its trail and clip (P8) and its bindings (P6.3).
         */
        Builder carry(CgVfxEmitter t) {
            trailPoints = t.trailPoints;
            clip = t.clip;
            playRate = t.playRate;
            userBindings.clear();
            userBindings.addAll(t.userBindings);
            return this;
        }

        /**
         * Binds one of its inputs to a user parameter, read from its effect's values each step ({@link CgVfxUserBinding}).
         *
         * <pre>{@code
         * .module(new CgVfxModule.Gravity(9.8f)).bind(CgVfxUserBinding.module(0, "gravity", 1, GRAVITY, -9.8f, 0f))
         * .bind(CgVfxUserBinding.spawn(1, SPEED, 1f, 0f))   // speed max: new spawns only
         * }</pre>
         *
         * <ul>
         *   <li>Module indices count its modules as added; an input its module's kind lacks throws when its shape is made.</li>
         * </ul>
         */
        public Builder bind(CgVfxUserBinding binding) {
            if (binding == null) throw new IllegalArgumentException("a null binding");
            userBindings.add(binding);
            return this;
        }

        /**
         * Reacts to what its particles do: children spawned where one lands, dies or reaches an age, rows to the CPU, or
         * both ({@link CgVfxEvent}).
         *
         * <pre>{@code
         * .event(CgVfxEvent.onLanding().spawn(DUST, 3).inherit(0.2f))   // debris raising dust
         * .event(CgVfxEvent.onDeath().readback(16))                     // a hiss for each ember that dies
         * }</pre>
         *
         * <ul>
         *   <li>At most {@link CgVfxEvent#MAX_EVENTS}, in the order given: an event's index is its place here.</li>
         *   <li>{@link CgVfxEvent#LEVELS} levels of children at most below an emitter spawned by nothing.</li>
         *   <li>An event {@link CgVfxEvent#at at} a node needs a {@link #sequence} whose clip bakes that node.</li>
         *   <li>A child spawns only from its parent's events: its own bursts and rate are ignored, so one definition can
         *       play alone and as a child.</li>
         * </ul>
         */
        /** An event handler of its file's ({@link CgVfxEventHandler}): its system resolves it. */
        public Builder handler(CgVfxEventHandler handler) {
            handlers.add(handler);
            return this;
        }

        public Builder event(CgVfxEvent event) {
            if (events.size() == CgVfxEvent.MAX_EVENTS) {
                throw new IllegalArgumentException(name + " has " + CgVfxEvent.MAX_EVENTS + " events already");
            }
            if (event.child() != null && !(event.child() instanceof CgVfxEmitter)) {
                // Its instance books the child's spawns from its numbers.
                throw new IllegalArgumentException(name + "'s child " + event.child().name() + " is not a CgVfxEmitter");
            }
            events.add(event);
            return this;
        }

        /** Size over life, as a share of the starting size; 1 throughout unless given. */
        public Builder size(CgKeyframes overLife) {
            sizeOverLife = overLife;
            return this;
        }

        /** Opacity over life; full until 80% of its life, then easing out, unless given. */
        public Builder opacity(CgKeyframes overLife) {
            opacityOverLife = overLife;
            return this;
        }

        /**
         * Its start colour, which multiplies the colours of every layer drawing it: Niagara's Initialize Particle colour.
         * White unless given. An {@code 0xAARRGGBB} colour as shaders author colour; its alpha multiplies the opacity.
         *
         * <pre>{@code
         * .color(0xFFFF8A3D)                     // orange embers on a white-hot spark shader
         * .color(0xFF66D9FF, 3f)                 // past white: rgb times 3, which blooms
         * }</pre>
         */
        public Builder color(int argb) {
            return color(argb, 1f);
        }

        /** Its start colour, {@code argb}'s rgb times {@code intensity}. */
        public Builder color(int argb, float intensity) {
            return color(argb, intensity, argb, intensity);
        }

        /**
         * A start colour between two, each channel rolled on its own a particle: Niagara's non-uniform range. Each end's
         * alpha bounds its opacity multiplier.
         *
         * <pre>{@code
         * .color(0xFFFFD27A, 0xFFFF5A1F)                   // gold to red-orange sparks: green and blue vary apart
         * .color(0xFFFFFFFF, 4f, 0xFF3A7BFF, 1.5f)         // white-hot to blue, each with its own intensity
         * }</pre>
         */
        public Builder color(int a, int b) {
            return color(a, 1f, b, 1f);
        }

        /** A start colour mixed between {@code a} times {@code intensityA} and {@code b} times {@code intensityB}. */
        public Builder color(int a, float intensityA, int b, float intensityB) {
            colorFrom = rgba(a, intensityA);
            colorTo = rgba(b, intensityB);
            return this;
        }

        /**
         * Its colour over life, each channel multiplying the start colour's: Niagara's Scale Color. 1 throughout unless
         * given; alpha is {@link #opacity(CgKeyframes)}.
         *
         * <pre>{@code
         * // white-hot, cooling through orange to a dull red
         * .colorOverLife(CgKeyframes.constant(1f),
         *         CgKeyframes.start(0f, 1f).to(0.4f, 0.55f, CgEasings.LINEAR).to(1f, 0.15f, CgEasings.LINEAR).build(),
         *         CgKeyframes.start(0f, 1f).to(0.3f, 0.2f, CgEasings.LINEAR).to(1f, 0.05f, CgEasings.LINEAR).build())
         * }</pre>
         */
        public Builder colorOverLife(CgKeyframes red, CgKeyframes green, CgKeyframes blue) {
            this.red = red;
            this.green = green;
            this.blue = blue;
            return this;
        }

        /**
         * Its colour's intensity over life, multiplying its rgb: a flash at birth fading to its plain colour, HDR. 1
         * throughout unless given.
         *
         * <pre>{@code
         * .intensityOverLife(CgKeyframes.start(0f, 6f).to(0.15f, 1f, CgEasings.OUT_QUAD).build())
         * }</pre>
         */
        public Builder intensityOverLife(CgKeyframes overLife) {
            intensity = overLife;
            return this;
        }

        /**
         * Its start scale on each axis, times its {@link #size(float, float) size}: Niagara's non-uniform sprite size and
         * mesh scale. A quad takes x across and y up; a mesh all three in its own axes. 1 unless given.
         *
         * <pre>{@code
         * .size(0.3f, 0.5f).scale(1f, 1f, 6f)             // a mesh stretched six times along z: a beam's streak
         * .size(0.1f, 0.2f).scale(0.5f, 2f, 1f)           // tall thin quads: rain
         * }</pre>
         */
        public Builder scale(float x, float y, float z) {
            return scale(x, y, z, x, y, z);
        }

        /**
         * A start scale between two, each axis rolled on its own a particle: Niagara's non-uniform range. For one roll
         * scaling every axis together, keep the scale fixed and give {@link #size(float, float)} the range.
         *
         * <pre>{@code
         * .scale(1f, 1f, 4f, 1.2f, 1.2f, 9f)               // streaks 4 to 9 times their width
         * }</pre>
         */
        public Builder scale(float x0, float y0, float z0, float x1, float y1, float z1) {
            scaleFrom = new float[]{x0, y0, z0};
            scaleTo = new float[]{x1, y1, z1};
            return this;
        }

        /**
         * Its scale on each axis over life, times its start scale: Niagara's Scale Mesh Size and Scale Sprite Size. 1
         * throughout unless given; {@link #size(CgKeyframes)} scales all three.
         *
         * <pre>{@code
         * // a shock ring flattening as it spreads
         * CgKeyframes wide = CgKeyframes.start(0f, 1f).to(1f, 3f, CgEasings.OUT_QUAD).build();
         * .scaleOverLife(wide, CgKeyframes.start(0f, 1f).to(1f, 0.2f, CgEasings.LINEAR).build(), wide)
         * }</pre>
         */
        public Builder scaleOverLife(CgKeyframes x, CgKeyframes y, CgKeyframes z) {
            scaleX = x;
            scaleY = y;
            scaleZ = z;
            return this;
        }

        private static float[] rgba(int argb, float intensity) {
            return new float[]{(argb >> 16 & 0xFF) / 255f * intensity, (argb >> 8 & 0xFF) / 255f * intensity,
                    (argb & 0xFF) / 255f * intensity, (argb >>> 24) / 255f};
        }

        public CgVfxEmitter build() {
            if (sweep != 0f) {
                // aaa-design 2.60: the shape's radius as a curve by the loop's seconds.
                String radius = spawn.kind == CgVfxSpawnShape.SPHERE ? "sphere_radius"
                        : spawn.kind == CgVfxSpawnShape.CYLINDER ? "cylinder_radius" : null;
                if (radius == null) throw new IllegalStateException(name + " sweeps a shape with no radius: a sphere, shell, cylinder or ring");
                if (!(state.durationMax() > 0f && state.durationMax() < Float.POSITIVE_INFINITY)) {
                    throw new IllegalStateException(name + " sweeps over its loop, which needs a finite duration");
                }
                spawnForm(CgVfxSpawnForm.of(CgVfxSpawnInput.RADIUS, ids.getOrDefault("shape", "shape"), radius,
                        CgVfxSpawnForm.swept(spawn.b, sweep, state.durationMax())));
                sweep = 0f;
            }
            if (spawnForm(CgVfxSpawnInput.ORBIT_OFFSET) != null) {
                for (CgVfxModule m : modules) {
                    CgVfxModule.CollisionResponse r = CgVfxModule.CollisionResponse.of(m);
                    if (r != null && (r.completion() == CgVfxModule.CollisionCompletion.FREEZE
                            || r.completion() == CgVfxModule.CollisionCompletion.FREEZE_MOVEMENT)) {
                        // Cascade holds a frozen particle's orbit where it was (UpdateOrbitData skips it); Look turns it by age.
                        throw new IllegalStateException(name + "'s orbit on a particle a collision freezes is not built yet");
                    }
                }
            }
            if (meshRotation != null && !meshRotation.steps().isEmpty()) {
                for (CgVfxModule m : modules) {
                    CgVfxModule.CollisionResponse r = CgVfxModule.CollisionResponse.of(m);
                    if (r != null && r.turns()) {
                        throw new IllegalStateException(name + "'s collision damps or freezes its turn, whose rate changes"
                                + " over life: not built yet");
                    }
                }
            }
            if (clip != null) {
                for (CgVfxModule m : modules) {
                    if (m.dataInterfaces().length > 0) {
                        throw new IllegalStateException(name + " plays a clip, so its particles read no world, but "
                                + m.getClass().getSimpleName() + " takes " + Arrays.toString(m.dataInterfaces()));
                    }
                }
                if (events.size() + clip.spawns().size() > CgVfxEvent.MAX_EVENTS) {
                    throw new IllegalStateException(name + " has " + events.size() + " events and its clip "
                            + clip.spawns().size() + " bursts and streams, past " + CgVfxEvent.MAX_EVENTS);
                }
            }
            for (CgVfxEvent e : events) {
                if (e.node() == CgVfxEvent.NO_NODE) continue;
                if (clip == null || e.node() >= clip.prefab().nodes() || !CgVfxClipAtlas.bakes(clip, e.node())) {
                    throw new IllegalStateException(name + ": an event at node " + e.node() + " needs a clip that bakes it: "
                            + "a node with parts, or one its bursts and streams start at");
                }
            }
            return new CgVfxEmitter(this);
        }
    }
}
