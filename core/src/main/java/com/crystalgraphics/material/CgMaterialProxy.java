package com.crystalgraphics.material;

import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.api.buffer.CgBufferLayout;
import com.crystalgraphics.gpu.resource.CgBufferLifetime;
import com.crystalgraphics.material.CgShader;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.compute.source.CgBufferDecl;
import com.crystalgraphics.gpu.resource.CgEngineBufferRegistry;
import com.crystalgraphics.gpu.resource.CgShaderBuffer;
import com.crystalgraphics.gpu.resource.CgUniformBuffer;
import com.crystalgraphics.gpu.staging.CgBufferWriter;
import com.crystalgraphics.gpu.staging.CgStagingBuffer;
import com.crystalgraphics.material.CgMaterialProperties;
import com.crystalgraphics.material.CgMaterialProperty;
import com.crystalgraphics.material.CgMaterialShader;
import com.crystalgraphics.material.CgMaterialShaderRegistry;
import com.crystalgraphics.material.parse.CgParsedPass;
import com.crystalgraphics.material.parse.CgPermutationDomain;
import com.crystalgraphics.material.parse.CgParsedShader;
import com.crystalgraphics.material.parse.CgShaderParser;
import com.crystalgraphics.render.CgWarmBudget;
import com.crystalgraphics.render.draw.CgMaterialBindings;
import com.crystalgraphics.render.draw.CgBlockVersion;
import com.crystalgraphics.api.buffer.CgBuffer;
import com.crystalgraphics.render.draw.CgBufferHandle;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgMaterialArena;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgMaterialState;
import com.crystalgraphics.api.material.CgMaterialValues;
import com.crystalgraphics.api.material.CgMaterialRegistry;
import com.crystalgraphics.api.material.CgMaterialDefinition;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.material.CgMaterialKey;
import lombok.Getter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import javax.annotation.Nullable;
import com.crystalgraphics.material.CgAttachedBuffer;
import com.crystalgraphics.render.shader.CgBindingModel;
import com.crystalgraphics.api.material.CgMaterialInterface;
import com.crystalgraphics.api.material.CgMaterialInstance;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgSampler;

/**
 * What the renderer asks of a material (Unreal's {@code FMaterialRenderProxy}): its pipelines per pass and kind, its
 * bindings, its passes and what it reads of the scene, beside the values and keywords its {@link CgMaterial} sets. A
 * consumer never sees it.
 *
 * <pre>{@code
 * CgMaterialProxy proxy = CgMaterialProxy.of(material);
 * CgPipeline p = proxy.pipeline(CgInstanceKind.OBJECT);
 * CgMaterialBindings bindings = proxy.bindings();
 * }</pre>
 *
 * <p>Engine-internal: one per {@link CgMaterial}, made with it.</p>
 */
public final class CgMaterialProxy {

    /** Each material's and instance's proxy, as {@link CgMaterial} answers it; installed by its class. */
    private static Function<CgMaterialInterface, CgMaterialProxy> access;
    /** Makes the material of a new proxy; installed by {@link CgMaterial}'s class. */
    private static Function<CgMaterialProxy, CgMaterial> materials;

    /** The material this is the proxy of, or an instance's material. */
    private CgMaterial material;
    /** The instance this is the proxy of; null for a material's. */
    @Nullable
    private CgMaterialInstance instance;
    /** Whether an instance or a variant was made of this material: its keywords are then fixed. */
    private boolean shared;
    /** A material's keyword variants by keyword mask, one table its whole family shares; null on an instance. */
    @Nullable
    private SparseVariants family;
    /** {@link #writer()}'s, made once. */
    @Nullable
    private CgMaterialValues writer;

    private static final Logger LOGGER = LogManager.getLogger("CgMaterialProxy");

    /** Monotonically incrementing counter — produces unique per-instance IDs across all materials. */
    private static final AtomicInteger NEXT_MATERIAL_ID = new AtomicInteger(0);

    /** Stable per-instance integer ID. Assigned at construction, never changes. */
    private final int materialId = NEXT_MATERIAL_ID.getAndIncrement();

    /** Block name of the engine-managed material properties UBO. */
    public static final String MATERIAL_PROPERTIES_BLOCK = "CgMaterialBlock";

    // ── Shared shader asset (asset-level, not instance-level) ─────────────────

    /**
     * Shared shader asset for this material. Owns compilation, render state, render queue,
     * and attached buffers. {@code null} for materials created via {@link #forTest}.
     */
    private final CgMaterialShader cgMaterialShader;

    /** Its render state where it is not its shader's: the blend and clip are its shader asset's, cull and depth write applied per pass here. */
    private final CgMaterialState state;

    // ── Per-instance state ─────────────────────────────────────────────────────

    /** Whether {@link #delete()} has been called. */
    private boolean deleted;

    /**
     * Fallback render state for materials created via {@link #forTest(CgRenderState, int)}.
     * In real materials, render state is per-pass and fetched via {@link #getPassRenderState(CgRenderPassVariant)}.
     */
    private CgRenderState renderState = CgRenderState.DEFAULT;

    /**
     * Fallback render queue for materials created via {@link #forTest(CgRenderState, int)}.
     * In real materials this is delegated to {@link CgMaterialShader#getRenderQueue()}.
     */
    private int renderQueue = 2000;

    /**
     * Its values by property, built from its shader's first parse. Never reallocated: {@link CgMaterialProperties#rebuild}
     * updates it in place on a new parse.
     */
    private CgMaterialProperties propStore = null;

    /** Bumped by every call that may change what it draws: values, textures, keywords, buffers, blocks. */
    private int changes;

    /**
     * How many times something that may change what it draws was set: a canvas starts a new draw where this moved, so
     * a value set between two items takes effect at the second.
     */
    public int changes() {
        return changes;
    }

    /** The parse {@link #propStore} was built from: a new parse rebuilds it, keeping the values set. */
    @Nullable
    private CgParsedShader propsParse;

    /** The properties block as a capture copies it. */
    @Nullable
    private CgBufferWriter capturePacker;
    private boolean capturePropsDirty = true;
    /** The properties block as captures last saw it: a new version only after a value moved. */
    @Nullable
    private CgBlockVersion blockVersion;
    private static final int BLOCK_WRITES = CgTrace.name("material.block-writes");
    /** This instance's own contents for uniform blocks attached to the shared shader, read in their place. */
    private Map<CgShaderBuffer, CgBufferWriter> blockOverrides;

    /** Forward then Emissive pipelines by instance kind and keyword mask, for {@link #pipelinesParse}. */
    @Nullable
    private CgPipeline[] pipelines;
    @Nullable
    private CgParsedShader pipelinesParse;

    /**
     * Its enabled keywords, of those its shader declares ({@code Keywords { }}).
     * Only names present in {@link CgParsedShader#featureNames()} are accepted.
     */
    private final Set<String> enabledKeywords = new LinkedHashSet<>();
    /** One view of {@link #enabledKeywords} for every bind: a program lookup hits by its identity. */
    private final Set<String> enabledView = Collections.unmodifiableSet(enabledKeywords);

    /**
     * Values written while its shader does not parse, by name, the last of each: written into {@link #propStore} at the
     * next parse. Null for none.
     */
    @Nullable
    private Map<String, Held> held;

