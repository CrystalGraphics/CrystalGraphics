package com.crystalgraphics.shadergraph;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * A GLSL type a shader-graph port can carry, plus the promotion rules the compiler emits casts for.
 *
 * <h3>Why this is an enum and {@code PortType} in the editor is an interface</h3>
 * <p>The editor's port types are open — a consumer registers whatever ids it likes, because a node graph
 * is a general-purpose editor and a dialogue graph's types are not a shader's. <b>GLSL's are not open.</b>
 * There is a fixed list, the compiler must emit a constructor for every promotion it permits, and a type
 * it cannot name is a type it cannot compile. An enum is the honest shape for that, and it is what makes
 * {@link #promote} total rather than a lookup that can fail at the worst moment.</p>
 *
 * <h3>Why this is not {@code CgMaterialProperty.Type}</h3>
 * <p>The two overlap and are deliberately separate, because they answer different questions.</p>
 *
 * <p>{@code CgMaterialProperty.Type} is a <b>property authoring</b> vocabulary — it carries two names,
 * the token an author writes in a {@code Properties} block and the GLSL type that token compiles to, and
 * it drags storage semantics behind it (UBO layout, sampler unit binding, range clamping). {@code COLOR}
 * compiles to {@code vec4} and {@code Range} to {@code float}: neither is a GLSL type, both are editor
 * affordances.</p>
 *
 * <p>This enum is <b>what flows along a wire</b>. It needs matrices and {@link #DYNAMIC}, which a uniform
 * never has, and promotion rules, which a uniform never needs — while a port typed {@code Range} would
 * be meaningless, since a Range <em>is</em> a float with metadata attached.</p>
 *
 * <p><b>They are bridged by the GLSL name, which both already carry</b>, so
 * {@code CgShaderType.parse(propertyType.getGlslName())} converts one way and {@link #glsl()} feeds
 * {@code CgMaterialProperty.Type.fromPropertyType} the other. {@code CgShaderTypeBridgeTest} pins that
 * every property type maps, so the two cannot drift apart silently when one gains a member.</p>
 *
 * <h3>{@link #DYNAMIC} — the reason node types are code, not only data</h3>
 * <p>Unity's {@code Add} accepts a float, a vec2, a vec3 or a vec4 and produces whichever is widest. A
 * port declared {@code DYNAMIC} resolves against what is actually connected to it, which is the
 * alternative to shipping four nodes per arithmetic operation. It never survives into emitted GLSL:
 * {@link CgGraphCompiler} resolves every dynamic port to a concrete type before a line is written, and a
 * dynamic port that resolves to nothing is a compile error rather than a guess.</p>
 */
public enum CgShaderType {

    /** Resolved from context, never emitted. See the class note. */
    DYNAMIC("", 0),

    BOOL("bool", 1),
    INT("int", 1),
    FLOAT("float", 1),
    VEC2("vec2", 2),
    VEC3("vec3", 3),
    VEC4("vec4", 4),
    MAT2("mat2", 0),
    MAT3("mat3", 0),
    MAT4("mat4", 0),
    SAMPLER2D("sampler2D", 0),
    SAMPLER2D_ARRAY("sampler2DArray", 0),
    SAMPLER3D("sampler3D", 0),
    SAMPLER_CUBE("samplerCube", 0);

    /** The GLSL keyword. Empty for {@link #DYNAMIC}, which has none by definition. */
    private final String glsl;

    /** Component count for the scalar/vector family, {@code 0} for everything else. This is the whole
     * of the widening order — a vector family is totally ordered by width, and nothing else is
     * ordered at all. */
    private final int components;

    CgShaderType(String glsl, int components) {
        this.glsl = glsl;
        this.components = components;
    }

    /** The GLSL keyword — {@code "vec3"}, {@code "sampler2D"}. */
    public String glsl() {
        return glsl;
    }

    public int components() {
        return components;
    }

    /** Whether this is a scalar or vector of floats, which is the only family with promotion rules. */
    public boolean isNumericVector() {
        return this == FLOAT || this == VEC2 || this == VEC3 || this == VEC4;
    }

    public boolean isSampler() {
        return this == SAMPLER2D || this == SAMPLER2D_ARRAY || this == SAMPLER3D || this == SAMPLER_CUBE;
    }

    public boolean isMatrix() {
        return this == MAT2 || this == MAT3 || this == MAT4;
    }

    /**
     * The width this type contributes to a dynamic node — its component count, and for a MATRIX its
     * dimension.
     *
     * <p>Distinct from {@link #components()}, which is 0 for a matrix because a matrix has no place in
     * the scalar/vector promotion order. It does still have a size a dynamic node reads: Unity's
     * {@code Matrix2x2} feeding a {@code Multiply} gives {@code A(2) B(2x2) Out(2)} — the matrix keeps
     * its own shape while the vector ports around it take the width 2. Kept as its own accessor so the
     * promotion arithmetic ({@link #canFeed}, {@link #promote}) stays untouched by it.</p>
     *
     * @return the width, or 0 for a type with no meaningful one (a sampler, a bool)
     */
    public int dynamicWidth() {
        return switch (this) {
            case MAT2 -> 2;
            case MAT3 -> 3;
            case MAT4 -> 4;
            default -> isNumericVector() ? components : 0;
        };
    }

    /**
     * Parses a GLSL type name, case-insensitively.
     *
     * @return the type, or {@code null} when the name is not one this compiler can emit — which the
     *         caller should report against the node that declared it, not swallow
     */
    @Nullable
    public static CgShaderType parse(String name) {
        if (name == null) return null;
        String needle = name.trim();
        if (needle.equalsIgnoreCase("dynamic")) return DYNAMIC;
        for (CgShaderType type : values()) {
            if (type.glsl.equalsIgnoreCase(needle)) return type;
        }
        // GLSL spells it `bool`; a graph author reasonably writes `boolean`, and the .shader Properties
        // block already accepts that spelling — so accepting it here keeps one vocabulary rather than two.
        if (needle.equalsIgnoreCase("boolean")) return BOOL;
        return null;
    }

    // ── Promotion ───────────────────────────────────────────────────────────

    /**
     * Whether a value of {@code this} type can feed a port of {@code target} type.
     *
     * <p>GLSL's own rule, narrowed: <b>a scalar promotes into any vector, and nothing demotes.</b> A
     * float feeding a vec3 is {@code vec3(x)} and is what makes a shader graph usable; a vec3 feeding a
     * float would have to pick a component, and picking silently is how a graph starts lying about what
     * it computes. That belongs in an explicit Split node.</p>
     */
    public boolean canFeed(CgShaderType target) {
        if (this == target) return true;
        if (this == DYNAMIC || target == DYNAMIC) return true;
        return this == FLOAT && target.isNumericVector();
    }

    /**
     * The GLSL expression that adapts {@code expression} from this type to {@code target}.
     *
     * <p>Emitting the cast is the <b>compiler's</b> job, not the graph author's. The editor already
     * permits a float to feed a vec3, so if the compiler did not insert {@code vec3(...)} the graph
     * would look legal and fail in the driver — an error the user has no way to act on.</p>
     *
     * @throws IllegalArgumentException if the conversion is not one {@link #canFeed} permits, which is a
     *         compiler bug rather than a user error by the time it is reached
     */
    public String promote(String expression, CgShaderType target) {
        if (this == target) return expression;
        if (!canFeed(target)) {
            throw new IllegalArgumentException("Cannot convert " + this + " to " + target
                    + " — connect-time validation should have refused this edge");
        }
        // float -> vecN. GLSL's single-argument vector constructor splats, which is exactly the
        // intended meaning of wiring a scalar into a vector slot.
        return target.glsl + "(" + expression + ")";
    }

    /**
     * The type a node's {@code DYNAMIC} ports take given everything connected to them — the
     * <b>narrowest</b> non-scalar of them, and every dynamic port on the node takes that one answer.
     *
     * <h4>Narrowest, which is Unity's rule and is not the obvious one</h4>
     * <p>{@code Add(vec4, vec2)} resolves to <b>vec2</b>: the vec4 is truncated to {@code .xy} and the
     * output is a vec2. Widest would instead have to widen a vec2 into a vec4, and there is no honest
     * value for the two channels that do not exist — zero and one are both inventions, and whichever
     * was chosen would silently change what the graph computes. Truncation at least only ever discards
     * data the user can see they wired in. Unity's own implementation says it in one line — "find the
     * minimum channel width excluding 1 as it can promote" — and this is a port of it.</p>
     *
     * <p>Getting it backwards is not a subtle failure: {@code Add(vec4, vec2)} simply refused to compile,
     * because resolving to vec4 then asked a vec2 to feed a vec4, which {@link #canFeed} correctly
     * forbids. The node previewed black with the error buried in a log.</p>
     *
     * <p>Resolved per NODE rather than per port — every dynamic port shares the answer — because
     * resolving each independently would make {@code Add}'s output a float whenever its first input
     * happened to be one, a result that depends on wiring order and is therefore unreproducible.</p>
     *
     * @return the resolved type; {@link #FLOAT} when nothing non-scalar decides it, or {@code null}
     *         when two genuinely incompatible concrete types meet (a matrix and a vector)
     */
    /** The scalar/vector type of a given component count, or null when there is none. */
    @Nullable
    public static CgShaderType ofWidth(int width) {
        return switch (width) {
            case 1 -> FLOAT;
            case 2 -> VEC2;
            case 3 -> VEC3;
            case 4 -> VEC4;
            default -> null;
        };
    }

    @Nullable
    public static CgShaderType resolveDynamic(Iterable<CgShaderType> candidates) {
        CgShaderType best = null;
        for (CgShaderType candidate : candidates) {
            // A scalar never decides the width: it promotes into whatever the others settle on, which is
            // what makes Add(float, vec3) a vec3 rather than a float. Skipping it here is the whole of
            // that rule — Unity spells it "excluding 1 as it can promote".
            if (candidate == null || candidate == DYNAMIC || candidate == FLOAT) continue;
            // A matrix contributes its DIMENSION as a width and resolves the node to the vector of that
            // width — Unity's Matrix2x2 -> Multiply gives A(2)/Out(2). It is deliberately not carried
            // through as a matrix: the node's other ports are vectors, and mat2 * vec2 is what the
            // emitted GLSL wants anyway.
            if (candidate.isMatrix()) candidate = ofWidth(candidate.dynamicWidth());
            if (candidate == null) continue;
            if (best == null) {
                best = candidate;
            } else if (candidate.isNumericVector() && best.isNumericVector()) {
                if (candidate.components < best.components) best = candidate;
            } else if (candidate != best) {
                // Two incompatible concrete types on one dynamic node — a matrix and a vector, say.
                // Not resolvable, and the caller reports it against the node rather than picking.
                return null;
            }
        }
        // Nothing non-scalar to go on — float, which is the identity of the promotion order and what an
        // entirely unconnected dynamic node emits. Never null here: null is reserved for a real conflict,
        // so the caller can tell "nothing decided it" from "the graph contradicts itself".
        return best == null ? FLOAT : best;
    }

    @Override
    public String toString() {
        return this == DYNAMIC ? "dynamic" : glsl;
    }

    /** For error messages that want a stable lowercase name. */
    public String displayName() {
        return toString().toLowerCase(Locale.ROOT);
    }

    /**
     * The token to write for this type in a {@code .shader} {@code Properties} block, or {@code null}
     * when it cannot be a property.
     *
     * <p><b>Not always {@link #glsl()}</b>, and the one exception is the reason this method exists:
     * GLSL spells it {@code bool} while the Properties block spells it {@code boolean}. Emitting the
     * GLSL name there produces a token the parser rejects — a generated shader that fails at parse
     * time, for a graph that is perfectly valid.</p>
     *
     * <p>Null for matrices, which have no property type today, and for {@link #DYNAMIC}, which is
     * resolved away long before anything is emitted.</p>
     */
    @Nullable
    public String propertyTypeName() {
        if (this == DYNAMIC || isMatrix()) return null;
        return this == BOOL ? "boolean" : glsl;
    }

    /**
     * The token to <b>write</b> in a {@code Properties} block — which is not always
     * {@link #propertyTypeName()}.
     *
     * <h3>vec3 cannot be a material property, and this is where that is absorbed</h3>
     * <p>{@code CgPropertiesParser} hard-bans it: STD140 pads a {@code vec3} to 16 bytes but the GLSL
     * compiler places the next field 12 bytes later, so a block containing one is silently mis-aligned.
     * A graph exposing a Vector 3 is an entirely reasonable thing to build, though, so refusing it in
     * the editor would push an alignment rule up into the user's face for no reason they could act on.</p>
     *
     * <p>So a {@code VEC3} property is <b>declared as {@code vec4}</b> and read back through
     * {@link #propertyAccessSuffix()}. The pair belongs here, beside the constraint it exists for,
     * rather than in whatever happens to be generating the block: a second generator would re-derive it,
     * get it wrong, and emit a shader that fails to parse for a graph that is perfectly valid.</p>
     */
    @Nullable
    public String propertyDeclarationType() {
        String name = propertyTypeName();
        if (name == null) return null;
        return this == VEC3 ? VEC4.glsl : name;
    }

    /**
     * What to append when READING this type's uniform, so the value has the type the graph expects.
     *
     * <p>Empty for everything but {@link #VEC3}, which is declared wider than it is — see
     * {@link #propertyDeclarationType()}.</p>
     */
    public String propertyAccessSuffix() {
        return this == VEC3 ? ".xyz" : "";
    }
}