    /** A value {@link #held} keeps: its kind, its numbers, an int's value or a sampler's unit, texture and state. */
    private record Held(int kind, @Nullable float[] numbers, int unit, @Nullable CgTexture texture,
                        @Nullable CgSampler sampler) {
        static final int INT = 0, FLOAT = 1, VEC2 = 2, VEC3 = 3, VEC4 = 4, ARRAY = 5, SAMPLER = 6;

        static Held of(int kind, float... numbers) {
            return new Held(kind, numbers, 0, null, null);
        }

        void into(String name, CgMaterialValues to) {
            float[] n = numbers;
            switch (kind) {
                case INT -> to.set1i(name, unit);
                case FLOAT -> to.set1f(name, n[0]);
                case VEC2 -> to.vec2(name, n[0], n[1]);
                case VEC3 -> to.vec3(name, n[0], n[1], n[2]);
                case VEC4 -> to.vec4(name, n[0], n[1], n[2], n[3]);
                case ARRAY -> to.array(name, n);
                default -> to.sampler(name, unit, texture, sampler);
            }
        }
    }

    // ── forTest-only fields (null for real materials) ──────────────────────────

    /**
     * Declared feature names injected for the forTest path (used by
     * {@link #enableKeyword(String)} validation in forTest mode).
     */
    private List<String> testFeatureNames;

    // ── Immutable key for the forTest program cache ────────────────────────────

    /** Cache key for active keywords on the forTest path. Set-equality for keywords. */
    private static final class ForTestKey {
        final Set<String> keywords;

        ForTestKey(Set<String> keywords) {
            this.keywords = Collections.unmodifiableSet(new LinkedHashSet<>(keywords));
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ForTestKey)) return false;
            return keywords.equals(((ForTestKey) o).keywords);
        }

        @Override
        public int hashCode() {
            return Objects.hash(keywords);
        }
    }

    // ── Constructors (private — use factory methods) ──────────────────────────

    /** Private no-arg constructor used by {@link #forTest} overloads. */
    public CgMaterialProxy() {
        this.cgMaterialShader = null;
        this.state = CgMaterialState.SHADER;
    }

    public CgMaterialProxy(CgMaterialShader asset) {
        this(asset, CgMaterialState.SHADER);
    }

    public CgMaterialProxy(CgMaterialShader asset, CgMaterialState state) {
        this.cgMaterialShader = asset;
        this.state = state;
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    /** The proxy of {@code material}, a material or an instance: what the renderer asks of it. */
    public static CgMaterialProxy of(CgMaterialInterface material) {
        return access.apply(material);
    }

    /** How {@link #of} reaches a proxy, and how a new proxy's material is made: {@link CgMaterial}'s, once. */
    public static void install(Function<CgMaterialInterface, CgMaterialProxy> access,
                               Function<CgMaterialProxy, CgMaterial> materials) {
        if (CgMaterialProxy.access != null) throw new IllegalStateException("installed already");
        CgMaterialProxy.access = access;
        CgMaterialProxy.materials = materials;
    }

    /** The material this is the proxy of, or an instance's material: whose programs it draws. */
    public CgMaterial material() {
        return material;
    }

    /** What a draw names: the instance this is the proxy of, or its material. */
    public CgMaterialInterface drawn() {
        return instance != null ? instance : material;
    }

    /**
     * The material of its family with keyword {@code name} on or off: made at the first ask with this one's values,
     * then the same. Allocates nothing once made.
     *
     * @throws IllegalArgumentException if {@code on} and its shader declares no keyword {@code name}
     */
    public CgMaterial keyword(String name, boolean on) {
        checkNotDeleted();
        if (instance != null) throw new IllegalStateException("an instance's keywords are its material's");
        if (cgMaterialShader != null) cgMaterialShader.ensureParsed();
        List<String> declared = getDeclaredFeatureNames();
        int bit = declared.indexOf(name);
        if (bit < 0) {
            if (!on) return material;
            throw new IllegalArgumentException("Keyword '" + name + "' is not in the Keywords of "
                    + this);
        }
        if (enabledKeywords.contains(name) == on) return material;
        if (on) refuseSecondValue(name);
        int mask = keywordMask(declared);
        return variant(on ? mask | 1 << bit : mask & ~(1 << bit), declared);
    }

    /** A material-format one-of ({@code SHAPE = BOX | CYLINDER}) holds one value: a second is refused, naming the first. */
    private void refuseSecondValue(String name) {
        CgParsedShader parsed = cgMaterialShader == null ? null : cgMaterialShader.getLastParsed();
        if (parsed == null || parsed.domain() == null) return;
        List<List<String>> dimensions = parsed.permutations().dimensions();
        for (int d = 0; d < dimensions.size(); d++) {   // indexed: keyword() allocates nothing once made
            List<String> one = dimensions.get(d);
            if (one.size() < 2 || !one.contains(name)) continue;
            for (int k = 0; k < one.size(); k++) {
                String other = one.get(k);
                if (!other.equals(name) && enabledKeywords.contains(other)) {
                    throw new IllegalArgumentException("Keyword '" + name + "' and '" + other + "' are two values of one"
                            + " one-of in " + this + ": turn '" + other + "' off first");
                }
            }
        }
    }

    private CgMaterial variant(int mask, List<String> declared) {
        SparseVariants members = family(declared);
        synchronized (members) {
            Object had = members.get(mask);
            if (had != null) return (CgMaterial) had;
            CgMaterialProxy v = new CgMaterialProxy(cgMaterialShader, state);
            for (int i = 0; i < declared.size(); i++) if ((mask & 1 << i) != 0) v.enabledKeywords.add(declared.get(i));
            v.inherit(this);
            v.family = members;
            CgMaterial made = materials.apply(v);
            members.put(mask, made);
            return made;
        }
    }

    private synchronized SparseVariants family(List<String> declared) {
        if (family == null) {
            family = new SparseVariants();
            family.put(keywordMask(declared), material);
        }
        return family;
    }

    /** The proxy of a new instance of this material: its keywords and programs, its values to start from. */
    public CgMaterialProxy instance(CgMaterialInstance owner) {
        checkNotDeleted();
        if (instance != null) throw new IllegalStateException("an instance of an instance: instance its material");
        CgMaterialProxy p = new CgMaterialProxy(cgMaterialShader, state);
        p.enabledKeywords.addAll(enabledKeywords);
        p.material = material;
        p.instance = owner;
        p.inherit(this);
        return p;
    }

    /** Copies {@code base}'s values, buffers and test stand-ins, fixing base's keywords from now on. */
    private void inherit(CgMaterialProxy base) {
        base.shared = true;
        renderState = base.renderState;
        renderQueue = base.renderQueue;
        testFeatureNames = base.testFeatureNames;
        if (base.buffers != null) buffers = new HashMap<>(base.buffers);
        base.syncPropsToParse();
        if (base.propStore != null) {
            propStore = new CgMaterialProperties(cloneProperties(base.propStore.all()));
            for (int i = 0; i < propStore.all().size(); i++) propStore.all().get(i).copyValueFrom(base.propStore.all().get(i));
            propsParse = base.propsParse;
        }
        if (base.held != null) held = new HashMap<>(base.held);
        if (base.blockOverrides != null) blockOverrides = new IdentityHashMap<>(base.blockOverrides);
    }

    /**
     * Writes its values by name: an instance's setters, and a material's as the registry makes it. Allocates nothing
     * once its shader has parsed.
     *
     * <pre>{@code
     * CgMaterialProxy.of(material).writer().set1f("_Alpha", 0.5f);   // as the registry makes it
     * }</pre>
     *
     * <p>An instance or a keyword variant copies its material's values when it is made: what the material is written
     * after reaches neither.</p>
     */
    public CgMaterialValues writer() {
        if (writer == null) writer = new Writer();
        return writer;
    }

    private final class Writer implements CgMaterialValues {

        /** The parsed values to write into, or null to hold the write until a parse. */
        @Nullable
        private CgMaterialValues open() {
            changes++;
            checkNotDeleted();
            syncPropsToParse();
            return propStore;
        }

        private CgMaterialValues done() {
            if (propStore.consumeBlockChanged()) {
                capturePropsDirty = true;
            }
            propStore.consumeSamplerUnitChanged();
            return this;
        }

        private CgMaterialValues hold(String name, Held value) {
            if (held == null) held = new HashMap<>();
            held.put(name, value);
            return this;
        }

        @Override
        public CgMaterialValues set1i(String name, int value) {
            CgMaterialValues to = open();
            if (to == null) return hold(name, new Held(Held.INT, null, value, null, null));
            to.set1i(name, value);
            return done();
        }

        @Override
        public CgMaterialValues set1f(String name, float value) {
            CgMaterialValues to = open();
            if (to == null) return hold(name, Held.of(Held.FLOAT, value));
            to.set1f(name, value);
            return done();
        }

        @Override
        public CgMaterialValues vec2(String name, float x, float y) {
            CgMaterialValues to = open();
            if (to == null) return hold(name, Held.of(Held.VEC2, x, y));
            to.vec2(name, x, y);
            return done();
        }

        @Override
        public CgMaterialValues vec3(String name, float x, float y, float z) {
            CgMaterialValues to = open();
            if (to == null) return hold(name, Held.of(Held.VEC3, x, y, z));
            to.vec3(name, x, y, z);
            return done();
        }

        @Override
        public CgMaterialValues vec4(String name, float x, float y, float z, float w) {
            CgMaterialValues to = open();
            if (to == null) return hold(name, Held.of(Held.VEC4, x, y, z, w));
            to.vec4(name, x, y, z, w);
            return done();
        }

        @Override
        public CgMaterialValues array(String name, float[] values) {
            CgMaterialValues to = open();
            if (to == null) return hold(name, Held.of(Held.ARRAY, values.clone()));
            to.array(name, values);
            return done();
        }

        @Override
        public CgMaterialValues sampler(String name, int unit, CgTexture texture, @Nullable CgSampler sampler) {
            CgMaterialValues to = open();
            if (to == null) return hold(name, new Held(Held.SAMPLER, null, unit, texture, sampler));
            to.sampler(name, unit, texture, sampler);
            return done();
        }
    }

    /** Makes this the proxy of {@code material}: once, as the material is made. */
    public void owner(CgMaterial material) {
        if (this.material != null) throw new IllegalStateException("already the proxy of " + this.material);
        this.material = material;
    }

    /**
     * What the driver said about this material's last failed compile, or null.
     *
     * <p>Forwarded from the shader so a <b>generated</b> material's owner can act on it: a graph-emitted
     * shader that the driver refuses is a message the user needs, and the line number in it maps back to
     * the node that wrote the line. For a shipped asset this is still just the log.</p>
     */
    @Nullable
    public String lastCompileError() {
        return cgMaterialShader == null ? null : cgMaterialShader.lastCompileError();
    }

    /** Its render state where it is not its shader's; {@link CgMaterialState#SHADER} for none. */
    public CgMaterialState state() {
        return state;
    }

    public static CgMaterialProxy forTest(CgRenderState renderState, int renderQueue) {
        CgMaterialProxy m = new CgMaterialProxy();
        m.renderState = renderState;
        m.renderQueue = renderQueue;
        return m;
    }

    public static CgMaterialProxy forTest(CgRenderState renderState, int renderQueue, CgMaterialProperties propStore) {
        CgMaterialProxy m = forTest(renderState, renderQueue);
        m.propStore = propStore;
        return m;
    }

    /**
     * Creates a test material with declared feature names for unit-testing keyword
     * enable/disable validation without a real GL context.
     *
     * @param renderState  render state for this test material
     * @param renderQueue  render queue priority
     * @param featureNames declared feature names to accept in {@link #enableKeyword(String)}
     * @return a test material with keyword validation backed by the given names
     */
    public static CgMaterialProxy forTest(CgRenderState renderState, int renderQueue,
                               List<String> featureNames) {
        CgMaterialProxy m = forTest(renderState, renderQueue);
        m.testFeatureNames = Collections.unmodifiableList(new ArrayList<>(featureNames));
        return m;
    }

    public CgMaterialProperty getPropertyForTest(String name) {
        if (propStore == null) return null;
        return propStore.all().stream().filter(p -> p.getName().equals(name)).findFirst().orElse(null);
    }

    // ── Property bindings ─────────────────────────────────────────────────────

    /**
     * The properties its shader declares, and under {@code PerDrawValues On} the hidden row slots the parser adds, from the
     * parse alone: what a binder checks a name and its kind against. Empty while the source does not parse.
     *
     * <pre>{@code
     * for (CgMaterialProperty p : material.properties()) if (p.isRowable()) bindable.add(p.getName());
     * }</pre>
     */
    public List<CgMaterialProperty> properties() {
        checkNotDeleted();
        syncPropsToParse();
        return propStore == null ? Collections.emptyList() : propStore.all();
    }

    /** The keywords its shader declares ({@code Keywords { }}), from the parse alone. */
    public List<String> declaredKeywords() {
        checkNotDeleted();
        if (cgMaterialShader != null) cgMaterialShader.ensureParsed();
        return getDeclaredFeatureNames();
    }

    /**
     * The keywords a renderer switches on its shader at runtime (its {@code switched} keywords and one-ofs), from the parse alone:
     * every combination a precache of the shader builds.
     */
    public CgPermutationDomain permutations() {
        checkNotDeleted();
        CgParsedShader parsed = cgMaterialShader == null ? null : cgMaterialShader.ensureParsed();
        return parsed == null ? CgPermutationDomain.EMPTY : parsed.permutations();
    }

    /**
     * Whether its shader's {@code Buffers { }} declares {@code name}, from the parse alone: what {@link #buffer} must bind
     * before its first draw.
     *
     * <pre>{@code
     * if (material.declaresBuffer("FX_CLIPS")) material.buffer("FX_CLIPS", clips);
     * }</pre>
     */
    public boolean declaresBuffer(String name) {
        checkNotDeleted();
        CgParsedShader parsed = cgMaterialShader == null ? null : cgMaterialShader.ensureParsed();
        return parsed != null && parsed.buffer(name) != null;
    }

    // ── Keyword API ───────────────────────────────────────────────────────────

    /**
     * Enables a keyword its shader declares ({@code Keywords { NAME }}) on a material being made, before anything draws
     * it or is made of it: the registry's, building a definition's material. Everyone else asks
     * {@link #keyword(String, boolean)}. Parses, compiles nothing.
     *
     * @throws IllegalArgumentException if {@code name} is not declared as a feature in this shader
     * @throws IllegalStateException once an instance or a variant was made of it
     */
    public void enableKeyword(String name) {
        if (shared || family != null || instance != null) {
            throw new IllegalStateException(this + "'s keywords are fixed once made: ask keyword(name, on)");
        }
        changes++;
        checkNotDeleted();
        // The declared features are a parse product: validating a keyword compiles nothing.
        if (cgMaterialShader != null) cgMaterialShader.ensureParsed();
        List<String> declared = getDeclaredFeatureNames();
        if (!declared.contains(name)) {
            throw new IllegalArgumentException(
                    "Keyword '" + name + "' is not in this shader's Keywords");
        }
        enabledKeywords.add(name);
    }

    /**
     * The forward program per keyword set, indexed by {@link #keywordMask}, for the shader revision in
     * {@link #variantsRevision}. A UI material is bound hundreds of times a frame with its keywords toggled between
     * draws, and each resolve built a program key and copied the keyword set: most of what a busy frame allocated.
     */
    @Nullable
    private CgShader[] variants;
    private int variantsRevision = -1;
    @Nullable
    private List<String> variantsDeclared;

    /** Keyword sets cached in a dense table, 2^n of them; past this, {@link SparseVariants}. */
    private static final int DENSE_KEYWORDS = 8;
    /** {@code CgInstanceKind.values().length}, once: {@code values()} copies its array each call. */
    private static final int KINDS = CgInstanceKind.values().length;
    /** The most keywords a shader declares (the parser's cap): a mask's width, an int's non-negative bits. */
    private static final int MAX_KEYWORDS = 31;

    /**
     * A sparse long-keyed cache of keyword variants: open addressing, so a lookup allocates nothing. For a shader past
     * {@link #DENSE_KEYWORDS}, whose 2^n table would be mostly empty.
     */
    private static final class SparseVariants {
        private long[] keys = new long[16];
        private Object[] values = new Object[16];
        private int size;

        Object get(long key) {
            int mask = keys.length - 1;
            for (int i = mix(key) & mask; ; i = (i + 1) & mask) {
                if (values[i] == null) return null;
                if (keys[i] == key) return values[i];
            }
        }

        void put(long key, Object value) {
            if ((size + 1) * 2 > keys.length) grow();
            int mask = keys.length - 1;
            for (int i = mix(key) & mask; ; i = (i + 1) & mask) {
                if (values[i] == null) {
                    keys[i] = key;
                    values[i] = value;
                    size++;
                    return;
                }
                if (keys[i] == key) {
                    values[i] = value;
                    return;
                }
            }
        }

        private void grow() {
            long[] k = keys;
            Object[] v = values;
            keys = new long[k.length * 2];
            values = new Object[k.length * 2];
            size = 0;
            for (int i = 0; i < k.length; i++) if (v[i] != null) put(k[i], v[i]);
        }

        void forEach(Consumer<Object> each) {
            for (Object v : values) if (v != null) each.accept(v);
        }

        private static int mix(long key) {
            long h = key * 0x9E3779B97F4A7C15L;
            return (int) (h ^ (h >>> 32));
        }
    }

    @Nullable
    private SparseVariants sparseVariants, sparsePipelines;

    /** The enabled keywords as bits over the declared features, or -1 past the 31 a shader may declare. */
    private int keywordMask() {
        return keywordMask(getDeclaredFeatureNames());
    }

    private int keywordMask(List<String> declared) {
        if (declared.size() > MAX_KEYWORDS) return -1;
        int mask = 0;
        for (int i = 0; i < declared.size(); i++) {
            if (enabledKeywords.contains(declared.get(i))) mask |= 1 << i;
        }
        return mask;
    }

    /** The forward pass's render state, and the parse it was read from. */
    @Nullable
    private CgRenderState forwardRenderState;
    @Nullable
    private CgParsedShader forwardRenderStateParse;

    /**
     * Returns {@code true} if the given keyword is currently enabled on this material instance.
     *
     * @param name keyword name to query
     * @return {@code true} if enabled, {@code false} otherwise
     */
    public boolean isKeywordEnabled(String name) {
        checkNotDeleted();
        return enabledKeywords.contains(name);
    }

    // ── Draw-time API ─────────────────────────────────────────────────────────

    /**
     * {@code -Dcrystalgraphics.shader.parallelCompile=false} makes {@link #precache} answer ready at once, so the
     * first bind compiles on the spot: the escape hatch for a driver that misreports completion.
     */
    private static final boolean PARALLEL_COMPILE = CgVar.bool("crystalgraphics.shader.parallelCompile", true,
            CgVar.DEV | CgVar.RESTART, "Materials compile ahead of their first bind where the driver links on its own"
                    + " threads; false compiles at the first bind: for a driver that misreports completion.").get();

    /**
     * Starts this material's compile without waiting for it, and says whether its Forward pipeline's program would
     * now build without waiting on the driver. What a caller that must never stall a frame polls instead of drawing.
     *
     * <pre>{@code
     * CgMaterialProxy next = CgMaterialProxy.fromSource(source);
     * // each frame:
     * if (next.precache()) {        // compiled, or failed: next.lastCompileError() says which
     *     current.delete();
     *     current = next;
     * }
     * draw(current.pipeline(kind));  // the old picture until the new one is ready
     * }</pre>
     *
     * <p>False on the frame it starts a compile, always: where the driver cannot report progress, that frame is
     * the head start its own compile threads get. False too while the frame's {@link CgWarmBudget} is spent, so
     * many materials precached at once spread over frames. Drawing before it answers true waits for the compile, as
     * a material that was never precached does.</p>
     *
     * <p>It compiles the Forward pass. A depth program the shader does not author is generated the first time a Depth
     * pipeline or {@link #hasCompiledDepthPass} asks for it.</p>
     */
    public boolean precache() {
        checkNotDeleted();
        if (cgMaterialShader == null || !PARALLEL_COMPILE) return true;
        if (!cgMaterialShader.isDirty() && !cgMaterialShader.isPending()) return true;
        if (!CgWarmBudget.allows()) return false;
        long start = System.nanoTime();
        try {
            if (cgMaterialShader.isDirty()) {
                cgMaterialShader.submitRecompile(true);
                return false;
            }
            return cgMaterialShader.pollPending();
        } finally {
            CgWarmBudget.spend(System.nanoTime() - start);
        }
    }

    // ── Recording ─────────────────────────────────────────────────────────────

    /**
     * This material's pipeline for {@code kind}: its Forward pass under the keywords enabled now, with that pass's
     * render state. Compiles nothing; the program is resolved when a batch draws with it.
     *
     * <pre>{@code
     * CgMaterialProxy proxy = CgMaterialProxy.of(material.keyword("WITH_MASK", true));
     * CgPipeline p = proxy.pipeline(CgInstanceKind.QUAD);
     * CgMaterialBindings b = proxy.bindings();
     * }</pre>
     *
     * @return null when the shader does not parse: there is nothing to draw
     */
    @Nullable
    public CgPipeline pipeline(CgInstanceKind kind) {
        return pipeline(CgRenderPassVariant.FORWARD, kind);
    }

    /**
     * The passes of a multi-pass post material that draw into targets of their own, in order; empty for one pass, and
     * while the shader does not parse. The same list until the shader parses again.
     */
    public List<CgParsedShader.PostTarget> postTargets() {
        checkNotDeleted();
        CgParsedShader parsed = cgMaterialShader == null ? null : cgMaterialShader.ensureParsed();
        return parsed == null ? Collections.emptyList() : parsed.postTargets();
    }

    /**
     * A {@code #domain post} material's {@code Location}, the {@code CgPostPoint} its file names; null where it names
     * none, and while the shader does not parse.
     */
    @Nullable
    public String postLocation() {
        checkNotDeleted();
        CgParsedShader parsed = cgMaterialShader == null ? null : cgMaterialShader.ensureParsed();
        return parsed == null ? null : parsed.postLocation();
    }

    /**
     * The name of its last Forward pass: a multi-pass post material's, the one drawing over the scene after every
     * pass in {@link #postTargets()}. Null while the shader does not parse.
     *
     * <pre>{@code
     * CgPipeline over = material.pipeline(material.lastForwardPass(), CgInstanceKind.OBJECT);
     * }</pre>
     */
    @Nullable
    public String lastForwardPass() {
        checkNotDeleted();
        CgParsedShader parsed = cgMaterialShader == null ? null : cgMaterialShader.ensureParsed();
        if (parsed == null) return null;
        String last = null;
        String forward = CgRenderPassVariant.FORWARD.lightModeName();
        for (CgParsedPass p : parsed.passes()) if (forward.equals(p.lightMode())) last = p.name();
        return last;
    }

    /** The {@code sampler2D} a post material reads what glows through ({@code "Emission"} tag); null for none. */
    @Nullable
    public String emissionInput() {
        checkNotDeleted();
        CgParsedShader parsed = cgMaterialShader == null ? null : cgMaterialShader.ensureParsed();
        return parsed == null ? null : parsed.emissionInput();
    }

    /**
     * As {@link #pipeline(CgInstanceKind)}, for the Forward pass named {@code pass} under its own render state: one
     * pass of a material drawing several in turn, as a multi-pass post material does.
     *
     * <pre>{@code
     * CgPipeline down = material.pipeline("Down", CgInstanceKind.OBJECT);   // Pass { Tags { "Name" = "Down" ... } }
     * }</pre>
     *
     * <ul>
     *   <li>Its keywords are the material's, as the first Forward pass's are; a {@code CgMaterialState} does not reach
     *       it.</li>
     *   <li>Each call makes a key: hold the answer for as long as the shader's revision holds.</li>
     * </ul>
     *
     * @return null when the shader does not parse or has no such pass
     */
    @Nullable
    public CgPipeline pipeline(String pass, CgInstanceKind kind) {
        checkNotDeleted();
        if (cgMaterialShader == null) return null;
        CgParsedShader parsed = cgMaterialShader.ensureParsed();
        if (parsed == null) return null;
        CgParsedPass own = parsed.getPassByName(pass);
        if (own == null) return null;
        return CgPipeline.pass(cgMaterialShader, pass, enabledKeywords, own.renderState(), kind);
    }

    /**
     * As {@link #pipeline(CgInstanceKind)}, for any pass. Keywords apply to {@link CgRenderPassVariant#FORWARD},
     * {@link CgRenderPassVariant#EMISSIVE} and {@link CgRenderPassVariant#DISTORTION} only.
     *
     * @return null when the shader does not parse
     */
    @Nullable
    public CgPipeline pipeline(CgRenderPassVariant pass, CgInstanceKind kind) {
        checkNotDeleted();
        if (cgMaterialShader == null) return null;
        CgParsedShader parsed = cgMaterialShader.ensureParsed();
        if (parsed == null) return null;
        int slot = pass == CgRenderPassVariant.FORWARD ? 0 : pass == CgRenderPassVariant.EMISSIVE ? 1
                : pass == CgRenderPassVariant.DISTORTION ? 2 : -1;
        List<String> declared = parsed.featureNames();
        int mask = keywordMask(declared);
        if (slot < 0 || mask < 0) {
            return CgPipeline.of(cgMaterialShader, pass, enabledKeywords, getPassRenderState(pass), kind);
        }
        int kinds = KINDS;
        if (declared.size() > DENSE_KEYWORDS) {
            // Past eight keywords a dense table would be 2^n entries a kind: a sparse one, keyed by slot, kind and mask.
            if (pipelinesParse != parsed || sparsePipelines == null) {
                sparsePipelines = new SparseVariants();
                pipelinesParse = parsed;
            }
            long key = (long) (slot * kinds + kind.ordinal()) << MAX_KEYWORDS | mask;
            CgPipeline pipeline = (CgPipeline) sparsePipelines.get(key);
            if (pipeline == null) {
                pipeline = CgPipeline.of(cgMaterialShader, pass, enabledKeywords, getPassRenderState(pass), kind);
                sparsePipelines.put(key, pipeline);
            }
            return pipeline;
        }
        int variants = 1 << declared.size();
        if (pipelinesParse != parsed || pipelines == null) {
            pipelines = new CgPipeline[3 * kinds * variants];
            pipelinesParse = parsed;
        }
        int at = (slot * kinds + kind.ordinal()) * variants + mask;
        CgPipeline pipeline = pipelines[at];
        if (pipeline == null) {
            pipelines[at] = pipeline = CgPipeline.of(cgMaterialShader, pass, enabledKeywords, getPassRenderState(pass), kind);
        }
        return pipeline;
    }

    /**
     * What a draw of this material binds, as it is now: its values' block, each sampler's texture at its declared unit,
     * its engine blocks and buffers, and each buffer bound with {@link #buffer}. The same object until one of them
     * moves; then a new one, and draws named the old one keep reading it. Compiles nothing.
     *
     * <pre>{@code
     * instance.colorARGB("_Color", 0xFFFF0000);
     * CgMaterialBindings red = CgMaterialProxy.of(instance).bindings();
     * instance.colorARGB("_Color", 0xFF0000FF);
     * CgMaterialBindings blue = CgMaterialProxy.of(instance).bindings();   // another; a draw named red still reads red
     * }</pre>
     *
     * <ul>
     *   <li>An attached buffer is bound as it is when the draw executes, not copied.</li>
     *   <li>A sampler's unit is its index among the shader's declared samplers, whatever unit
     *       {@code sampler(name, unit, texture)} was given.</li>
     * </ul>
     *
     * @throws IllegalStateException if its shader reads a buffer nothing is bound to
     */
    // Synchronized: documents on their own threads draw one shared material, and the kept builder is filled in place.
    public synchronized CgMaterialBindings bindings() {
        checkNotDeleted();
        syncPropsToParse();
        boolean engine = engineBlocksMoved();
        if (bindings != null && !engine && bindingsChanges == changes && bindingsParse == propsParse
                && !capturePropsDirty) {
            return bindings;
        }
        List<CgBufferDecl> declared = propsParse == null ? List.of() : propsParse.buffers();
        for (int i = 0; i < declared.size(); i++) {
            String name = declared.get(i).name();
            if (buffers == null || buffers.get(name) == null) {
                throw new IllegalStateException("this material reads buffer " + name + ", and nothing is bound to it: "
                        + "material.buffer(\"" + name + "\", buffer)");
            }
        }
        if (building == null) building = CgMaterialBindings.builder();
        CgMaterialBindings.Builder made = building.reset();
        if (propStore != null) {
            if (propStore.hasUboProps()) made.block(CgBindingModel.MATERIAL_BLOCK, blockVersion());
            propStore.captureSamplers(made);
        }
        if (engineBlocks != null) {
            for (int i = 0; i < engineBlocks.length; i++) {
                if (engineBlocks[i] != null) made.block(engineBlockSlots[i], engineBlocks[i]);
                else made.buffer(engineBlockBuffers[i]);
            }
        }
        if (propsParse != null) {
            List<String> tokens = propsParse.engineBuffers();
            for (int i = 0; i < tokens.size(); i++) {
                CgEngineBufferRegistry.Provider provider = CgEngineBufferRegistry.get(tokens.get(i));
                if (provider.perDraw()) made.buffer(provider.buffer().get());
            }
        }
        for (int i = 0; i < declared.size(); i++) made.storage(CgBindingModel.buffer(i), buffers.get(declared.get(i).name()));
        bindingsChanges = changes;
        bindingsParse = propsParse;
        // A value rewritten unchanged moves the change count, not the version: compared before anything is made.
        if (bindings == null || !made.sameAs(bindings)) bindings = made.build();
        made.reset();
        return bindings;
    }

    /** {@link #bindings}' last version, and the change count and parse it was made at. */
    @Nullable
    private CgMaterialBindings bindings;
    private int bindingsChanges = -1;
    /** What {@link #bindings} fills each time it looks, kept so a look that finds nothing moved allocates nothing. */
    @Nullable
    private CgMaterialBindings.Builder building;
    @Nullable
    private CgParsedShader bindingsParse;
    /**
     * Per engine uniform block its shader names, the version of what its writer (or this material's override) holds,
     * null where it holds nothing and the block binds as the buffer itself; with each one's slot and buffer.
     */
    @Nullable
    private CgBlockVersion[] engineBlocks;
    private CgBindingModel.Slot[] engineBlockSlots;
    private CgShaderBuffer[] engineBlockBuffers;

    /** What {@link #buffer} bound, by declared name. */
    @Nullable
    private Map<String, CgBufferHandle> buffers;

    /**
     * Binds {@code buffer} as buffer {@code name} of the shader's {@code Buffers { }}, which each pass reads as
     * {@code NAME(i)}: usually a frame graph's buffer a kernel writes, which the graph orders the draw after.
     *
     * <pre>{@code
     * // sparks.shader, at material scope:
     * //   struct Spark { vec4 positionLife; vec4 velocitySeed; };
     * //   Buffers { SPARKS ("Sparks", Spark, readonly) }
     * sparkMaterial.buffer("SPARKS", sparks);
     * world.draw(CgMesh.quads(capacity), sparkMaterial).indirect(alive, 0, CgIndirect.INDICES, 6).at(x, y, z).submit();
     * }</pre>
     *
     * <ul>
     *   <li>Captured as a texture is: a draw recorded before the call reads what was bound then.</li>
     *   <li>Recorded draws read it ({@link #bindings}).</li>
     *   <li>Every declared buffer is bound before the first capture, which throws naming one that is not.</li>
     * </ul>
     *
     * @throws IllegalArgumentException if the shader declares no buffer {@code name}
     */
    public CgMaterialProxy buffer(String name, CgBufferHandle buffer) {
        changes++;
        checkNotDeleted();
        CgParsedShader parsed = cgMaterialShader == null ? null : cgMaterialShader.ensureParsed();
        if (parsed == null || parsed.buffer(name) == null) {
            throw new IllegalArgumentException("'" + name + "' is no buffer this material's Buffers { } declares");
        }
        if (buffer instanceof CgBuffer own) parsed.buffer(name).check(own.layout());
        if (buffers == null) buffers = new HashMap<>();
        buffers.put(name, Objects.requireNonNull(buffer, "buffer"));
        return this;
    }

    /**
     * The properties block as it is now, persistent: a new version only after a value moved (counted,
     * {@code material.block-writes}), so an unchanged material uploads nothing frame to frame. In its shader's arena
     * where its values live there.
     */
    private CgBlockVersion blockVersion() {
        CgBlockVersion current = blockVersion;
        CgMaterialArena arena = arena();
        if (current != null && !capturePropsDirty && current.arena() == arena) return current;
        CgBufferWriter packer = capturePacker();
        if (current != null) current.supersede();
        blockVersion = current = arena != null ? CgBlockVersion.in(arena, packer.rawData(), packer.rawCursor())
                : CgBlockVersion.of(packer.rawData(), packer.rawCursor());
        CgTrace.add(CgChannels.GL, BLOCK_WRITES, 1);
        return current;
    }

    /** Its shader's arena where its values live there ({@link CgParsedShader#valuesInArena}); null otherwise. */
    @Nullable
    private CgMaterialArena arena() {
        CgParsedShader parsed = cgMaterialShader == null ? null : cgMaterialShader.ensureParsed();
        if (parsed == null || !parsed.valuesInArena() || propStore == null || !propStore.hasUboProps()) return null;
        if (arenaFloats < 0) arenaFloats = propStore.buildUboFormat().getFloatCount();
        return cgMaterialShader.arena(arenaFloats);
    }

    /** The properties block's float count, for {@link #arena}; -1 until asked after a parse rebuilt the properties. */
    private int arenaFloats = -1;

    /** The properties block packed as it is now, repacked only after a value moved. */
    private CgBufferWriter capturePacker() {
        if (capturePacker == null) {
            CgBufferLayout format = propStore.buildUboFormat();
            capturePacker = new CgBufferWriter(new CgStagingBuffer(format.getFloatCount()), format);
            capturePropsDirty = true;
        }
        if (capturePropsDirty) {
            propStore.writeUboProps(capturePacker);
            capturePropsDirty = false;
        }
        return capturePacker;
    }

    /**
     * Gives this material its own contents for {@code block}, a uniform block attached to the shader every instance
     * of it shares: {@link #bindings} keeps {@code contents} instead of the block's own writer. For a renderer
     * that keeps per-instance values in a shared block, so two instances recording at once cannot read each other's.
     *
     * <pre>{@code
     * CgBufferWriter mine = new CgBufferWriter(new CgStagingBuffer(format.getFloatCount()), format);
     * material.overrideBlock(sharedBlock, mine);
     * mine.reset().beginRecord().mat4("u_Projection", projection);   // what this instance's next capture keeps
     * }</pre>
     */
    public CgMaterialProxy overrideBlock(CgUniformBuffer block, CgBufferWriter contents) {
        changes++;
        if (blockOverrides == null) blockOverrides = new IdentityHashMap<>();
        blockOverrides.put(block, contents);
        return this;
    }

    /**
     * Brings {@link #engineBlocks} up to what the engine blocks its shader names hold now, answering whether any moved: a
     * version made only when a block's floats differ from the last one's, since their owners write them before a draw.
     */
    private boolean engineBlocksMoved() {
        if (cgMaterialShader == null) return false;
        List<CgAttachedBuffer> attached = cgMaterialShader.engineBuffers();
        int count = 0;
        for (int i = 0; i < attached.size(); i++) if (attached.get(i).isUbo()) count++;
        boolean moved = false;
        if (engineBlocks == null || engineBlocks.length != count) {
            engineBlocks = new CgBlockVersion[count];
            engineBlockSlots = new CgBindingModel.Slot[count];
            engineBlockBuffers = new CgShaderBuffer[count];
            moved = true;
        }
        for (int i = 0, k = 0; i < attached.size(); i++) {
            CgAttachedBuffer buffer = attached.get(i);
            if (!buffer.isUbo()) continue;
            CgShaderBuffer target = buffer.getBuffer();
            CgBufferWriter override = blockOverrides == null ? null : blockOverrides.get(target);
            CgBufferWriter written = override != null ? override : target.writer();
            CgBlockVersion had = engineBlocks[k];
            if (engineBlockBuffers[k] != target) {
                engineBlockBuffers[k] = target;
                engineBlockSlots[k] = target.slot();
                moved = true;
            }
            int floats = written.rawCursor();
            if (floats == 0) {
                if (had != null) {
                    had.supersede();
                    engineBlocks[k] = null;
                    moved = true;
                }
            } else if (had == null || !had.holds(written.rawData(), floats)) {
                if (had != null) had.supersede();
                engineBlocks[k] = CgBlockVersion.of(written.rawData(), floats);
                moved = true;
            }
            k++;
        }
        return moved;
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    /**
     * Returns the render state for the given pass variant from the last successful compile.
     * Falls back to the per-instance {@code renderState} field for forTest materials.
     *
     * <p>For FORWARD: resolves via the first Forward pass. For the others: by the variant's pass name
     * ({@link CgRenderPassVariant#lightModeName()}). Returns {@link CgRenderState#DEFAULT} when the pass is not found.</p>
     *
     * @param variant the pass variant whose render state to retrieve; must not be null
     * @return render state for the pass; never {@code null}
     */
    public CgRenderState getPassRenderState(CgRenderPassVariant variant) {
        if (cgMaterialShader == null) return renderState;
        CgParsedShader parsed = cgMaterialShader.ensureParsed();
        if (parsed == null) return CgRenderState.DEFAULT;
        if (variant == CgRenderPassVariant.FORWARD) {
            if (forwardRenderState != null && forwardRenderStateParse == parsed) return forwardRenderState;
            CgParsedPass forwardPass = parsed.getPassByLightMode(CgRenderPassVariant.FORWARD.lightModeName());
            CgRenderState own = forwardPass == null ? CgRenderState.DEFAULT : forwardPass.renderState();
            forwardRenderState = state.onPass(own, true);
            forwardRenderStateParse = parsed;
            return forwardRenderState;
        }
        CgRenderState own = cgMaterialShader.getRenderState(variant.lightModeName());
        if (state.cull() == null) return own;
        // One derived state per pass's own, so its pipeline is the same object every frame.
        CgRenderState[] held = passStates;
        int at = variant.ordinal() * 2;
        if (held != null && held[at] == own) return held[at + 1];
        CgRenderState[] next = held == null ? new CgRenderState[CgRenderPassVariant.values().length * 2] : held.clone();
        next[at] = own;
        next[at + 1] = state.onPass(own, false);
        passStates = next;
        return next[at + 1];
    }

    /** Each pass's own state and its state under {@link #state}'s cull, by variant: replaced whole, never edited. */
    @Nullable
    private volatile CgRenderState[] passStates;

    /**
     * Returns {@code true} when this material has an explicitly authored Depth pass in the
     * last successful parse. Based on parse-time pass presence — use to gate a Depth pipeline
     * ({@link #pipeline(CgRenderPassVariant, CgInstanceKind)}).
     *
     * <p>This is a parse-state check (explicit authored {@code [Depth]} block only).
     * To also detect auto-generated depth variants produced by {@code recompile()},
     * use {@link #hasCompiledDepthPass()} instead.</p>
     *
     * @see #hasCompiledDepthPass()
     */
    public boolean hasDepthPass() {
        return cgMaterialShader != null && cgMaterialShader.hasParsedPass(CgRenderPassVariant.DEPTH.lightModeName());
    }

    /**
     * Whether this material authors an Emissive pass: the world renderer then draws it into the bloom target too. A
     * parse, never a compile, so it is answered on any thread.
     */
    public boolean hasEmissivePass() {
        if (cgMaterialShader == null) return false;
        CgParsedShader parsed = cgMaterialShader.ensureParsed();
        return parsed != null && parsed.getPassByLightMode(CgRenderPassVariant.EMISSIVE.lightModeName()) != null;
    }

    /**
     * Whether its light follows the draw's exposure ({@code _cg_Exposure}): a {@code PerDrawValues On} shader whose Forward
     * colour is {@code "ColorSpace" = "Linear"}, or that has an Emissive pass. A parse, never a compile.
     *
     * <pre>{@code
     * if (material.readsExposure()) material.definition().requireRow("_cg_Exposure", who);   // written per draw
     * }</pre>
     */
    public boolean readsExposure() {
        if (cgMaterialShader == null) return false;
        CgParsedShader parsed = cgMaterialShader.ensureParsed();
        return parsed != null && parsed.engineBuffers().contains(CgShaderParser.ROWS) && (parsed.linearLight()
                || parsed.getPassByLightMode(CgRenderPassVariant.EMISSIVE.lightModeName()) != null);
    }

    /**
     * Whether its shader says {@code "Depth" = "Prepass"}: the world renderer draws its depth where {@code cg_Clip}
     * passes ({@code CgPipeline.depthPrepass}) before every transparent draw of the firing. A parse, never a compile.
     */
    public boolean hasDepthPrepass() {
        if (cgMaterialShader == null) return false;
        CgParsedShader parsed = cgMaterialShader.ensureParsed();
        return parsed != null && parsed.depthMode() == CgParsedShader.DepthMode.PREPASS;
    }

    /**
     * Whether its Forward draw writes depth ({@code "Depth" = "Clip"}, or {@code DepthWrite ON}). A transparent one then
     * draws first in its group, before what blends over it. A prepass is not this: its depth is drawn before every
     * transparent draw, and its blend keeps its order, so what is behind its thin edge never blends over it. A parse,
     * never a compile.
     */
    public boolean writesDepth() {
        if (cgMaterialShader == null) return false;
        CgParsedShader parsed = cgMaterialShader.ensureParsed();
        if (parsed == null) return false;
        CgParsedPass forward = parsed.getPassByLightMode(CgRenderPassVariant.FORWARD.lightModeName());
        CgDepthState depth = forward == null ? null : forward.renderState().getDepth();
        return depth != null && depth.write();
    }

    /**
     * Whether its Forward draw is a solid: it writes depth and blends nothing (Unreal's opaque blend mode), whatever its
     * queue. Such a draw casts shadows as an opaque one does; a translucent one never does. A parse, never a compile.
     *
     * <pre>{@code
     * if (material.solid()) lights.caster(...);   // debris drawn late, lit in its own program
     * }</pre>
     */
    public boolean solid() {
        if (!writesDepth()) return false;
        CgParsedPass forward = cgMaterialShader.ensureParsed().getPassByLightMode(CgRenderPassVariant.FORWARD.lightModeName());
        CgBlendState blend = forward.renderState().getBlend();
        return blend == null || !blend.enabled();
    }

    /**
     * Whether this material authors a Distortion pass: the world renderer then adds it into the distortion target, which
     * bends the scene after the transparent pass. A parse, never a compile.
     */
    public boolean hasDistortionPass() {
        if (cgMaterialShader == null) return false;
        CgParsedShader parsed = cgMaterialShader.ensureParsed();
        return parsed != null && parsed.getPassByLightMode(CgRenderPassVariant.DISTORTION.lightModeName()) != null;
    }

    /**
     * Returns {@code true} when a compiled depth prepass GL program exists for this material.
     *
     * <p>Unlike {@link #hasDepthPass()} (which checks for an explicit authored
     * {@code [Depth]} pass), this method also returns {@code true} for auto-generated depth
     * variants produced by {@link CgMaterialShader#recompile()}
     * during {@code CgMaterialShaderMap.autoGenDepth()}. It is the correct query for
     * a depth prepass deciding whether to bind {@code DEPTH} or fall back to the forward pass.</p>
     *
     * @return {@code true} if a compiled depth variant program is available
     */
    public boolean hasCompiledDepthPass() {
        return cgMaterialShader != null && cgMaterialShader.hasCompiledPass(CgRenderPassVariant.DEPTH.lightModeName());
    }

    /**
     * Returns a stable integer ID unique to this {@code CgMaterialProxy} instance.
     * Assigned at construction from a monotonically incrementing counter.
     * The world renderer's opaque sort key takes it as the 16-bit material id — more reliable than {@code System.identityHashCode}
     * which can collide for different live instances.
     *
     * @return the per-instance stable material ID (non-negative, unique per instance)
     */
    public int getMaterialId() {
        return materialId;
    }

    /**
     * Whether a pass of this material samples the scene's depth ({@code cg_DepthBuffer}), so a world renderer takes a
     * depth snapshot only when something reads it. True for a material not parsed yet: a needless copy beats an
     * unset texture.
     */
    public boolean readsSceneDepth() {
        return cgMaterialShader == null || cgMaterialShader.readsSceneDepth();
    }

    /**
     * Whether its translucent draws go after every other, Unreal's separate translucency: its state's
     * {@code separateTranslucency()}, or its shader's {@code TranslucencyPass Separate}. False for the shader's until it
     * is parsed.
     */
    public boolean separateTranslucency() {
        if (state.separateTranslucency()) return true;
        CgParsedShader parsed = cgMaterialShader == null ? null : cgMaterialShader.getLastParsed();
        return parsed != null && parsed.separateTranslucency();
    }

    /**
     * Whether its shader is {@code "Lighting" = "Surface"}: lit by the direction of the world's lights at its normal,
     * so the world's lights write their directional volume in a frame that draws it. False until it is parsed.
     */
    public boolean surfaceLit() {
        CgParsedShader parsed = cgMaterialShader == null ? null : cgMaterialShader.getLastParsed();
        return parsed != null && parsed.surfaceLit();
    }

    /**
     * Whether its shader writes custom data or a shading model ({@code CG_CUSTOM_DATA_OUT}, {@code CG_SHADING_MODEL_OUT}):
     * the world renderer draws its opaque draws again into the custom data, in a frame a post material reads it. False
     * until it is parsed.
     */
    public boolean writesCustomData() {
        CgParsedShader parsed = cgMaterialShader == null ? null : cgMaterialShader.getLastParsed();
        return parsed != null && parsed.writesCustomData();
    }

    /** Whether its shader reads the scene textures it is handed ({@code CG_CUSTOM_STENCIL(}, {@code CG_CUSTOM_DATA(}, ...). */
    public boolean handedScene() {
        CgParsedShader parsed = cgMaterialShader == null ? null : cgMaterialShader.ensureParsed();
        return parsed != null && parsed.handedScene();
    }

    /**
     * Whether its shader reads the world's lights it is handed ({@code cg_NearestLights}, {@code CG_DRAW_POINT_LIGHT_*}):
     * the world renderer binds the light grid to its draws. Parses it if nothing has: its first draw's capture needs
     * the grid bound. False for a shader that cannot be parsed.
     */
    public boolean handedLights() {
        CgParsedShader parsed = cgMaterialShader == null ? null : cgMaterialShader.ensureParsed();
        return parsed != null && parsed.handedLights();
    }

    /**
     * Whether its shader reads the sun's shadow it is handed ({@code CG_DIRECTIONAL_SHADOW}): the world renderer binds the
     * sun's shadow map to its draws. Parses it if nothing has. False for a shader that cannot be parsed.
     */
    public boolean handedSun() {
        CgParsedShader parsed = cgMaterialShader == null ? null : cgMaterialShader.ensureParsed();
        return parsed != null && parsed.handedSun();
    }

    /**
     * The texture unit sampler property {@code name} is wired to: its index among its shader's samplers, what
     * {@code sampler(name, unit, texture)} takes. -1 where it declares none of that name.
     *
     * <pre>{@code
     * instance.sampler("_Map", CgMaterialProxy.of(instance).samplerUnit("_Map"), map);
     * }</pre>
     */
    public int samplerUnit(String name) {
        CgParsedShader parsed = cgMaterialShader == null ? null : cgMaterialShader.ensureParsed();
        if (parsed == null) return -1;
        int unit = 0;
        for (CgMaterialProperty p : parsed.properties()) {
            if (!p.getType().isSampler()) continue;
            if (p.getName().equals(name)) return unit;
            unit++;
        }
        return -1;
    }

    /**
     * Whether a pass of its shader names {@code identifier} as a whole word, in its declarations or its stages' code,
     * parsing it if it is not yet: what a renderer asks before writing a value only some shaders read.
     *
     * <pre>{@code
     * boolean bounds = material.names("CG_OBJECT_POSITION") || material.names("CG_OBJECT_RADIUS");
     * }</pre>
     *
     * <ul>
     *   <li>A name used only inside an included file is not seen: the shader's own code is searched.</li>
     * </ul>
     */
    public boolean names(String identifier) {
        CgParsedShader parsed = cgMaterialShader == null ? null : cgMaterialShader.ensureParsed();
        if (parsed == null) return false;
        Pattern word = Pattern.compile("\\b" + Pattern.quote(identifier) + "\\b");
        for (CgParsedPass pass : parsed.passes()) {
            if (word.matcher(pass.globalDecls()).find() || word.matcher(pass.vertexBody()).find()
                    || word.matcher(pass.fragmentBody()).find()) {
                return true;
            }
        }
        return false;
    }

    /** As {@link #readsSceneDepth()}, for the scene's colour ({@code cg_SceneColor}). */
    public boolean readsSceneColor() {
        return cgMaterialShader == null || cgMaterialShader.readsSceneColor();
    }

    /** Whether its shader may discard a fragment ({@code discard}, {@code cg_Clip}); true until parsed. */
    public boolean discards() {
        return cgMaterialShader == null || cgMaterialShader.discards();
    }

    /**
     * Returns the numeric render queue priority for this material (default: 2000 = Geometry): its shader's as of the
     * last successful compile ({@link CgMaterialShader#getRenderQueue()}), moved by its state's blend
     * ({@link CgMaterialState#queue}).
     */
    public int getRenderQueue() {
        return state.queue(cgMaterialShader != null ? cgMaterialShader.getRenderQueue() : renderQueue);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Frees the per-instance property UBO. After this call, the material is unusable.
     *
     * <p>The backing shader programs are <strong>not</strong> deleted here — they are owned by
     * the shared {@link CgMaterialShader} asset and managed by {@link CgMaterialShaderRegistry}.
     * Only the property UBO (instance-owned) is freed.</p>
     *
     * <p>Idempotent — subsequent calls are no-ops.</p>
     */
    public void delete() {
        if (!deleted) {
            deleted = true;
            if (blockVersion != null) blockVersion.supersede();
            // Do NOT delete cgMaterialShader.getShader() — that is asset-owned, not instance-owned.
            // A material's variants go with it; its instances are their owners'.
            if (family != null && instance == null) {
                synchronized (family) {
                    family.forEach(m -> of((CgMaterial) m).delete());
                }
            }
        }
    }

    @Override
    public String toString() {
        String what = cgMaterialShader == null ? "test material" : cgMaterialShader.getResourcePath();
        return (instance != null ? "an instance of " : "") + what
                + (enabledKeywords.isEmpty() ? "" : " " + enabledKeywords);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Builds {@link #propStore} from the shader's parse, or rebuilds it when the shader has parsed anew, keeping each
     * value whose property kept its name and type. CPU only: what a capture and a property write need, before any
     * compile.
     */
    private void syncPropsToParse() {
        if (cgMaterialShader == null) return;
        CgParsedShader parsed = cgMaterialShader.ensureParsed();
        if (parsed == null || parsed == propsParse) return;
        propsParse = parsed;
        List<CgMaterialProperty> clones = cloneProperties(parsed.properties());
        if (propStore == null) {
            propStore = new CgMaterialProperties(clones);
        } else {
            Map<String, CgMaterialProperty> oldValues = new HashMap<>();
            for (CgMaterialProperty p : propStore.all()) oldValues.put(p.getName(), p);
            for (CgMaterialProperty clone : clones) {
                CgMaterialProperty old = oldValues.get(clone.getName());
                if (old != null && old.getType() == clone.getType()) clone.copyValueFrom(old);
            }
            propStore.rebuild(clones);
        }
        capturePacker = null;
        arenaFloats = -1;
        capturePropsDirty = true;

        if (held != null) {
            held.forEach((name, value) -> value.into(name, propStore));
            held = null;
        }
    }

    /**
     * Returns the declared feature names for this material.
     * For real materials: from the last successful parse. For forTest: from {@code testFeatureNames}.
     */
    private List<String> getDeclaredFeatureNames() {
        if (cgMaterialShader != null && cgMaterialShader.getLastParsed() != null) {
            return cgMaterialShader.getLastParsed().featureNames();
        }
        if (testFeatureNames != null) {
            return testFeatureNames;
        }
        return Collections.emptyList();
    }

    /**
     * Returns a fresh list of per-instance property clones from a shared parsed property list.
     * Each property is cloned via {@link CgMaterialProperty#copyWithDefaults()} so that each
     * material instance has independent, mutable property objects.
     */
    private static List<CgMaterialProperty> cloneProperties(List<CgMaterialProperty> source) {
        List<CgMaterialProperty> result = new ArrayList<>(source.size());
        for (CgMaterialProperty p : source)
            result.add(p.copyWithDefaults());
        return result;
    }

    private void checkNotDeleted() {
        if (deleted) throw new IllegalStateException("CgMaterialProxy has been deleted");
    }

}
